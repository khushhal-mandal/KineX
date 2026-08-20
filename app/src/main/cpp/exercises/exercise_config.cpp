#include "exercises/exercise_config.h"

#include <cmath>

#include "geometry.h"

namespace kinex {
namespace {

// The design doc's exercise table numbers the squat as the first exercise.
constexpr int kSquatId = 0;

// ---------------------------------------------------------------------------
// Target angles, and where each number comes from
// ---------------------------------------------------------------------------
//
// Every target below is a published figure converted into this engine's angle convention,
// except the two rows that say plainly that no published figure exists.
//
// **The conversion is the part to get right.** Clinical range-of-motion norms — AAOS's, and
// the goniometry texts built on them — measure *excursion from anatomical neutral*: "elbow
// flexion 0-150" means the elbow bends 150 degrees away from straight. This engine measures
// the *included* angle of three landmarks at joint_b, where a straight limb reads 180. The
// two are supplements:
//
//     included_angle = 180 - published_flexion
//
// So AAOS's 150-degree elbow flexion is a 30-degree target here, and the whole of what the
// squat's long-standing 90 means is "90 degrees of knee flexion". The two shoulder rows are
// the exception and need no conversion: hip -> shoulder -> elbow measures the upper arm
// against the torso, which *is* abduction as a goniometer reads it, zero at the side.
//
// **A ROM norm is a ceiling, not a target.** AAOS knee flexion is 135 degrees; nobody
// prescribes a squat to 135. Where a movement has a published *technique* endpoint that is
// what this file uses, and a ROM norm appears only where the movement's endpoint genuinely is
// end range — the curl's elbow, the press's lockout.
//
// **These are published numbers, not measurements of one athlete, and where the two disagree
// the published number wins.** The 20 Aug device sweep concluded the leg raise target was
// "about 65 degrees too shallow" from peaks reading 25 degrees — which is 155 degrees of hip
// flexion, past every published hip ROM there is. That was the measurement failing, not a
// deeper rep. Each row names its source so the next person can check the number rather than
// trust it.

// Squat: 90 degrees of knee flexion, which is a 90-degree included angle here.
//
// Straub RK, Powers CM, "A Biomechanical Review of the Squat Exercise: Implications for
// Clinical Practice", Int J Sports Phys Ther 19(4):490-501, 2024, which operationally defines
// squat depth as "partial/shallow (0-90 deg knee flexion), medium (90-110 deg knee flexion or
// thigh parallel to floor), or full/deep (110-135 deg knee flexion)". ACSM's Foundations of
// Strength Training and Conditioning gives the same endpoint in words — descend until the
// thighs are parallel to the ground.
// https://pmc.ncbi.nlm.nih.gov/articles/PMC10987311/
//
// The standing start angle is not here on purpose — calibration captures it per user.
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

// Push-up: 90 degrees of elbow flexion at the bottom, a 90-degree included angle here.
//
// The standard down position across counted push-up protocols — the subject "lowers the body
// to a predetermined point, to touch the ground or some other object, or until there is a
// 90-degree angle at the elbows". The Army and Navy variants ask for the upper arms at least
// parallel to the ground, which is the same position said a different way.
// https://www.topendsports.com/testing/tests/push-up.htm
//
// Same joint triple and same target as the shoulder press runs to in reverse, which is the
// case worth having in the table: one closes the elbow from straight and the other opens it
// from bent, and the only difference anywhere in the engine is the sign of (target - start).
constexpr float kPushUpTargetAngleDegrees = 90.0f;

// Bicep curl: AAOS puts normal elbow flexion at 150 degrees, and a full-range curl is
// prescribed to end-range flexion. 180 - 150 = 30.
//
// This is a row where the ROM norm is the right anchor rather than a technique cue: the curl
// has no endpoint short of the forearm meeting the biceps, so the joint's limit *is* the
// exercise's target. AAOS normative ROM — elbow flexion 150, extension 0:
// https://goniometer.io/range-of-motion
//
// **Moved from an estimated 45.** The 20 Aug sweep read 19 degrees at the top of a real curl,
// which is 161 degrees of flexion and past the published limit; soft-tissue apposition and
// foreshortening in the image plane both push a real reading below the goniometric number, so
// 30 is not expected to be hit exactly. It is the published end of the movement, which is
// what a target is. Deeper than the push-up's, so the same joint reads a different range of
// motion — the target belongs to the exercise, not to the joint.
constexpr float kBicepCurlTargetAngleDegrees = 30.0f;

// Shoulder press: full elbow extension at lockout. AAOS puts elbow extension at 0 degrees of
// flexion — a straight arm — which is a 180-degree included angle here.
//
// ACE's Seated Overhead Press: "press the dumbbells overhead in unison until your elbows are
// fully extended without any arching in your low back".
// https://www.acefitness.org/resources/everyone/exercise-library/45/seated-overhead-press/
// AAOS elbow extension 0: https://goniometer.io/range-of-motion
//
// **Moved from an estimated 170.** Two consequences, neither a problem:
//
//   - JointAngleDegrees returns [0, 180] by construction, so with the target *at* 180 no
//     measurement can drive progress above 1.0 on this row. Slot [4]'s clamp and slot [6]'s
//     unclamped peak agree here by arithmetic rather than by luck.
//   - Nothing measures a straight arm as exactly 180 — the sweep read 173-174 — so the HUD
//     ring will not quite fill on a good rep. depth_pass is what the FSM actually tests, and
//     at 0.85 from a 90-degree start that is 166.5 degrees, which a real lockout clears.
//
// Above the ~90-degree start, so the span is positive; this is the reversed exercise the
// normalization rule exists for.
constexpr float kShoulderPressTargetAngleDegrees = 180.0f;

// Lateral raise: 90 degrees of shoulder abduction, arms level with the shoulders.
//
// ACE's Lateral Raise: "Continue raising the dumbbells until your arms are level with your
// shoulders and approximately parallel with the floor."
// https://www.acefitness.org/resources/everyone/exercise-library/26/lateral-raise/
//
// No conversion on this row. hip -> shoulder -> elbow measures the upper arm against the
// torso, which is shoulder abduction as a goniometer reads it — zero with the arm at the
// side, 90 at shoulder height — so the published number goes straight in.
//
// The start is a hand-by-the-hip ~15 degrees, so the span is positive and small — 75 degrees,
// third narrowest behind the glute bridge's 60 and the sit-up's 70. This comment used to call
// it the narrowest, which stopped being true when ids 6..9 landed. It is still the row where a
// calibration a few degrees off costs the most proportionally, and the one most likely to want
// its own depth_pass once a fixture exists.
constexpr float kLateralRaiseTargetAngleDegrees = 90.0f;

// Sit-up: **no clean published number. This 65 is the original estimate, unchanged.**
//
// Searched for and not found. What exists is a different measurement of a different movement:
// ACSM standardizes the *curl-up* at 30 degrees of trunk flexion, and trunk flexion there is
// spinal — the shoulder blades leave the mat while the hip angle barely moves — so it says
// nothing about the shoulder -> hip -> knee angle this row reads. The kinematics literature
// on the full sit-up describes a multi-phase movement whose split between spinal flexion and
// hip flexion varies with style and foot fixation, and reports peak hip flexion as a finding
// that differs between sit-up variants rather than as a number to prescribe.
//
// The endpoint therefore depends on how bent the knees are and how far the trunk travels, and
// no published figure fixes both. 65 stays an estimate and is marked as one everywhere it
// appears. Per-user ROM calibration is what retires it; a different guess is not.
//
// Below the start angle, so the span is negative and progress still counts up from 0 — the
// same signed division the squat uses.
constexpr float kSitUpTargetAngleDegrees = 65.0f;

// Lunge: 90 degrees of knee flexion at the bottom, a 90-degree included angle.
//
// Boyd J, Milton K, "The Undervalued Lunge", NSCA Personal Training Quarterly 4.4, p44:
// "lower the hips until both knees are bent at about a 90-degree angle, if possible", and
// "at the lowest point of the downward movement, the hip, knee, and ankle joints should be no
// less than 90 degrees".
// https://www.nsca.com/contentassets/24dd7222ed1b4caeb8a0a46b81bd11f3/ptq-4.4.9-the-undervalued-lunge.pdf
//
// The same joint and the same target as the squat, which is not a duplicate row: the movement
// differs, and the squat's violation rules were validated on a squat fixture and do not come
// with the geometry. Worth noting the two reach 90 out of different literatures — the squat's
// from a depth definition, the lunge's from a technique cue — rather than one being copied
// off the other.
constexpr float kLungeTargetAngleDegrees = 90.0f;

// Glute bridge: hips extended until shoulder, hip and knee are in line — a 180-degree
// included angle, on a row that measures exactly those three landmarks.
//
// NASM: "The goal is to raise your hips until your body is in a straight line from your knee
// to your hip and to your shoulder." The same source names overextension past that line as
// the common fault, so 180 is the endpoint rather than a floor to beat.
// https://blog.nasm.org/how-to-do-a-glute-bridge
//
// **Moved from an estimated 170.** Same structural note as the shoulder press: with the
// target at 180, no measurement can drive progress above 1.0 on this row.
//
// Above the start, so the span is positive — the second of the two reversed rows this file
// has, and the one the increasing-angle test drives alongside the jumping jack.
constexpr float kGluteBridgeTargetAngleDegrees = 180.0f;

// Jumping jack: **no clean published number. This 160 is the original estimate, unchanged.**
//
// AAOS puts shoulder abduction at 180 degrees, so the anatomical ceiling is known and 160 sits
// safely under it — but a ceiling is not an endpoint, and no strength-and-conditioning source
// prescribes how far the arms travel on a jumping jack the way ACE does for a lateral raise.
// It is a calisthenic done to a count and described as "arms overhead"; how far overhead is
// nobody's published number.
// AAOS shoulder abduction 180: https://goniometer.io/range-of-motion
//
// The row is otherwise the lateral raise's hip -> shoulder -> elbow triple carried most of the
// way round: 15 degrees by the hips to 160 overhead. A front-view row, and one the pose model
// can actually see, which is the whole reason it replaced the tricep extension.
constexpr float kJumpingJackTargetAngleDegrees = 160.0f;

// Leg raise: legs vertical over a flat torso, which is 90 degrees of hip flexion and a
// 90-degree included shoulder -> hip -> ankle angle. Unchanged, and now with a reason.
//
// The published bracket: AAOS puts hip flexion at 120 degrees, and the supine straight-leg
// raise — this exact position — is measured clinically at 110-120 with the knee extended. The
// exercise's own endpoint is vertical, which is 90, comfortably inside that.
// https://goniometer.io/range-of-motion · https://fpnotebook.com/Ortho/Exam/HpRngOfMtn.htm
//
// **This is the row where the published number and the 20 Aug device sweep disagree, and the
// literature wins.** The sweep read peaks of 25 degrees and concluded this target was "about
// 65 degrees too shallow". Twenty-five degrees included is 155 degrees of hip flexion, which
// is 35 past the AAOS maximum and not a movement a hip performs. That was the measurement
// failing — a torso lifting off the floor moves the shoulder landmark and closes this angle
// without the hip flexing at all — so the target stays at 90 and the sweep's conclusion about
// it is withdrawn. Below the start, like the squat.
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
