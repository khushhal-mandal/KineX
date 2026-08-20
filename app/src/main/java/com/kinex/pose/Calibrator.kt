package com.kinex.pose

import kotlin.math.abs

/**
 * Captures the athlete's start angle: the pose they hold still for [HOLD_MS].
 *
 * That angle is progress 0 for every rep of the set that follows, so it is the number the
 * whole normalization rule rests on — `progress = (current - start) / (target - start)`.
 * Getting it from the person in frame rather than from the design doc's "typical" column is the
 * entire point of the exercise; a table entry judges a stranger.
 *
 * The angle handed in is the engine's own slot [3], read back out of a session opened with
 * [RepCounter.openUncalibrated]. That works because the primary angle does not depend on the
 * calibration — it is the joint triple of the active exercise measured off the filtered
 * landmarks, and nothing else — so the engine can report the angle that is about to become
 * its own start angle. Recomputing the same geometry in Kotlin would be a second
 * implementation of it, which is a second thing to be wrong.
 *
 * "Held still" is a window, not a countdown: every sample has to stay within
 * [STEADY_BAND_DEGREES] of the angle the window opened at, and one that does not opens a new
 * window where it stands. Without that, a countdown started mid-movement captures a turnaround
 * and calls it a start pose — which is exactly what `find_still` did to `squat_8rep`'s sidecar,
 * where the "still" window is the sweep between two reps and reads 153 degrees for a standing
 * 170.
 *
 * Not thread-safe, and does not need to be: every call arrives on the analysis thread.
 */
class Calibrator {

    /**
     * How long the current window has held, for the countdown on screen. Back to zero
     * whenever a sample falls outside the band.
     */
    var heldMs: Long = 0L
        private set

    private val samples = FloatArray(MAX_SAMPLES)
    private var count = 0
    private var anchorDegrees = 0f
    private var windowStartMs = 0L

    /**
     * One frame's primary angle. Returns null while the hold is still being served, and the
     * captured start angle on the frame that completes it — after which the caller is done
     * with this instance until it [reset]s it.
     *
     * The captured value is the median of the window rather than its last sample or its mean:
     * a single frame where MediaPipe lost a limb would drag a mean and would land on the
     * answer if the last sample were taken, and a median of ninety frames ignores it.
     */
    fun observe(angleDegrees: Float, timestampMs: Long): Float? {
        // geometry.h's degeneracy sentinel is -1, and no real angle is negative. A frame with
        // nothing to say is not evidence of stillness, so it restarts the hold rather than
        // being skipped — the athlete may well have left.
        if (angleDegrees < 0f) {
            reset()
            return null
        }

        // The band is measured against the angle the window opened at, not against the
        // previous frame, so slow drift cannot walk out of range one imperceptible step at a
        // time. Total movement across a completed hold is bounded by the band, which is what
        // makes "still" mean anything.
        if (count == 0 || abs(angleDegrees - anchorDegrees) > STEADY_BAND_DEGREES) {
            windowStartMs = timestampMs
            anchorDegrees = angleDegrees
            count = 0
        }
        // A window longer than the buffer keeps timing but stops sampling. At 30 FPS the
        // buffer holds three times the hold, so this is reached only by a clock running
        // slower than the frames, and a median of the first 256 is still a median.
        if (count < MAX_SAMPLES) {
            samples[count++] = angleDegrees
        }

        heldMs = timestampMs - windowStartMs
        if (heldMs < HOLD_MS) return null
        return median()
    }

    fun reset() {
        count = 0
        heldMs = 0L
    }

    private fun median(): Float {
        val window = samples.copyOf(count)
        window.sort()
        return window[count / 2]
    }

    companion object {
        /** Three seconds, per the Phase 4 ask. Untuned — nobody has held a pose at it yet. */
        const val HOLD_MS = 3_000L

        /**
         * How far a sample may sit from the angle its window opened at.
         *
         * Untuned, and picked to be passable rather than tight: on `still_baseline` — a real
         * clip of someone standing — the filtered knee angle spreads 1.49-5.38 degrees over a
         * 3 s window, median 3.01. A band of 5 either side allows twice that, so a genuine
         * standing pose clears it comfortably while a rep, which sweeps 130 degrees, cannot.
         * Tightening it is what makes calibration accurate; it is also what makes it fail to
         * complete, and that trade needs a device and a person, not a fixture.
         */
        private const val STEADY_BAND_DEGREES = 5f

        /** 30 FPS for [HOLD_MS] is 90 samples; this is headroom, not a target. */
        private const val MAX_SAMPLES = 256
    }
}
