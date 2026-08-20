#pragma once

// Shared loader for the recorded squat, used by every host-side test that replays it.
// It lives here rather than in one test file because two of them now need it, and a
// second copy of a hand-rolled JSON scan is a second place for it to be subtly wrong.
//
// ---------------------------------------------------------------------------
// Fixtures: fixtures/*.jsonl
//
// A recording written by LandmarkRecorder — one JSON object per line, in the format
// LandmarkRecorder documents, pulled off the device from
//   /sdcard/Android/data/com.kinex/files/recordings/
//
// Every fixture holds:
//   - a side (profile) view, the same framing the squat rules assume
//   - at least 3 seconds of standing still at the start, which is the variance baseline
//     and the stand-in for calibration
//
// squat_10rep.jsonl holds exactly 10 clean reps, no partial or aborted ones.
// squat_10rep_sloppy.jsonl holds the same movement done badly: reps that stop short of
// depth and reps with the torso pitched past 55 degrees. Its rep count is whatever it is —
// no test asserts one, because the point of it is what the FSM makes of bad form.
//
// Sidecar: fixtures/<name>.segments.json
//
// Frame indices are 0-based and half-open, [begin, end), counting lines in the .jsonl:
//   {
//     "frames": 912,
//     "still": [15, 105],
//     "depthTargetDeg": 95.0,
//     "descent": [[130, 168], [201, 240], ... one pair per attempted rep]
//   }
//
// "frames" must equal the fixture's line count. It is what catches a re-recorded fixture
// whose segments were never updated — stale indices that still happen to be in range
// would otherwise measure the wrong frames and pass.
//
// "depthTargetDeg" is the knee angle this recording's range of motion runs to — the FSM's
// target, progress 1, which the peak threshold is then a fraction of. It is recorded here
// with the fixture it belongs to rather than guessed at in a test. Optional: only the FSM
// replay reads it, and it says so when it is missing.
// ---------------------------------------------------------------------------

#include <cstdint>
#include <string>
#include <vector>

// For kInvalidAngle, which is how an absent "depthTargetDeg" is reported. Header-only —
// this does not put geometry.cpp on every fixture-linking test's source list.
#include "exercises/exercise_config.h"
#include "geometry.h"
#include "landmark_layout.h"

namespace kinex {

// One recording and what is known about it before it is opened.
//
// |expected_reps| is a cross-check on the sidecar, not a measurement: it catches a sidecar
// that lost a descent pair against a fixture that still has the reps. Zero means the
// recording's rep count is not known up front — take however many descents are marked —
// which covers both the sloppy fixture, where how many attempts the FSM makes of it is the
// question, and the still baseline, which has no reps at all.
//
// |requirement| is what to tell someone who has to produce this fixture. It lives on the
// spec because each fixture wants a different sentence, and a conditional in
// MissingFixtureMessage was already picking the wrong one the moment a third fixture landed.
struct FixtureSpec {
    const char* recording_path;
    const char* segments_path;
    int expected_reps;
    const char* requirement;
};

// Paths are relative to cpp/test/, which is where the Makefile runs the binaries from.
inline constexpr FixtureSpec kSquat10Rep{
    "fixtures/squat_10rep.jsonl", "fixtures/squat_10rep.segments.json", 10,
    "It must hold exactly 10 clean reps, side view, with at least 3 seconds of standing "
    "still at the start."};

inline constexpr FixtureSpec kSquat10RepSloppy{
    "fixtures/squat_10rep_sloppy.jsonl", "fixtures/squat_10rep_sloppy.segments.json", 0,
    "It must be the same movement done badly — reps short of depth, reps with the torso "
    "pitched past 55 degrees — side view, with at least 3 seconds of standing still at the "
    "start. However many reps it holds is fine; mark each attempt."};

// Openly licensed stock footage (Pexels 5025965), extracted by tools/extract_landmarks.py.
// Eight reps, not ten, and no standing head — the clip opens mid-movement, so its still
// window is 0.6 s of the pause between two reps rather than 3 s of calibration. It is not a
// substitute for kSquat10Rep and no test asserts a rep count against it.
//
// What it is good for is the case no synthetic bench reaches: this athlete bottoms out
// around 35 degrees against a 38.6-degree target, so normalized progress runs past 1.0 on
// every rep. That is the arithmetic the engine has to survive on real data.
inline constexpr FixtureSpec kSquat8Rep{
    "fixtures/squat_8rep.jsonl", "fixtures/squat_8rep.segments.json", 8,
    "It must hold exactly 8 reps, side view. Its still window is poor — see the note above — "
    "so anything measuring stillness should read kStillBaseline instead."};

// A second athlete, a second camera, standing still (Pexels 9034585, 24 fps, 288 frames).
// No reps: "descent" is an empty list, which is what a variance baseline needs to be.
//
// It exists because stillness and reps are separate measurements that were sharing one
// recording, and no clip of stock footage supplies both well. Nothing that compares a rep
// against a standing pose may use this — different person, different camera, different limb
// proportions — and the tests that do are named in the note at the top of geometry_test.
//
// The still window is [208, 288): the longest span of at least the 3 s the design doc asks for
// whose RAW knee angle stays inside 3 degrees peak-to-peak. The rule is stated here because
// the choice matters — across every 3 s window in this clip the FILTERED spread runs 1.49 to
// 5.38 degrees (median 3.01), so a window picked to flatter a tolerance would be picking the
// answer. This one measures 1.49.
inline constexpr FixtureSpec kStillBaseline{
    "fixtures/still_baseline.jsonl", "fixtures/still_baseline.segments.json", 0,
    "It must hold a person standing still in a side view, and no reps at all. Mark the "
    "quietest span of at least 3 seconds as \"still\" and leave \"descent\" empty."};

inline constexpr int kComponentX = 0;
inline constexpr int kComponentY = 1;
inline constexpr int kComponentVisibility = 3;

// The MediaPipe pose indices these fixtures are read through — kLeftKnee and the rest —
// come from exercises/exercise_config.h, included above. They used to be declared here as
// well, with a note that they belonged in the exercise config once it existed. It exists,
// so this header no longer carries a second copy of them.

struct Segment {
    int begin;
    int end;
};

struct Recording {
    std::vector<int64_t> timestamps_ms;
    std::vector<float> frames;  // frame count x kLandmarkFloats, flat

    size_t frame_count() const { return timestamps_ms.size(); }
};

// The recording as it came off the device, the same frames after the One Euro filter, and
// the hand-marked segments. Tests generally want the filtered frames — that is what the
// engine will see — but keeping the raw ones is what lets a test measure the filter.
struct SquatFixture {
    Recording raw;
    Recording filtered;
    Segment still;
    std::vector<Segment> descents;

    // kInvalidAngle when the sidecar carries no "depthTargetDeg".
    float depth_target_degrees;

    int frames() const { return static_cast<int>(raw.frame_count()); }

    // Hand-marked descents: how many reps the recording was judged to attempt.
    int reps() const { return static_cast<int>(descents.size()); }
};

// True once someone has recorded and pulled this fixture. Tests skip when this is false.
bool SquatFixtureExists(const FixtureSpec& spec);

// What to print when it does not — the path, how to record one, and what makes it valid.
std::string MissingFixtureMessage(const FixtureSpec& spec);

// Loads both files, cross-checks them, and runs the filter over the whole recording.
// Every failure here is a hard failure for the caller, never a skip: a fixture that is
// present but unreadable, or whose sidecar disagrees with it, is a fixture measuring the
// wrong frames.
bool LoadSquatFixture(const FixtureSpec& spec, SquatFixture* fixture, std::string* error);

// The frames of one rep: from where its descent starts to where the next one does, and to
// the end of the recording for the last. Wide enough to hold the whole
// descent-bottom-ascent arc, so a filter's lag cannot masquerade as lost depth.
Segment RepWindow(const SquatFixture& fixture, int rep);

// One landmark's component over a span of frames, as a series.
std::vector<float> Channel(const Recording& recording, int landmark, int component,
                           const Segment& segment);

}  // namespace kinex
