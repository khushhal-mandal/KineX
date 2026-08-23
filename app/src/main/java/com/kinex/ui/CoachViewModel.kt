package com.kinex.ui

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kinex.BuildConfig
import com.kinex.sync.ApiException
import com.kinex.sync.Authenticator
import com.kinex.sync.CoachChatResponse
import com.kinex.sync.DeviceIdentity
import com.kinex.sync.KineXApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * One question, one answer, no memory on either side of the wire.
 *
 * **A ViewModel rather than composable state, for the reason the workout screen's is one:** a
 * request is in flight across a rotation. `remember` would drop the transcript and orphan the
 * reply — it would arrive into a composition that no longer exists and be discarded, which on
 * screen looks like the coach silently refusing to answer. Scoped to the Coach navigation
 * entry, so a rotation keeps both the transcript and the request.
 *
 * **It survives a tab switch too, which was checked rather than assumed** — and the first
 * version of this comment asserted the opposite. `switchTab` pops with `saveState` and
 * navigates back with `restoreState`, which restores the same back-stack entry and therefore
 * the same ViewModel store, so a transcript is still there after a trip through History.
 *
 * What it does not survive is process death, because the transcript is snapshot state rather
 * than `SavedStateHandle`. Left that way deliberately: the backend keeps no conversation
 * either, so nothing is lost that the server could have restored, and a chat log is not a
 * record of training the way a session row is.
 *
 * **Nothing here ever invents a reply.** Every failure lands as [CoachEntry.Failure], which
 * the screen renders as an error and not as the coach speaking. There is no fallback text, no
 * cached answer and no "I couldn't reach the server, but based on your last workout…".
 */
class CoachViewModel(application: Application) : AndroidViewModel(application) {

    // Constructed once rather than per question. The API object holds no connection — every
    // call opens and disconnects its own — but the authenticator caches a token through
    // DeviceIdentity, so a second question inside 24 hours costs no handshake.
    private val api = KineXApi(BuildConfig.API_BASE_URL)
    private val authenticator = Authenticator(api, DeviceIdentity.get(application))

    private val transcript = mutableStateListOf<CoachEntry>()

    /**
     * The transcript, oldest first: questions, replies and failures in the order they happened.
     *
     * Read-only to the screen, and a snapshot list underneath, so appending here recomposes
     * the message list without the screen holding a second copy that could disagree with it.
     */
    val entries: List<CoachEntry> get() = transcript

    /** True from the moment a question is sent until its reply or its failure lands. */
    var asking by mutableStateOf(false)
        private set

    /**
     * Ask [question], appending it to the transcript immediately and the outcome when it comes.
     *
     * Refuses to run two at once. The backend is rate-limited per device and each request
     * spends a shared free-tier quota, so a double tap should cost one question rather than
     * two — and with no conversation history there is no ordering to preserve that would make
     * a second concurrent question meaningful anyway.
     */
    fun ask(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty() || asking) return

        transcript += CoachEntry.Question(trimmed)
        asking = true
        viewModelScope.launch {
            val outcome = try {
                val response = send(trimmed)
                CoachEntry.Reply(
                    text = response.reply,
                    model = response.model,
                    sessionsConsidered = response.grounding.sessionsConsidered,
                )
            } catch (cancelled: CancellationException) {
                // The scope is being torn down with the destination. Rethrow rather than
                // writing a failure into a transcript nobody will see again.
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "coach request failed", failure)
                CoachEntry.Failure(describe(failure))
            }
            transcript += outcome
            asking = false
        }
    }

    /**
     * The same 401-then-retry the sync worker does, and for the same reason: a token that was
     * valid by our clock but not by the server's is fixed by asking for another one. A second
     * 401 is a signing failure rather than a stale token, and falls through to [describe].
     */
    private suspend fun send(question: String): CoachChatResponse {
        return try {
            api.coachChat(authenticator.bearerToken(), question)
        } catch (rejected: ApiException) {
            if (rejected.status != 401) throw rejected
            api.coachChat(authenticator.refreshedToken(), question)
        }
    }

    /**
     * A failure, in words, without guessing.
     *
     * Each branch says what actually happened and what it means for the athlete, because the
     * alternative — one "something went wrong" for all of them — makes an unreachable laptop
     * and a rate limit look like the same problem and neither look fixable. The last branch
     * carries the raw exception on purpose: a case not anticipated here is exactly the one
     * where a friendly sentence would be a guess, and the unpretty truth is more use.
     */
    private fun describe(failure: Throwable): String = when {
        failure is ApiException && failure.status == 401 ->
            "The backend rejected this device's key. That is a signing or server-secret " +
                "problem rather than a network one, and asking again will not fix it."

        failure is ApiException && failure.status == 429 ->
            "Too many questions too quickly. The coach is rate-limited per device because it " +
                "spends a shared free-tier quota. Wait a minute and ask again."

        failure is ApiException && failure.status == 503 ->
            "The backend answered, but the model behind the coach did not. Nothing has been " +
                "made up in its place. Try again in a moment."

        failure is ApiException ->
            "The backend refused the question. ${failure.message}"

        // ApiException extends IOException, so this branch is everything the network did to
        // us rather than anything the server said: no route, refused connection, timeout.
        failure is IOException ->
            "Can't reach the backend at ${BuildConfig.API_BASE_URL}. Your sets are still " +
                "recorded on this phone; the coach is the one thing here that needs the server."

        else ->
            "The question was never sent: ${failure.message ?: failure.javaClass.simpleName}"
    }

    private companion object {
        const val TAG = "KineXCoach"
    }
}

/** One line of the transcript. */
sealed interface CoachEntry {

    /** What the athlete asked, as sent — trimmed, and never edited afterwards. */
    data class Question(val text: String) : CoachEntry

    /**
     * What the coach answered, with what it was answering from.
     *
     * [sessionsConsidered] and [model] are shown under the reply rather than dropped. The
     * backend returns them because "why did it say that" is otherwise unanswerable from
     * outside the process, and on screen they are the difference between a reply grounded in
     * this athlete's sets and a plausible paragraph.
     */
    data class Reply(val text: String, val model: String, val sessionsConsidered: Int) : CoachEntry

    /** Why there is no answer. Rendered as an error, never as the coach speaking. */
    data class Failure(val text: String) : CoachEntry
}
