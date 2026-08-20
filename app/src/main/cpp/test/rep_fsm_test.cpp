// Host-side tests for the rep FSM — no device, no emulator: `make test`.
//
// Tests 1 and 2 replay real recordings, and they are the only ones that can say whether the
// thresholds are right. Everything else here is about whether the FSM is a machine: that its
// hysteresis holds, that its duration window bites, that every arrow in the state graph is
// reachable, and that no sequence of frames — including degenerate ones — can walk it
// somewhere undefined.
//
// Tests 3 to 5 drive angles directly rather than synthesising skeletons. Landmarks reaching
// the FSM as an angle is geometry_test's subject; what an angle does to the state graph is
// this file's, and a fabricated skeleton between the two would only add a way for this test
// to fail for a reason that is not about the FSM.
//
// The fixtures and their sidecars are described at the top of squat_fixture.h.

#include "rep_fsm.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <iomanip>
#include <iostream>
#include <limits>
#include <set>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "geometry.h"
#include "squat_fixture.h"

namespace kinex {
namespace {

// 30 FPS, the rate Phase 1 verified on the device. Synthetic tests only — the fixtures carry
// their own timestamps, and the FSM reads the clock, not a rate.
constexpr int64_t kFrameMs = 33;

// Test-bench numbers, not tuning constants. The start angle is the design doc's typical standing
// knee angle and the target is the squat row's 90-degree range-of-motion peak, so this bench
// runs on the exercise the rest of the project is calibrated against rather than on a
// plausible-looking pair invented here.
constexpr float kStart = 170.0f;
constexpr float kTarget = 90.0f;

// The sit-up's start, the design doc's estimate for a knees-bent supine pose and the least
// trustworthy number in its table — no sit-up fixture exists. Used by one test, which is
// about which exercise's rules the FSM was handed and not about the sit-up: all that number
// has to be is far enough from the row's 65-degree target for a rep to fit between them.
constexpr float kSitUpStartDegrees = 135.0f;

// The bench drives angles, because that is what arrives from geometry and what the FSM
// normalizes. The three thresholds are therefore needed in degrees — but derived from the
// FSM's own progress constants rather than written down, so a retuned threshold moves the
// bench with it instead of leaving the two silently disagreeing about where 0.30 is.
constexpr float AngleAtProgress(float progress) {
    return kStart + progress * (kTarget - kStart);
}

constexpr float kAdvanceAngle = AngleAtProgress(kAdvanceTriggerProgress);  // 146
constexpr float kCompleteAngle = AngleAtProgress(kRepCompleteProgress);    // 154
constexpr float kPeakAngle = AngleAtProgress(kDefaultDepthPass);           // 102

// The squat's RepConfig with the target swapped for |target|. The bench and the fixture
// replay both need a target the squat row does not carry — the bench so its thresholds can
// be derived from one pair, the replay because a recording runs to its sidecar's
// depthTargetDeg rather than to 90 degrees — and swapping the field on a copy of the row is
// how RepConfigFor takes an override without growing a parameter for it.
RepConfig SquatRepConfigAt(float start, float target) {
    ExerciseConfig squat = SquatConfig();
    squat.target_angle_degrees = target;
    return RepConfigFor(squat, start);
}

const char* StateName(RepState state) {
    switch (state) {
        case RepState::kIdle: return "IDLE";
        case RepState::kAligning: return "ALIGNING";
        case RepState::kReady: return "READY";
        case RepState::kAdvancing: return "ADVANCING";
        case RepState::kPeak: return "PEAK";
        case RepState::kReturning: return "RETURNING";
    }
    return "UNDEFINED";
}

using Transition = std::pair<int, int>;
using TransitionSet = std::set<Transition>;

Transition Arrow(RepState from, RepState to) {
    return Transition{static_cast<int>(from), static_cast<int>(to)};
}

std::string Describe(const Transition& arrow) {
    return std::string(StateName(static_cast<RepState>(arrow.first))) + " -> " +
           StateName(static_cast<RepState>(arrow.second));
}

// Every arrow the graph in rep_fsm.h draws. Test 5 asserts the FSM reaches all of them and
// invents none of them, which is the pair of claims that together mean the graph in the
// header is the graph in the code.
//
// Nothing returns to IDLE. Alignment loss lands in ALIGNING from every state that can lose
// it, and IDLE is now only where a session starts — so the absence of the five X -> IDLE
// arrows this list used to carry is itself asserted, by the second loop of test 5a and by
// the fuzz.
const TransitionSet& LegalTransitions() {
    static const TransitionSet* legal = new TransitionSet{
        Arrow(RepState::kIdle, RepState::kAligning),
        Arrow(RepState::kAligning, RepState::kReady),
        Arrow(RepState::kReady, RepState::kAligning),
        Arrow(RepState::kReady, RepState::kAdvancing),
        Arrow(RepState::kAdvancing, RepState::kAligning),
        Arrow(RepState::kAdvancing, RepState::kPeak),
        Arrow(RepState::kAdvancing, RepState::kReturning),
        Arrow(RepState::kAdvancing, RepState::kReady),
        Arrow(RepState::kPeak, RepState::kAligning),
        Arrow(RepState::kPeak, RepState::kReturning),
        Arrow(RepState::kPeak, RepState::kReady),
        Arrow(RepState::kReturning, RepState::kAligning),
        Arrow(RepState::kReturning, RepState::kAdvancing),
        Arrow(RepState::kReturning, RepState::kReady),
    };
    return *legal;
}

void Report(const std::string& line) {
    std::cout << "[  REPORT  ] " << line << std::endl;
}

// ---------------------------------------------------------------------------
// A frame source: feeds the FSM, advances its clock, and records what it saw
// ---------------------------------------------------------------------------

class Driver {
public:
    Driver() : Driver(kStart, kTarget) {}

    // Takes the calibration pair rather than a built RepConfig, so a test cannot drive the
    // bench from one start angle while the FSM normalizes against another.
    Driver(float start, float target) : Driver(SquatRepConfigAt(start, target)) {}

    // The one case that needs the built config: a test about which exercise's rules the FSM
    // was handed cannot go through a constructor that builds the squat's. The start angle is
    // read back off the config for the same reason as above — there is only one of it.
    explicit Driver(const RepConfig& config)
        : fsm_(config),
          start_(config.start_angle_degrees),
          last_{0, RepState::kIdle, 0, 0.0f, 0.0f},
          now_ms_(0) {}

    RepOutput Feed(float angle, float lean = 0.0f, bool aligned = true) {
        const RepState before = last_.state;
        const int reps_before = last_.rep_count;

        last_ = fsm_.Update(RepInput{angle, lean, aligned}, now_ms_);
        now_ms_ += kFrameMs;

        if (last_.state != before) {
            transitions_.insert(Arrow(before, last_.state));
        }
        if (last_.rep_count != reps_before) {
            rep_masks_.push_back(last_.violation_mask);
        }
        return last_;
    }

    void FeedFor(int64_t duration_ms, float angle, float lean = 0.0f, bool aligned = true) {
        for (int64_t elapsed = 0; elapsed < duration_ms; elapsed += kFrameMs) {
            Feed(angle, lean, aligned);
        }
    }

    // At the start pose, aligned, long enough to clear the ~1 s hold and land in READY.
    Driver& Settle() {
        FeedFor(1500, start_);
        EXPECT_EQ(RepState::kReady, last_.state) << "the bench never reached READY";
        return *this;
    }

    // A rep as a linear sweep out to |peak_angle| and back, spread over |sweep_ms| of frames.
    //
    // The FSM measures a rep from the frame that crosses the advance trigger to the frame
    // that crosses rep-complete, so the duration it judges is always shorter than the sweep —
    // the standing parts at either end are not part of any rep.
    void Sweep(float peak_angle, int64_t sweep_ms, float lean = 0.0f) {
        const int frames = static_cast<int>(sweep_ms / kFrameMs);
        const int out = frames / 2;
        const int back = frames - out;
        ASSERT_GT(out, 0);
        ASSERT_GT(back, 0);
        for (int i = 0; i < out; ++i) {
            const float t = static_cast<float>(i + 1) / static_cast<float>(out);
            Feed(start_ + (peak_angle - start_) * t, lean);
        }
        for (int i = 0; i < back; ++i) {
            const float t = static_cast<float>(i + 1) / static_cast<float>(back);
            Feed(peak_angle + (start_ - peak_angle) * t, lean);
        }
    }

    const RepOutput& last() const { return last_; }
    const TransitionSet& transitions() const { return transitions_; }
    const std::vector<int>& rep_masks() const { return rep_masks_; }
    void Reset() { fsm_.Reset(); }

private:
    RepFsm fsm_;
    float start_;
    RepOutput last_;
    int64_t now_ms_;
    TransitionSet transitions_;
    std::vector<int> rep_masks_;
};

// ---------------------------------------------------------------------------
// Fixture replay: recording -> filter -> geometry -> FSM
// ---------------------------------------------------------------------------

// The four landmarks a squat is judged on, down one side of the body.
struct Side {
    const char* name;
    int shoulder;
    int hip;
    int knee;
    int ankle;
};

constexpr Side kLeftSide{"left", kLeftShoulder, kLeftHip, kLeftKnee, kLeftAnkle};
constexpr Side kRightSide{"right", kRightShoulder, kRightHip, kRightKnee, kRightAnkle};

const float* Frame(const Recording& recording, int frame) {
    return &recording.frames[static_cast<size_t>(frame) * kLandmarkFloats];
}

double Mean(const std::vector<float>& series) {
    double total = 0.0;
    for (float value : series) {
        total += value;
    }
    return total / static_cast<double>(series.size());
}

double Median(std::vector<float> series) {
    std::sort(series.begin(), series.end());
    return series[series.size() / 2];
}

double MeanVisibility(const Recording& recording, const Side& side, const Segment& segment) {
    double total = 0.0;
    for (int landmark : {side.shoulder, side.hip, side.knee, side.ankle}) {
        total += Mean(Channel(recording, landmark, kComponentVisibility, segment));
    }
    return total / 4.0;
}

// The camera-side half of the body. In a profile view the far side is occluded and MediaPipe
// is guessing at it, so this is the side that was actually seen. geometry_test makes the same
// choice over three landmarks; this one includes the shoulder, because the torso lean rule
// needs it and a lean measured off an occluded shoulder is a violation flagged at random.
const Side& NearestSide(const SquatFixture& fixture) {
    return MeanVisibility(fixture.filtered, kLeftSide, fixture.still) >=
                   MeanVisibility(fixture.filtered, kRightSide, fixture.still)
               ? kLeftSide
               : kRightSide;
}

float KneeAngleAt(const Recording& recording, const Side& side, int frame) {
    const float* points = Frame(recording, frame);
    return JointAngleDegrees(LandmarkPoint(points, side.hip), LandmarkPoint(points, side.knee),
                             LandmarkPoint(points, side.ankle));
}

float TorsoLeanAt(const Recording& recording, const Side& side, int frame) {
    const float* points = Frame(recording, frame);
    return TorsoLeanDegrees(LandmarkPoint(points, side.shoulder), LandmarkPoint(points, side.hip));
}

struct Replay {
    int reps;
    std::vector<int> rep_masks;

    // Slot [6], one entry per counted rep: the peak the engine itself recorded, which is what
    // a session row stores. Held next to max_raw_progress below so the two can be compared —
    // they are the same quantity arrived at from opposite directions, one by the FSM and one
    // by this file.
    std::vector<float> rep_peaks;
    double start_degrees;
    TransitionSet transitions;

    // The unclamped normalization the FSM compares its thresholds against, and the clamped
    // value it writes into result slot [4]. Held apart on purpose: a rep deeper than the
    // configured target reads above 1.0 in the first and exactly 1.0 in the second, and
    // treating the HUD number as the threshold input is how that difference gets lost.
    float max_raw_progress;
    float max_output_progress;
    bool output_progress_finite;
};

// Plays the whole recording through the FSM at its own timestamps.
//
// aligned is true throughout: computing the real gate is Phase 4, and these recordings are
// framed the way the gate exists to insist on. The FSM's own ~1 s hold still runs, which the
// 3 seconds of standing at the head of every fixture covers.
Replay ReplayFixture(const SquatFixture& fixture, const Side& side) {
    // The still segment stands in for calibration: it is the athlete's start pose, progress
    // 0, and the sidecar's depthTargetDeg is the range of motion the recording runs to,
    // progress 1. Everything the FSM does with the frames between them is normalized against
    // that pair, which is why nothing here converts a threshold into degrees.
    const double start =
        Median(std::vector<float>([&] {
            std::vector<float> standing;
            for (int frame = fixture.still.begin; frame < fixture.still.end; ++frame) {
                standing.push_back(KneeAngleAt(fixture.filtered, side, frame));
            }
            return standing;
        }()));

    RepFsm fsm(SquatRepConfigAt(static_cast<float>(start), fixture.depth_target_degrees));

    Replay replay{0,
                  {},
                  {},
                  start,
                  {},
                  -std::numeric_limits<float>::infinity(),
                  -std::numeric_limits<float>::infinity(),
                  true};

    RepState previous = RepState::kIdle;
    int previous_reps = 0;
    for (int frame = 0; frame < fixture.frames(); ++frame) {
        const float angle = KneeAngleAt(fixture.filtered, side, frame);

        // The same normalization the FSM is about to do internally, kept unclamped so the
        // replay can report how far past the target this athlete actually went.
        float raw = 0.0f;
        if (RepProgress(angle, static_cast<float>(start), fixture.depth_target_degrees, &raw)) {
            replay.max_raw_progress = std::max(replay.max_raw_progress, raw);
        }

        const RepOutput out =
            fsm.Update(RepInput{angle, TorsoLeanAt(fixture.filtered, side, frame), true},
                       fixture.filtered.timestamps_ms[static_cast<size_t>(frame)]);

        if (std::isnan(out.rep_progress) || std::isinf(out.rep_progress)) {
            replay.output_progress_finite = false;
        }
        replay.max_output_progress = std::max(replay.max_output_progress, out.rep_progress);

        if (out.state != previous) {
            replay.transitions.insert(Arrow(previous, out.state));
            previous = out.state;
        }
        if (out.rep_count != previous_reps) {
            replay.rep_masks.push_back(out.violation_mask);
            replay.rep_peaks.push_back(out.rep_peak_progress);
            previous_reps = out.rep_count;
        }
        replay.reps = out.rep_count;
    }
    return replay;
}

std::string DescribeMask(int mask) {
    if (mask == 0) {
        return "clean";
    }
    std::string described;
    if ((mask & kViolationDepthMiss) != 0) {
        described += "depth";
    }
    if ((mask & kViolationTorsoLean) != 0) {
        described += described.empty() ? "lean" : "+lean";
    }
    return described;
}

// Both fixture tests need the same two things present before they mean anything, and they
// skip rather than fail for the same reason the other Phase 3 tests do: an absent recording
// is work not done yet, not a broken FSM.
bool FixtureReady(const FixtureSpec& spec, SquatFixture* fixture, std::string* skip_reason) {
    if (!SquatFixtureExists(spec)) {
        *skip_reason = MissingFixtureMessage(spec);
        return false;
    }
    std::string error;
    // A present fixture with an absent or stale sidecar is a hard failure everywhere else in
    // this suite, and it stays one here.
    EXPECT_TRUE(LoadSquatFixture(spec, fixture, &error)) << error;
    if (!error.empty()) {
        return false;
    }
    if (!IsValidAngle(fixture->depth_target_degrees)) {
        *skip_reason = std::string(spec.segments_path) +
                       " carries no \"depthTargetDeg\", so there is no range of motion to "
                       "normalize this recording against.\nWithout it there is no progress, "
                       "and without progress there is no threshold to compare — add the "
                       "angle this recording's reps run to. Schema at the top of "
                       "squat_fixture.h.";
        return false;
    }
    return true;
}

// ---------------------------------------------------------------------------
// 1. The clean recording: ten reps, no violations
// ---------------------------------------------------------------------------

TEST(RepFsmTest, CleanRecordingCountsExactlyTenReps) {
    SquatFixture fixture;
    std::string skip_reason;
    if (!FixtureReady(kSquat10Rep, &fixture, &skip_reason)) {
        GTEST_SKIP() << skip_reason;
    }

    const Side& side = NearestSide(fixture);
    const Replay replay = ReplayFixture(fixture, side);

    Report(std::string("camera-side: ") + side.name + ", start " +
           std::to_string(static_cast<int>(replay.start_degrees)) + " deg, target " +
           std::to_string(static_cast<int>(fixture.depth_target_degrees)) + " deg");
    Report("counted " + std::to_string(replay.reps) + " reps against " +
           std::to_string(fixture.reps()) + " hand-marked descents");

    EXPECT_EQ(fixture.reps(), replay.reps)
        << "the sidecar marks " << fixture.reps() << " descents and the FSM counted "
        << replay.reps
        << ". Miscounting a clean recording is the thresholds or the duration window, not the "
           "athlete";

    for (size_t rep = 0; rep < replay.rep_masks.size(); ++rep) {
        EXPECT_EQ(0, replay.rep_masks[rep])
            << "rep " << rep << " of a recording marked clean came back as "
            << DescribeMask(replay.rep_masks[rep]);
    }
}

// ---------------------------------------------------------------------------
// 2. The sloppy recording: whatever it counts, with its violations flagged
// ---------------------------------------------------------------------------

TEST(RepFsmTest, SloppyRecordingFlagsDepthAndLean) {
    SquatFixture fixture;
    std::string skip_reason;
    if (!FixtureReady(kSquat10RepSloppy, &fixture, &skip_reason)) {
        GTEST_SKIP() << skip_reason;
    }

    const Side& side = NearestSide(fixture);
    const Replay replay = ReplayFixture(fixture, side);

    // Reported, not asserted. How many of a set of deliberately bad reps survive the duration
    // window is the number this test exists to find out, and pinning it to a guess would only
    // record the guess.
    Report(std::string("camera-side: ") + side.name + ", start " +
           std::to_string(static_cast<int>(replay.start_degrees)) + " deg, target " +
           std::to_string(static_cast<int>(fixture.depth_target_degrees)) + " deg");
    Report("counted " + std::to_string(replay.reps) + " reps against " +
           std::to_string(fixture.reps()) + " hand-marked attempts (" +
           std::to_string(fixture.reps() - replay.reps) + " discarded on duration or never "
           "resolved)");

    std::string per_rep;
    for (size_t rep = 0; rep < replay.rep_masks.size(); ++rep) {
        per_rep += (rep == 0 ? "" : "  ") + std::to_string(rep) + ":" +
                   DescribeMask(replay.rep_masks[rep]);
    }
    Report("per-rep violations: " + (per_rep.empty() ? "none counted" : per_rep));

    ASSERT_GT(replay.reps, 0) << "a recording of bad reps produced no reps at all — the FSM is "
                                 "discarding everything, which flags nothing";

    int seen = 0;
    for (int mask : replay.rep_masks) {
        seen |= mask;
    }
    EXPECT_NE(0, seen & kViolationDepthMiss)
        << "no rep in a recording made to miss depth was flagged for it";
    EXPECT_NE(0, seen & kViolationTorsoLean)
        << "no rep in a recording made to lean past 55 degrees was flagged for it";
}

// ---------------------------------------------------------------------------
// 2b. The stock 8-rep clip: progress past 1.0 on real data
// ---------------------------------------------------------------------------

// This fixture exists for the one thing no synthetic bench in this file reaches: an athlete
// whose reps go *past* the configured target, so normalized progress runs above 1.0 on every
// rep. Everything about the count is reported rather than asserted — it is 8 reps of stock
// footage with no standing head, and the design doc is explicit that a test which measures the
// athlete gets loosened until it measures nothing.
//
// What is asserted is what the code controls: that the value crossing JNI stays inside
// 0..1 and finite however deep the athlete goes.
TEST(RepFsmTest, StockClipRunsProgressPastOneWithoutBreaking) {
    SquatFixture fixture;
    std::string skip_reason;
    if (!FixtureReady(kSquat8Rep, &fixture, &skip_reason)) {
        GTEST_SKIP() << skip_reason;
    }

    const Side& side = NearestSide(fixture);
    const Replay replay = ReplayFixture(fixture, side);

    // Per-rep minima: the deepest knee angle inside each hand-marked rep window.
    std::vector<float> minima;
    for (int rep = 0; rep < fixture.reps(); ++rep) {
        const Segment window = RepWindow(fixture, rep);
        float deepest = 180.0f;
        for (int frame = window.begin; frame < window.end; ++frame) {
            deepest = std::min(deepest, KneeAngleAt(fixture.filtered, side, frame));
        }
        minima.push_back(deepest);
    }

    const auto fixed = [](double value, int places) {
        std::ostringstream text;
        text << std::fixed << std::setprecision(places) << value;
        return text.str();
    };

    Report(std::string("camera-side: ") + side.name + ", start " +
           fixed(replay.start_degrees, 1) + " deg, target " +
           fixed(fixture.depth_target_degrees, 1) + " deg");

    std::string per_rep;
    for (size_t rep = 0; rep < minima.size(); ++rep) {
        per_rep += (rep == 0 ? "" : "  ") + std::to_string(rep) + ":" + fixed(minima[rep], 1);
    }
    Report("per-rep minimum knee angle: " + per_rep);

    const float shallowest = *std::max_element(minima.begin(), minima.end());
    const float deepest = *std::min_element(minima.begin(), minima.end());
    Report("spread: " + fixed(deepest, 1) + " to " + fixed(shallowest, 1) + " deg, range " +
           fixed(shallowest - deepest, 1) + " deg, median " +
           fixed(Median(minima), 1) + " deg");

    Report("FSM counted " + std::to_string(replay.reps) + " reps against " +
           std::to_string(fixture.reps()) + " hand-marked descents");

    std::string masks;
    for (size_t rep = 0; rep < replay.rep_masks.size(); ++rep) {
        masks += (rep == 0 ? "" : "  ") + std::to_string(rep) + ":" +
                 DescribeMask(replay.rep_masks[rep]);
    }
    Report("per-rep violations: " + (masks.empty() ? "none counted" : masks));

    std::string peaks;
    for (size_t rep = 0; rep < replay.rep_peaks.size(); ++rep) {
        peaks += (rep == 0 ? "" : "  ") + std::to_string(rep) + ":" +
                 fixed(replay.rep_peaks[rep], 4);
    }
    Report("per-rep peak, slot [6]: " + (peaks.empty() ? "none counted" : peaks));

    Report("max progress, unclamped (what the FSM compares): " +
           fixed(replay.max_raw_progress, 4));
    Report("max progress, slot [4] (what the HUD reads):     " +
           fixed(replay.max_output_progress, 4));
    const float deepest_recorded =
        replay.rep_peaks.empty()
            ? 0.0f
            : *std::max_element(replay.rep_peaks.begin(), replay.rep_peaks.end());
    Report("max progress, slot [6] (what a session stores):  " + fixed(deepest_recorded, 4));

    // The one assertion this comparison earns, and it is a real one: slot [6] and the
    // unclamped maximum are the same quantity computed by two different pieces of code — the
    // FSM's running max on one side, this file's own normalization on the other. On a
    // recording where every rep counts they have to agree on the deepest frame. If they ever
    // diverge, either the FSM's high-water mark is missing frames or it is clamping.
    ASSERT_FALSE(replay.rep_peaks.empty()) << "no rep counted, so slot [6] proves nothing";
    EXPECT_NEAR(replay.max_raw_progress, deepest_recorded, 1e-4f)
        << "the engine's recorded peak and this file's own unclamped maximum disagree";
    EXPECT_GT(deepest_recorded, replay.max_output_progress)
        << "slot [6] did not exceed the clamped slot [4] on a clip that goes past its target, "
        << "so the unclamped slot is not carrying anything the clamped one does not";

    // The HUD value is clamped, deliberately and by contract — slot [4] is documented as
    // 0.0..1.0 and a progress ring has nowhere to draw 1.03. The FSM's own comparisons run
    // on the unclamped value, which is why the clamp cannot cost a rep.
    EXPECT_TRUE(replay.output_progress_finite)
        << "a NaN or an infinity reached result slot [4]";
    EXPECT_GE(replay.max_output_progress, 0.0f);
    EXPECT_LE(replay.max_output_progress, 1.0f)
        << "slot [4] escaped its documented 0..1 range at " << replay.max_output_progress;

    // Nothing undefined escaped, and the graph held on real frames.
    for (int mask : replay.rep_masks) {
        EXPECT_EQ(0, mask & ~kViolationMaskAll)
            << "a bit outside the mask Kotlin decodes reached a counted rep";
    }
    for (const Transition& arrow : replay.transitions) {
        EXPECT_EQ(1u, LegalTransitions().count(arrow))
            << Describe(arrow) << " happened on real frames but is not in the graph rep_fsm.h "
                                  "documents";
    }
}

// ---------------------------------------------------------------------------
// 0. The thresholds are derived from calibration, not pinned to one athlete
// ---------------------------------------------------------------------------

// The design doc fixes the thresholds in progress — 0.30 to advance, 0.20 to complete — and
// calibration fixes what progress is measured *from*. A build that hardcoded 146 and 154
// degrees would pass every other test in this file, because every other test starts at 170.
//
// So this one calibrates somewhere else, against the same 90-degree squat target. 140 degrees
// has to mean "advancing" to an athlete who stands at 170 and "still standing" to one who
// stands at 150, and there is no way to satisfy both with an angle.
//
// The normalization is what makes that work: 140 is 0.375 of the way from 170 to 90, and only
// 0.167 of the way from 150 to 90. Same target, same threshold, opposite verdicts.
TEST(RepFsmTest, ThresholdsAreDerivedFromTheCalibratedBaseline) {
    constexpr float kShortStart = 150.0f;  // advance lands at 132 deg, complete at 138

    {
        Driver tall(kStart, kTarget);
        tall.Settle();
        tall.FeedFor(500, 140.0f);
        EXPECT_EQ(RepState::kAdvancing, tall.last().state)
            << "140 deg is 0.375 of the way from a 170 start to a 90 target, past the 0.30 "
               "advance trigger";
    }
    {
        Driver compact(kShortStart, kTarget);
        compact.Settle();
        compact.FeedFor(500, 140.0f);
        EXPECT_EQ(RepState::kReady, compact.last().state)
            << "140 deg is only 0.167 of the way from a 150 start to the same 90 target, "
               "nowhere near the advance trigger. A rep starting here means the trigger is "
               "pinned to an angle rather than normalized against the calibration passed in";
    }

    // And the whole cycle still works down there: a counted, clean rep from a start pose
    // twenty degrees below the one every other test in this file uses.
    {
        Driver compact(kShortStart, kTarget);
        compact.Settle();
        compact.Sweep(kTarget - 5.0f, 2000);
        EXPECT_EQ(1, compact.last().rep_count);
        EXPECT_EQ(0, compact.last().violation_mask);
    }

    // The same movement judged against the tall athlete's calibration is a different rep —
    // shorter in progress, and a different fraction of their range — which is the point of
    // calibrating at all.
    {
        Driver mismatched(kStart, kTarget);
        mismatched.Settle();
        mismatched.Sweep(kTarget - 5.0f, 2000);
        EXPECT_EQ(1, mismatched.last().rep_count);
    }
}

// ---------------------------------------------------------------------------
// 3. Oscillating across the advance trigger counts nothing
// ---------------------------------------------------------------------------

TEST(RepFsmTest, OscillatingAcrossTheAdvanceTriggerCountsNothing) {
    Driver driver;
    driver.Settle();

    // Straddling the 0.30 trigger without ever reaching 0.20 — 141 and 151 degrees, which
    // are progress 0.3625 and 0.2375. This is the athlete bobbing at the top of a squat, and
    // it is the failure the gap in the design doc exists to prevent: on a single-threshold FSM
    // every one of these thirty crossings is a rep.
    //
    // The shallow leg has to stay inside the hysteresis band, or this stops being a test of
    // hysteresis and becomes a test that counts reps. Angles run opposite to progress, so
    // "inside the band" reads as an angle below the completion angle.
    static_assert(kAdvanceAngle + 5.0f < kCompleteAngle,
                  "the shallow leg of the oscillation crosses rep-complete");

    // Fifteen cycles is 4950 ms of frames, deliberately short of the 6000 ms attempt timeout.
    // Twenty would expire the attempt partway through and leave this measuring the timeout
    // instead of the hysteresis — the count would still be zero, and for the wrong reason.
    for (int cycle = 0; cycle < 15; ++cycle) {
        driver.FeedFor(150, kAdvanceAngle - 5.0f);  // 141, progress 0.3625
        driver.FeedFor(150, kAdvanceAngle + 5.0f);  // 151, progress 0.2375
    }

    EXPECT_EQ(0, driver.last().rep_count)
        << "a signal that never crossed rep-complete produced " << driver.last().rep_count
        << " reps, so the enter and exit thresholds have collapsed onto each other";

    // And it did cross the trigger — otherwise this test would pass by never starting.
    EXPECT_TRUE(driver.transitions().count(Arrow(RepState::kReady, RepState::kAdvancing)) == 1)
        << "the bench never crossed the advance trigger, so it proved nothing";
    EXPECT_TRUE(
        driver.transitions().count(Arrow(RepState::kReturning, RepState::kAdvancing)) == 1)
        << "the bench never re-crossed the trigger, so it proved nothing";
    EXPECT_EQ(0, driver.last().violation_mask);
}

// ---------------------------------------------------------------------------
// 4. Reps outside the duration window are discarded, not counted
// ---------------------------------------------------------------------------

// The duration floor has two behaviours now, and the pair of tests below is the whole rule.
//
// The design doc: the floor rejects jitter, but applied unconditionally it also silently deletes
// fast shallow reps — the user does 10, the app says 7, and there is no explanation. So it
// applies only to attempts that never reached the peak. A rep that hit the target is real
// regardless of speed.
//
// One test would pin one half of that and leave the other free to regress, which is exactly
// what happened when there was one: a single 500 ms full-depth rep asserted to be discarded
// documented the behaviour the design doc now calls a bug.

TEST(RepFsmTest, ARepFasterThanTheMinimumButAtFullDepthCounts) {
    Driver driver;
    driver.Settle();

    // A full-depth rep in 500 ms of frames — well under the 700 ms floor even before the
    // standing parts at either end are excluded. Fast, but it reached the target, and a rep
    // cannot be reached and not have happened.
    driver.Sweep(kTarget, 500);

    EXPECT_EQ(1, driver.last().rep_count)
        << "a full-depth rep faster than 700 ms was discarded. The floor is being applied to "
           "every attempt again, which is what silently deletes fast reps";
    EXPECT_EQ(0, driver.last().violation_mask)
        << "a fast rep that reached the peak came back as "
        << DescribeMask(driver.last().violation_mask);
    EXPECT_EQ(RepState::kReady, driver.last().state);

    // It really did reach the peak — that is the half of the rule doing the work here.
    EXPECT_TRUE(driver.transitions().count(Arrow(RepState::kAdvancing, RepState::kPeak)) == 1);
}

TEST(RepFsmTest, ARepFasterThanTheMinimumThatMissedDepthIsDiscarded) {
    Driver driver;
    driver.Settle();

    // The sibling: the same 500 ms, dipping just past the advance trigger and no further.
    // Nothing an athlete does; everything a landmark glitch does, and the floor is what
    // rejects it.
    driver.Sweep(kAdvanceAngle - 5.0f, 500);

    EXPECT_EQ(0, driver.last().rep_count)
        << "a 500 ms attempt that never reached the peak was counted, so the floor is not "
           "being applied at all";
    // Discarded, not merely uncounted: the FSM is back at the top ready for the next one.
    EXPECT_EQ(RepState::kReady, driver.last().state);

    // It started, and it never peaked. Without both of these the test could pass by never
    // having attempted a rep in the first place.
    EXPECT_TRUE(driver.transitions().count(Arrow(RepState::kReady, RepState::kAdvancing)) == 1)
        << "the bench never crossed the advance trigger, so it proved nothing";
    EXPECT_EQ(0u, driver.transitions().count(Arrow(RepState::kAdvancing, RepState::kPeak)))
        << "the bench reached the peak, so this is not the shallow case";
}

TEST(RepFsmTest, ARepSlowerThanTheMaximumIsDiscarded) {
    Driver driver;
    driver.Settle();

    // Still zero, but by a different mechanism than it used to be: the attempt expires where
    // it stands partway through this sweep rather than being judged too slow at the end.
    // AnAttemptHeldPastTheMaximumTimesOutToReady pins the mechanism; this pins the verdict.
    driver.Sweep(kTarget, 10000);

    EXPECT_EQ(0, driver.last().rep_count)
        << "a rep slower than 6000 ms was counted, so the maximum duration is not being applied";
    EXPECT_EQ(RepState::kReady, driver.last().state);
}

// The maximum duration is a timeout, not a check run when an attempt completes. An attempt
// that never completes has to end anyway — otherwise an athlete who racks the bar and walks
// off mid-rep leaves the HUD pinned in ADVANCING until the app is restarted.
TEST(RepFsmTest, AnAttemptHeldPastTheMaximumTimesOutToReady) {
    Driver driver;
    driver.Settle();

    driver.FeedFor(200, kAdvanceAngle - 5.0f);
    ASSERT_EQ(RepState::kAdvancing, driver.last().state) << "the bench never started an attempt";

    // Held at exactly the same progress, well past 6000 ms. Nothing crosses any threshold, so
    // a completion-time check would never run and the FSM would sit in ADVANCING forever.
    driver.FeedFor(7000, kAdvanceAngle - 5.0f);

    EXPECT_EQ(RepState::kReady, driver.last().state)
        << "an attempt held past the maximum was still in flight, so the maximum is only "
           "being read when a rep completes";
    EXPECT_EQ(0, driver.last().rep_count) << "a timed-out attempt counted as a rep";

    // And it does not immediately re-arm from the pose it timed out in: the athlete is still
    // past the advance trigger, and a fresh attempt every six seconds is not a rest.
    driver.FeedFor(1000, kAdvanceAngle - 5.0f);
    EXPECT_EQ(RepState::kReady, driver.last().state)
        << "the FSM started a new attempt from the pose the last one timed out in";
    EXPECT_EQ(0, driver.last().rep_count);

    // Returning to the top re-arms it, and the next rep counts normally. Without this the
    // timeout would be a trap the set never recovers from.
    driver.FeedFor(500, kStart);
    driver.Sweep(kTarget, 2000);
    EXPECT_EQ(1, driver.last().rep_count)
        << "the FSM never re-armed after a timeout, so the set is over";
    EXPECT_EQ(0, driver.last().violation_mask);
}

// A consequence of measuring a rep from the trigger crossing rather than from the top, and
// the one most likely to be mistaken for a bug on real data: two reps at the same tempo, one
// deep and one shallow, do not last the same length of time as far as the FSM is concerned.
// The shallow one is past the trigger for a fraction of its sweep, and the duration floor
// can discard it before the depth-miss rule is ever consulted.
//
// The floor only applies to attempts that missed the peak now, which is precisely the case
// here — so this survives the change to it rather than being made moot by it. A shallow rep
// is exactly the rep the floor still judges.
//
// This is asserted rather than merely noted because it decides what the sloppy fixture will
// show: shallow reps at an ordinary tempo come back as nothing at all, not as reps flagged
// for depth.
TEST(RepFsmTest, AShallowRepIsPastTheTriggerForLessTimeThanItsSweep) {
    Driver quick;
    quick.Settle();
    quick.Sweep(kAdvanceAngle - 5.0f, 2000);
    EXPECT_EQ(0, quick.last().rep_count)
        << "a 2000 ms sweep that dips 5 degrees past the trigger is past it for well under "
           "the floor — counting it means the duration is being measured from somewhere "
           "other than the trigger crossing";

    Driver slow;
    slow.Settle();
    slow.Sweep(kAdvanceAngle - 5.0f, 3000);
    EXPECT_EQ(1, slow.last().rep_count);
    EXPECT_EQ(kViolationDepthMiss, slow.last().violation_mask);
}

TEST(RepFsmTest, ARepInsideTheWindowCountsAndReportsItsForm) {
    Driver driver;
    driver.Settle();

    driver.Sweep(kTarget, 2000);
    EXPECT_EQ(1, driver.last().rep_count);
    EXPECT_EQ(0, driver.last().violation_mask) << "a deep, upright rep came back as "
                                               << DescribeMask(driver.last().violation_mask);

    // Deep enough, but folded over. Lean is judged across the whole attempt, so one leaning
    // frame is enough — the rule is a violation, not an average.
    driver.Sweep(kTarget, 2000, 70.0f);
    EXPECT_EQ(2, driver.last().rep_count);
    EXPECT_EQ(kViolationTorsoLean, driver.last().violation_mask);

    // Upright, but stopping short of the target. It never enters PEAK, which is the whole of
    // the depth-miss rule.
    //
    // The sweep is longer than the deep reps above, and that is not padding. A rep is timed
    // from the frame it crosses the advance trigger to the frame it crosses rep-complete, so
    // a rep that only dips 5 degrees past the trigger is past it for a fraction of its own
    // sweep — at 2000 ms that is under the floor, and since the rep also missed the peak the
    // floor still applies to it. A shallow rep has to be slow to count at all.
    driver.Sweep(kAdvanceAngle - 5.0f, 3000);
    EXPECT_EQ(3, driver.last().rep_count);
    EXPECT_EQ(kViolationDepthMiss, driver.last().violation_mask);

    // Both at once.
    driver.Sweep(kAdvanceAngle - 5.0f, 3000, 70.0f);
    EXPECT_EQ(4, driver.last().rep_count);
    EXPECT_EQ(kViolationDepthMiss | kViolationTorsoLean, driver.last().violation_mask);

    ASSERT_EQ(4u, driver.rep_masks().size());
    EXPECT_EQ(0, driver.rep_masks()[0]);
    EXPECT_EQ(kViolationTorsoLean, driver.rep_masks()[1]);
    EXPECT_EQ(kViolationDepthMiss, driver.rep_masks()[2]);
    EXPECT_EQ(kViolationDepthMiss | kViolationTorsoLean, driver.rep_masks()[3]);
}

// Slots [4] and [6] are both rep progress and they are allowed to disagree — that
// disagreement is the entire reason [6] was added to the contract. [4] is clamped because a
// HUD ring cannot be 102% full; [6] is not, because a session record that stores 1.00 for a
// rep that reached 1.02 has destroyed the difference permanently.
TEST(RepFsmTest, TheRecordedPeakIsUnclampedWhereTheHudValueIsClamped) {
    Driver driver;
    driver.Settle();

    // Ten percent past the configured target — a real rep, deeper than the exercise asks for.
    driver.Sweep(AngleAtProgress(1.10f), 2000);
    ASSERT_EQ(1, driver.last().rep_count);
    EXPECT_NEAR(1.10f, driver.last().rep_peak_progress, 0.01f)
        << "the recorded peak was flattened, which is the one thing slot [6] exists to stop";
    EXPECT_LE(driver.last().rep_progress, 1.0f) << "slot [4] stopped being clamped";

    // A shallow rep, which is what the number is actually diagnostic for. Nothing clamps at
    // this end, so both slots agree.
    driver.Sweep(AngleAtProgress(0.55f), 3000);
    ASSERT_EQ(2, driver.last().rep_count);
    EXPECT_NEAR(0.55f, driver.last().rep_peak_progress, 0.01f);
    EXPECT_EQ(kViolationDepthMiss, driver.last().violation_mask)
        << "a rep at 0.55 should have missed a 0.85 depth pass";

    // Held until the next rep counts, in the same tense as violation_mask. A discarded
    // attempt — too fast and short of the peak — leaves both alone, because Kotlin reads
    // them on a rep_count change and a discarded attempt does not produce one.
    driver.Sweep(AngleAtProgress(0.40f), 400);
    ASSERT_EQ(2, driver.last().rep_count) << "the bench counted an attempt it should discard";
    EXPECT_NEAR(0.55f, driver.last().rep_peak_progress, 0.01f)
        << "a discarded attempt overwrote the last counted rep's peak";
}

// The whole of what "form violations do not generalize" costs in code: a row without
// kViolationTorsoLean is handed kNoLeanLimitDegrees by RepConfigFor, and nothing a torso can
// do trips it.
//
// The engine already keeps such an exercise safe by handing the FSM kInvalidAngle for lean,
// which is why this test feeds a real 89-degree lean rather than a sentinel: it is testing
// the other lock, the one that holds if a caller ever passes a lean it measured. The squat
// half is what proves the bench can produce the flag at all — without it, an assertion that
// no bit was set would pass just as happily on an FSM that never sets bits.
TEST(RepFsmTest, OnlyAnExerciseCarryingTheLeanRuleIsJudgedOnLean) {
    constexpr float kFoldedOver = 89.0f;

    ExerciseConfig sit_up = SitUpConfig();
    Driver unjudged(RepConfigFor(sit_up, kSitUpStartDegrees));
    unjudged.Settle();
    unjudged.Sweep(sit_up.target_angle_degrees, 2000, kFoldedOver);
    ASSERT_EQ(1, unjudged.last().rep_count) << "the sit-up bench never counted a rep";
    EXPECT_EQ(0, unjudged.last().violation_mask)
        << "an exercise with no violation rules came back flagged for "
        << DescribeMask(unjudged.last().violation_mask) << ", which is form advice nothing "
        << "has validated";

    ExerciseConfig squat = SquatConfig();
    Driver judged(RepConfigFor(squat, kStart));
    judged.Settle();
    judged.Sweep(squat.target_angle_degrees, 2000, kFoldedOver);
    ASSERT_EQ(1, judged.last().rep_count) << "the squat bench never counted a rep";
    EXPECT_EQ(kViolationTorsoLean, judged.last().violation_mask)
        << "the same lean on the one exercise that judges it was not flagged, so the "
        << "assertion above proves nothing";
}

TEST(RepFsmTest, ADiscardedRepReportsNothingAboutItsForm) {
    Driver driver;
    driver.Settle();

    driver.Sweep(kTarget, 2000);
    ASSERT_EQ(1, driver.last().rep_count);
    ASSERT_EQ(0, driver.last().violation_mask);

    // Too fast to be a rep, short of the peak, and leaning badly. Neither the count nor the
    // mask moves: a rep that did not happen has no form to critique, and Kotlin reads the
    // mask on a rep_count change that never comes.
    driver.Sweep(kAdvanceAngle - 5.0f, 400, 80.0f);
    EXPECT_EQ(1, driver.last().rep_count);
    EXPECT_EQ(0, driver.last().violation_mask);
    EXPECT_EQ(1u, driver.rep_masks().size());
}

// ---------------------------------------------------------------------------
// 5a. Every arrow in the graph is reachable
// ---------------------------------------------------------------------------

TEST(RepFsmTest, EveryTransitionIsReachable) {
    TransitionSet seen;
    const auto absorb = [&seen](const Driver& driver) {
        seen.insert(driver.transitions().begin(), driver.transitions().end());
    };

    // IDLE -> ALIGNING. Losing alignment straight afterwards is deliberately included and
    // deliberately not listed: it must produce no arrow at all, because ALIGNING -> IDLE no
    // longer exists and the second loop below is what catches it if it comes back.
    {
        Driver driver;
        driver.Feed(kStart);
        driver.Feed(kStart, 0.0f, false);
        absorb(driver);
    }
    // ALIGNING -> READY, then READY -> ALIGNING.
    {
        Driver driver;
        driver.Settle();
        driver.Feed(kStart, 0.0f, false);
        absorb(driver);
    }
    // READY -> ADVANCING -> PEAK -> RETURNING -> READY: a whole rep.
    {
        Driver driver;
        driver.Settle();
        driver.Sweep(kTarget, 2000);
        absorb(driver);
    }
    // ADVANCING -> RETURNING without ever reaching the peak, then RETURNING -> ADVANCING.
    {
        Driver driver;
        driver.Settle();
        driver.FeedFor(150, kAdvanceAngle - 5.0f);
        driver.FeedFor(150, kAdvanceAngle + 5.0f);
        driver.FeedFor(150, kAdvanceAngle - 5.0f);
        absorb(driver);
    }
    // Alignment lost from each of the three in-rep states.
    {
        Driver driver;
        driver.Settle();
        driver.FeedFor(150, kAdvanceAngle - 5.0f);
        driver.Feed(kAdvanceAngle - 5.0f, 0.0f, false);
        absorb(driver);
    }
    {
        Driver driver;
        driver.Settle();
        driver.FeedFor(150, kTarget);
        driver.Feed(kTarget, 0.0f, false);
        absorb(driver);
    }
    {
        Driver driver;
        driver.Settle();
        driver.FeedFor(150, kTarget);
        driver.FeedFor(150, kAdvanceAngle + 2.0f);
        driver.Feed(kAdvanceAngle + 2.0f, 0.0f, false);
        absorb(driver);
    }
    // ADVANCING -> READY and PEAK -> READY: an attempt held past the timeout in each of the
    // two states that can still reach the peak. (RETURNING -> READY is already covered by the
    // whole rep above, and the timeout reaches it by the same arrow.)
    {
        Driver driver;
        driver.Settle();
        driver.FeedFor(7000, kAdvanceAngle - 5.0f);
        absorb(driver);
    }
    {
        Driver driver;
        driver.Settle();
        driver.FeedFor(7000, kTarget);
        absorb(driver);
    }

    for (const Transition& arrow : LegalTransitions()) {
        EXPECT_EQ(1u, seen.count(arrow))
            << Describe(arrow) << " is drawn in rep_fsm.h but no input sequence here reaches it";
    }
    for (const Transition& arrow : seen) {
        EXPECT_EQ(1u, LegalTransitions().count(arrow))
            << Describe(arrow) << " happened but is not in the graph rep_fsm.h documents";
    }
}

// ---------------------------------------------------------------------------
// 5b. No sequence of frames leaves the FSM undefined
// ---------------------------------------------------------------------------

// Deterministic pseudorandom, so a failure reproduces exactly. Same LCG the filter tests use.
class Rng {
public:
    explicit Rng(uint32_t seed) : state_(seed) {}

    uint32_t Next() {
        state_ = state_ * 1664525u + 1013904223u;
        return state_ >> 8;
    }

    int Below(int bound) { return static_cast<int>(Next() % static_cast<uint32_t>(bound)); }

    float Unit() { return static_cast<float>(Next()) / static_cast<float>(1u << 24); }

private:
    uint32_t state_;
};

TEST(RepFsmTest, NoInputSequenceLeavesAnUndefinedState) {
    Rng rng(20260818u);
    RepFsm fsm(SquatRepConfigAt(kStart, kTarget));

    RepState previous = RepState::kIdle;
    int previous_reps = 0;
    int64_t now_ms = 0;

    for (int frame = 0; frame < 200000; ++frame) {
        // Angles drawn from the whole legal range plus the values that break naive code: the
        // degeneracy sentinel, and each threshold landed on exactly.
        float angle;
        switch (rng.Below(8)) {
            case 0: angle = kInvalidAngle; break;
            case 1: angle = kAdvanceAngle; break;
            case 2: angle = kCompleteAngle; break;
            case 3: angle = kPeakAngle; break;
            case 4: angle = 0.0f; break;
            case 5: angle = 180.0f; break;
            default: angle = rng.Unit() * 180.0f; break;
        }
        const float lean = rng.Below(6) == 0 ? kInvalidAngle : rng.Unit() * 180.0f;
        const bool aligned = rng.Below(10) != 0;

        // A frame clock that stalls, ticks, and occasionally jumps a long way — a dropped
        // frame, or an app resumed after being backgrounded.
        now_ms += rng.Below(4) == 0 ? 0 : rng.Below(3) == 0 ? rng.Below(9000) : kFrameMs;

        const RepOutput out = fsm.Update(RepInput{angle, lean, aligned}, now_ms);

        const int state = static_cast<int>(out.state);
        ASSERT_GE(state, static_cast<int>(RepState::kIdle)) << "frame " << frame;
        ASSERT_LE(state, static_cast<int>(RepState::kReturning))
            << "frame " << frame << " left the FSM in state " << state;

        ASSERT_EQ(0, out.violation_mask & ~kViolationMaskAll)
            << "frame " << frame << " set a bit outside the mask Kotlin knows how to decode";

        ASSERT_FALSE(std::isnan(out.rep_progress)) << "frame " << frame;
        ASSERT_GE(out.rep_progress, 0.0f) << "frame " << frame;
        ASSERT_LE(out.rep_progress, 1.0f) << "frame " << frame;

        ASSERT_GE(out.rep_count, previous_reps) << "frame " << frame << ": the rep count went "
                                                   "backwards";
        ASSERT_LE(out.rep_count - previous_reps, 1)
            << "frame " << frame << " counted more than one rep";

        if (out.state != previous) {
            const Transition arrow = Arrow(previous, out.state);
            ASSERT_EQ(1u, LegalTransitions().count(arrow))
                << "frame " << frame << ": " << Describe(arrow)
                << " is not in the graph rep_fsm.h documents";
            previous = out.state;
        }
        previous_reps = out.rep_count;
    }

    Report("fuzz: 200000 frames, " + std::to_string(previous_reps) +
           " reps counted, every transition legal");
}

TEST(RepFsmTest, ResetReturnsToAKnownState) {
    Driver driver;
    driver.Settle();
    driver.Sweep(kTarget, 2000, 70.0f);
    ASSERT_EQ(1, driver.last().rep_count);
    ASSERT_EQ(kViolationTorsoLean, driver.last().violation_mask);

    driver.Reset();

    // The first frame after a reset is the first frame of a session: IDLE, nothing counted,
    // nothing flagged, and the alignment hold to serve again before anything can be. Reset is
    // the only route back to IDLE now that alignment loss lands in ALIGNING.
    const RepOutput out = driver.Feed(kStart);
    EXPECT_EQ(0, out.rep_count);
    EXPECT_EQ(0, out.violation_mask);
    EXPECT_EQ(RepState::kAligning, out.state);
    EXPECT_FLOAT_EQ(0.0f, out.rep_progress);
}

// The design doc: alignment lost mid-rep discards the in-flight rep and returns to ALIGNING, and
// the rep is not resumed if alignment comes back. IDLE narrows to the state before alignment
// has ever been acquired, which after this change nothing but Reset() can reach.
TEST(RepFsmTest, LosingAlignmentMidRepReturnsToAligningAndDiscardsTheRep) {
    Driver driver;
    driver.Settle();

    driver.FeedFor(300, kTarget);
    ASSERT_EQ(RepState::kPeak, driver.last().state);

    driver.Feed(kTarget, 0.0f, false);
    EXPECT_EQ(RepState::kAligning, driver.last().state)
        << "alignment lost mid-rep dropped to IDLE. IDLE is now only the state before "
           "alignment has ever been acquired — the athlete has already aligned once";

    // Back in frame and standing: the ~1 s hold is served again from scratch rather than the
    // rep picking up where it left off.
    driver.FeedFor(600, kStart);
    EXPECT_EQ(RepState::kAligning, driver.last().state)
        << "the hold was not served again after alignment came back";

    driver.FeedFor(600, kStart);
    EXPECT_EQ(RepState::kReady, driver.last().state);
    EXPECT_EQ(0, driver.last().rep_count)
        << "the rep that was in flight when alignment was lost came back and counted";
}

TEST(RepFsmTest, ADroppedDetectionHoldsRatherThanAbandonsTheRep) {
    Driver driver;
    driver.Settle();

    // Out to the peak, then lose the landmarks for half a second. The sentinel is -1, which
    // as an angle reads deeper than any squat — an FSM that compared it would call this the
    // peak reached, or a rep completed, on a frame that saw nothing at all.
    driver.FeedFor(300, kTarget);
    ASSERT_EQ(RepState::kPeak, driver.last().state);
    const float progress_at_peak = driver.last().rep_progress;

    driver.FeedFor(500, kInvalidAngle);
    EXPECT_EQ(RepState::kPeak, driver.last().state)
        << "a dropped detection moved the FSM out of PEAK";
    EXPECT_FLOAT_EQ(progress_at_peak, driver.last().rep_progress)
        << "the HUD ring moved on a frame with no landmarks in it";

    // The rep finishes normally once the landmarks come back.
    driver.FeedFor(300, kStart);
    EXPECT_EQ(1, driver.last().rep_count);
    EXPECT_EQ(0, driver.last().violation_mask);
}

}  // namespace
}  // namespace kinex
