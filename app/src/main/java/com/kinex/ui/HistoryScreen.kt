package com.kinex.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kinex.data.RepEntity
import com.kinex.data.SessionEntity
import com.kinex.data.SessionRepository
import com.kinex.pose.Exercise
import com.kinex.pose.Violation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Past sets, newest first.
 *
 * Everything here reads and nothing writes. Sets are saved by the workout screen when they
 * end; there is no save button and no delete, so this screen cannot disagree with the store.
 *
 * All local. No account, no sync — Phase 8 onwards.
 *
 * What used to live here and no longer does: the session detail view, and the hand-rolled
 * one-level back stack that swapped between the two. Detail is [SessionDetailScreen], a real
 * destination reached by id, and `BackHandler` is gone because navigation owns back now.
 */
@Composable
fun HistoryScreen(onOpenSession: (Long) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { SessionRepository.get(context) }
    // remember, so a recomposition does not re-run the query. The flow is cold and Room
    // re-emits on every write to the tables it touches.
    val sessions by remember { repository.sessions() }.collectAsState(initial = emptyList())

    Column(modifier.padding(16.dp)) {
        Text(
            text = "History",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        if (sessions.isEmpty()) {
            Text(
                text = "No sets yet. A set is saved when it ends — when you change exercise, " +
                    "recalibrate, leave the workout screen, or stop repping for two minutes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(sessions, key = { it.id }) { session ->
                SessionRow(session = session, onClick = { onOpenSession(session.id) })
                HorizontalDivider()
            }
        }
    }
}

/**
 * One past set and every rep in it.
 *
 * Takes an id rather than a [SessionEntity] because it is a navigation destination now, and a
 * route carries values that survive being written to a Bundle. The row is re-queried here, so
 * what is on screen is the row rather than a copy of it taken when the list was tapped.
 */
@Composable
fun SessionDetailScreen(sessionId: Long, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { SessionRepository.get(context) }
    val session by remember(sessionId) { repository.session(sessionId) }.collectAsState(null)
    val reps by remember(sessionId) { repository.reps(sessionId) }
        .collectAsState(initial = emptyList())

    val open = session
    Column(modifier.padding(16.dp)) {
        Header(title = open?.let { exerciseLabel(it.exerciseId) } ?: "Set", onBack = onBack)
        if (open == null) {
            // Null covers two states that look the same for the one frame it takes Room to
            // answer: still loading, and gone. Neither is worth distinguishing to somebody
            // who tapped a row a moment ago, and nothing deletes sessions today.
            Text(
                text = "That set is no longer here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Text(
            text = "${DATE_FORMAT.format(Date(open.startedAtMs))}  ·  ${open.repCount} " +
                "reps  ·  ${formatDuration(open.durationMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        // What "peak" means, spelled out: the reading is only interesting against the target
        // it was measured toward, and that target lives in the config table rather than here.
        Text(
            text = "Peak is how far through the exercise's full range the rep got. " +
                "1.00 is the target; above it is deeper than the target asks for.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(reps, key = { it.id }) { rep ->
                RepRow(rep)
                HorizontalDivider()
            }
        }
    }
}

/**
 * A session as one line. Shared with Home's recent list, which wants exactly this and should
 * not grow a second copy of it that drifts.
 */
@Composable
internal fun SessionRow(session: SessionEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(text = exerciseLabel(session.exerciseId), fontWeight = FontWeight.Bold)
            Text(
                text = DATE_FORMAT.format(Date(session.startedAtMs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "${session.repCount} reps  ·  ${formatDuration(session.durationMs)}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun RepRow(rep: RepEntity) {
    val violations = Violation.decode(rep.violationMask)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = "Rep ${rep.repIndex}")
        Text(
            text = "peak %.2f  ·  %s".format(
                rep.peakProgress,
                if (violations.isEmpty()) "clean" else violations.joinToString("+") { it.label },
            ),
            color = if (violations.isEmpty()) {
                MaterialTheme.colorScheme.onSurface
            } else {
                FlaggedColor
            },
        )
    }
}

@Composable
internal fun Header(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
    }
}

/**
 * An id this build does not know still gets a row. It is a record of something that
 * happened, and hiding it because the table moved on would be worse than showing the number.
 */
internal fun exerciseLabel(exerciseId: Int): String =
    Exercise.from(exerciseId)?.label ?: "Exercise $exerciseId"

/**
 * Sets run from seconds to a few minutes, so this stops at minutes. Rounds down, because a
 * 59-second set reading "1m" is a set that did not happen.
 */
internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

internal val DATE_FORMAT = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
