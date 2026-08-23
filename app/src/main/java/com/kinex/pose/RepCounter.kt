package com.kinex.pose

/** 33 landmarks x (x, y, z, visibility) — the exact width [NativeEngine.process] requires. */
const val LANDMARK_FLOATS = 33 * LANDMARK_STRIDE

/**
 * Rep FSM states, numbered exactly as the engine numbers them — slot [1] of the result is
 * this ordinal. Direction-neutral on purpose: the engine works on normalized progress
 * toward an exercise's target, so a squat closing the knee and a press opening the elbow
 * drive the same graph. Do not reintroduce "descending".
 */
enum class RepState {
    IDLE, ALIGNING, READY, ADVANCING, PEAK, RETURNING;

    companion object {
        fun from(value: Int): RepState = values().getOrElse(value) { IDLE }
    }
}

/**
 * Violation bits, mirrored from `exercises/exercise_config.h`. The engine packs them into
 * result slot [2] and Kotlin decodes them — that split is the JNI contract's, which is why
 * these values are duplicated here rather than any string crossing.
 *
 * **[label] and [cue] are deliberately different text for the same bit.** A HUD tag is read
 * at a glance off a screen two metres away and has to be short enough to fit a chip, so it
 * names the *fault*: "DEPTH". A spoken cue arrives while the athlete is mid-set and cannot be
 * re-read, so it names the *correction*: "Go deeper". Saying "depth" out loud tells somebody
 * which rule fired and not what to do about it, which is the wrong half.
 *
 * Cues are short because the queue policy in `audio/CueSpeaker` gives them whatever time is
 * left between reps — see there. A sentence would be cut off by the next rep at any real
 * training tempo.
 *
 * Only the squat sets either bit today. An exercise with `violation_rules = 0` reports an
 * empty mask forever, which is not the same as a rep having no faults.
 */
enum class Violation(val bit: Int, val label: String, val cue: String) {
    DEPTH_MISS(1 shl 0, "DEPTH", "Go deeper"),
    TORSO_LEAN(1 shl 1, "LEAN", "Chest up");

    companion object {
        fun decode(mask: Int): List<Violation> = entries.filter { mask and it.bit != 0 }
    }
}

/**
 * One frame's worth of engine output, decoded out of the seven-float result array.
 *
 * A value class rather than a read of the shared array, because that array is the engine's
 * own buffer and is overwritten on the next frame — anything held across frames has to be
 * a copy.
 */
data class RepSnapshot(
    val repCount: Int,
    val state: RepState,
    val violationMask: Int,
    val primaryAngleDegrees: Float,
    val repProgress: Float,
    val alignmentScore: Float,
    /**
     * The peak the last counted rep reached, unclamped — slot [6]. Held until the next rep
     * counts, the same tense as [violationMask], so both describe one rep and Kotlin reads
     * both when [repCount] changes.
     *
     * Not derived here. Kotlin used to reconstruct this as a running max of [repProgress]
     * over an attempt, which could only ever produce the clamped value; the engine keeps the
     * real one now and there is exactly one derivation of it.
     */
    val repPeakProgress: Float,
)

/**
 * Owns one native engine handle for the length of a session.
 *
 * Every method is synchronized. The frames arrive on MediaPipe's result thread while
 * [close] is called from the main thread on disposal, and the engine is explicitly not
 * thread-safe — without the lock a frame in flight during teardown reads a freed handle.
 * A monitor per frame at 30 FPS costs nothing measurable.
 */
class RepCounter private constructor(private var handle: Long) {

    /**
     * Returns null when the engine is gone or the frame is not the width the contract
     * requires. Exactly one JNI crossing per call.
     */
    @Synchronized
    fun process(points: FloatArray, timestampMs: Long): RepSnapshot? {
        if (handle == 0L || points.size != LANDMARK_FLOATS) return null
        val out = NativeEngine.process(handle, points, timestampMs)
        return RepSnapshot(
            repCount = out[SLOT_REP_COUNT].toInt(),
            state = RepState.from(out[SLOT_STATE].toInt()),
            violationMask = out[SLOT_VIOLATION_MASK].toInt(),
            primaryAngleDegrees = out[SLOT_PRIMARY_ANGLE],
            repProgress = out[SLOT_REP_PROGRESS],
            alignmentScore = out[SLOT_ALIGNMENT_SCORE],
            repPeakProgress = out[SLOT_REP_PEAK_PROGRESS],
        )
    }

    /**
     * Drops the filter and FSM state but keeps the calibrated thresholds, so a set can be
     * restarted without recalibrating.
     */
    @Synchronized
    fun reset() {
        if (handle != 0L) NativeEngine.reset(handle)
    }

    @Synchronized
    fun close() {
        if (handle != 0L) {
            NativeEngine.destroy(handle)
            handle = 0L
        }
    }

    companion object {
        // Result slot indices — the JNI contract, mirrored from engine.h.
        private const val SLOT_REP_COUNT = 0
        private const val SLOT_STATE = 1
        private const val SLOT_VIOLATION_MASK = 2
        private const val SLOT_PRIMARY_ANGLE = 3
        private const val SLOT_REP_PROGRESS = 4
        private const val SLOT_ALIGNMENT_SCORE = 5
        private const val SLOT_REP_PEAK_PROGRESS = 6

        /**
         * A session counting [exerciseId] against the [startAngleDegrees] [Calibrator]
         * measured on the person in frame. There is no default for either: the exercise comes
         * from the picker and the angle from a held pose, and a default for the angle is how
         * this spent Phase 4 judging every athlete against 165 degrees.
         */
        fun open(exerciseId: Int, startAngleDegrees: Float): RepCounter? =
            open(exerciseId, floatArrayOf(startAngleDegrees))

        /**
         * A session with no calibration at all, which is what [Calibrator] reads the start
         * angle out of.
         *
         * The empty payload is not a placeholder for a number this could have passed — it is
         * the documented absent case, and the engine answers it with
         * `kUncalibratedStartAngleDegrees`. What comes back is used for slot [3] and nothing
         * else, and slot [3] is the joint angle of the active exercise, computed with no
         * reference to the start angle. Every other slot this engine produces is measured
         * against a table entry and must be ignored.
         */
        fun openUncalibrated(exerciseId: Int): RepCounter? = open(exerciseId, FloatArray(0))

        /**
         * Null if the native library is missing or the engine refused a handle, so a broken
         * build shows a message over a working preview instead of taking the app down.
         */
        private fun open(exerciseId: Int, calibration: FloatArray): RepCounter? = try {
            val handle = NativeEngine.create(exerciseId, calibration)
            if (handle == 0L) null else RepCounter(handle)
        } catch (error: UnsatisfiedLinkError) {
            null
        }
    }
}
