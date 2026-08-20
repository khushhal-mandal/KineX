#pragma once

#include <cstdint>

namespace kinex {

// Which way the camera sees the athlete. Read by the alignment gate, which asks the opposite
// question of each — a side view wants the shoulders stacked, a front view wants them apart.
// The FSM never reads it.
enum class View : int {
    kSide = 0,
    kFront = 1,
};

// The alignment gate's two thresholds, on shoulder separation divided by torso height —
// The design doc's numbers, and they survived contact with both fixtures.
//
// Measured before the gate was switched on, so these are not a guess: on squat_8rep, a
// genuinely side-on clip, the ratio runs 0.000-0.244 and every one of its 395 frames clears
// the SIDE bound. On still_baseline, a front-facing clip, it runs 1.106-1.151 and every one
// of its 288 frames clears FRONT while none clears SIDE. The two populations do not overlap.
//
// The SIDE margin is thin, though: 0.244 against 0.25 is 2.4 percent, on the one clip that
// has to keep passing. A side-on athlete who opens their shoulders slightly will be refused.
// That is the correct direction to fail in — a refused set says so on screen, where a
// miscounted one does not — but it is the number to revisit first if the gate proves fussy.
inline constexpr float kSideViewMaxShoulderRatio = 0.25f;
inline constexpr float kFrontViewMinShoulderRatio = 0.60f;

// Below this a landmark is a guess, and an angle built from guesses is a confident wrong
// number. The design doc's figure.
inline constexpr float kMinLandmarkVisibility = 0.7f;

// Violation bits, packed into result slot [2]. Kotlin decodes them and maps each to a TTS
// cue string — no strings cross JNI, so this bitfield is the whole vocabulary.
//
// They live here rather than in rep_fsm.h because ExerciseConfig carries the set an
// exercise runs, and rep_fsm.h includes this header. The other direction cycles.
enum ViolationBit : int {
    kViolationDepthMiss = 1 << 0,
    kViolationTorsoLean = 1 << 1,
};

// Every bit the engine can set. Kotlin does not need it; a test asserting that no undefined
// bit escapes does.
inline constexpr int kViolationMaskAll = kViolationDepthMiss | kViolationTorsoLean;

// MediaPipe pose landmark indices, from the design doc's table so nobody guesses them.
inline constexpr int kLeftShoulder = 11;
inline constexpr int kRightShoulder = 12;
inline constexpr int kLeftElbow = 13;
inline constexpr int kRightElbow = 14;
inline constexpr int kLeftWrist = 15;
inline constexpr int kRightWrist = 16;
inline constexpr int kLeftHip = 23;
inline constexpr int kRightHip = 24;
inline constexpr int kLeftKnee = 25;
inline constexpr int kRightKnee = 26;
inline constexpr int kLeftAnkle = 27;
inline constexpr int kRightAnkle = 28;

// The share of full range of motion that counts as having reached the peak. The design doc's
// default, and the only value any exercise uses today.
inline constexpr float kDefaultDepthPass = 0.85f;

// How many rows the table has. Ids are the design doc's table order and run 0..kExerciseCount-1
// with no gaps, which is what lets a caller enumerate the table by counting rather than by
// keeping a second list of ids beside it.
//
// A stored session refers to an exercise by id, so a row may be added at the end and none
// may be renumbered. That is why the sit-up sits at 5, the next free id, rather than at the
// next implemented one, and why the four rows after it took 6..9 in the order they were
// written rather than being interleaved with the movements they resemble.
inline constexpr int kExerciseCount = 10;

// The absolute floor on a span, so RepProgress has something to divide by. This one is
// arithmetic, not judgement: below it the division is noise amplified into progress.
//
// It is **not** the calibration guard. It used to be the only check, and the ten-exercise
// sweep showed what that was worth — a shoulder press calibrated in the finish pose gave a
// start of 174 against what was then a 170-degree target, a 4-degree span, which sailed
// through this and produced a rep peak of 38.75 with the HUD ring pinned full. Four degrees
// is not a demanding calibration, it is a wrong one. kMinCalibrationSpanFraction is the real
// guard now. (That target is 180 since the targets were sourced from published ROM, so the
// same calibration now reads a 6-degree span — still a collapse, and still refused, by the
// fraction rather than by this.)
inline constexpr float kMinCalibrationSpanDegrees = 1.0f;

// How much of an exercise's configured span a calibration has to leave to be believable.
//
// Half. A calibration whose measured start sits closer to the target than half the distance
// the movement is supposed to cover is one taken in the wrong pose — most often the finish
// pose, which is where the athlete's hands already are when they pick the exercise. Half is
// deliberately loose: the sweep's sit-up start missed its estimate by 40 degrees and its
// push-up by 16, and both are real poses that must still be accepted. What must not be
// accepted is a span that has collapsed toward zero.
inline constexpr float kMinCalibrationSpanFraction = 0.5f;

// The narrowest angle three landmarks on a real joint can form.
//
// Chosen from the sweep, not from anatomy alone: the smallest genuine reading across all ten
// exercises was a 19-degree elbow at the top of a bicep curl, and a 25-degree hip at the top
// of a leg raise. The bad readings clustered at 0-3 degrees and showed up as rep peaks of
// 2.02, 2.08 and 2.09 — a knee folded flat, which is not a pose. Ten degrees sits with about
// two times' margin under the lowest real reading and well clear of the noise.
//
// Soft tissue means no joint actually closes past roughly 20 degrees; this is set below that
// because the measurement is a projection into the image plane, and foreshortening can make
// a real angle read smaller than it is. The bound is here to reject impossible values, not to
// second-guess plausible ones.
inline constexpr float kMinPlausibleJointAngleDegrees = 10.0f;

// The upper bound is structural rather than anatomical: JointAngleDegrees returns [0, 180]
// by construction, so nothing can exceed it. It is named so the plausible range reads as a
// range, and so a future angle measure that can exceed 180 finds the check already here.
inline constexpr float kMaxPlausibleJointAngleDegrees = 180.0f;

// One exercise's rules. Fields are the design doc's, named in this file's snake_case rather than
// the spec's camelCase so they read like every other struct in cpp/.
//
// Note what is *not* here: the start angle. The design doc captures that per user at
// calibration, so it arrives at the FSM alongside the config rather than inside it — the
// same squat config serves an athlete who stands at 168 degrees and one who stands at 174.
//
// Note also what is not here: the advance and complete thresholds, and the duration window.
// Those are FSM-wide in progress units now, identical for every exercise, so they live with
// the FSM. That is the whole claim of the normalization rule; putting per-exercise copies
// here would quietly reintroduce the thing it removes.
struct ExerciseConfig {
    // Matches the exerciseId Kotlin passes to create().
    int id;

    View view;

    // The three landmarks of the primary angle. The angle is measured at |joint_b|, between
    // the segments running out to |joint_a| and |joint_c| — squat, side view: hip, knee,
    // ankle. Read by the frame pipeline, never by the FSM, which is handed an angle.
    int joint_a;
    int joint_b;
    int joint_c;

    // What the start pose is *expected* to measure — the design doc's "typical start" column,
    // moved into code because the calibration guard needs something to compare against.
    //
    // This is emphatically not used to normalize anything. Calibration still captures the
    // start angle on the person in frame, and that captured value is what progress is
    // measured from; an athlete whose sit-up starts at 175 is judged from 175 even though
    // this says 135. The only thing this feeds is IsCalibrationPlausible, which asks whether
    // the captured pose is in the right *neighbourhood* — a question that needs a nominal to
    // be a question at all.
    float nominal_start_degrees;

    // Full range-of-motion peak, in degrees. Paired with the calibrated start angle this is
    // what normalizes a frame; see RepProgress below. No constraint on whether it sits
    // above or below the start — a squat closes the knee (170 -> 90) and a shoulder press
    // opens the elbow (90 -> 170), and the sign of the difference is the only thing that
    // differs between them anywhere in this engine.
    float target_angle_degrees;

    // Progress at or past which a rep counts as having reached the peak. kDefaultDepthPass
    // unless an exercise has a fixture proving otherwise.
    float depth_pass;

    // Which violations this exercise runs, as ViolationBit values. Zero means rep counting
    // only, which is every exercise but the squat.
    //
    // The design doc: rep counting generalizes across exercises, form violations do not. Copying
    // the squat's rules onto an unvalidated exercise ships wrong form advice, which is
    // worse than none — so the default is zero and a fixture is what changes it.
    uint32_t violation_rules;
};

// Normalized rep progress: 0 at the calibrated start pose, 1 at the exercise's target.
//
//     progress = (angle - start) / (target - start)
//
// This one signed division is the whole of the engine's exercise-independence. A squat's
// target is below its start (170 -> 90, span -80) and a shoulder press's is above it
// (90 -> 170, span +80); dividing by the signed span makes both count up from 0 toward 1,
// so every comparison in the FSM points one way and no exercise needs a direction flag.
//
// Deliberately unclamped. Below 0 is real — an athlete standing straighter than they
// calibrated — and above 1 is real too, a rep deeper than the configured peak. The FSM
// needs both distinctions; only the HUD value in result slot [4] is clamped, at the point
// it is written.
//
// Returns false and leaves |progress| untouched for a degenerate input: geometry's angle
// sentinel, or a calibration whose span is under kMinCalibrationSpanDegrees.
//
// A bool-and-out-parameter rather than geometry.h's sentinel-value style, because the trick
// that works there does not work here. geometry.h can return -1 for an invalid angle since
// no real angle is negative. Progress has no such spare value: negative progress and
// progress above 1 are both meaningful readings, so any sentinel would collide with an
// answer somebody needs.
bool RepProgress(float angle_degrees, float start_angle_degrees, float target_angle_degrees,
                 float* progress);

// Whether a captured start angle is a believable calibration for |exercise|.
//
// False when the measured start sits closer to the target than kMinCalibrationSpanFraction of
// the exercise's configured span, or when the absolute span is degenerate. A false here is a
// calibration to refuse and re-take, not one to run with: the sweep's shoulder press is the
// worked example, where accepting a 4-degree span turned every degree of movement into a
// quarter of a rep and recorded a peak of 38.75.
//
// The check is direction-agnostic like everything else here — it compares distances to the
// target, so it reads the same for a squat closing a joint and a press opening one.
bool IsCalibrationPlausible(const ExerciseConfig& exercise, float start_angle_degrees);

// Whether |angle_degrees| is an angle a real joint could form, per
// kMinPlausibleJointAngleDegrees. Rejects geometry's sentinel too, so one call covers both
// "no answer" and "an answer no body could produce".
bool IsPlausibleJointAngle(float angle_degrees);

// The angle |progress| corresponds to, given the two ends that normalized it — the inverse of
// RepProgress. The FSM works in progress and has to get back to degrees to ask whether a
// recorded peak was anatomically possible.
float AngleAtProgress(float progress, float start_angle_degrees, float target_angle_degrees);

// The squat, from the design doc's table: side view, hip -> knee -> ankle, 90-degree target,
// and the only exercise with violation rules — depth miss and torso lean, both validated
// against a fixture.
//
// Left-side landmarks. Which side actually faces the camera is the alignment gate's
// question in Phase 4, and answering it means substituting the mirrored triple; there is no
// point choosing here.
ExerciseConfig SquatConfig();

// The sit-up: side view, shoulder -> hip -> knee with the angle at the hip, 65-degree
// target, and no violation rules. Rep counting only until a fixture earns more — the design doc
// is explicit that copying the squat's rules onto an unvalidated exercise is worse than
// shipping none.
//
// **The 65 is an estimate and is one of the two targets in this table with no published
// number behind it.** ACSM standardizes the curl-up at 30 degrees of *spinal* flexion, which
// is not this angle and not this movement; the sit-up kinematics literature reports peak hip
// flexion as something that varies by style rather than as a figure to prescribe. The reason
// is argued at kSitUpTargetAngleDegrees in the .cpp.
//
// Left-side landmarks, same as the squat and for the same reason: which side faces the
// camera is the alignment gate's question, not this table's.
ExerciseConfig SitUpConfig();

// The remaining four rows of the design doc's table, all of them rep counting only. None has a
// fixture, and an exercise without a fixture does not get violation rules: copying the
// squat's onto an unvalidated movement ships wrong form advice, which is worse than none.
//
//   push-up         side,  shoulder -> elbow -> wrist,  target  90
//   bicep curl      side,  shoulder -> elbow -> wrist,  target  30
//   shoulder press  front, shoulder -> elbow -> wrist,  target 180
//   lateral raise   front, hip -> shoulder -> elbow,    target  90
//
// All four targets are published figures converted into this engine's included-angle
// convention, each cited at its constant in the .cpp. Sourcing a target is not the same as
// validating a row: it fixes where the movement is supposed to end, and says nothing about
// whether this pipeline counts that movement correctly, which still wants a fixture.
//
// Left-side landmarks throughout, including the two front-view rows, and that is not an
// oversight to be fixed with a per-camera swap. KineX mirrors coordinates rather than the
// image, so MediaPipe's own left/right assignment is untouched and index 11 is the shoulder
// the model called left on either lens — see "Camera facing and mirroring" in the design doc,
// where that was measured. A swap here would introduce the bug it looks like it prevents.
ExerciseConfig PushUpConfig();
ExerciseConfig BicepCurlConfig();
ExerciseConfig ShoulderPressConfig();
ExerciseConfig LateralRaiseConfig();

// Ids 6, 7 and 9. The jumping jack holds 8 and is documented below. Side view throughout,
// rep counting only, and left-side landmarks for the same reason every row above uses them.
//
//   lunge         hip -> knee -> ankle,      target  90
//   glute bridge  shoulder -> hip -> knee,   target 180
//   leg raise     shoulder -> hip -> ankle,  target  90
//
// **These three targets are published figures now, not the estimates they landed as.** The
// lunge's 90 is NSCA's technique cue, the bridge's 180 is the shoulder-hip-knee line every
// coaching source describes, and the leg raise's 90 is legs vertical, bracketed by AAOS hip
// flexion. Each is cited at its constant in the .cpp.
//
// **The start angles that pair with them are still estimates**, and they are not in this file
// at all — calibration captures a start per athlete. That is what makes a sourced target
// usable: the span the normalization divides by is half measured on the person in frame.
// Per-user ROM calibration, capturing the target the same way the start already is, remains
// the step that would make a row's *own* range of motion its target rather than a norm's.
//
// Two of them reuse a joint triple that is already in the table — the lunge measures the same
// knee as the squat, the glute bridge the same hip as the sit-up. That is expected and is not
// a reason to merge them: the target belongs to the exercise, not to the joint, and the lunge
// in particular is the squat's geometry deliberately *without* the squat's violation rules,
// because the squat's fixture is a squat.
ExerciseConfig LungeConfig();
ExerciseConfig GluteBridgeConfig();
ExerciseConfig LegRaiseConfig();

// The jumping jack: front view, hip -> shoulder -> elbow with the angle at the shoulder,
// 15 -> 160 degrees. Same joint triple as the lateral raise, taken far further.
//
// **The 160 is an estimate and is the other of the two targets with no published number**
// (the sit-up's 65 is the first). AAOS gives shoulder abduction a 180-degree ceiling, which
// 160 sits under, but no strength-and-conditioning source prescribes how far the arms travel
// on a jumping jack the way ACE does for a lateral raise. Argued at its constant in the .cpp.
//
// **It occupies id 8, which the tricep extension used to hold.** That is the one deliberate
// exception to this file's own rule that ids are appended and never reused, and it is safe
// for exactly one reason: the tricep extension never completed a calibration on any device,
// so it never counted a rep, and SessionRepository refuses to write a set with no reps. No
// stored row can carry exerciseId 8. If one ever could have, this would have to be id 10 and
// the tricep row would have to stay as a tombstone.
//
// The tricep extension was removed rather than retuned because its failure was not a number.
// Across two attempts totalling about 140 seconds the elbow angle swung between 32 and 179
// degrees from one sample to the next: arms overhead with the forearms folded back put the
// left elbow and wrist behind both the head and the torso, and the pose model does not
// recover them. No target, depth pass or start angle fixes a landmark that is not there.
ExerciseConfig JumpingJackConfig();

// The table: the design doc's row for |exercise_id|, which is the id Kotlin handed create().
// This is the whole of what "adding an exercise is a config row" means — nothing downstream
// of here branches on which exercise it got.
//
// An id outside the table is a caller bug: Kotlin's picker offers rows that exist and
// nothing else builds an id. It gets the squat rather than a half-filled struct, and the id
// it was handed is not kept anywhere, so a session run under an unknown id is counted and
// recorded as a squat. Harmless while the picker is the only source of ids; Phase 6 stores
// them, and if an id ever arrives from stored data this has to refuse rather than substitute.
ExerciseConfig ConfigForExercise(int exercise_id);

}  // namespace kinex
