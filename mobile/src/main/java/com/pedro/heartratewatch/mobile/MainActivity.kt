package com.pedro.heartratewatch.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pedro.heartratewatch.shared.AlertType
import com.pedro.heartratewatch.shared.ThresholdMode
import com.pedro.heartratewatch.shared.TrainingSettings
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * MIME types offered to the document picker for a custom alert sound. The generic "audio"
 * wildcard MIME type alone should be enough in theory, but some OEM file-picker apps (Samsung's
 * included) list results by matching the exact MIME strings a caller passes rather than reliably
 * expanding the wildcard, so the common audio formats are spelled out explicitly to make sure
 * mp3/m4a/ogg/flac files actually show up, not just wav.
 */
private val SUPPORTED_AUDIO_MIME_TYPES = arrayOf(
    "audio/*",
    "audio/wav",
    "audio/x-wav",
    "audio/mpeg",
    "audio/mp4",
    "audio/aac",
    "audio/ogg",
    "audio/flac",
    "audio/x-flac",
    "audio/3gpp",
    "audio/amr",
    "audio/webm"
)

class MainActivity : ComponentActivity() {

    private val settingsRepository by lazy { SettingsRepository(applicationContext) }
    private val alertPlayer by lazy { AlertPlayer(applicationContext) }
    private val calibrationRepository by lazy { CalibrationRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SettingsScreen(settingsRepository, alertPlayer, calibrationRepository)
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    repository: SettingsRepository,
    alertPlayer: AlertPlayer,
    calibrationRepository: CalibrationRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by repository.settingsFlow.collectAsStateWithLifecycle(initialValue = TrainingSettings())
    val latestCalibration by calibrationRepository.latestFlow.collectAsStateWithLifecycle(initialValue = null)
    var draft by remember(saved) { mutableStateOf(saved) }
    // Tracks which *required* number fields are currently left blank, keyed by label, so Save
    // can refuse and say which one(s) still need a value instead of silently reusing old ones.
    val blankFields = remember { mutableStateMapOf<String, Boolean>() }
    val snackbarHostState = remember { SnackbarHostState() }

    val breakAlertSoundPicker = rememberSoundPicker(AlertType.HIGH_HR, alertPlayer)
    val pushHarderSoundPicker = rememberSoundPicker(AlertType.LOW_HR, alertPlayer)
    val targetReachedSoundPicker = rememberSoundPicker(AlertType.TARGET_REACHED, alertPlayer)

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("PulseGuard settings", style = MaterialTheme.typography.headlineSmall)

            Text(
                latestCalibration?.let {
                    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.recordedAtMillis))
                    "Calibrated max HR: ${it.bpm} bpm ($date)"
                } ?: "No max HR calibration yet -- run \"Calibrate max HR\" on your watch."
            )
            Button(onClick = { context.startActivity(Intent(context, RunHistoryActivity::class.java)) }) {
                Text("View run history")
            }

            Text("Threshold input", style = MaterialTheme.typography.titleMedium)
            SwitchRow(
                "Use % of max HR instead of bpm (needs a calibration)",
                draft.thresholdMode == ThresholdMode.PERCENT_MAX_HR
            ) { usePercent ->
                draft = draft.copy(
                    thresholdMode = if (usePercent) ThresholdMode.PERCENT_MAX_HR else ThresholdMode.BPM
                )
            }
            if (draft.thresholdMode == ThresholdMode.PERCENT_MAX_HR) {
                NumberField(
                    "Lower threshold (% of max HR)",
                    draft.lowerThresholdPercent,
                    onChange = { draft = draft.copy(lowerThresholdPercent = it) },
                    onBlankChanged = { blankFields["Lower threshold (% of max HR)"] = it }
                )
                Text("= ${draft.resolvedLowerBpm(latestCalibration?.bpm)} bpm")
                NumberField(
                    "Upper threshold (% of max HR)",
                    draft.upperThresholdPercent,
                    onChange = { draft = draft.copy(upperThresholdPercent = it) },
                    onBlankChanged = { blankFields["Upper threshold (% of max HR)"] = it }
                )
                Text("= ${draft.resolvedUpperBpm(latestCalibration?.bpm)} bpm")
            } else {
                NumberField(
                    "Lower threshold (bpm)",
                    draft.lowerThresholdBpm,
                    onChange = { draft = draft.copy(lowerThresholdBpm = it) },
                    onBlankChanged = { blankFields["Lower threshold (bpm)"] = it }
                )
                NumberField(
                    "Upper threshold (bpm)",
                    draft.upperThresholdBpm,
                    onChange = { draft = draft.copy(upperThresholdBpm = it) },
                    onBlankChanged = { blankFields["Upper threshold (bpm)"] = it }
                )
            }

            Text("Break timer", style = MaterialTheme.typography.titleMedium)
            NumberField(
                "Break length (seconds)",
                draft.breakTimerSeconds,
                onChange = { draft = draft.copy(breakTimerSeconds = it) },
                onBlankChanged = { blankFields["Break length (seconds)"] = it }
            )

            Text("Distance", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Use GPS (more accurate, uses noticeably more battery)", draft.useGpsForDistance) {
                draft = draft.copy(useGpsForDistance = it)
            }
            NumberField(
                "Distance target (meters, optional, 0 = none)",
                draft.distanceTargetMeters?.toInt() ?: 0,
                optional = true,
                onChange = { draft = draft.copy(distanceTargetMeters = if (it > 0) it.toFloat() else null) }
            )

            Text("Notifications", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Vibration on watch", draft.vibrationEnabled) {
                draft = draft.copy(vibrationEnabled = it)
            }
            Button(onClick = { pushHarderSoundPicker.launch(SUPPORTED_AUDIO_MIME_TYPES) }) {
                Text("Upload custom sound for push-harder alert")
            }
            Button(onClick = { breakAlertSoundPicker.launch(SUPPORTED_AUDIO_MIME_TYPES) }) {
                Text("Upload custom sound for break alert")
            }
            Button(onClick = { targetReachedSoundPicker.launch(SUPPORTED_AUDIO_MIME_TYPES) }) {
                Text("Upload custom sound for target-reached alert")
            }
            Text("All three alerts (push-harder, break, target-reached) support a custom sound upload above.")

            Button(
                onClick = {
                    // Only the fields for the currently-selected threshold mode are relevant --
                    // a stale blank flag from the other mode's (now hidden) field shouldn't block
                    // Save.
                    val relevantLabels = if (draft.thresholdMode == ThresholdMode.BPM) {
                        setOf("Lower threshold (bpm)", "Upper threshold (bpm)", "Break length (seconds)")
                    } else {
                        setOf(
                            "Lower threshold (% of max HR)",
                            "Upper threshold (% of max HR)",
                            "Break length (seconds)"
                        )
                    }
                    val missing = blankFields.filterValues { it }.keys.intersect(relevantLabels)
                    if (missing.isNotEmpty()) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                "Enter a value for: ${missing.joinToString(", ")}"
                            )
                        }
                    } else {
                        scope.launch { repository.save(draft) }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }
        }
    }
}

/** A document picker pre-wired to save whatever audio file the user picks as `type`'s alert sound. */
@Composable
private fun rememberSoundPicker(type: AlertType, alertPlayer: AlertPlayer) =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { alertPlayer.setCustomSound(type, it) }
    }

@Composable
private fun NumberField(
    label: String,
    value: Int,
    optional: Boolean = false,
    onChange: (Int) -> Unit,
    onBlankChanged: (Boolean) -> Unit = {}
) {
    // Local text state (rather than deriving straight from `value`) so the field can sit empty
    // mid-edit instead of snapping back to the last valid number -- onChange only fires once the
    // text parses to a real Int again. Keyed on `value` so an edit in a sibling field (which
    // recomposes this one with the same value) doesn't clobber text the user is still typing.
    var text by remember(value) { mutableStateOf(value.toString()) }

    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            val parsed = newText.toIntOrNull()
            when {
                parsed != null -> onChange(parsed)
                newText.isBlank() && optional -> onChange(0)
            }
            onBlankChanged(newText.isBlank() && !optional)
        },
        label = { Text(label) },
        isError = text.isBlank() && !optional,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}