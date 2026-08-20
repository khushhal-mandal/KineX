// Host-side tests for the normalization rule — no device, no emulator: `make test`.
//
// The claim under test is the design doc's: that one signed division makes the FSM
// direction-agnostic, so an exercise that closes a joint at peak contraction and one that
// opens it need the same code and differ only in a config row.
//
// The proof is test 2. It runs a squat (170 -> 90) and a shoulder press (90 -> 170) through
// the same function and asserts they produce identical progress at mirrored angles. If that
// ever fails, the engine has grown a notion of "down" and adding an exercise is no longer a
// config row.

#include "exercises/exercise_config.h"

#include <gtest/gtest.h>

#include <cmath>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>

#include "geometry.h"

namespace kinex {
namespace {

// The design doc's FSM thresholds, in progress units, identical for every exercise. They belong
// to the FSM and will live in rep_fsm.h once the refactor lands; they are repeated here so
// this test can report what they mean in degrees for each exercise before that happens.
constexpr float kAdvanceTriggerProgress = 0.30f;
constexpr float kRepCompleteProgress = 0.20f;

// The exercises in the design doc's table, with the typical start angles it lists. The starts are
// "what to expect, not a constant to hardcode" — they are here to report against, and no
// assertion depends on any of them.
//
// **Every start below is still an estimate. Eight of the ten targets are not.** The targets
// were sourced from published kinesiology — AAOS ROM norms where the movement ends at end
// range, a technique endpoint where one is published — and each is cited at its constant in
// exercises/exercise_config.cpp. The two that no published figure covers are marked "(est.)"
// below, and are the sit-up's 65 and the jumping jack's 160. The marker means the *target* is
// a guess; every start in this table is one regardless.
struct Exercise {
    const char* name;
    float typical_start_degrees;
    float target_degrees;
};

constexpr Exercise kTable[] = {
    {"squat", 170.0f, 90.0f},
    {"push-up", 170.0f, 90.0f},
    {"bicep curl", 170.0f, 30.0f},
    {"shoulder press", 90.0f, 180.0f},
    {"lateral raise", 15.0f, 90.0f},
    // The sit-up's start is an estimate for a knees-bent supine pose, not a measured one —
    // no fixture exists — and its target is the one ACSM's curl-up protocol does not answer,
    // because 30 degrees of spinal flexion is not this hip angle. Both columns are guesses.
    {"sit-up (est.)", 135.0f, 65.0f},
    {"lunge", 170.0f, 90.0f},
    {"glute bridge", 120.0f, 180.0f},
    // Jumping jack took id 8 from the tricep extension, which was removed rather than
    // retuned: its elbow was not trackable overhead. Its target is the second of the two with
    // no published endpoint — AAOS's 180-degree abduction ceiling is not a prescription.
    {"jumping jack (est.)", 15.0f, 160.0f},
    {"leg raise", 170.0f, 90.0f},
};

// The two rows whose angle runs upward, with the estimated start each pairs with. The starts
// are not in ExerciseConfig — calibration captures one per athlete — so a test that wants to
// normalize a real config has to supply one, and these are the design doc's estimates.
constexpr float kGluteBridgeEstimatedStartDegrees = 120.0f;
constexpr float kJumpingJackEstimatedStartDegrees = 15.0f;

void Report(const std::string& line) {
    std::cout << "[  REPORT  ] " << line << std::endl;
}

std::string Fixed(double value, int precision = 2) {
    std::ostringstream text;
    text << std::fixed << std::setprecision(precision) << value;
    return text.str();
}

float ProgressOrDie(float angle, float start, float target) {
    float progress = 0.0f;
    EXPECT_TRUE(RepProgress(angle, start, target, &progress))
        << "angle " << angle << " against start " << start << " target " << target;
    return progress;
}

// The angle at which a given progress is reached — the inverse, for reporting in degrees.
float AngleAt(float progress, float start, float target) {
    return start + progress * (target - start);
}

// ---------------------------------------------------------------------------
// 1. The squat maps to the numbers the design doc states
// ---------------------------------------------------------------------------

TEST(RepProgressTest, SquatMapsToTheDocumentedProgressValues) {
    constexpr float kStart = 170.0f;
    constexpr float kTarget = 90.0f;  // span -80

    EXPECT_NEAR(0.00f, ProgressOrDie(kStart, kStart, kTarget), 1e-5f);
    EXPECT_NEAR(1.00f, ProgressOrDie(kTarget, kStart, kTarget), 1e-5f);

    // The three thresholds, in degrees: advance at 146, complete at 154, peak at 102.
    EXPECT_NEAR(kAdvanceTriggerProgress, ProgressOrDie(146.0f, kStart, kTarget), 1e-5f);
    EXPECT_NEAR(kRepCompleteProgress, ProgressOrDie(154.0f, kStart, kTarget), 1e-5f);
    EXPECT_NEAR(kDefaultDepthPass, ProgressOrDie(102.0f, kStart, kTarget), 1e-5f);

    // Progress rises as the athlete descends, which is the direction every FSM comparison
    // will assume.
    EXPECT_LT(ProgressOrDie(160.0f, kStart, kTarget), ProgressOrDie(120.0f, kStart, kTarget));
}

// ---------------------------------------------------------------------------
// 2. A reversed exercise produces identical progress — the whole claim
// ---------------------------------------------------------------------------

TEST(RepProgressTest, ReversedExerciseProducesIdenticalProgress) {
    // Squat: the joint closes at peak contraction. Shoulder press: it opens. Same span
    // magnitude, opposite sign.
    constexpr float kSquatStart = 170.0f;
    constexpr float kSquatTarget = 90.0f;
    constexpr float kPressStart = 90.0f;
    constexpr float kPressTarget = 170.0f;

    // Every tenth of progress, both exercises. The angles run in opposite directions and
    // the progress values are the same to the last bit.
    for (int step = 0; step <= 10; ++step) {
        const float expected = static_cast<float>(step) / 10.0f;
        const float squat_angle = AngleAt(expected, kSquatStart, kSquatTarget);
        const float press_angle = AngleAt(expected, kPressStart, kPressTarget);

        const float squat = ProgressOrDie(squat_angle, kSquatStart, kSquatTarget);
        const float press = ProgressOrDie(press_angle, kPressStart, kPressTarget);

        EXPECT_NEAR(expected, squat, 1e-5f) << "squat at " << squat_angle << " deg";
        EXPECT_NEAR(expected, press, 1e-5f) << "press at " << press_angle << " deg";
        EXPECT_NEAR(squat, press, 1e-5f)
            << "progress " << expected << ": the squat reads " << squat << " at "
            << squat_angle << " deg and the press reads " << press << " at " << press_angle
            << " deg. The engine has grown a notion of direction";
    }

    // And the two move in opposite angular directions while both counting up.
    EXPECT_GT(AngleAt(0.0f, kSquatStart, kSquatTarget), AngleAt(1.0f, kSquatStart, kSquatTarget));
    EXPECT_LT(AngleAt(0.0f, kPressStart, kPressTarget), AngleAt(1.0f, kPressStart, kPressTarget));
}

// ---------------------------------------------------------------------------
// 2b. The increasing-angle rows, on the numbers the engine actually runs
// ---------------------------------------------------------------------------

// The glute bridge (120 -> 170) and the jumping jack (15 -> 160) both open their joint toward
// the peak, so (target - start) is positive and the angle climbs while progress does.
//
// Test 2 above already proves the signed division is direction-agnostic, but it proves it
// against literals it declares itself. This one reads target_angle_degrees off the real
// configs, so a row that lands with its target on the wrong side of its start — a glute
// bridge written as 170 -> 120 by someone copying the sit-up beside it — fails here rather
// than counting backwards on a device. The starts are still estimates supplied by the test;
// calibration owns that number and there is nothing in the config to read it from.
TEST(RepProgressTest, IncreasingAngleConfigsRiseFromZeroTowardOne) {
    struct Row {
        const char* name;
        ExerciseConfig config;
        float estimated_start_degrees;
    };
    const Row rows[] = {
        {"glute bridge", GluteBridgeConfig(), kGluteBridgeEstimatedStartDegrees},
        {"jumping jack", JumpingJackConfig(), kJumpingJackEstimatedStartDegrees},
    };

    for (const Row& row : rows) {
        const float start = row.estimated_start_degrees;
        const float target = row.config.target_angle_degrees;

        // The premise: these rows run upward. A row that fails this is one whose target and
        // start have been swapped, and every assertion below it would be measuring the wrong
        // claim.
        ASSERT_GT(target, start) << row.name << ": target does not sit above the start, so "
                                 << "this is not an increasing-angle exercise";

        EXPECT_NEAR(0.0f, ProgressOrDie(start, start, target), 1e-5f) << row.name;
        EXPECT_NEAR(1.0f, ProgressOrDie(target, start, target), 1e-5f) << row.name;

        // Every tenth of the way up. Progress rises monotonically with the angle, which is
        // the direction every FSM comparison assumes for every exercise.
        float previous_progress = ProgressOrDie(start, start, target);
        for (int step = 1; step <= 10; ++step) {
            const float fraction = static_cast<float>(step) / 10.0f;
            const float angle = start + fraction * (target - start);
            const float progress = ProgressOrDie(angle, start, target);

            EXPECT_NEAR(fraction, progress, 1e-5f)
                << row.name << " at " << angle << " deg";
            EXPECT_GT(progress, previous_progress)
                << row.name << ": the angle rose to " << angle
                << " deg and progress did not rise with it";
            previous_progress = progress;
        }

        // The FSM's own thresholds land inside the span and in the right order, which is what
        // makes the shared progress numbers mean anything on a row that runs this way.
        const float advance = AngleAt(kAdvanceTriggerProgress, start, target);
        const float complete = AngleAt(kRepCompleteProgress, start, target);
        const float peak = AngleAt(row.config.depth_pass, start, target);
        EXPECT_LT(start, complete) << row.name;
        EXPECT_LT(complete, advance) << row.name;
        EXPECT_LT(advance, peak) << row.name;
        EXPECT_LT(peak, target) << row.name;
    }
}

// ---------------------------------------------------------------------------
// 3. Progress is unclamped, both ends
// ---------------------------------------------------------------------------

TEST(RepProgressTest, ProgressIsUnclampedAtBothEnds) {
    constexpr float kStart = 170.0f;
    constexpr float kTarget = 90.0f;

    // Standing straighter than calibration. Negative, and the FSM needs to see it as
    // "further from a rep than the start pose" rather than as zero.
    EXPECT_LT(ProgressOrDie(178.0f, kStart, kTarget), 0.0f);

    // Deeper than the configured peak. Above 1, which is a rep that exceeded its target
    // rather than a rep that merely met it.
    EXPECT_GT(ProgressOrDie(80.0f, kStart, kTarget), 1.0f);

    // The same, reversed.
    EXPECT_LT(ProgressOrDie(85.0f, 90.0f, 170.0f), 0.0f);
    EXPECT_GT(ProgressOrDie(175.0f, 90.0f, 170.0f), 1.0f);
}

// ---------------------------------------------------------------------------
// 4. Degenerate input is refused rather than answered
// ---------------------------------------------------------------------------

TEST(RepProgressTest, DegenerateInputIsRefused) {
    float progress = 12345.0f;

    // geometry's sentinel for landmarks that collapsed onto each other. -1 is not an angle,
    // and normalizing it would hand the FSM a confident wrong number.
    EXPECT_FALSE(RepProgress(kInvalidAngle, 170.0f, 90.0f, &progress));
    EXPECT_FLOAT_EQ(12345.0f, progress) << "a refused call still wrote to the out parameter";

    // A calibration that never happened: start and target the same pose.
    EXPECT_FALSE(RepProgress(150.0f, 170.0f, 170.0f, &progress));
    EXPECT_FALSE(RepProgress(150.0f, 170.0f, 170.5f, &progress));
    EXPECT_FLOAT_EQ(12345.0f, progress);

    // Just past the span guard it answers again, so the guard rejects a failed calibration
    // rather than a narrow one.
    EXPECT_TRUE(RepProgress(150.0f, 170.0f, 168.0f, &progress));
    EXPECT_FALSE(std::isnan(progress));
}

// ---------------------------------------------------------------------------
// 5. The squat config is the design doc's row
// ---------------------------------------------------------------------------

TEST(ExerciseConfigTest, SquatConfigMatchesTheDocumentedRow) {
    const ExerciseConfig squat = SquatConfig();

    EXPECT_EQ(View::kSide, squat.view);
    EXPECT_EQ(kLeftHip, squat.joint_a);
    EXPECT_EQ(kLeftKnee, squat.joint_b);
    EXPECT_EQ(kLeftAnkle, squat.joint_c);
    EXPECT_FLOAT_EQ(90.0f, squat.target_angle_degrees);
    EXPECT_FLOAT_EQ(kDefaultDepthPass, squat.depth_pass);

    // The one exercise with validated violation rules.
    EXPECT_EQ(static_cast<uint32_t>(kViolationDepthMiss | kViolationTorsoLean),
              squat.violation_rules);
    EXPECT_EQ(0u, squat.violation_rules & ~static_cast<uint32_t>(kViolationMaskAll));
}

TEST(ExerciseConfigTest, SitUpConfigMatchesTheDocumentedRow) {
    const ExerciseConfig sit_up = SitUpConfig();

    EXPECT_EQ(View::kSide, sit_up.view);
    EXPECT_EQ(kLeftShoulder, sit_up.joint_a);
    EXPECT_EQ(kLeftHip, sit_up.joint_b);
    EXPECT_EQ(kLeftKnee, sit_up.joint_c);
    EXPECT_FLOAT_EQ(65.0f, sit_up.target_angle_degrees);
    EXPECT_FLOAT_EQ(kDefaultDepthPass, sit_up.depth_pass);

    // Rep counting only. The design doc: no exercise gets violation rules without a fixture, and
    // this one has none. If this ever fails, a rule was copied across rather than validated.
    EXPECT_EQ(0u, sit_up.violation_rules);

    // Distinct from the squat, or the config table cannot tell them apart.
    EXPECT_NE(SquatConfig().id, sit_up.id);
}

// The four rows added in Phase 5, against the design doc's table. Written out one field at a time
// rather than looped, because a loop over a second copy of the same numbers would only be
// asserting that the copy was made accurately.
TEST(ExerciseConfigTest, TheRemainingRowsMatchTheDocumentedTable) {
    const ExerciseConfig push_up = PushUpConfig();
    EXPECT_EQ(View::kSide, push_up.view);
    EXPECT_EQ(kLeftShoulder, push_up.joint_a);
    EXPECT_EQ(kLeftElbow, push_up.joint_b);
    EXPECT_EQ(kLeftWrist, push_up.joint_c);
    EXPECT_FLOAT_EQ(90.0f, push_up.target_angle_degrees);

    const ExerciseConfig curl = BicepCurlConfig();
    EXPECT_EQ(View::kSide, curl.view);
    EXPECT_EQ(kLeftShoulder, curl.joint_a);
    EXPECT_EQ(kLeftElbow, curl.joint_b);
    EXPECT_EQ(kLeftWrist, curl.joint_c);
    // 180 - 150, AAOS elbow flexion. Was an estimated 45.
    EXPECT_FLOAT_EQ(30.0f, curl.target_angle_degrees);

    const ExerciseConfig press = ShoulderPressConfig();
    EXPECT_EQ(View::kFront, press.view);
    EXPECT_EQ(kLeftShoulder, press.joint_a);
    EXPECT_EQ(kLeftElbow, press.joint_b);
    EXPECT_EQ(kLeftWrist, press.joint_c);
    // Full elbow extension at lockout, which AAOS puts at 0 degrees of flexion. Was 170.
    EXPECT_FLOAT_EQ(180.0f, press.target_angle_degrees);

    const ExerciseConfig raise = LateralRaiseConfig();
    EXPECT_EQ(View::kFront, raise.view);
    EXPECT_EQ(kLeftHip, raise.joint_a);
    EXPECT_EQ(kLeftShoulder, raise.joint_b);
    EXPECT_EQ(kLeftElbow, raise.joint_c);
    EXPECT_FLOAT_EQ(90.0f, raise.target_angle_degrees);
}

// Ids 6..9, against the design doc's table. Three of these four targets are published figures now
// and the jumping jack's is still an estimate, and both kinds are pinned for the same reason:
// a number that drifts silently is indistinguishable from one somebody chose, and the day a
// fixture or a source replaces one it should be a deliberate edit with a citation behind it
// rather than something that happened.
TEST(ExerciseConfigTest, TheFourRowsThatTookTheTableToTenMatchTheDocumentedTable) {
    const ExerciseConfig lunge = LungeConfig();
    EXPECT_EQ(View::kSide, lunge.view);
    EXPECT_EQ(kLeftHip, lunge.joint_a);
    EXPECT_EQ(kLeftKnee, lunge.joint_b);
    EXPECT_EQ(kLeftAnkle, lunge.joint_c);
    EXPECT_FLOAT_EQ(90.0f, lunge.target_angle_degrees);
    // The squat's geometry without the squat's rules, which is the whole point of the row
    // being separate. OnlyTheSquatCarriesViolationRules covers the table; this says it about
    // the one row where copying them across would have looked most reasonable.
    EXPECT_EQ(0u, lunge.violation_rules);
    EXPECT_NE(SquatConfig().id, lunge.id);

    const ExerciseConfig bridge = GluteBridgeConfig();
    EXPECT_EQ(View::kSide, bridge.view);
    EXPECT_EQ(kLeftShoulder, bridge.joint_a);
    EXPECT_EQ(kLeftHip, bridge.joint_b);
    EXPECT_EQ(kLeftKnee, bridge.joint_c);
    // Shoulder, hip and knee in line at the top — the three landmarks this row reads. Was 170.
    EXPECT_FLOAT_EQ(180.0f, bridge.target_angle_degrees);

    // Jumping jack, on id 8, which the tricep extension used to hold. Front view and the
    // lateral raise's joint triple carried much further round.
    const ExerciseConfig jack = JumpingJackConfig();
    EXPECT_EQ(View::kFront, jack.view);
    EXPECT_EQ(kLeftHip, jack.joint_a);
    EXPECT_EQ(kLeftShoulder, jack.joint_b);
    EXPECT_EQ(kLeftElbow, jack.joint_c);
    EXPECT_FLOAT_EQ(160.0f, jack.target_angle_degrees);
    EXPECT_FLOAT_EQ(15.0f, jack.nominal_start_degrees);

    const ExerciseConfig leg_raise = LegRaiseConfig();
    EXPECT_EQ(View::kSide, leg_raise.view);
    EXPECT_EQ(kLeftShoulder, leg_raise.joint_a);
    EXPECT_EQ(kLeftHip, leg_raise.joint_b);
    EXPECT_EQ(kLeftAnkle, leg_raise.joint_c);
    EXPECT_FLOAT_EQ(90.0f, leg_raise.target_angle_degrees);
}

// ---------------------------------------------------------------------------
// 5c. The calibration guard, against the angles the 20 Aug sweep actually measured
// ---------------------------------------------------------------------------

// The case the guard exists for. A shoulder press calibrated in the finish pose measured 174
// degrees against what was then a 170-degree target: a 4-degree span, which the old absolute
// floor of 1 degree happily accepted and which turned every degree of movement into a quarter
// of a rep. The device recorded a peak of 38.75 and pinned the HUD ring full for the whole
// set. The target is 180 now, so the same capture reads a 6-degree span — the collapse is
// slightly less extreme and the verdict is unchanged, which is the point of asserting it
// against the config rather than against a remembered number.
TEST(CalibrationGuardTest, RefusesTheShoulderPressSpanCollapse) {
    const ExerciseConfig press = ShoulderPressConfig();
    EXPECT_FALSE(IsCalibrationPlausible(press, 174.0f))
        << "a 4-degree span was accepted; this is the failure the guard was added for";

    // The old floor is what let it through, so pin that this is a genuinely new verdict
    // rather than something the absolute check would have caught anyway.
    EXPECT_GT(std::fabs(press.target_angle_degrees - 174.0f), kMinCalibrationSpanDegrees);

    // A press calibrated in the pose it documents is fine.
    EXPECT_TRUE(IsCalibrationPlausible(press, press.nominal_start_degrees));
}

// Every other start the sweep measured has to keep working, including the two that missed
// their estimate badly. The guard is about a collapsed span, not about a pose disagreeing
// with a guess — a sit-up that starts 40 degrees from where the design doc predicted is still a
// sit-up, and refusing it would be the guard doing harm.
TEST(CalibrationGuardTest, AcceptsEveryOtherStartTheSweepMeasured) {
    struct Measured {
        const char* name;
        ExerciseConfig config;
        float start_degrees;
    };
    const Measured measured[] = {
        {"squat 171", SquatConfig(), 171.0f},
        {"push-up 154 (16 under its estimate)", PushUpConfig(), 154.0f},
        {"bicep curl 173", BicepCurlConfig(), 173.0f},
        {"lateral raise 25 (10 over)", LateralRaiseConfig(), 25.0f},
        {"sit-up 175 (40 over)", SitUpConfig(), 175.0f},
        {"lunge 178", LungeConfig(), 178.0f},
        {"glute bridge 75 (45 under)", GluteBridgeConfig(), 75.0f},
        {"leg raise 170", LegRaiseConfig(), 170.0f},
    };
    for (const Measured& row : measured) {
        EXPECT_TRUE(IsCalibrationPlausible(row.config, row.start_degrees))
            << row.name << " was refused; the guard is rejecting real poses";
    }
}

// The rule, stated as a rule rather than as a list of cases: exactly half the configured span
// is the boundary, and it is inclusive.
TEST(CalibrationGuardTest, HalfTheConfiguredSpanIsTheBoundary) {
    for (int id = 0; id < kExerciseCount; ++id) {
        const ExerciseConfig config = ConfigForExercise(id);
        const float span = config.target_angle_degrees - config.nominal_start_degrees;
        const float half = config.nominal_start_degrees + span * 0.5f;

        // Exactly half the way in: accepted, so a demanding calibration is not punished.
        EXPECT_TRUE(IsCalibrationPlausible(config, half)) << "exercise id " << config.id;

        // Nine tenths of the way to the target: refused.
        EXPECT_FALSE(IsCalibrationPlausible(config, config.nominal_start_degrees + span * 0.9f))
            << "exercise id " << config.id;

        // And a start on the far side of the nominal — further from the target than expected
        // — is always fine. Standing straighter than the table predicted is not an error.
        EXPECT_TRUE(IsCalibrationPlausible(config, config.nominal_start_degrees - span * 0.5f))
            << "exercise id " << config.id;
    }
}

// Every row carries a nominal, and it is on the correct side of the target — otherwise the
// guard would be measuring against a span of the wrong sign.
TEST(ExerciseConfigTest, EveryRowCarriesAUsableNominalStart) {
    for (int id = 0; id < kExerciseCount; ++id) {
        const ExerciseConfig config = ConfigForExercise(id);
        const float span =
            std::fabs(config.target_angle_degrees - config.nominal_start_degrees);
        EXPECT_GT(span, kMinCalibrationSpanDegrees)
            << "exercise id " << config.id << " has a nominal start on top of its target";
        // Every row in the design doc's table covers a real range of motion. The narrowest is the
        // glute bridge at 60 degrees — 120 nominal to a 180 target — with the sit-up at 70 and
        // the lateral raise at 75 behind it; anything much under that is a typo, not an
        // exercise. This comment used to name the lateral raise as narrowest, which was wrong
        // from the moment ids 6..9 landed: the bridge was 50 degrees on its estimated 170
        // target, and sourcing that target from the shoulder-hip-knee line *widened* it to 60.
        EXPECT_GE(span, 40.0f) << "exercise id " << config.id << " spans only " << span
                               << " degrees, which no row in the table does";
    }
}

// ---------------------------------------------------------------------------
// 5d. The anatomical plausibility bound
// ---------------------------------------------------------------------------

TEST(PlausibleAngleTest, RejectsWhatNoJointCanFormAndKeepsWhatTheSweepMeasured) {
    // The readings that produced rep peaks of 2.02, 2.08 and 2.09 on the device: a joint
    // folded to nothing, which is the pose model collapsing landmarks rather than a body.
    EXPECT_FALSE(IsPlausibleJointAngle(0.0f));
    EXPECT_FALSE(IsPlausibleJointAngle(3.0f));
    EXPECT_FALSE(IsPlausibleJointAngle(9.9f));

    // geometry's degeneracy sentinel is negative and must not read as a tiny valid angle.
    EXPECT_FALSE(IsPlausibleJointAngle(kInvalidAngle));

    // The tightest genuine readings from the sweep. Rejecting either would delete real reps:
    // 19 degrees is the top of a bicep curl, 25 the top of a leg raise.
    EXPECT_TRUE(IsPlausibleJointAngle(19.0f));
    EXPECT_TRUE(IsPlausibleJointAngle(25.0f));

    // And the straight-limb end, which every start pose in the table sits near.
    EXPECT_TRUE(IsPlausibleJointAngle(180.0f));
    EXPECT_FALSE(IsPlausibleJointAngle(181.0f));
}

// AngleAtProgress is the inverse the FSM needs to ask the plausibility question at all, so it
// has to round-trip against RepProgress rather than merely look right.
TEST(PlausibleAngleTest, AngleAtProgressInvertsRepProgress) {
    for (int id = 0; id < kExerciseCount; ++id) {
        const ExerciseConfig config = ConfigForExercise(id);
        const float start = config.nominal_start_degrees;
        const float target = config.target_angle_degrees;
        for (float progress : {-0.2f, 0.0f, 0.5f, 1.0f, 2.09f}) {
            const float angle = AngleAtProgress(progress, start, target);

            // A large progress on a wide-span row runs the implied angle off the end of what
            // an angle can be — 2.09 on the bicep curl's 125-degree span implies -91 degrees.
            // RepProgress refuses that, correctly, so there is no round trip to assert. It is
            // also exactly the case the plausibility check is there to catch: an impossible
            // peak resolves to an impossible angle, and the rep is discarded.
            if (!IsValidAngle(angle)) {
                EXPECT_FALSE(IsPlausibleJointAngle(angle))
                    << "exercise id " << config.id << ": an out-of-range angle was judged "
                    << "plausible, so an impossible peak would be recorded";
                continue;
            }

            float round_trip = 0.0f;
            ASSERT_TRUE(RepProgress(angle, start, target, &round_trip))
                << "exercise id " << config.id << " progress " << progress;
            EXPECT_NEAR(progress, round_trip, 1e-4f) << "exercise id " << config.id;
        }
    }
}

// ---------------------------------------------------------------------------
// 5b. The table itself
// ---------------------------------------------------------------------------

// Every id in range selects the row that claims that id. This is what "adding an exercise is
// a config row" rests on: a row whose id disagrees with its slot is one that counts a
// different exercise than the picker asked for, and nothing downstream can notice.
TEST(ExerciseConfigTest, EveryIdSelectsTheRowThatClaimsIt) {
    for (int id = 0; id < kExerciseCount; ++id) {
        EXPECT_EQ(id, ConfigForExercise(id).id)
            << "id " << id << " selected the row for exercise " << ConfigForExercise(id).id;
    }
}

// The ten named constructors and the ten table slots are the same ten rows. Catches a row
// added to one and forgotten in the other, which is the failure the switch invites.
TEST(ExerciseConfigTest, TheTableHoldsEveryNamedConfig) {
    const ExerciseConfig named[] = {SquatConfig(),        PushUpConfig(),
                                    BicepCurlConfig(),    ShoulderPressConfig(),
                                    LateralRaiseConfig(), SitUpConfig(),
                                    LungeConfig(),        GluteBridgeConfig(),
                                    JumpingJackConfig(),  LegRaiseConfig()};
    ASSERT_EQ(static_cast<size_t>(kExerciseCount), sizeof(named) / sizeof(named[0]))
        << "kExerciseCount and the list of named configs disagree";

    for (const ExerciseConfig& config : named) {
        const ExerciseConfig from_table = ConfigForExercise(config.id);
        EXPECT_EQ(config.id, from_table.id);
        EXPECT_EQ(config.view, from_table.view);
        EXPECT_EQ(config.joint_a, from_table.joint_a);
        EXPECT_EQ(config.joint_b, from_table.joint_b);
        EXPECT_EQ(config.joint_c, from_table.joint_c);
        EXPECT_FLOAT_EQ(config.target_angle_degrees, from_table.target_angle_degrees);
        EXPECT_FLOAT_EQ(config.depth_pass, from_table.depth_pass);
        EXPECT_EQ(config.violation_rules, from_table.violation_rules);
    }
}

// The documented behaviour for an id nothing in the table claims. Asserted rather than left
// to chance because the alternative — a struct with whatever the switch fell through to in
// it — is the one outcome that produces reps against uninitialized thresholds.
TEST(ExerciseConfigTest, AnUnknownIdFallsBackToTheSquat) {
    for (int id : {-1, kExerciseCount, 99}) {
        EXPECT_EQ(SquatConfig().id, ConfigForExercise(id).id) << "id " << id;
    }
}

// The precondition rep_fsm.h states and does not check: depth_pass has to sit above the
// advance trigger. Below it, every attempt clears the peak by definition — nothing reaches
// kAdvancing without crossing the trigger — and kViolationDepthMiss becomes unreachable.
// Walks the table rather than a list of configs, so a new row cannot land in breach.
TEST(ExerciseConfigTest, EveryConfigLeavesDepthMissReachable) {
    for (int id = 0; id < kExerciseCount; ++id) {
        const ExerciseConfig config = ConfigForExercise(id);
        EXPECT_GT(config.depth_pass, kAdvanceTriggerProgress)
            << "exercise id " << config.id << ": depth_pass sits at or below the advance "
            << "trigger, so every attempt reaches the peak and depth miss can never fire";
    }
}

// The design doc, and the reason nine of these ten rows exist at all: rep counting generalizes
// across exercises, form violations do not. The squat is the only exercise with a fixture,
// so it is the only one allowed a violation rule. A failure here means a rule was copied
// onto an unvalidated exercise, which ships wrong form advice.
TEST(ExerciseConfigTest, OnlyTheSquatCarriesViolationRules) {
    for (int id = 0; id < kExerciseCount; ++id) {
        const ExerciseConfig config = ConfigForExercise(id);
        if (config.id == SquatConfig().id) {
            EXPECT_NE(0u, config.violation_rules);
        } else {
            EXPECT_EQ(0u, config.violation_rules)
                << "exercise id " << config.id << " carries violation rules, and no fixture "
                << "exists that could have validated them";
        }
        EXPECT_EQ(0u, config.violation_rules & ~static_cast<uint32_t>(kViolationMaskAll))
            << "exercise id " << config.id << " sets a bit outside kViolationMaskAll";
    }
}

// The angle a config measures has to be one three distinct landmarks can form. Two indices
// the same is a triple that collapses to a straight line or to nothing, which geometry.h
// answers with its degeneracy sentinel on every frame — a config that never counts a rep and
// never says why.
TEST(ExerciseConfigTest, EveryConfigNamesThreeDistinctJoints) {
    for (int id = 0; id < kExerciseCount; ++id) {
        const ExerciseConfig config = ConfigForExercise(id);
        EXPECT_NE(config.joint_a, config.joint_b) << "exercise id " << config.id;
        EXPECT_NE(config.joint_b, config.joint_c) << "exercise id " << config.id;
        EXPECT_NE(config.joint_a, config.joint_c) << "exercise id " << config.id;
    }
}

// ---------------------------------------------------------------------------
// 6. What the shared thresholds mean for each exercise — reported, not asserted
// ---------------------------------------------------------------------------

TEST(RepProgressTest, ReportsTheThresholdAnglesForEveryExercise) {
    Report("shared thresholds in degrees, per exercise (start angles are the design doc's");
    Report("'typical', captured per user at calibration — nothing here is asserted)");
    Report("");
    Report("targets are published figures; (est.) marks the two with no source — see");
    Report("exercises/exercise_config.cpp, where every target names where it came from");
    Report("");
    Report("exercise              start   target  advance   complete  peak(0.85)");
    for (const Exercise& exercise : kTable) {
        const float start = exercise.typical_start_degrees;
        const float target = exercise.target_degrees;

        std::ostringstream row;
        row << std::left << std::setw(20) << exercise.name << std::right << std::setw(6)
            << Fixed(start, 0) << std::setw(9) << Fixed(target, 0) << std::setw(9)
            << Fixed(AngleAt(kAdvanceTriggerProgress, start, target), 1) << std::setw(11)
            << Fixed(AngleAt(kRepCompleteProgress, start, target), 1) << std::setw(12)
            << Fixed(AngleAt(kDefaultDepthPass, start, target), 1);
        Report(row.str());

        // The only thing worth asserting across the table: the hysteresis gap never
        // collapses or inverts, whichever way the exercise runs.
        const float advance = AngleAt(kAdvanceTriggerProgress, start, target);
        const float complete = AngleAt(kRepCompleteProgress, start, target);
        EXPECT_GT(std::fabs(advance - start), std::fabs(complete - start))
            << exercise.name << ": the advance trigger is not further from the start pose "
            << "than the completion threshold, so the hysteresis has inverted";
    }
}

}  // namespace
}  // namespace kinex
