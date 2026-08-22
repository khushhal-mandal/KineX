package com.kinex.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kinex.sync.DeviceIdentity
import com.kinex.ui.theme.ErrorRed
import kotlinx.coroutines.launch

/**
 * The twelve words that are the account, shown once and re-readable from Settings.
 *
 * **This screen is the only place the phrase is ever displayed, and the only copy of it that
 * exists outside the device is the one the user writes down.** The server has never seen a
 * recovery phrase and has no endpoint that accepts one — if it could derive the phrase it
 * could impersonate every user, and "no password, no PII" would stop meaning anything. So the
 * warning below is a literal statement of the failure mode, not boilerplate.
 *
 * `FLAG_SECURE` is set for as long as this screen is on top. It keeps the phrase out of the
 * recents thumbnail and refuses screenshots and screen recording. A phrase that survives in
 * the recents card of a phone somebody hands to a friend is exactly the leak this exists to
 * prevent, and the recents thumbnail is taken automatically without the user doing anything.
 */
@Composable
fun RecoveryPhraseScreen(
    firstRun: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val identity = remember { DeviceIdentity.get(context) }
    var phrase by remember { mutableStateOf<List<String>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    // Set on the way in and cleared on the way out, rather than for the whole app: the workout
    // screen has no secret on it, and a permanently secure window would also block a user
    // screenshotting their own rep count, which is a thing people do.
    val window = (context as? Activity)?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    LaunchedEffect(Unit) {
        // First call is also what creates the account, so this is where the keypair comes from
        // on a fresh install. It runs off the main thread inside DeviceIdentity.
        runCatching { identity.recoveryPhrase() }
            .onSuccess { phrase = it.split(" ") }
            .onFailure { failure = it.message ?: it.javaClass.simpleName }
    }

    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text(
            text = "Your recovery phrase",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "KineX has no email and no password. These twelve words are the only way " +
                "to get your training history back on another phone, or on this one after a " +
                "reinstall.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        val words = phrase
        when {
            failure != null -> Text(
                text = "Could not read the recovery phrase: $failure",
                color = ErrorRed,
                style = MaterialTheme.typography.bodyMedium,
            )

            words == null -> Box(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            else -> PhraseGrid(words)
        }

        Spacer(Modifier.height(20.dp))

        WarningCard()

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                if (firstRun) {
                    // Acknowledging is what stops this screen opening on every launch. It is
                    // recorded before navigating away so that leaving cannot lose the answer.
                    scope.launch {
                        runCatching { identity.acknowledgePhrase() }
                        onDone()
                    }
                } else {
                    onDone()
                }
            },
            enabled = words != null || failure != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (firstRun) "I've written it down" else "Done")
        }

        if (firstRun) {
            Spacer(Modifier.height(8.dp))
            // Says out loud that this is not the last chance, because a screen that looks like
            // one gets tapped through by people who intend to come back to it.
            Text(
                text = "You can read it again later under Settings › Account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Two numbered columns.
 *
 * Numbered because the order is part of the secret — twelve words in the wrong order derive a
 * different key, or more likely fail the checksum, and somebody copying them into a notebook
 * without indices has no way to check their own transcription afterwards. Monospaced for the
 * same reason: it is being copied by hand.
 */
@Composable
private fun PhraseGrid(words: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val half = (words.size + 1) / 2
        for (row in 0 until half) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NumberedWord(row + 1, words[row], Modifier.weight(1f))
                words.getOrNull(row + half)?.let {
                    NumberedWord(row + half + 1, it, Modifier.weight(1f))
                } ?: Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NumberedWord(position: Int, word: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "$position.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = word,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun WarningCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ErrorRed, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Write these down on paper",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = ErrorRed,
        )
        // Each line is a real consequence of the auth model rather than generic caution.
        Text(
            text = "Nobody can recover this for you. The phrase never leaves your phone and " +
                "is not stored on the server, so if you lose it and lose the phone, the " +
                "history synced under it is gone for good.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Anyone who has these words has your account. Do not photograph this " +
                "screen and do not put it in a notes app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
