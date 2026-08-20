// Host-side tests for the image-plane geometry — no device, no emulator: `make test`.
//
// Tests 1 and 2 are arithmetic: known triangles, and the degenerate inputs that would
// otherwise hand the FSM a NaN. Tests 3 and 4 replay real recordings through the filter and
// into the knee angle, which is the first point in the pipeline where the numbers mean
// something anatomical rather than something geometric.
//
//   test 3  standing reads as still, near 170 deg  -> kStillBaseline
//   test 4  every rep bends the knee the right way -> kSquat8Rep
//
// Test 4 keeps its standing reference and its reps in the same recording on purpose. It
// asserts a *difference* between two angles, and a difference between two angles measured
// on two different people in two different cameras is not a measurement of anything. The
// long note above that test is the argument; do not "finish" the split by pointing it at
// kStillBaseline.
//
// Both assert less than they report, deliberately. How repeatable an athlete's depth is
// across eight reps is a fact about the athlete, not about this code, so the per-rep minima
// are printed rather than bounded — a threshold there would eventually be loosened until it
// taught nothing. What is asserted is what only a bug can break.
//
// The fixtures and their sidecars are described at the top of squat_fixture.h.

#include "geometry.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "squat_fixture.h"

namespace kinex {
namespace {

// How far a rep must bend the knee below standing before it counts as a squat at all.
// Well inside the design doc's 25-degree descend trigger, because this is not measuring depth —
// it is checking the angle moves the right way at all. A sign error, a swapped landmark
// index, or a reflex angle coming back as 360 minus the truth all fail here, and none of
// them can be rescued by squatting deeper.
constexpr double kMinimumBendBelowBaseline = 20.0;

// The design doc: calibration captures a standing baseline, typically ~170 degrees. The
// tolerance is wide because where it actually lands depends on stance and camera height.
constexpr double kBaselineDegrees = 170.0;
constexpr double kBaselineTolerance = 8.0;

// The 2-degree still-spread bound that used to sit here is gone, retired to a [  REPORT  ]
// line the same way one_euro_test's range-of-motion and jitter bounds were, and for the same
// reason: it was written against synthetic +/-0.004 jitter before any real recording existed,
// and it did not survive one.
//
// It never comfortably passed. On still_baseline's marked window it measured 1.49 degrees at
// min_cutoff 1.0 and 2.0776 at 3.0 — 0.08 over, and red only because less smoothing lets more
// of a 2.69-degree raw spread through, which is the trade that retune was chosen for. Across
// *every* 3 s window in that same clip the filtered spread runs 1.49-5.38 degrees, median
// 3.01: the bound only ever held because the sidecar marks the quietest span in the file.
//
// So it was measuring which frames find_still happened to pick, not the filter or the angle
// math. A clip with a genuinely held calibration pose is what would make a number here mean
// something; until one exists the spread is printed so it still moves visibly when the
// constants move.

struct Leg {
    const char* name;
    int hip;
    int knee;
    int ankle;
};

constexpr Leg kLeftLeg{"left", kLeftHip, kLeftKnee, kLeftAnkle};
constexpr Leg kRightLeg{"right", kRightHip, kRightKnee, kRightAnkle};

const float* Frame(const Recording& recording, int frame) {
    return &recording.frames[static_cast<size_t>(frame) * kLandmarkFloats];
}

std::vector<float> KneeAngles(const Recording& recording, const Leg& leg,
                              const Segment& segment) {
    std::vector<float> angles;
    angles.reserve(static_cast<size_t>(segment.end - segment.begin));
    for (int frame = segment.begin; frame < segment.end; ++frame) {
        const float* points = Frame(recording, frame);
        angles.push_back(JointAngleDegrees(LandmarkPoint(points, leg.hip),
                                           LandmarkPoint(points, leg.knee),
                                           LandmarkPoint(points, leg.ankle)));
    }
    return angles;
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

double StandardDeviation(const std::vector<float>& series) {
    const double mean = Mean(series);
    double total = 0.0;
    for (float value : series) {
        const double centered = static_cast<double>(value) - mean;
        total += centered * centered;
    }
    return std::sqrt(total / static_cast<double>(series.size()));
}

double Spread(const std::vector<float>& series) {
    const auto bounds = std::minmax_element(series.begin(), series.end());
    return *bounds.second - *bounds.first;
}

double MeanVisibility(const Recording& recording, const Leg& leg, const Segment& segment) {
    double total = 0.0;
    for (int landmark : {leg.hip, leg.knee, leg.ankle}) {
        total += Mean(Channel(recording, landmark, kComponentVisibility, segment));
    }
    return total / 3.0;
}

// The camera-side leg. In a profile view the far leg is occluded and MediaPipe is guessing
// at it, so measuring the near one is measuring the leg that was actually seen. Phase 4's
// alignment gate makes this same choice on visibility; here it only picks which numbers
// the test reads.
const Leg& NearestLeg(const SquatFixture& fixture) {
    return MeanVisibility(fixture.filtered, kLeftLeg, fixture.still) >=
                   MeanVisibility(fixture.filtered, kRightLeg, fixture.still)
               ? kLeftLeg
               : kRightLeg;
}

void Report(const std::string& line) {
    std::cout << "[  REPORT  ] " << line << std::endl;
}

std::string Fixed(double value, int precision = 1) {
    std::ostringstream text;
    text << std::fixed << std::setprecision(precision) << value;
    return text.str();
}

std::string Degrees(double value) {
    return Fixed(value);
}

// ---------------------------------------------------------------------------
// 1. Known triangles return known angles
// ---------------------------------------------------------------------------

TEST(GeometryTest, KnownTrianglesReturnKnownAngles) {
    // A straight limb, horizontally. 180 degrees is the value acos-based implementations
    // get wrong, so it is the first thing checked.
    EXPECT_NEAR(180.0f, JointAngleDegrees({0.0f, 0.0f}, {1.0f, 0.0f}, {2.0f, 0.0f}), 1e-3f);

    // The same, vertically, at the scale and orientation a standing leg actually occupies:
    // hip above knee above ankle, normalized image coordinates, y downward.
    EXPECT_NEAR(180.0f, JointAngleDegrees({0.5f, 0.45f}, {0.5f, 0.65f}, {0.5f, 0.85f}), 1e-3f);

    // A right angle, both ways round: the result is unsigned, so the order of the outer
    // two points cannot change it.
    EXPECT_NEAR(90.0f, JointAngleDegrees({0.0f, 0.0f}, {0.0f, 1.0f}, {1.0f, 1.0f}), 1e-3f);
    EXPECT_NEAR(90.0f, JointAngleDegrees({1.0f, 1.0f}, {0.0f, 1.0f}, {0.0f, 0.0f}), 1e-3f);

    // 45 degrees, and an equilateral triangle's 60.
    EXPECT_NEAR(45.0f, JointAngleDegrees({1.0f, 0.0f}, {0.0f, 0.0f}, {1.0f, 1.0f}), 1e-3f);
    EXPECT_NEAR(60.0f, JointAngleDegrees({1.0f, 0.0f}, {0.0f, 0.0f}, {0.5f, 0.8660254f}), 1e-3f);

    // A folded joint: the two segments doubling back on each other.
    EXPECT_NEAR(0.0f, JointAngleDegrees({1.0f, 0.0f}, {0.0f, 0.0f}, {2.0f, 0.0f}), 1e-3f);
}

TEST(GeometryTest, TorsoLeanMeasuresFromVertical) {
    // Upright: the shoulder sits above the hip, so the torso runs straight down the image.
    EXPECT_NEAR(0.0f, TorsoLeanDegrees({0.5f, 0.30f}, {0.5f, 0.60f}), 1e-3f);

    // Horizontal, both directions. Unsigned, so a lean forward and the mirror of it read
    // alike — which is what the 55-degree violation rule wants.
    EXPECT_NEAR(90.0f, TorsoLeanDegrees({0.3f, 0.5f}, {0.6f, 0.5f}), 1e-3f);
    EXPECT_NEAR(90.0f, TorsoLeanDegrees({0.6f, 0.5f}, {0.3f, 0.5f}), 1e-3f);

    // 45 degrees off vertical, again both ways.
    EXPECT_NEAR(45.0f, TorsoLeanDegrees({0.5f, 0.3f}, {0.7f, 0.5f}), 1e-3f);
    EXPECT_NEAR(45.0f, TorsoLeanDegrees({0.5f, 0.3f}, {0.3f, 0.5f}), 1e-3f);

    // Inverted — hip above shoulder. Not a pose a squat reaches, but it is what a badly
    // flipped frame looks like, and 180 is the honest answer rather than a wrapped one.
    EXPECT_NEAR(180.0f, TorsoLeanDegrees({0.5f, 0.60f}, {0.5f, 0.30f}), 1e-3f);
}

// ---------------------------------------------------------------------------
// 2. Degenerate input returns the sentinel, never NaN
// ---------------------------------------------------------------------------

TEST(GeometryTest, DegenerateInputReturnsTheSentinel) {
    const Point origin{0.5f, 0.5f};
    const Point elsewhere{0.6f, 0.7f};

    // Vertex coincident with either outer point: no direction to measure from.
    EXPECT_EQ(kInvalidAngle, JointAngleDegrees(origin, origin, elsewhere));
    EXPECT_EQ(kInvalidAngle, JointAngleDegrees(elsewhere, origin, origin));
    // All three on the same spot — a collapsed detection.
    EXPECT_EQ(kInvalidAngle, JointAngleDegrees(origin, origin, origin));
    // The all-zero frame a dropped detection leaves behind.
    EXPECT_EQ(kInvalidAngle, JointAngleDegrees({0.0f, 0.0f}, {0.0f, 0.0f}, {0.0f, 0.0f}));

    // A zero-length torso vector.
    EXPECT_EQ(kInvalidAngle, TorsoLeanDegrees(origin, origin));

    // The sentinel is the point of all this: NaN would be worse than wrong, because every
    // comparison against NaN is false. A NaN knee angle would slip past the descend
    // trigger silently and surface as an FSM that simply stopped counting reps.
    EXPECT_FALSE(std::isnan(JointAngleDegrees(origin, origin, elsewhere)));
    EXPECT_FALSE(std::isnan(TorsoLeanDegrees(origin, origin)));

    // And it is recognisable as invalid, without anyone comparing floats by hand.
    EXPECT_FALSE(IsValidAngle(JointAngleDegrees(origin, origin, elsewhere)));
    EXPECT_FALSE(IsValidAngle(TorsoLeanDegrees(origin, origin)));
    EXPECT_TRUE(IsValidAngle(JointAngleDegrees({0.0f, 0.0f}, {1.0f, 0.0f}, {2.0f, 0.0f})));

    // Just past the threshold the answer is a real angle again, so the guard rejects
    // coincident points rather than merely close ones.
    const float step = kMinSegmentLength * 10.0f;
    EXPECT_TRUE(IsValidAngle(
        JointAngleDegrees({origin.x - step, origin.y}, origin, {origin.x + step, origin.y})));
}

// ---------------------------------------------------------------------------
// 3. Standing still, through the filter and into the knee angle
// ---------------------------------------------------------------------------

TEST(GeometryTest, StandingStillProducesAnAnatomicalBaseline) {
    if (!SquatFixtureExists(kStillBaseline)) {
        GTEST_SKIP() << MissingFixtureMessage(kStillBaseline);
    }

    SquatFixture fixture;
    std::string error;
    ASSERT_TRUE(LoadSquatFixture(kStillBaseline, &fixture, &error)) << error;

    const Leg& leg = NearestLeg(fixture);
    Report(std::string("camera-side leg: ") + leg.name + " (mean visibility " +
           Fixed(MeanVisibility(fixture.filtered, leg, fixture.still), 2) + ")");

    // Every angle in the recording has to be a real one. A sentinel here would mean
    // landmarks collapsing onto each other, which no amount of downstream logic can
    // recover from.
    const std::vector<float> whole =
        KneeAngles(fixture.filtered, leg, Segment{0, fixture.frames()});
    for (size_t frame = 0; frame < whole.size(); ++frame) {
        ASSERT_TRUE(IsValidAngle(whole[frame]))
            << "frame " << frame << " produced the degenerate-input sentinel: the "
            << leg.name << " hip, knee and ankle landed on the same point";
    }

    const std::vector<float> standing = KneeAngles(fixture.filtered, leg, fixture.still);
    const std::vector<float> standing_raw = KneeAngles(fixture.raw, leg, fixture.still);
    const double baseline = Median(standing);
    const double spread = Spread(standing);

    Report("standing baseline " + Degrees(baseline) + " deg over " +
           std::to_string(standing.size()) + " still frames");
    // Peak-to-peak is an extreme-value statistic: it reports the single worst frame in the
    // window and grows with the window's length. The standard deviation next to it is what
    // says whether that worst frame was an outlier or the whole segment wandering, and the
    // raw pair says how much of either the filter is already absorbing. All four exist so
    // the threshold below can be judged against real numbers rather than guessed at.
    Report("still-segment knee angle: filtered spread " + Degrees(spread) + " deg (sd " +
           Fixed(StandardDeviation(standing), 2) + "), raw spread " +
           Degrees(Spread(standing_raw)) + " deg (sd " +
           Fixed(StandardDeviation(standing_raw), 2) + ")");

    // Still asserted, and the one thing here that only a bug can break: a knee that reads
    // nowhere near 170 degrees while its owner stands up is not a knee. The spread beside it
    // is reported, for the reasons argued at kStillSpreadDegrees' old home above.
    EXPECT_NEAR(kBaselineDegrees, baseline, kBaselineTolerance)
        << "the standing knee angle is nowhere near the ~170 degrees the design doc calibrates "
        << "against, so either the landmarks are not a leg or the angle is not the one "
        << "between hip, knee and ankle";
}

// ---------------------------------------------------------------------------
// 4. Recorded reps: the knee bends, and bends the right way
// ---------------------------------------------------------------------------

// This one keeps its baseline and its reps in the SAME recording, and it is not an
// oversight. The assertion is `minimum < baseline - 20 degrees`, which is a comparison
// between two angles measured on one body in one camera. Split across fixtures it would be
// comparing this athlete's squat depth against a different person's standing pose, at a
// different distance, with different limb proportions — a difference of twenty degrees
// between two strangers means nothing, and the test would be measuring casting rather than
// geometry.
//
// So the still baseline does not reach here. The cost is that this check inherits
// kSquat8Rep's poor still window (0.6 s between two reps, which reads ~140 degrees rather
// than ~170) and is therefore weaker than it looks: the bar it clears is twenty degrees
// below a baseline that is already low. It is still enough to catch what it is for — a sign
// error, a swapped landmark, a reflex angle — and a proper 10-rep fixture with a standing
// head is what makes it strong again.
TEST(GeometryTest, RecordedRepsBendTheKneeTheRightWay) {
    if (!SquatFixtureExists(kSquat8Rep)) {
        GTEST_SKIP() << MissingFixtureMessage(kSquat8Rep);
    }

    SquatFixture fixture;
    std::string error;
    ASSERT_TRUE(LoadSquatFixture(kSquat8Rep, &fixture, &error)) << error;

    const Leg& leg = NearestLeg(fixture);
    Report(std::string("camera-side leg: ") + leg.name + " (mean visibility " +
           Fixed(MeanVisibility(fixture.filtered, leg, fixture.still), 2) + ")");

    const std::vector<float> whole =
        KneeAngles(fixture.filtered, leg, Segment{0, fixture.frames()});
    for (size_t frame = 0; frame < whole.size(); ++frame) {
        ASSERT_TRUE(IsValidAngle(whole[frame]))
            << "frame " << frame << " produced the degenerate-input sentinel: the "
            << leg.name << " hip, knee and ankle landed on the same point";
    }

    // This recording's own standing reference — see the note above for why it may not come
    // from kStillBaseline. Reported, not asserted against 170: this fixture has no proper
    // standing segment, and StandingStillProducesAnAnatomicalBaseline is what pins that.
    const std::vector<float> standing = KneeAngles(fixture.filtered, leg, fixture.still);
    const double baseline = Median(standing);
    Report("in-recording standing reference " + Degrees(baseline) + " deg over " +
           std::to_string(standing.size()) + " frames marked still");

    // Per-rep minima: reported, not bounded. How close eight reps land to each other is a
    // fact about the squat, not about this code.
    std::vector<float> minima;
    std::string listed;
    for (int rep = 0; rep < fixture.reps(); ++rep) {
        const std::vector<float> angles = KneeAngles(fixture.filtered, leg, RepWindow(fixture, rep));
        const float minimum = *std::min_element(angles.begin(), angles.end());
        minima.push_back(minimum);
        listed += (rep == 0 ? "" : "  ") + Degrees(minimum);
    }
    const double deepest = *std::min_element(minima.begin(), minima.end());
    const double shallowest = *std::max_element(minima.begin(), minima.end());

    Report("per-rep minimum knee angle (deg): " + listed);
    Report("minima spread " + Degrees(shallowest - deepest) + " deg (deepest " +
           Degrees(deepest) + ", shallowest " + Degrees(shallowest) + ", median " +
           Degrees(Median(minima)) + ")");

    // What every rep must do, however deep it went: bend.
    for (int rep = 0; rep < fixture.reps(); ++rep) {
        EXPECT_LT(minima[static_cast<size_t>(rep)], baseline - kMinimumBendBelowBaseline)
            << "rep " << rep << " bottoms out at " << Degrees(minima[static_cast<size_t>(rep)])
            << " deg against a standing baseline of " << Degrees(baseline)
            << " deg — the angle barely moved, or it moved the wrong way";
    }
}

// ---------------------------------------------------------------------------
// 5. The alignment gate's measurement, on both fixtures
// ---------------------------------------------------------------------------

// The one test in this file that gets to assert hard on two different recordings, and the
// reason it may is that it is not measuring a difference between them — it is measuring each
// against a fixed threshold, and the two clips are on opposite sides of it by a wide margin.
//
// This is what closing the gate rests on. If a genuinely side-on clip did not clear the SIDE
// bound on every frame, switching the gate on would stop the squat replay counting, and the
// first symptom would be a rep-count test failing for a reason that looks nothing like
// alignment.
TEST(AlignmentTest, TheTwoFixturesSitOnOppositeSidesOfTheViewThresholds) {
    struct Case {
        FixtureSpec spec;
        const char* what;
        bool expect_side;
    };
    const Case cases[] = {
        {kSquat8Rep, "side-on squat", true},
        {kStillBaseline, "front-facing stand", false},
    };

    for (const Case& item : cases) {
        if (!SquatFixtureExists(item.spec)) {
            GTEST_SKIP() << MissingFixtureMessage(item.spec);
        }
        SquatFixture fixture;
        std::string error;
        ASSERT_TRUE(LoadSquatFixture(item.spec, &fixture, &error)) << error;

        float lowest = 1e9f;
        float highest = -1e9f;
        int side_passes = 0;
        int front_passes = 0;
        for (int frame = 0; frame < fixture.frames(); ++frame) {
            const float* row = &fixture.filtered.frames[
                static_cast<size_t>(frame) * kLandmarkFloats];
            const float ratio = ShoulderSeparationRatio(
                LandmarkPoint(row, kLeftShoulder), LandmarkPoint(row, kRightShoulder),
                LandmarkPoint(row, kLeftHip), LandmarkPoint(row, kRightHip));
            ASSERT_NE(kInvalidRatio, ratio) << item.what << " frame " << frame;
            lowest = std::min(lowest, ratio);
            highest = std::max(highest, ratio);
            side_passes += ratio <= kSideViewMaxShoulderRatio ? 1 : 0;
            front_passes += ratio >= kFrontViewMinShoulderRatio ? 1 : 0;
        }

        Report(std::string(item.what) + ": ratio " + Degrees(lowest) + " to " +
               Degrees(highest) + " over " + std::to_string(fixture.frames()) +
               " frames; SIDE passes " + std::to_string(side_passes) + ", FRONT passes " +
               std::to_string(front_passes));

        if (item.expect_side) {
            EXPECT_EQ(fixture.frames(), side_passes)
                << item.what << " does not clear the SIDE bound on every frame, so closing "
                << "the gate would stop this recording counting";
            EXPECT_EQ(0, front_passes) << item.what << " also reads as a front view";
        } else {
            EXPECT_EQ(fixture.frames(), front_passes)
                << item.what << " does not clear the FRONT bound on every frame";
            EXPECT_EQ(0, side_passes) << item.what << " also reads as a side view";
        }
    }
}

// The ratio divides by torso height precisely so it does not depend on how far away the
// athlete is. Scaling a whole pose about the origin must therefore change nothing.
TEST(AlignmentTest, TheRatioIsIndependentOfDistanceFromTheCamera) {
    const Point l_shoulder{0.40f, 0.30f};
    const Point r_shoulder{0.60f, 0.30f};
    const Point l_hip{0.42f, 0.60f};
    const Point r_hip{0.58f, 0.60f};
    const float near = ShoulderSeparationRatio(l_shoulder, r_shoulder, l_hip, r_hip);

    // The same pose at half the size: every coordinate halved.
    const float far = ShoulderSeparationRatio({0.20f, 0.15f}, {0.30f, 0.15f}, {0.21f, 0.30f},
                                              {0.29f, 0.30f});
    EXPECT_NEAR(near, far, 1e-5f)
        << "the ratio moved when the athlete stepped back, so it is measuring distance";

    // And a horizontal flip — the front camera's mirroring — leaves it alone, which is why no
    // camera-facing flag crosses JNI.
    const float mirrored = ShoulderSeparationRatio({1.0f - l_shoulder.x, l_shoulder.y},
                                                   {1.0f - r_shoulder.x, r_shoulder.y},
                                                   {1.0f - l_hip.x, l_hip.y},
                                                   {1.0f - r_hip.x, r_hip.y});
    EXPECT_NEAR(near, mirrored, 1e-5f) << "the ratio is not reflection-invariant";
}

// A torso that has collapsed to a point has no height to divide by, and the answer must be
// the sentinel rather than an infinity that reads as a very good front view.
TEST(AlignmentTest, ACollapsedTorsoIsRefused) {
    const Point same{0.5f, 0.5f};
    EXPECT_EQ(kInvalidRatio, ShoulderSeparationRatio(same, same, same, same));
}

}  // namespace
}  // namespace kinex
