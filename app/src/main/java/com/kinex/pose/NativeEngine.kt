package com.kinex.pose

/**
 * Facade over the native engine. Native code owns all per-frame state; Kotlin holds an
 * opaque handle and crosses JNI exactly once per frame.
 *
 * [process] returns the engine's own preallocated array — read it before the next call,
 * do not retain it. Its seven slots are:
 *
 *     [0] repCount   [1] state (0=IDLE 1=ALIGNING 2=READY 3=ADVANCING 4=PEAK 5=RETURNING)
 *     [2] violationMask   [3] primaryAngle (degrees)
 *     [4] repProgress (0..1, clamped)   [5] alignmentScore (0..1)
 *     [6] repPeakProgress (unclamped)
 *
 * [4] and [6] are both rep progress and are not the same number. [4] is live and clamped
 * because it drives the HUD progress ring. [6] is the peak the last counted rep reached and
 * is not clamped, because it is written to a session record — a stored 1.00 that was really
 * 1.02 cannot be un-flattened later. Display and recording are different jobs.
 *
 * The state names are direction-neutral because the engine is: it works on normalized
 * progress toward the exercise's target, so a squat closing the knee and a shoulder press
 * opening the elbow drive the same graph. Do not reintroduce "descending" here.
 *
 * [calibration] slot 0 is the athlete's start angle in degrees — progress 0. The engine
 * falls back to a documented standing angle if it is absent, which counts reps against a
 * table entry rather than against this athlete.
 */
object NativeEngine {
    init {
        System.loadLibrary("kinex_engine")
    }

    external fun create(exerciseId: Int, calibration: FloatArray): Long

    /** [landmarks] must be exactly 132 floats: 33 landmarks x (x, y, z, visibility). */
    external fun process(handle: Long, landmarks: FloatArray, timestampMs: Long): FloatArray

    external fun reset(handle: Long)

    external fun destroy(handle: Long)
}
