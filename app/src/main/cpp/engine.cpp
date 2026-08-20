#include "engine.h"

#include <algorithm>

#include "geometry.h"

namespace kinex {
namespace {

// The start angle calibration captured, or the documented fallback when create() was handed
// nothing. Read once at construction rather than per frame: calibration does not change
// within a session, and re-deriving it every frame would be a second place for it to differ.
float StartAngleFrom(const float* calibration, int count) {
    return count > kCalibrationStartAngle ? calibration[kCalibrationStartAngle]
                                          : kUncalibratedStartAngleDegrees;
}

}  // namespace

Engine::Engine(int exercise_id, const float* calibration, int calibration_count)
    : calibration_count_(std::clamp(calibration_count, 0, kMaxCalibrationFloats)),
      calibration_{},
      // The config table, and the whole of what this engine knows about which exercise it is
      // running. Everything below reads exercise_; nothing anywhere reads the id again.
      exercise_(ConfigForExercise(exercise_id)),
      // An uncalibrated engine is always accepted: it exists to read the angle a calibration
      // will be made from, and there is nothing yet to disbelieve.
      calibration_accepted_(
          calibration_count_ <= kCalibrationStartAngle ||
          IsCalibrationPlausible(exercise_, calibration[kCalibrationStartAngle])),
      filter_(),
      // Declaration order puts exercise_ and calibration_count_ ahead of fsm_, so both are
      // built by the time this line reads them.
      fsm_(RepConfigFor(exercise_, StartAngleFrom(calibration, calibration_count_))),
      filtered_{} {
    for (int i = 0; i < calibration_count_; ++i) {
        calibration_[i] = calibration[i];
    }
}

float Engine::AlignmentScore(const float* frame) const {
    // Every landmark the gate's verdict depends on: the three the exercise measures, plus the
    // four the ratio is built from. The design doc names only the config's own landmarks, but a
    // gate cannot judge a shoulder ratio it cannot see — an invisible shoulder produces a
    // confident number from a guessed position, which is the failure the visibility rule
    // exists to prevent in the first place.
    const int required[] = {exercise_.joint_a, exercise_.joint_b, exercise_.joint_c,
                            kLeftShoulder,     kRightShoulder,    kLeftHip,
                            kRightHip};
    for (const int index : required) {
        if (LandmarkVisibility(frame, index) < kMinLandmarkVisibility) {
            return 0.0f;
        }
    }

    const float ratio = ShoulderSeparationRatio(
        LandmarkPoint(frame, kLeftShoulder), LandmarkPoint(frame, kRightShoulder),
        LandmarkPoint(frame, kLeftHip), LandmarkPoint(frame, kRightHip));
    if (ratio == kInvalidRatio) {
        return 0.0f;
    }

    // Graded rather than boolean, because slot [5] is what a HUD would use to tell somebody
    // *how* to move, and "not aligned" is not advice. 1.0 exactly means the gate passes; the
    // FSM reads nothing finer than that.
    if (exercise_.view == View::kSide) {
        // Shoulders stacked. Below the bound is a pass; the score falls as they open out.
        if (ratio <= kSideViewMaxShoulderRatio) {
            return 1.0f;
        }
        return std::clamp(kSideViewMaxShoulderRatio / ratio, 0.0f, 1.0f);
    }
    // Shoulders spread. Above the bound is a pass; the score falls as the athlete turns away.
    if (ratio >= kFrontViewMinShoulderRatio) {
        return 1.0f;
    }
    return std::clamp(ratio / kFrontViewMinShoulderRatio, 0.0f, 1.0f);
}

void Engine::Process(const float* landmarks, int64_t timestamp_ms, float* out) {
    filter_.Apply(landmarks, timestamp_ms, filtered_);

    // The exercise's primary angle, off the filtered landmarks — the FSM judges what the
    // filter produced, not what MediaPipe emitted, so the debug overlay must show the same
    // frames it decided on. "Raw" in the JNI contract means unnormalized degrees rather
    // than unfiltered.
    const float primary_angle =
        JointAngleDegrees(LandmarkPoint(filtered_, exercise_.joint_a),
                          LandmarkPoint(filtered_, exercise_.joint_b),
                          LandmarkPoint(filtered_, exercise_.joint_c));

    // Torso lean, only for an exercise that judges it. The side is pinned to the same left
    // half the squat config names; choosing the camera-facing side is the alignment gate's
    // job in Phase 4, and a lean measured off an occluded shoulder is a violation raised at
    // random. kInvalidAngle where the rule is not run, which the FSM skips rather than reads
    // as upright.
    const float torso_lean =
        (exercise_.violation_rules & kViolationTorsoLean) != 0
            ? TorsoLeanDegrees(LandmarkPoint(filtered_, kLeftShoulder),
                               LandmarkPoint(filtered_, kLeftHip))
            : kInvalidAngle;

    const float alignment_score = AlignmentScore(filtered_);
    const bool aligned = alignment_score >= 1.0f;

    const RepOutput result =
        fsm_.Update(RepInput{primary_angle, torso_lean, aligned}, timestamp_ms);

    out[kResultRepCount] = static_cast<float>(result.rep_count);
    out[kResultState] = static_cast<float>(static_cast<int>(result.state));
    out[kResultViolationMask] = static_cast<float>(result.violation_mask);
    out[kResultPrimaryAngle] = primary_angle;
    out[kResultRepProgress] = result.rep_progress;
    out[kResultAlignmentScore] = alignment_score;
    out[kResultRepPeakProgress] = result.rep_peak_progress;
}

void Engine::Reset() {
    // Per-session state only. The exercise and the calibrated thresholds inside fsm_'s
    // config survive, which is what lets a set be restarted without recalibrating.
    filter_.Reset();
    fsm_.Reset();
}

}  // namespace kinex
