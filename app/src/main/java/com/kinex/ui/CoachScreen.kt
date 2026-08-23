package com.kinex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kinex.ui.theme.ErrorRed

/**
 * Ask the backend about the sessions this device has synced.
 *
 * **The coach answers from synced sets, not from the ones on this phone.** Those are different
 * populations: a set is written locally the moment it ends and uploaded later, on unmetered
 * Wi-Fi, so a workout finished ten minutes ago on mobile data is in History and not yet in
 * anything the coach can read. The empty state says so, because "I don't have any workouts for
 * you yet" arriving on a phone that is visibly showing a history is otherwise indistinguishable
 * from a bug.
 *
 * **Every question is answered cold, and this screen says so where it matters.** The backend
 * carries no conversation: it re-retrieves against the full question every time, which is what
 * keeps the numbers in an answer real, and it means "what about last month?" has no antecedent
 * and gets answered as though asked first. The note sits directly above the input rather than
 * only in the empty state — the mistake it prevents is made while typing a *follow-up*, which
 * is the one moment an intro paragraph scrolled past ten minutes ago cannot reach.
 */
@Composable
fun CoachScreen(modifier: Modifier = Modifier) {
    val model: CoachViewModel = viewModel()
    // rememberSaveable rather than remember: a half-typed question is exactly what a rotation
    // should not throw away, and unlike the transcript it is a Bundle-sized thing.
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Follow the bottom as entries land. Keyed on `asking` as well as the count so the thinking
    // row scrolls into view too — it appears without the transcript growing.
    LaunchedEffect(model.entries.size, model.asking) {
        if (model.entries.isNotEmpty()) {
            listState.animateScrollToItem(model.entries.size)
        }
    }

    Column(modifier.imePadding()) {
        Text(
            text = "Coach",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (model.entries.isEmpty()) {
                item { EmptyState() }
            }
            transcript(model.entries)
            if (model.asking) {
                item { Thinking() }
            }
            // Keeps the last bubble clear of the input row when scrolled to the end.
            item { Spacer(Modifier.height(4.dp)) }
        }

        ColdAnswerNote()

        InputRow(
            draft = draft,
            enabled = !model.asking,
            onDraftChange = { draft = it },
            onSend = {
                model.ask(draft)
                draft = ""
            },
        )
    }
}

/**
 * The transcript as list items rather than one composable, so the list virtualises.
 *
 * Deliberately unkeyed. Entries are only ever appended — never reordered, edited or removed —
 * and asking the same question twice is legitimate, which is precisely the case any key derived
 * from the content would collide on.
 */
private fun LazyListScope.transcript(entries: List<CoachEntry>) {
    items(entries.size) { index ->
        when (val entry = entries[index]) {
            is CoachEntry.Question -> QuestionBubble(entry)
            is CoachEntry.Reply -> ReplyBubble(entry)
            is CoachEntry.Failure -> FailureBubble(entry)
        }
    }
}

@Composable
private fun QuestionBubble(entry: CoachEntry.Question) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text = entry.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .background(
                    MaterialTheme.colorScheme.secondaryContainer,
                    RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ReplyBubble(entry: CoachEntry.Reply) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = remember(entry.text) { withBoldSpans(entry.text) },
            style = MaterialTheme.typography.bodyMedium,
        )
        // The backend answers a device with no synced sessions itself, rather than spending a
        // model on a history it would have to be trusted not to invent. That reply is not a
        // model's, and is not attributed to one here.
        if (entry.model != NO_MODEL) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "from ${entry.sessionsConsidered} synced " +
                    "${if (entry.sessionsConsidered == 1) "session" else "sessions"} · " +
                    entry.model,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A failure, shaped so it cannot be mistaken for an answer.
 *
 * Outlined in the error colour and labelled, rather than the reply's own bubble with unhappy
 * text in it. An app that renders "the server is down" in the same box as "your squat depth
 * improved" has taught the reader to skim past the difference.
 */
@Composable
private fun FailureBubble(entry: CoachEntry.Failure) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .border(1.dp, ErrorRed, RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "No answer",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = ErrorRed,
        )
        Text(
            text = entry.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Thinking() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            // Names the two round trips it is waiting on rather than saying "thinking",
            // because when this hangs, which half it hung in is the only useful question.
            text = "Reading your sessions, then asking the model…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyState() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Ask about your training.",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "The coach reads the sets this phone has synced — how many, which " +
                "exercises, how deep the reps were, and the nightly summaries written from " +
                "them. It cannot see a set that has not uploaded yet, so a workout finished " +
                "off Wi-Fi reaches History before it reaches here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        // Three the retrieval behind this endpoint can genuinely answer: the first two out of
        // SQL over the session rows, the third out of the embedded nightly narratives.
        Suggestion("How has my squat volume changed?")
        Suggestion("Which exercise am I doing most?")
        Suggestion("What should I work on next?")
    }
}

/**
 * An example question, deliberately not tappable.
 *
 * A chip that fills the box is the obvious next step and is the wrong one here: it teaches
 * people to ask the three questions the developer picked, and the retrieval is interesting
 * precisely when the question is not one of them.
 */
@Composable
private fun Suggestion(text: String) {
    Text(
        text = "“$text”",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * The statelessness notice, above the input and always on screen.
 *
 * The failure it prevents is a follow-up — "and what about last month?" — coming back answered
 * cold, which reads as the coach being broken rather than as the coach being stateless. So the
 * sentence has to be where a person's eyes are when they type the *second* question, not in an
 * intro they have scrolled past. Small and grey because it is a standing fact about the
 * endpoint, not a warning about this particular message.
 */
@Composable
private fun ColdAnswerNote() {
    Text(
        text = "Each question is answered on its own — the coach keeps no history of this " +
            "chat, so say what you mean rather than “and last month?”",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        lineHeight = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
private fun InputRow(
    draft: String,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val sendable = enabled && draft.isNotBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            // The backend's own cap, applied here so hitting it is a key that does nothing
            // rather than a 422 arriving after the question has been typed out in full.
            onValueChange = { if (it.length <= MAX_QUESTION_CHARS) onDraftChange(it) },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Ask about your training") },
            enabled = enabled,
            maxLines = 4,
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (sendable) onSend() }),
        )
        FilledIconButton(
            onClick = onSend,
            enabled = sendable,
            modifier = Modifier
                .padding(bottom = 4.dp)
                .size(48.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}

/**
 * `**bold**` as bold, because the model emits it and a `Text` renders the asterisks.
 *
 * Seen on the first real reply: *"You've done \*\*four squat sets\*\*"*. The cause is an
 * asymmetry in `backend/app/coach/prompts.py` — `SUMMARY_SYSTEM` says "no headings, no bullet
 * points, no markdown" and `CHAT_SYSTEM` says nothing about formatting, so the chat model is
 * free to reach for emphasis and does.
 *
 * **Fixed here rather than by adding that sentence to the prompt**, on this project's own
 * recorded lesson: a prompt constraint is a request, and a model declines it often enough to
 * matter. What a reply looks like is the client's job, and doing it here is deterministic
 * whatever the provider is swapped for.
 *
 * Inline emphasis only — not a Markdown renderer, and deliberately not the beginning of one.
 * Block syntax is left alone because it degrades honestly: a stray `- ` reads as a dash. A
 * heading would not, and if headings start appearing the answer is a shorter reply from the
 * prompt, not a parser here.
 */
private fun withBoldSpans(source: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    while (true) {
        val open = source.indexOf(BOLD_MARKER, cursor)
        if (open < 0) break
        val close = source.indexOf(BOLD_MARKER, open + BOLD_MARKER.length)
        // An unclosed marker is left exactly as it arrived. Swallowing it would delete
        // characters the model actually sent, which is a worse lie than showing them.
        if (close < 0) break
        append(source, cursor, open)
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(source, open + BOLD_MARKER.length, close)
        }
        cursor = close + BOLD_MARKER.length
    }
    append(source, cursor, source.length)
}

private const val BOLD_MARKER = "**"

/** `ChatRequest.message` is `max_length=1000` in `backend/app/api/coach.py`. */
private const val MAX_QUESTION_CHARS = 1000

/** What `ChatResponse.model` carries when the backend answered without a model. */
private const val NO_MODEL = "none"
