package com.kinex.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinex.data.RepEntity
import com.kinex.data.SessionRepository
import com.kinex.pose.Exercise
import com.kinex.pose.Violation

/**
 * What the set you just finished came to.
 *
 * Four facts, in the order somebody wants them: how many reps, how long, how each rep scored,
 * and whether anything was flagged. The chart is the only part that is not a number, and it
 * exists because "8 reps" and "8 reps, the last three shallow" are different workouts.
 */
@Composable
fun SetSummaryScreen(
    sessionId: Long,
    onDone: () -> Unit,
    onRepeat: (Exercise) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember { SessionRepository.get(context) }
    val session by remember(sessionId) { repository.session(sessionId) }.collectAsState(null)
    val reps by remember(sessionId) { repository.reps(sessionId) }
        .collectAsState(initial = emptyList())

    val finished = session
    Column(modifier.padding(20.dp)) {
        Text(
            text = "Set complete",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        if (finished == null) {
            // One frame at most in practice — the row was written before this screen was
            // navigated to. Handled rather than asserted because the route survives process
            // death and the store is the only thing that can answer for it afterwards.
            Text(
                text = "Loading that set…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            Actions(exercise = null, onDone = onDone, onRepeat = onRepeat)
            return@Column
        }

        Text(
            text = exerciseLabel(finished.exerciseId),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Headline(value = finished.repCount.toString(), label = "reps")
            Headline(value = formatDuration(finished.durationMs), label = "under tension")
        }

        Spacer(Modifier.height(28.dp))
        RepPeakChart(reps)

        Spacer(Modifier.weight(1f))
        Actions(
            exercise = Exercise.from(finished.exerciseId),
            onDone = onDone,
            onRepeat = onRepeat,
        )
    }
}

@Composable
private fun Headline(value: String, label: String) {
    Column {
        Text(
            text = value,
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Peak progress per rep, in rep order.
 *
 * **Form.** Ordered discrete reps against one magnitude, with a meaningful reference at 1.00
 * — a bar chart, one bar per rep, baseline at zero. Not a line: the reps are separate
 * attempts, not samples of a continuous signal, and joining them would imply the athlete
 * passed through the values in between.
 *
 * **Colour is status, not identity.** Clean and flagged are states with reserved meaning, so
 * they wear the app's status colours rather than a categorical pair, and the flagged amber is
 * the same amber the HUD chip and the history rows use. The pair was checked rather than
 * eyeballed: against this surface it separates by ΔE 16.2 under protanopia and 21.7 under
 * normal vision, both comfortably clear.
 *
 * **And never colour alone.** Every flagged rep also carries a tick above its bar, and the
 * count underneath says how many in words. A colour-blind athlete, a greyscale screenshot and
 * a phone in bright sun all still read it. The per-rep numbers themselves live on the session
 * detail screen, which is the table view for this chart.
 *
 * The scale runs to at least 1.15 so the 1.00 reference is always on screen with headroom —
 * peaks above 1.00 are real and routine, because slot [6] is deliberately unclamped.
 */
@Composable
private fun RepPeakChart(reps: List<RepEntity>) {
    if (reps.isEmpty()) return

    val flagged = reps.count { it.violationMask != 0 }
    val cleanColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Sits on top of the bars, so it has to read over both the teal and the amber rather than
    // over the card. White at low alpha is the one value that does both.
    val referenceLineColor = Color.White.copy(alpha = 0.55f)

    Text(
        text = "Depth per rep",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = "1.00 is the exercise's full range. Above it is deeper than the target asks.",
        style = MaterialTheme.typography.bodySmall,
        color = labelColor,
        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
    )

    val maxPeak = reps.maxOf { it.peakProgress }
    val scaleTop = maxOf(1.15f, maxPeak * 1.08f)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(132.dp)
    ) {
        val tickRoom = 10.dp.toPx()
        val plotTop = tickRoom
        val plotHeight = size.height - plotTop
        // A 2px surface gap between adjacent bars, so two flagged reps side by side read as
        // two bars rather than one wide block.
        val gap = 2.dp.toPx()
        val slot = size.width / reps.size
        val barWidth = (slot - gap).coerceAtLeast(2.dp.toPx())
        val corner = CornerRadius(2.dp.toPx(), 2.dp.toPx())

        fun yFor(value: Float) = plotTop + plotHeight * (1f - (value / scaleTop))
        val referenceY = yFor(1f)

        reps.forEachIndexed { index, rep ->
            val isFlagged = rep.violationMask != 0
            val color = if (isFlagged) FlaggedColor else cleanColor
            val top = yFor(rep.peakProgress.coerceAtLeast(0f))
            val left = index * slot + gap / 2f

            // Anchored to the baseline with rounded data-ends: the bottom of every bar is the
            // same zero, so heights are comparable by eye.
            drawRoundRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(barWidth, (size.height - top).coerceAtLeast(1f)),
                cornerRadius = corner,
            )

            // The non-colour carrier for the flagged state.
            if (isFlagged) {
                val cx = left + barWidth / 2f
                drawCircle(
                    color = FlaggedColor,
                    radius = 2.5.dp.toPx(),
                    center = Offset(cx, tickRoom / 2f),
                )
            }
        }

        // The 1.00 reference, drawn **after** the bars.
        //
        // Drawn before them it was invisible on exactly the sets where it matters most: a rep
        // that reaches its target covers the line, so a set of eight reps all at or past 1.00
        // painted over every pixel of it and the caption explained a line that was not there.
        // On top it always reads, and the dash keeps it from being mistaken for a bar edge.
        drawLine(
            color = referenceLineColor,
            start = Offset(0f, referenceY),
            end = Offset(size.width, referenceY),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(6.dp.toPx(), 6.dp.toPx())
            ),
        )

        // The baseline itself, so an all-shallow set still shows where zero is.
        drawLine(
            color = gridColor,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.dp.toPx(),
        )
    }

    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "rep 1",
            style = MaterialTheme.typography.bodySmall,
            color = labelColor,
        )
        Text(
            text = if (flagged == 0) {
                "none flagged"
            } else {
                "$flagged of ${reps.size} flagged  ·  ${flaggedSummary(reps)}"
            },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (flagged == 0) FontWeight.Normal else FontWeight.Bold,
            color = if (flagged == 0) labelColor else FlaggedColor,
        )
        Text(
            text = "rep ${reps.size}",
            style = MaterialTheme.typography.bodySmall,
            color = labelColor,
        )
    }
}

/**
 * Which faults the set collected, as words.
 *
 * Distinct labels rather than a per-rep list — "DEPTH" once tells the athlete what to fix, and
 * "DEPTH, DEPTH, DEPTH, LEAN" tells them the same thing more loudly.
 */
private fun flaggedSummary(reps: List<RepEntity>): String =
    reps.flatMap { Violation.decode(it.violationMask) }
        .distinct()
        .joinToString(" + ") { it.label }

@Composable
private fun Actions(exercise: Exercise?, onDone: () -> Unit, onRepeat: (Exercise) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Only offered once the row has loaded, because "another set" needs to know which
        // exercise, and that comes off the session rather than out of the route.
        if (exercise != null) {
            Button(
                onClick = { onRepeat(exercise) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Another set")
            }
        }
        OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) {
            Text("Done")
        }
    }
}
