package com.kinex.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.kinex.pose.PoseLandmarkerHelper

/**
 * Turns each RGBA_8888 analysis frame into an upright bitmap and hands it to MediaPipe.
 *
 * Runs on the analysis executor's single thread. Both bitmaps and the canvas are
 * reused across frames, so the steady-state per-frame cost is a pixel copy and a blit.
 */
class PoseAnalyzer(private val helper: PoseLandmarkerHelper) : ImageAnalysis.Analyzer {

    private val matrix = Matrix()
    private val canvas = Canvas()
    private var source: Bitmap? = null
    private var upright: Bitmap? = null

    override fun analyze(imageProxy: ImageProxy) {
        val frame = toUprightBitmap(imageProxy)
        // Pixels are already copied into our own bitmap, so the frame can go back now.
        imageProxy.close()
        helper.detectAsync(BitmapImageBuilder(frame).build(), SystemClock.uptimeMillis())
    }

    /**
     * CameraX reports rotation rather than applying it, so the analysis buffer arrives
     * in sensor orientation. MediaPipe needs it upright, and so does the overlay, which
     * treats landmark coordinates as normalised to this rotated image.
     */
    private fun toUprightBitmap(imageProxy: ImageProxy): Bitmap {
        val width = imageProxy.width
        val height = imageProxy.height
        val rotation = imageProxy.imageInfo.rotationDegrees

        var src = source
        if (src == null || src.width != width || src.height != height) {
            src = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            source = src
        }
        src.copyPixelsFromBuffer(imageProxy.planes[0].buffer.rewind())

        val swapped = rotation == 90 || rotation == 270
        val dstWidth = if (swapped) height else width
        val dstHeight = if (swapped) width else height

        var dst = upright
        if (dst == null || dst.width != dstWidth || dst.height != dstHeight) {
            dst = Bitmap.createBitmap(dstWidth, dstHeight, Bitmap.Config.ARGB_8888)
            upright = dst
            canvas.setBitmap(dst)
        }

        matrix.reset()
        matrix.postTranslate(-width / 2f, -height / 2f)
        matrix.postRotate(rotation.toFloat())
        matrix.postTranslate(dstWidth / 2f, dstHeight / 2f)
        canvas.drawBitmap(src, matrix, null)
        return dst
    }
}
