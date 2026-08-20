#include "rep_fsm.h"

#include <algorithm>

#include "geometry.h"

namespace kinex {
namespace {

// The design doc, "Squat violations", side view only and the one validated exercise. A starting
// value to tune against real recordings, not a constant to rederive — and on squat_8rep it
// already flags all eight reps at 55.1-57.3 degrees, so the tuning is owed.
constexpr float kSquatTorsoLeanLimit = 55.0f;

// The design doc, "FSM, all exercises". Not the squat's: the same window for every row, which is
// why the names lost the prefix they used to carry.
constexpr int64_t kMinRepMs = 700;
constexpr int64_t kMaxRepMs = 6000;

// "Both conditions must hold continuously for ~1 s before the set starts."
constexpr int64_t kAlignHoldMs = 1000;

}  // namespace

RepConfig RepConfigFor(const ExerciseConfig& exercise, float start_angle_degrees) {
    return RepConfig{start_angle_degrees,
                     exercise.target_angle_degrees,
                     exercise.depth_pass,
                     (exercise.violation_rules & kViolationTorsoLean) != 0
                         ? kSquatTorsoLeanLimit
                         : kNoLeanLimitDegrees,
                     kMinRepMs,
                     kMaxRepMs,
                     kAlignHoldMs};
}

RepFsm::RepFsm(const RepConfig& config) : config_(config) { Reset(); }

void RepFsm::Reset() {
    state_ = RepState::kIdle;
    rep_count_ = 0;
    violation_mask_ = 0;
    attempt_start_ms_ = 0;
    attempt_reached_peak_ = false;
    attempt_leaned_ = false;
    attempt_peak_progress_ = 0.0f;
    last_rep_peak_progress_ = 0.0f;
    awaiting_return_ = false;
    aligned_since_ms_ = 0;
    has_aligned_since_ = false;
    last_valid_progress_ = 0.0f;
}

bool RepFsm::InAttempt() const {
    return state_ == RepState::kAdvancing || state_ == RepState::kPeak ||
           state_ == RepState::kReturning;
}

void RepFsm::BeginAttempt(int64_t timestamp_ms, float progress) {
    attempt_start_ms_ = timestamp_ms;
    attempt_reached_peak_ = false;
    attempt_leaned_ = false;
    // Seeded from the frame that crossed the trigger rather than from zero. The running max
    // below only sees frames from the *next* one on, because this frame's transition is
    // already spent; without the seed a rep that crossed and returned inside two frames would
    // report a peak it never had.
    attempt_peak_progress_ = progress;
}

void RepFsm::ResolveAttempt(int64_t timestamp_ms) {
    const int64_t duration_ms = timestamp_ms - attempt_start_ms_;

    // The duration floor rejects landmark jitter, and applied to every attempt it also
    // silently deletes fast shallow reps: the user does ten, the app says seven, and there
    // is no explanation anywhere in the UI. So it applies only to attempts that never
    // reached the peak.
    //
    // The asymmetry is the design doc's and it is the right way round. An attempt that hit the
    // target is a rep however fast it was — a rep cannot be reached and not have happened.
    // One that missed the target and still lasted longer than the floor is a genuine shallow
    // rep, and it counts with the depth-miss bit set rather than vanishing.
    //
    // There is no maximum-duration check here. Outlasting the window is a timeout that fires
    // while the attempt is still in flight, so anything reaching this function was inside it.
    if (!attempt_reached_peak_ && duration_ms < config_.min_rep_duration_ms) {
        // No count, and no violations either — the previous rep's mask stands, because
        // Kotlin reads it on a rep_count change and a discarded attempt does not produce one.
        return;
    }

    // An attempt whose peak implies an angle no joint can form did not happen. The peak is a
    // running maximum, so a single frame where the pose model collapsed two landmarks onto
    // each other drags it to a value the rest of the attempt never went near — and because
    // the peak is what slot [6] reports and what the session row stores, that one frame
    // otherwise becomes a permanent record of a rep nobody did.
    //
    // The 20 Aug sweep is what this is for: peaks of 2.02, 2.08 and 2.09 across three
    // different exercises, each implying a joint angle near zero degrees. Discarding the whole
    // attempt rather than clamping the peak is deliberate — the attempt contained a frame the
    // measurement cannot account for, so its peak is unknown, not merely large. A clamped peak
    // would be a number nobody measured, written to history as though somebody had.
    const float peak_angle = AngleAtProgress(attempt_peak_progress_, config_.start_angle_degrees,
                                             config_.target_angle_degrees);
    if (!IsPlausibleJointAngle(peak_angle)) {
        return;
    }

    ++rep_count_;
    // Latched alongside the mask and in the same tense: both describe the rep that just
    // counted, and Kotlin reads both on a rep_count change.
    last_rep_peak_progress_ = attempt_peak_progress_;
    violation_mask_ = 0;
    if (!attempt_reached_peak_) {
        violation_mask_ |= kViolationDepthMiss;
    }
    if (attempt_leaned_) {
        violation_mask_ |= kViolationTorsoLean;
    }
}

void RepFsm::ObserveViolations(const RepInput& input) {
    // Unsigned lean, so leaning forward and leaning back trip the same rule. A degenerate
    // frame is not evidence of good form, so the sentinel is skipped rather than compared —
    // kInvalidAngle is -1 and would read as perfectly upright.
    if (IsValidAngle(input.torso_lean_degrees) &&
        input.torso_lean_degrees > config_.torso_lean_limit_degrees) {
        attempt_leaned_ = true;
    }
}

RepOutput RepFsm::Snapshot() const {
    return RepOutput{rep_count_, state_, violation_mask_,
                     std::clamp(last_valid_progress_, 0.0f, 1.0f), last_rep_peak_progress_};
}

RepOutput RepFsm::Update(const RepInput& input, int64_t timestamp_ms) {
    // The one place a raw angle becomes the quantity the graph reads. Everything below this
    // line is in progress units, which is the whole reason the same graph counts a squat and
    // a shoulder press.
    //
    // RepProgress refuses two things: geometry's degeneracy sentinel, and a calibration whose
    // start and target are the same pose. Both arrive here as "this frame has nothing to
    // say", which is also why last_valid_progress_ can never hold a NaN.
    float progress = 0.0f;
    const bool progress_valid =
        RepProgress(input.primary_angle_degrees, config_.start_angle_degrees,
                    config_.target_angle_degrees, &progress);
    if (progress_valid) {
        last_valid_progress_ = progress;
    }

    if (!input.aligned) {
        // Alignment gates everything. Losing it abandons an attempt in flight silently — the
        // athlete stepped out of frame or turned, and neither is a rep — and drops back to
        // kAligning rather than kIdle, because kIdle now means only "alignment has never been
        // acquired". Coming back into frame serves the hold again; it does not resume the
        // rep that was discarded.
        if (state_ != RepState::kIdle) {
            state_ = RepState::kAligning;
        }
        has_aligned_since_ = false;
        return Snapshot();
    }

    if (!has_aligned_since_) {
        has_aligned_since_ = true;
        aligned_since_ms_ = timestamp_ms;
    }

    // The maximum duration is a timeout, not a verdict handed down when an attempt finishes.
    // An attempt still in flight after max_rep_duration_ms is over now, wherever the athlete
    // happens to be — a rep that never ends is not a rep that ends late, and waiting for a
    // completion that may never come would leave the HUD pinned mid-rep indefinitely.
    //
    // Checked ahead of the graph, so an attempt cannot resolve on the same frame it expires.
    // It returns to kReady rather than kIdle: whatever the athlete was doing, they are still
    // standing in frame, and kIdle would demand the alignment hold again for nothing.
    if (InAttempt() && timestamp_ms - attempt_start_ms_ > config_.max_rep_duration_ms) {
        state_ = RepState::kReady;
        awaiting_return_ = true;
        return Snapshot();
    }

    // The attempt's high-water mark, taken before the graph runs so the frame that ends an
    // attempt is counted in the attempt it ended. Unclamped on purpose — this is what slot
    // [6] carries into a session record, and clamping it here would be clamping it at the
    // only point where the information still exists.
    if (progress_valid && InAttempt()) {
        attempt_peak_progress_ = std::max(attempt_peak_progress_, progress);
    }

    // A refused progress value is a frame with nothing to say: MediaPipe collapsed the
    // landmarks onto each other. Every transition below is a comparison against progress, so
    // guarding each one keeps that frame out of all of them at the cost of holding state —
    // which is the right answer anyway. A dropped detection mid-rep should not abandon it;
    // sustained loss is the alignment gate's to catch.
    switch (state_) {
        case RepState::kIdle:
            state_ = RepState::kAligning;
            break;

        case RepState::kAligning:
            if (timestamp_ms - aligned_since_ms_ >= config_.align_hold_ms) {
                state_ = RepState::kReady;
            }
            break;

        case RepState::kReady:
            if (progress_valid && progress < kRepCompleteProgress) {
                // Back at the top. This re-arms an attempt that timed out, and is a no-op
                // every other time, since every other route into kReady arrives from below
                // this threshold already.
                awaiting_return_ = false;
            } else if (progress_valid && !awaiting_return_ &&
                       progress > kAdvanceTriggerProgress) {
                BeginAttempt(timestamp_ms, progress);
                state_ = RepState::kAdvancing;
            }
            break;

        case RepState::kAdvancing:
            ObserveViolations(input);
            if (progress_valid && progress > config_.depth_pass) {
                attempt_reached_peak_ = true;
                state_ = RepState::kPeak;
            } else if (progress_valid && progress < kAdvanceTriggerProgress) {
                state_ = RepState::kReturning;
            }
            break;

        case RepState::kPeak:
            ObserveViolations(input);
            if (progress_valid && progress < kAdvanceTriggerProgress) {
                state_ = RepState::kReturning;
            }
            break;

        case RepState::kReturning:
            ObserveViolations(input);
            if (progress_valid && progress < kRepCompleteProgress) {
                ResolveAttempt(timestamp_ms);
                state_ = RepState::kReady;
            } else if (progress_valid && progress > kAdvanceTriggerProgress) {
                // Sank back past the trigger without finishing. Same attempt and the same
                // clock: bobbing in the hysteresis band is one rep being done badly, not
                // several, and the timeout is what eventually ends it.
                state_ = RepState::kAdvancing;
            }
            break;
    }

    return Snapshot();
}

}  // namespace kinex
