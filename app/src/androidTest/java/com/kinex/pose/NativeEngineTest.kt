package com.kinex.pose

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 2 verify: the handle lifecycle and the array marshalling, on their own, before
 * any math exists. The engine is a stub, so every process() call must return the same
 * six values no matter what goes in.
 */
@RunWith(AndroidJUnit4::class)
class NativeEngineTest {

    private val calibration = floatArrayOf(170f)

    // 33 landmarks x (x, y, z, visibility).
    private fun landmarks(seed: Float) = FloatArray(LANDMARK_COUNT * LANDMARK_STRIDE) { index ->
        seed + index * 0.001f
    }

    @Test
    fun processReturnsTheStubContract() {
        val handle = NativeEngine.create(EXERCISE_SQUAT, calibration)
        assertNotEquals("create returned a null handle", 0L, handle)
        try {
            val result = NativeEngine.process(handle, landmarks(0.1f), 1_000L)

            assertEquals("result must be 6 floats", 6, result.size)
            assertEquals("repCount", 0f, result[0], 0f)
            assertEquals("state should be READY", 2f, result[1], 0f)
            assertEquals("violationMask", 0f, result[2], 0f)
            assertEquals("primaryAngle", 170f, result[3], 0f)
            assertEquals("depthPercent", 0f, result[4], 0f)
            assertEquals("alignmentScore", 1f, result[5], 0f)
        } finally {
            NativeEngine.destroy(handle)
        }
    }

    /** The contract says the engine hands back a preallocated array, not a fresh one. */
    @Test
    fun processReusesOneResultArray() {
        val handle = NativeEngine.create(EXERCISE_SQUAT, calibration)
        try {
            val first = NativeEngine.process(handle, landmarks(0.1f), 1_000L)
            val second = NativeEngine.process(handle, landmarks(0.2f), 1_033L)
            assertSame("process() allocated a new array", first, second)
        } finally {
            NativeEngine.destroy(handle)
        }
    }

    /** Input varies, output must not — the stub ignores what it is given. */
    @Test
    fun stubIgnoresItsInput() {
        val handle = NativeEngine.create(EXERCISE_SQUAT, calibration)
        try {
            val expected = NativeEngine.process(handle, landmarks(0f), 0L).copyOf()
            for (frame in 1..500) {
                val result = NativeEngine.process(handle, landmarks(frame * 0.37f), frame * 33L)
                assertEquals("frame $frame drifted", expected.toList(), result.toList())
            }
            NativeEngine.reset(handle)
            val afterReset = NativeEngine.process(handle, landmarks(9f), 20_000L)
            assertEquals("reset changed the output", expected.toList(), afterReset.toList())
        } finally {
            NativeEngine.destroy(handle)
        }
    }

    /** Handles are distinct, independent, and survive churn without the VM complaining. */
    @Test
    fun handleLifecycleSurvivesChurn() {
        val live = mutableListOf<Long>()
        repeat(64) { live += NativeEngine.create(EXERCISE_SQUAT, calibration) }
        assertEquals("handles collided", live.size, live.distinct().size)
        live.forEach { handle ->
            NativeEngine.process(handle, landmarks(0.5f), 1_000L)
        }
        live.forEach { NativeEngine.destroy(it) }

        repeat(500) {
            val handle = NativeEngine.create(EXERCISE_SQUAT, calibration)
            NativeEngine.process(handle, landmarks(0.5f), 1_000L)
            NativeEngine.destroy(handle)
        }
    }

    /** create() returns 0 on failure, and teardown paths call destroy() unconditionally. */
    @Test
    fun destroyToleratesZeroHandle() {
        NativeEngine.destroy(0L)
    }

    private companion object {
        const val EXERCISE_SQUAT = 0
        const val LANDMARK_COUNT = 33
    }
}
