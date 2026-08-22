package com.kinex.ui

import androidx.camera.core.CameraSelector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kinex.BuildConfig
import com.kinex.data.AppSettings
import com.kinex.data.SessionRepository
import com.kinex.pose.Exercise

/**
 * Camera default, a TTS placeholder, and about.
 *
 * Preferences are held in composable state and written through on every change, rather than
 * gathered and saved behind a button. There is no "apply" here and nothing to cancel, so a
 * save button would only be a way to lose a change by leaving the screen.
 */
@Composable
fun SettingsScreen(
    onShowRecoveryPhrase: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    var lensFacing by remember { mutableIntStateOf(settings.defaultLensFacing) }
    var speakCues by remember { mutableStateOf(settings.speakCues) }
    var engineReadout by remember { mutableStateOf(settings.showEngineReadout) }
    var confirmingPhrase by remember { mutableStateOf(false) }
    val waiting by remember { SessionRepository.get(context).unsyncedCount() }
        .collectAsState(initial = 0)

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 20.dp),
        )

        SectionTitle("Camera")
        Text(
            text = "Which lens a workout opens on. You can still switch mid-session; that " +
                "restarts the set, because the two lenses hand the engine mirrored landmarks.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LensChip(
                label = "Back",
                selected = lensFacing == CameraSelector.LENS_FACING_BACK,
                onClick = {
                    lensFacing = CameraSelector.LENS_FACING_BACK
                    settings.defaultLensFacing = lensFacing
                },
            )
            LensChip(
                label = "Front",
                selected = lensFacing == CameraSelector.LENS_FACING_FRONT,
                onClick = {
                    lensFacing = CameraSelector.LENS_FACING_FRONT
                    settings.defaultLensFacing = lensFacing
                },
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        SectionTitle("Audio")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(text = "Speak rep counts and cues")
                // Said plainly rather than hidden. A switch that remembers its position and
                // changes nothing is a bug report waiting to be filed; a switch labelled as
                // not built yet is a roadmap.
                Text(
                    text = "Not built yet. The switch remembers your answer so it is set " +
                        "correctly when spoken cues land.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = speakCues,
                onCheckedChange = {
                    speakCues = it
                    settings.speakCues = it
                },
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        SectionTitle("Workout screen")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(text = "Show engine readout")
                Text(
                    text = "FSM state, joint angle, live progress and what the last rep " +
                        "scored. Off by default — it is how a new exercise gets tuned, not " +
                        "something to read while training.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = engineReadout,
                onCheckedChange = {
                    engineReadout = it
                    settings.showEngineReadout = it
                },
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        SectionTitle("Account")
        Text(
            text = "KineX has no email and no password. Your phone holds a key derived from " +
                "a twelve-word recovery phrase, and that phrase is the only way back into " +
                "your history from another device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(text = "Recovery phrase")
                Text(
                    text = "Shown once when the account is created. Read it again here if " +
                        "you did not write it down.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { confirmingPhrase = true }) { Text("Show") }
        }
        Spacer(Modifier.height(10.dp))
        // The count, not a status. "Synced" would be a claim about the network made by a
        // screen that has not asked it anything; a backlog that stops going down is the one
        // symptom a stalled sync actually has, and this is where it shows.
        AboutLine(
            "Waiting to sync",
            if (waiting == 0) "Nothing" else "$waiting ${if (waiting == 1) "set" else "sets"}",
        )

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        SectionTitle("About")
        AboutLine("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        AboutLine("Exercises", "${Exercise.entries.size} tracked")
        AboutLine("Pose model", "MediaPipe pose_landmarker_lite, on device")
        AboutLine("Data", "Pose runs on device. Finished sets sync over Wi-Fi.")
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Rep counting works across every exercise. Form violations are only " +
                "checked for the squat — the one movement validated against a recording. " +
                "Everywhere else a rep is counted and nothing is judged.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // A plain confirm, not a biometric prompt. The barrier that matters is "do not open this
    // in front of somebody", which a sentence answers; a fingerprint check on a fitness app
    // would be ceremony, and the phrase is already behind the device's own lock screen.
    if (confirmingPhrase) {
        AlertDialog(
            onDismissRequest = { confirmingPhrase = false },
            title = { Text("Show recovery phrase?") },
            text = {
                Text(
                    "Anyone who reads these twelve words has your account. Make sure nobody " +
                        "is looking at your screen."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingPhrase = false
                    onShowRecoveryPhrase()
                }) { Text("Show") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingPhrase = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun LensChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun AboutLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.6f),
        )
    }
}
