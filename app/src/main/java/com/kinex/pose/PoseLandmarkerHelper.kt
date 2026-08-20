package com.kinex.pose

import android.content.Context
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/** Floats per landmark: x, y, z, visibility. 33 landmarks x 4 is the 132-float JNI layout. */
const val LANDMARK_STRIDE = 4

/**
 * One frame of pose landmarks, normalised to the upright image the detector saw.
 *
 * [points] holds 33 landmarks x (x, y, z, visibility) in MediaPipe order — 132 floats,
 * the same layout the native engine will take across JNI, so a recorded frame can drive
 * it unchanged. x and y are in 0..1; z is weakly calibrated relative depth and is carried
 * for the recording only, not for logic.
 */
class PoseFrame(
    val points: FloatArray,
    val imageWidth: Int,
    val imageHeight: Int,
    val timestampMs: Long,
)

/**
 * MediaPipe Pose Landmarker in LIVE_STREAM mode.
 *
 * [onFrame] fires on MediaPipe's own result thread, not the caller's, and receives
 * null for frames where no pose was found.
 *
 * [mirrorX] flips x to `1 - x` on the way out, and must be set for a front-facing camera.
 * PreviewView mirrors a front camera's preview itself, but ImageAnalysis hands us the
 * unmirrored sensor buffer — so without this the skeleton is drawn as the mirror image of
 * the body it is tracking, and raising one arm lights up the other one on screen.
 *
 * Mirroring the 33 coordinates rather than the bitmap is deliberate: it is 33 subtractions
 * inside a loop that already runs, against a full extra blit, and the detector keeps seeing
 * the true scene. That last part matters — see the left/right note in the design doc.
 */
class PoseLandmarkerHelper(
    context: Context,
    private val onFrame: (PoseFrame?) -> Unit,
    private val onError: (String) -> Unit,
    private val mirrorX: Boolean = false,
) {
    private val landmarker: PoseLandmarker = PoseLandmarker.createFromOptions(
        context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .setDelegate(Delegate.CPU)
                    .build()
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setResultListener { result, input -> emit(result, input) }
            .setErrorListener { error -> onError(error.message ?: "MediaPipe error") }
            .build()
    )

    /** [timestampMs] must increase monotonically across calls. */
    fun detectAsync(image: MPImage, timestampMs: Long) {
        landmarker.detectAsync(image, timestampMs)
    }

    fun close() {
        landmarker.close()
    }

    private fun emit(result: PoseLandmarkerResult, input: MPImage) {
        val landmarks = result.landmarks().firstOrNull()
        if (landmarks == null) {
            onFrame(null)
            return
        }
        val points = FloatArray(landmarks.size * LANDMARK_STRIDE)
        for (i in landmarks.indices) {
            val landmark = landmarks[i]
            val base = i * LANDMARK_STRIDE
            points[base] = if (mirrorX) 1f - landmark.x() else landmark.x()
            points[base + 1] = landmark.y()
            points[base + 2] = landmark.z()
            points[base + 3] = landmark.visibility().orElse(0f)
        }
        onFrame(PoseFrame(points, input.width, input.height, result.timestampMs()))
    }

    private companion object {
        const val MODEL_ASSET = "pose_landmarker_lite.task"
    }
}
