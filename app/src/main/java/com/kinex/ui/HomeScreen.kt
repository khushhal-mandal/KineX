package com.kinex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.kinex.data.SessionEntity
import com.kinex.data.SessionRepository
import com.kinex.data.TrainingTotals
import com.kinex.pose.Exercise
import java.util.Calendar

/**
 * The start destination: what to train, what has been trained lately, and how much this week.
 *
 * Ordered by what somebody opening the app is here to do. The week strip is one line at the
 * top because it is context, the exercise grid is the body because starting a set is the
 * point, and recent activity sits underneath because it is a glance rather than a task —
 * History is a whole tab for when it is not.
 */
@Composable
fun HomeScreen(
    onStart: (Exercise) -> Unit,
    onOpenSession: (Long) -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember { SessionRepository.get(context) }

    // Remembered once per composition, so a session left open across midnight on the last day
    // of the week keeps reporting the old week until something recomposes this. Known and
    // accepted: the cost is a stale count on a screen the user is looking at while not
    // training, and the alternative is a ticker.
    val weekStartMs = remember { startOfWeekMs() }
    val totals by remember(weekStartMs) { repository.totalsSince(weekStartMs) }
        .collectAsState(initial = TrainingTotals(setCount = 0, repCount = 0))
    val recent by remember { repository.recentSessions(RECENT_COUNT) }
        .collectAsState(initial = emptyList())

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = "KineX",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))

        ThisWeek(totals)
        Spacer(Modifier.height(24.dp))

        Text(
            text = "Start a workout",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "The app counts whatever you pick. It cannot tell one movement from " +
                "another, so pick the one you are actually doing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
        )
        ExerciseGrid(onStart)

        Spacer(Modifier.height(28.dp))
        RecentActivity(recent = recent, onOpenSession = onOpenSession, onOpenHistory = onOpenHistory)
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Two numbers, and nothing derived from them. No streaks, no goals, no comparison against
 * last week — none of that has been asked for, and each one is a product decision rather than
 * a formatting one.
 */
@Composable
private fun ThisWeek(totals: TrainingTotals) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Stat(value = totals.setCount.toString(), label = "sets this week")
        Stat(value = totals.repCount.toString(), label = "reps this week")
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
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
 * All ten exercises, two to a row, every cell the same size.
 *
 * Deliberately not a `LazyVerticalGrid`. Ten is a fixed, tiny number that all exists at once,
 * and a lazy grid inside this screen's scrolling Column would need a nested-scroll workaround
 * or a height guess to be legal at all. `chunked(2)` inside the scroll the screen already has
 * is less machinery for the same picture.
 *
 * The cards are large and carry the start pose because the choice is load-bearing in a way
 * the athlete cannot see: several rows share a joint triple — the lunge measures the same
 * hip-knee-ankle as the squat, the glute bridge the same hip as the sit-up — so the engine
 * genuinely cannot tell which movement it is watching, and counts whatever it was told to.
 * Picking the wrong card produces confident, plausible, wrong reps. A dense list of ten
 * similar words is how somebody taps "Squat" while intending the lunge next to it.
 */
@Composable
private fun ExerciseGrid(onStart: (Exercise) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Exercise.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { exercise ->
                    ExerciseCard(
                        exercise = exercise,
                        onClick = { onStart(exercise) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // A trailing gap rather than a stretched last card, so every cell in the grid
                // is the same size even when the table has an odd number of rows.
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ExerciseCard(exercise: Exercise, onClick: () -> Unit, modifier: Modifier) {
    Card(
        modifier = modifier
            .heightIn(min = 116.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            // Stated rather than inferred. cardColors derives content colour from the
            // container, and for surfaceVariant that is onSurfaceVariant — which would put
            // the exercise name, the one word on this card that has to be unmistakable, in
            // the same dimmed grey as the hint underneath it.
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = exercise.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = exercise.startPose,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecentActivity(
    recent: List<SessionEntity>,
    onOpenSession: (Long) -> Unit,
    onOpenHistory: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Recent activity",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (recent.isNotEmpty()) {
            TextButton(onClick = onOpenHistory) { Text("All") }
        }
    }

    if (recent.isEmpty()) {
        Text(
            text = "Nothing yet. Finish a set and it lands here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        return
    }

    recent.forEach { session ->
        SessionRow(session = session, onClick = { onOpenSession(session.id) })
        HorizontalDivider()
    }
}

/** How many sessions the recent list shows. Three, as specified. */
private const val RECENT_COUNT = 3

/**
 * Midnight at the start of the current week, in local time.
 *
 * `Calendar` rather than `java.time` because `minSdk` is 24 and core library desugaring is
 * not switched on in `build.gradle.kts`; `LocalDate` would compile and crash on API 24-25.
 *
 * `firstDayOfWeek` comes from the locale, so this is Monday in most of the world and Sunday
 * in the US — which is what a person means by "this week" in each place. The `+ 7` handles
 * the wrap when today is earlier in the week enumeration than the locale's first day.
 */
private fun startOfWeekMs(nowMs: Long = System.currentTimeMillis()): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = nowMs
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    var daysIn = calendar.get(Calendar.DAY_OF_WEEK) - calendar.firstDayOfWeek
    if (daysIn < 0) daysIn += 7
    calendar.add(Calendar.DAY_OF_YEAR, -daysIn)
    return calendar.timeInMillis
}
