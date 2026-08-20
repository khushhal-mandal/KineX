#pragma once

#include <cstdint>

#include "exercises/exercise_config.h"

namespace kinex {

// Rep FSM states, matching the JNI contract's numbering. Kotlin reads these integers out of
// result slot [1], so the values are the contract and cannot be renumbered.
//
// The names are direction-neutral on purpose. DESCENDING/BOTTOM/ASCENDING described a squat
// and were simply wrong for a shoulder press, which advances by opening the elbow rather
// than closing it. ADVANCING/PEAK/RETURNING describe progress toward the target, which is
// the only thing this FSM measures.
enum class RepState : int {
    kIdle = 0,
    kAligning = 1,
    kReady = 2,
    kAdvancing = 3,
    kPeak = 4,
    kReturning = 5,
};

// ViolationBit and kViolationMaskAll live in exercises/exercise_config.h: ExerciseConfig
// carries the set of rules an exercise runs, so the bits have to be declared where that
// struct can name them, and this header includes it. The other direction cycles.

// The design doc's FSM thresholds, in progress units and shared by every exercise.
//
// They are constants rather than RepConfig fields, and they are not in ExerciseConfig
// either. That is the whole claim of the normalization rule: these are the same two numbers
// for a squat and for a shoulder press, and a per-exercise copy is how a claim like that
// quietly stops being true.
//
// The gap between them is the hysteresis the design doc tells us to keep. Collapse it and the
// FSM chatters out a rep per frame at the boundary; invert it and an attempt completes
// before it has started.
inline constexpr float kAdvanceTriggerProgress = 0.30f;
inline constexpr float kRepCompleteProgress = 0.20f;

// A lean limit no torso can exceed. TorsoLeanDegrees returns [0, 180] and ObserveViolations
// compares strictly greater, so nothing trips this.
//
// It is what an exercise without a validated lean rule is given, and the point is that the
// alternative is worse: handing every exercise the squat's 55 degrees means the day somebody
// switches kViolationTorsoLean on for a shoulder press, it starts judging that press against
// a number measured on a squat, and it looks like it works. This way the rule is inert until
// a fixture puts a real limit in the row.
inline constexpr float kNoLeanLimitDegrees = 180.0f;

// The thresholds one athlete, on one exercise, is judged against.
//
// Three sources feed this, and the split is the point:
//   - |start_angle_degrees| comes from calibration, so it is per athlete.
//   - |target_angle_degrees|, |depth_pass| and |torso_lean_limit_degrees| come from
//     ExerciseConfig, so they are per exercise.
//   - the duration window and the alignment hold are the design doc's "FSM, all exercises"
//     numbers, identical for every row and with no fixture anywhere to vary them against.
//
// Note what is no longer here: any threshold expressed in degrees. The descend trigger and
// the rep-complete angle used to be fields, derived from the baseline at construction.
// Deriving them from a baseline was the squat-specific thing; they are
// kAdvanceTriggerProgress and kRepCompleteProgress now.
struct RepConfig {
    // The athlete's calibrated start pose, in degrees. Progress 0.
    float start_angle_degrees;

    // The exercise's full range-of-motion peak, in degrees. Progress 1. Free to sit above
    // or below the start — a squat closes the knee (170 -> 90) and a shoulder press opens
    // the elbow (90 -> 170), and the sign of that difference is the only thing that differs
    // between them anywhere in this engine.
    float target_angle_degrees;

    // Progress at or past which an attempt counts as having reached the peak. It must sit
    // above kAdvanceTriggerProgress: nothing enters kAdvancing without having crossed the
    // trigger, so a depth_pass on the wrong side of it is one every attempt clears by
    // definition, and the depth-miss violation becomes dead code. That ordering is a
    // precondition, not something the FSM checks.
    float depth_pass;

    // Degrees off vertical, unsigned. Still an angle, and rightly so: the lean rule judges a
    // posture against gravity, not progress toward a target.
    //
    // kNoLeanLimitDegrees for an exercise whose ExerciseConfig does not carry
    // kViolationTorsoLean, which is every exercise but the squat.
    float torso_lean_limit_degrees;

    int64_t min_rep_duration_ms;
    int64_t max_rep_duration_ms;
    int64_t align_hold_ms;
};

// One athlete on one exercise: |exercise|'s target, depth pass and lean rule, against the
// |start_angle_degrees| calibration captured on the person in frame.
//
// The lean limit is the only number here that varies by row, and it varies because the rule
// does: an exercise carrying kViolationTorsoLean gets the design doc's 55 degrees, and every
// other exercise gets kNoLeanLimitDegrees. That is belt and braces with the engine, which
// already hands an exercise without the rule kInvalidAngle for lean — deliberately, because
// the two locks fail in opposite directions and the thing being kept out is the squat's one
// validated number silently judging nine unvalidated exercises.
//
// The duration window and the alignment hold do not vary. They are the same for every row
// today; the design doc's rule is that a threshold moves when a fixture says so, and there is no
// fixture for anything but the squat.
//
// A fixture replay passes an |exercise| whose target has been swapped for the recording's
// own "depthTargetDeg", which is the range of motion that recording was made against.
RepConfig RepConfigFor(const ExerciseConfig& exercise, float start_angle_degrees);

// One frame, already reduced to the quantities the FSM judges.
//
// Landmarks do not appear here on purpose. Turning 132 floats into an angle is geometry.h's
// job and it is already done; leaving that seam open is also what lets the host tests drive
// the state graph directly instead of synthesising skeletons to imply an angle.
struct RepInput {
    // The exercise's primary angle: hip -> knee -> ankle for the squat. kInvalidAngle when
    // the landmarks were degenerate.
    //
    // Normalized to progress on the first line of Update() and never compared as a raw
    // angle after that. It arrives in degrees rather than pre-normalized because the
    // calibration and the target that normalize it are exactly what RepConfig holds, and
    // splitting the division from its two operands would put half the rule in the caller.
    float primary_angle_degrees;

    // Shoulder -> hip against vertical. kInvalidAngle when degenerate.
    float torso_lean_degrees;

    // The alignment gate's verdict for this frame: view held, the active config's landmarks
    // visible. Computing it is Phase 4's job (build order: "calibration flow, alignment
    // gate, HUD, TTS"), so the FSM consumes the answer rather than the landmarks. The ~1 s
    // continuous hold the design doc requires before a set starts is this file's business, and
    // it is what kAligning measures.
    bool aligned;
};

struct RepOutput {
    int rep_count;
    RepState state;

    // The violations of the last rep that counted, held until the next one counts. Not a
    // live reading: depth miss is only knowable once a rep is over, and a field that meant
    // "right now" for one bit and "last rep" for the other would be a field Kotlin cannot
    // write a single cue rule against. Kotlin fires cues when rep_count changes.
    int violation_mask;

    // Normalized progress, clamped to 0..1. Slot [4] of the contract, for the HUD progress
    // ring. Clamped only here: the FSM's own comparisons run against the unclamped value,
    // because standing straighter than calibration and going deeper than the target are both
    // real and both distinctions the graph needs.
    float rep_progress;

    // The highest progress the last rep that counted reached, unclamped. Slot [6], and held
    // until the next rep counts — the same "last counted rep" tense as violation_mask, and
    // for the same reason: a peak is only knowable once the attempt is over.
    //
    // Not clamped, unlike rep_progress. This is the number a session record stores, and a rep
    // that reached 1.02 has to be distinguishable later from one that reached exactly 1.00.
    // Clamping at write time destroys that difference permanently; no migration brings it
    // back. Zero before the first rep of a set counts.
    float rep_peak_progress;
};

// Counts reps for one exercise, one set, from a stream of frames.
//
// The state graph, and the whole of the counting rule. Every comparison below is in progress
// units — the FSM has no concept of "down" or "up", which is what lets one graph serve an
// exercise that closes a joint and one that opens it:
//
//     kIdle       alignment has never been acquired. The first frame of a session, and
//                 nothing returns here short of Reset().
//                   -> kAligning   on the first aligned frame
//
//     kAligning   waiting out the hold. Alignment lost here restarts the hold rather than
//                 moving anywhere.
//                   -> kReady      held continuously for align_hold_ms
//
//     kReady      at the top, between reps as well as before the first one.
//                   -> kAligning   alignment lost
//                   -> kAdvancing  progress > kAdvanceTriggerProgress   (clock starts)
//
//     kAdvancing  past the trigger, short of the peak.
//                   -> kAligning   alignment lost
//                   -> kReady      attempt outlasted max_rep_duration_ms
//                   -> kPeak       progress > depth_pass          (peak reached, latched)
//                   -> kReturning  progress < kAdvanceTriggerProgress  (came back short)
//
//     kPeak       at or past the exercise's target range of motion.
//                   -> kAligning   alignment lost
//                   -> kReady      attempt outlasted max_rep_duration_ms
//                   -> kReturning  progress < kAdvanceTriggerProgress
//
//     kReturning  back under the trigger, not yet complete: the hysteresis band between
//                 kAdvanceTriggerProgress and kRepCompleteProgress is exactly this state.
//                   -> kAligning   alignment lost
//                   -> kAdvancing  progress > kAdvanceTriggerProgress  (sank back; same
//                                                                       attempt, same clock)
//                   -> kReady      progress < kRepCompleteProgress     (attempt resolved),
//                                  or the attempt outlasted max_rep_duration_ms
//
// Everything is a threshold crossing. Nothing reads the sign of a derivative, which is what
// keeps a landmark shaking at the peak of a rep from being read as a direction change.
//
// Losing alignment abandons the attempt in flight and returns to kAligning, not kIdle: the
// athlete is still mid-session, and the ~1 s hold is served again before anything can count.
// The abandoned rep is never resumed.
//
// A resolved attempt counts unless it is both faster than min_rep_duration_ms and short of
// the peak — see ResolveAttempt, where that asymmetry is argued. An attempt that outlasts
// max_rep_duration_ms never resolves at all; it times out where it stands.
//
// Depth miss falls out of the graph rather than being tracked beside it: an attempt that
// never entered kPeak is an attempt that never reached the target.
//
// Not thread-safe, and allocates nothing. Update() is on the per-frame path.
class RepFsm {
public:
    explicit RepFsm(const RepConfig& config);

    // One frame. |timestamp_ms| is the frame clock, monotonic per the JNI contract; rep
    // durations and the alignment hold are both measured on it rather than on a frame count,
    // so a dropped frame costs accuracy and not correctness.
    //
    // At most one state transition per call. At 30 FPS the extra frame this costs on
    // kIdle -> kAligning -> kReady is 33 ms against a 1 s hold, and one transition per frame
    // is a graph that can be read off a log line by line.
    RepOutput Update(const RepInput& input, int64_t timestamp_ms);

    // Back to kIdle with a zero rep count. The config survives; per-set state does not.
    void Reset();

private:
    // True in the three states an attempt is in flight, which is where the duration timeout
    // applies and nowhere else.
    bool InAttempt() const;

    void BeginAttempt(int64_t timestamp_ms, float progress);
    void ResolveAttempt(int64_t timestamp_ms);
    void ObserveViolations(const RepInput& input);
    RepOutput Snapshot() const;

    RepConfig config_;

    RepState state_;
    int rep_count_;
    int violation_mask_;

    // The attempt in flight — meaningless outside kAdvancing, kPeak and kReturning.
    int64_t attempt_start_ms_;
    bool attempt_reached_peak_;
    bool attempt_leaned_;

    // Running max of unclamped progress across the attempt in flight, and the value the last
    // counted rep finished with. attempt_reached_peak_ answers "did it get there"; this
    // answers "how far did it get", which is the question a shallow rep is interesting for
    // and the one a bool cannot hold.
    float attempt_peak_progress_;
    float last_rep_peak_progress_;

    // Set when an attempt times out, cleared when progress comes back under
    // kRepCompleteProgress. A timeout is the only way to arrive in kReady while still past
    // the advance trigger; every other route in arrives from below it. Without this, the
    // frame after a timeout would start a fresh attempt from a pose the athlete never
    // returned from, and hanging at the bottom would mint one doomed attempt every six
    // seconds forever. kReady means "at the top", and this is what keeps that true.
    bool awaiting_return_;

    // When the current unbroken run of aligned frames began.
    int64_t aligned_since_ms_;
    bool has_aligned_since_;

    // The last progress value that was not refused, so a dropped detection freezes the HUD
    // ring rather than snapping it to a depth nobody reached.
    float last_valid_progress_;
};

}  // namespace kinex
