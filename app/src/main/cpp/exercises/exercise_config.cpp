#include "exercises/exercise_config.h"

#include <cmath>

#include "geometry.h"

namespace kinex {
namespace {

// The design doc's exercise table numbers the squat as the first exercise.
constexpr int kSquatId = 0;

// Full range-of-motion peak for a squat, per the design doc's table. The standing start angle is
// not here on purpose — calibration captures it per user.
constexpr float kSquatTargetAngleDegrees = 90.0f;

// The rest of the design doc's table, in its order. The sit-up took the next free id rather than
// the next implemented one when it landed alone, which is why it sits after the four rows
// below it here.
constexpr int kPushUpId = 1;
constexpr int kBicepCurlId = 2;
constexpr int kShoulderPressId = 3;
constexpr int kLateralRaiseId = 4;
constexpr int kSitUpId = 5;

// The four rows that took the table to ten, in the order they were written. Same rule as
// the sit-up: the next free id, never a renumbering, because a stored session refers to an
// exercise by this number.
constexpr int kLungeId = 6;
constexpr int kGluteBridgeId = 7;
constexpr int kJumpingJackId = 8;
constexpr int kLegRaiseId = 9;

// Elbow at the bottom of a push-up. Same joint triple and same target as the shoulder press
// runs to in reverse, which is the case worth having in the table: one closes the elbow from
// straight and the other opens it from bent, and the only difference anywhere in the engine
// is the sign of (target - start).
constexpr float kPushUpTargetAngleDegrees = 90.0f;

// Elbow at the top of a curl. Deeper than the push-up's, so the same joint reads a different
// range of motion — the target belongs to the exercise, not to the joint.
constexpr float kBicepCurlTargetAngleDegrees = 45.0f;

// Elbow locked out overhead. Above the ~90-degree start, so the span is positive; this is
// the reversed exercise the normalization rule exists for.
constexpr float kShoulderPressTargetAngleDegrees = 170.0f;

// Arm horizontal at the top of a lateral raise, measured at the shoulder against the torso.
// The start is a hand-by-the-hip ~15 degrees, so the span is positive and small — the
// narrowest in the table, and the row most likely to want its own depth_pass once a fixture
// exists.
constexpr float kLateralRaiseTargetAngleDegrees = 90.0f;

// Torso to thigh at the top of a sit-up. Below the start angle, so the span is negative and
// progress still counts up from 0 — the same signed division the squat uses.
constexpr float kSitUpTargetAngleDegrees = 65.0f;

// The four targets below are estimates. Nothing here was read off a fixture — there is no
// fixture for any of these movements — and none of them has been run on a device. They are
// what the movement is meant to reach, which is enough for a row to exist and not enough to
// call a row validated. Per-user range-of-motion calibration is the known next step for all
// four: capture the target on the athlete the way the start angle already is, rather than
// tuning a shared guess.

// Front knee at the bottom of a lunge. The same joint and the same target as the squat,
// which is not a duplicate row: the movement differs, and the squat's violation rules were
// validated on a squat fixture and do not come with the geometry.
constexpr float kLungeTargetAngleDegrees = 90.0f;

// Hip extended at the top of a glute bridge, from a knees-bent supine start around 120. Above
// the start, so the span is positive — the second of the two reversed rows this file has, and
// the one the increasing-angle test drives alongside the tricep extension.
constexpr float kGluteBridgeTargetAngleDegrees = 170.0f;

// Arms overhead at the top of a jumping jack, measured at the shoulder against the torso.
// The same hip -> shoulder -> elbow triple the lateral raise uses, carried most of the way
// round: 15 degrees by the hips to 160 overhead. A front-view row, and one the pose model
// can actually see, which is the whole reason it replaced the tricep extension.
constexpr float kJumpingJackTargetAngleDegrees = 160.0f;

// Shoulder to hip to ankle at the top of a lying leg raise: legs vertical against a torso
// that stays flat, so the angle closes from a straight ~170 supine line. Below the start,
// like the squat.
constexpr float kLegRaiseTargetAngleDegrees = 90.0f;


// The design doc's "typical start" column, moved into code so IsCalibrationPlausible has a nominal
// to measure a captured pose against. Nothing normalizes against these — calibration still
// captures the real start on the person in frame.
//
// They are the documented estimates, deliberately not the values the 20 Aug sweep measured.
// Replacing an estimate with one athlete's reading would be tuning the table to one body, and
// the guard only needs the right neighbourhood: it asks whether the captured start left at
// least half the configured span, which tolerates the sweep's 40-degree sit-up miss and its
// 16-degree push-up miss while still refusing the shoulder press's 4-degree collapse.
constexpr float kSquatNominalStartDegrees = 170.0f;
constexpr float kPushUpNominalStartDegrees = 170.0f;
constexpr float kBicepCurlNominalStartDegrees = 170.0f;
constexpr float kShoulderPressNominalStartDegrees = 90.0f;
constexpr float kLateralRaiseNominalStartDegrees = 15.0f;
constexpr float kSitUpNominalStartDegrees = 135.0f;
constexpr float kLungeNominalStartDegrees = 170.0f;
constexpr float kGluteBridgeNominalStartDegrees = 120.0f;
constexpr float kJumpingJackNominalStartDegrees = 15.0f;
constexpr float kLegRaiseNominalStartDegrees = 170.0f;

}  // namespace

bool RepProgress(float angle_degrees, float start_angle_degrees, float target_angle_degrees,
                 float* progress) {
    if (!IsValidAngle(angle_degrees)) {
        return false;
    }
    const float span = target_angle_degrees - start_angle_degrees;
    if (std::fabs(span) < kMinCalibrationSpanDegrees) {
        return false;
    }
    *progress = (angle_degrees - start_angle_degrees) / span;
    return true;
}

float AngleAtProgress(float progress, float start_angle_degrees, float target_angle_degrees) {
    return start_angle_degrees + progress * (target_angle_degrees - start_angle_degrees);
}

bool IsPlausibleJointAngle(float angle_degrees) {
    return angle_degrees >= kMinPlausibleJointAngleDegrees &&
           angle_degrees <= kMaxPlausibleJointAngleDegrees;
}

bool IsCalibrationPlausible(const ExerciseConfig& exercise, float start_angle_degrees) {
    const float measured_span = std::fabs(exercise.target_angle_degrees - start_angle_degrees);
    if (measured_span < kMinCalibrationSpanDegrees) {
        return false;
    }
    // What the row expects the movement to cover. Compared as a magnitude, so the rule reads
    // the same whether the exercise opens a joint or closes one.
    const float configured_span =
        std::fabs(exercise.target_angle_degrees - exercise.nominal_start_degrees);
    return measured_span >= configured_span * kMinCalibrationSpanFraction;
}

ExerciseConfig SquatConfig() {
    return ExerciseConfig{kSquatId,
                          View::kSide,
                          kLeftHip,
                          kLeftKnee,
                          kLeftAnkle,
                          kSquatNominalStartDegrees,
                          kSquatTargetAngleDegrees,
                          kDefaultDepthPass,
                          kViolationDepthMiss | kViolationTorsoLean};
}

ExerciseConfig SitUpConfig() {
    return ExerciseConfig{kSitUpId,
                          View::kSide,
                          kLeftShoulder,
                          kLeftHip,
                          kLeftKnee,
                          kSitUpNominalStartDegrees,
                          kSitUpTargetAngleDegrees,
                          kDefaultDepthPass,
                          0};
}

ExerciseConfig PushUpConfig() {
    return ExerciseConfig{kPushUpId,
                          View::kSide,
                          kLeftShoulder,
                          kLeftElbow,
                          kLeftWrist,
                          kPushUpNominalStartDegrees,
                          kPushUpTargetAngleDegrees,
                          kDefaultDepthPass,
                          0};
}

ExerciseConfig BicepCurlConfig() {
    return ExerciseConfig{kBicepCurlId,
                          View::kSide,
                          kLeftShoulder,
                          kLeftElbow,
                          kLeftWrist,
                          kBicepCurlNominalStartDegrees,
                          kBicepCurlTargetAngleDegrees,
                          kDefaultDepthPass,
                          0};
}

ExerciseConfig ShoulderPressConfig() {
    return ExerciseConfig{kShoulderPressId,
                          View::kFront,
                          kLeftShoulder,
                          kLeftElbow,
                          kLeftWrist,
                          kShoulderPressNominalStartDegrees,
                          kShoulderPressTargetAngleDegrees,
                          kDefaultDepthPass,
                          0};
}

ExerciseConfig LateralRaiseConfig() {
    return ExerciseConfig{kLateralRaiseId,
                          View::kFront,
                          kLeftHip,
                          kLeftShoulder,
                          kLeftElbow,
                          kLateralRaiseNominalStartDegrees,
                          kLateralRaiseTargetAngleDegrees,
                          kDefaultDepthPass,
                          0};
}

ExerciseConfig LungeConfig() {
    return ExerciseConfig{kLungeId,
                          View::kSide,
                          kLeftHip,
                          kLeftKnee,
                          kLeftAnkle,
                          kLungeNominalStartDegrees,
                          kLungeTargetAngleDegrees,
                          kDefaultDepthPass,
                          0};
}

ExerciseConfig GluteBridgeConfig() {
    return ExerciseConfig{kGluteBridgeId,
                          View::kSide,
                          kLeftShoulder,
                          kLeftHip,
                          kLeftKnee,
                          kGluteBridgeNominalStartDegrees,
                          kGluteBridgeTargetAngleDegrees,
                          kDefaultDepthPass,
                          0};
}

ExerciseConfig JumpingJackConfig() {
    return ExerciseConfig{kJumpingJackId,
                          View::kFront,
                          kLeftHip,
                          kLeftShoulder,
                          kLeftElbow,
                          kJumpingJackNominalStartDegrees,
                          kJumpingJackTargetAngleDegrees,
                          kDefaultDepthPass,
                          0};
}

ExerciseConfig LegRaiseConfig() {
    return ExerciseConfig{kLegRaiseId,
                          View::kSide,
                          kLeftShoulder,
                          kLeftHip,
                          kLeftAnkle,
                          kLegRaiseNominalStartDegrees,
                          kLegRaiseTargetAngleDegrees,
                          kDefaultDepthPass,
                          0};
}

ExerciseConfig ConfigForExercise(int exercise_id) {
    switch (exercise_id) {
        case kPushUpId:
            return PushUpConfig();
        case kBicepCurlId:
            return BicepCurlConfig();
        case kShoulderPressId:
            return ShoulderPressConfig();
        case kLateralRaiseId:
            return LateralRaiseConfig();
        case kSitUpId:
            return SitUpConfig();
        case kLungeId:
            return LungeConfig();
        case kGluteBridgeId:
            return GluteBridgeConfig();
        case kJumpingJackId:
            return JumpingJackConfig();
        case kLegRaiseId:
            return LegRaiseConfig();
        case kSquatId:
        default:
            // An id the table does not know, argued at the declaration: the squat, and the
            // session is counted as one.
            return SquatConfig();
    }
}

}  // namespace kinex
