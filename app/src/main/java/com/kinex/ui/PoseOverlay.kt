package com.kinex.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.kinex.pose.LANDMARK_STRIDE
import com.kinex.pose.PoseFrame
import kotlin.math.min

/**
 * The skeleton when nothing is happening: the athlete is being tracked but no attempt is in
 * flight. Deliberately desaturated, so "in motion" is a change the eye catches peripherally
 * from across a room rather than a hue somebody has to look at to identify.
 */
internal val IdleSkeletonColor = Color(0xFF8A98A5)

private val LandmarkColor = Color(0xFFFFFFFF)

/**
 * Draws the 33 landmarks and MediaPipe's skeleton connections over the preview.
 *
 * [frame] and [tint] are both deliberately lambdas: reading the state inside the draw scope
 * invalidates only the draw phase, so 30 updates a second never trigger recomposition. [tint]
 * is a lambda for the same reason and not because it changes as often — it changes on an FSM
 * transition, a few times a rep — but routing it through the draw phase keeps the whole
 * overlay out of recomposition entirely.
 *
 * Landmarks are normalised to the analysis image, which the preview letterboxes with
 * FIT_CENTER. Both use cases run at the same aspect ratio, so fitting the image rect
 * into this canvas the same way puts the skeleton exactly where the body is.
 */
@Composable
fun PoseOverlay(
    frame: () -> PoseFrame?,
    modifier: Modifier = Modifier,
    tint: () -> Color = { IdleSkeletonColor },
) {
    Canvas(modifier) {
        val pose = frame() ?: return@Canvas
        val connectionColor = tint()

        val scale = min(size.width / pose.imageWidth, size.height / pose.imageHeight)
        val fittedWidth = pose.imageWidth * scale
        val fittedHeight = pose.imageHeight * scale
        val left = (size.width - fittedWidth) / 2f
        val top = (size.height - fittedHeight) / 2f

        fun x(index: Int) = left + pose.points[index * LANDMARK_STRIDE] * fittedWidth
        fun y(index: Int) = top + pose.points[index * LANDMARK_STRIDE + 1] * fittedHeight

        val strokeWidth = 3.dp.toPx()
        for (connection in PoseLandmarker.POSE_LANDMARKS) {
            drawLine(
                color = connectionColor,
                start = Offset(x(connection.start()), y(connection.start())),
                end = Offset(x(connection.end()), y(connection.end())),
                strokeWidth = strokeWidth,
            )
        }

        val radius = 4.dp.toPx()
        for (index in 0 until pose.points.size / LANDMARK_STRIDE) {
            drawCircle(color = LandmarkColor, radius = radius, center = Offset(x(index), y(index)))
        }
    }
}
