#pragma once

#include <cstdint>

#include "exercises/exercise_config.h"
#include "landmark_layout.h"
#include "one_euro.h"
#include "rep_fsm.h"

namespace kinex {

// Result layout. These indices are the contract — Kotlin reads the same positions.
//
// Slots [4] and [6] are both rep progress and are deliberately not the same number. [4] is
// live and clamped to 0..1, because it drives the HUD progress ring and a ring cannot be
// 102% full. [6] is the peak the last counted rep actually reached, unclamped, because that
// one is written to a session record and a stored 1.00 that was really 1.02 cannot be
// un-flattened afterwards. Display and recording are different jobs; do not collapse them
// back into one slot.
inline constexpr int kResultFloats = 7;
enum ResultIndex : int {
    kResultRepCount = 0,
    kResultState = 1,
    kResultViolationMask = 2,
    kResultPrimaryAngle = 3,
    kResultRepProgress = 4,
    kResultAlignmentScore = 5,
    kResultRepPeakProgress = 6,
};

// RepState lives in rep_fsm.h — the FSM defines it, and engine.h will hold an FSM by value
// once Phase 3's wiring lands, which is the include direction that does not cycle.

// Upper bound on the calibration payload the Kotlin side may pass to create().
// Phase 4 defines the rest of it; a fixed buffer keeps the engine free of heap ownership.
inline constexpr int kMaxCalibrationFloats = 8;

// Slot 0 of that payload is the athlete's start angle in degrees — the pose calibration
// captured, which normalization measures progress from. Phase 4 owns the flow that fills it.
inline constexpr int kCalibrationStartAngle = 0;

// Stand-in start angle when create() was handed no calibration at all. It is the design doc's
// "typical" standing knee angle, and it is a fallback rather than a default: a set run on
// it is measuring a table entry instead of the athlete in frame. Named here so that when a
// rep count looks wrong, this is greppable rather than a bare 170 in the middle of a
// constructor.
inline constexpr float kUncalibratedStartAngleDegrees = 170.0f;

// One session's engine, living behind an opaque handle. Owns all per-frame state.
//
// Not thread-safe by design: every call arrives on the single analysis thread, so
// nothing here locks.
class Engine {
public:
    // Copies at most kMaxCalibrationFloats floats out of |calibration|.
    Engine(int exercise_id, const float* calibration, int calibration_count);

    // Reads kLandmarkFloats floats from |landmarks| and writes kResultFloats floats
    // into |out|. Allocates nothing — this runs once per frame.
    //
    // The whole per-frame pipeline: One Euro over every landmark, the exercise's primary
    // angle out of the filtered ones, then the FSM. One JNI crossing gets all of it.
    void Process(const float* landmarks, int64_t timestamp_ms, float* out);

    // Drops per-session state. Exercise and calibration survive.
    void Reset();

    // False when create() was handed a calibration this exercise cannot believe — a start
    // pose closer to the target than half the configured span. The JNI bridge refuses the
    // handle in that case, so a rejected calibration reaches Kotlin as a null RepCounter and
    // the athlete is asked to re-pose.
    //
    // A constructor cannot fail, and this is the alternative to the one that could: throwing.
    // The engine allocates nothing and is built on the analysis thread's frame path, so a
    // flag read once by the caller costs less than exceptions would.
    //
    // Always true for an uncalibrated engine. openUncalibrated exists precisely to read the
    // angle that a calibration is about to be made from, and refusing it would leave nothing
    // to read the pose off.
    bool calibration_accepted() const { return calibration_accepted_; }

private:
    // Shoulder-separation ratio against the active view, plus the visibility of every
    // landmark the verdict rests on. 1.0 when the gate passes; below it, how close the
    // athlete is to passing. Reads the filtered frame, which carries visibility through
    // unchanged.
    float AlignmentScore(const float* frame) const;

    // The exercise id is not held. ConfigForExercise() turns it into exercise_ at
    // construction and nothing downstream asks the question again, so a second copy of the
    // id would only be a second thing that could disagree with the row it selected.
    int calibration_count_;
    float calibration_[kMaxCalibrationFloats];

    ExerciseConfig exercise_;
    bool calibration_accepted_;
    LandmarkFilter filter_;
    RepFsm fsm_;

    // The filter's output, held across calls so process() allocates nothing. Only ever
    // written by filter_.Apply() and read by the geometry below it in the same call.
    float filtered_[kLandmarkFloats];
};

}  // namespace kinex
