package com.pedro.heartratewatch.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import kotlin.math.roundToInt

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

/** m:ss, e.g. "5:30" or "5:05". */
private val PACE_TEXT_PATTERN = Regex("""^(\d+):([0-5]?\d)$""")

class MainActivity : ComponentActivity() {

    private val settingsRepository by lazy { SettingsRepository(applicationContext) }
    private val alertPlayer by lazy { AlertPlayer(applicationContext) }
    private val calibrationRepository by lazy { CalibrationRepository(applicationContext) }
    private val unitPreferencesRepository by lazy { UnitPreferencesRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SettingsScreen(settingsRepository, alertPlayer, calibrationRepository, unitPreferencesRepository)
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    repository: SettingsRepository,
    alertPlayer: AlertPlayer,
    calibrationRepository: CalibrationRepository,
    unitPreferencesRepository: UnitPreferencesRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by repository.settingsFlow.collectAsStateWithLifecycle(initialValue = TrainingSettings())
    val latestCalibration by calibrationRepository.latestFlow.collectAsStateWithLifecycle(initialValue = null)
    val targetDistanceUnit by unitPreferencesRepository.targetDistanceUnitFlow
        .collectAsStateWithLifecycle(initialValue = DistanceUnit.KILOMETERS)
    val paceUnit by unitPreferencesRepository.paceUnitFlow
        .collectAsStateWithLifecycle(initialValue = DistanceUnit.KILOMETERS)
    var draft by remember(saved) { mutableStateOf(saved) }
    // Tracks which *required* fields are currently left blank/invalid, keyed by label, so Save
    // can refuse and say which one(s) still need a value instead of silently reusing old ones.
    val blankFields = remember { mutableStateMapOf<String, Boolean>() }
    val snackbarHostState = remember { SnackbarHostState() }

    val breakAlertSoundPicker = rememberSoundPicker(AlertType.HIGH_HR, alertPlayer)
    val pushHarderSoundPicker = rememberSoundPicker(AlertType.LOW_HR, alertPlayer)
    val paceTooSlowSoundPicker = rememberSoundPicker(AlertType.PACE_TOO_SLOW, alertPlayer)
    val paceTooFastSoundPicker = rememberSoundPicker(AlertType.PACE_TOO_FAST, alertPlayer)
    val targetReachedSoundPicker = rememberSoundPicker(AlertType.TARGET_REACHED, alertPlayer)

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "PulseGuard settings",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 16.dp)
            )
            Button(onClick = { context.startActivity(Intent(context, RunHistoryActivity::class.java)) }) {
                Text("View run history")
            }

            AccordionSection("General", initiallyExpanded = true) {
                SwitchRow("Vibration on watch", draft.vibrationEnabled) {
                    draft = draft.copy(vibrationEnabled = it)
                }
                NumberField(
                    "Break length (seconds)",
                    draft.breakTimerSeconds,
                    onChange = { draft = draft.copy(breakTimerSeconds = it) },
                    onBlankChanged = { blankFields["Break length (seconds)"] = it }
                )
            }

            AccordionSection("Heart rate") {
                // Independent of the Pace section's toggle below -- either, both, or neither can
                // be on.
                SwitchRow("Enable heart rate alerts", draft.heartRateAlertsEnabled) {
                    draft = draft.copy(heartRateAlertsEnabled = it)
                }
                Text(
                    latestCalibration?.let {
                        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.recordedAtMillis))
                        "Calibrated max HR: ${it.bpm} bpm ($date)"
                    } ?: "No max HR calibration yet -- run \"Calibrate max HR\" on your watch."
                )
                NumberField(
                    "Manual max HR override (bpm, optional -- overrides calibration)",
                    draft.manualMaxHrBpm ?: 0,
                    optional = true,
                    onChange = { draft = draft.copy(manualMaxHrBpm = if (it > 0) it else null) }
                )
                SwitchRow(
                    "Use % of max HR instead of bpm",
                    draft.thresholdMode == ThresholdMode.PERCENT_MAX_HR
                ) { usePercent ->
                    draft = draft.copy(
                        thresholdMode = if (usePercent) ThresholdMode.PERCENT_MAX_HR else ThresholdMode.BPM
                    )
                }
                val effectiveMaxHr = draft.manualMaxHrBpm ?: latestCalibration?.bpm
                if (draft.thresholdMode == ThresholdMode.PERCENT_MAX_HR) {
                    NumberField(
                        "Lower threshold (% of max HR)",
                        draft.lowerThresholdPercent,
                        onChange = { draft = draft.copy(lowerThresholdPercent = it) },
                        onBlankChanged = { blankFields["Lower threshold (% of max HR)"] = it }
                    )
                    Text("= ${draft.resolvedLowerBpm(effectiveMaxHr)} bpm")
                    NumberField(
                        "Upper threshold (% of max HR)",
                        draft.upperThresholdPercent,
                        onChange = { draft = draft.copy(upperThresholdPercent = it) },
                        onBlankChanged = { blankFields["Upper threshold (% of max HR)"] = it }
                    )
                    Text("= ${draft.resolvedUpperBpm(effectiveMaxHr)} bpm")
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
            }

            AccordionSection("Pace") {
                SwitchRow("Enable pace alerts", draft.paceAlertsEnabled) {
                    draft = draft.copy(paceAlertsEnabled = it)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pace unit")
                    UnitDropdown(
                        selected = paceUnit,
                        options = PACE_UNITS,
                        onSelect = { scope.launch { unitPreferencesRepository.setPaceUnit(it) } }
                    )
                }
                val fastestSecPerUnit = (draft.fastestPaceSecPerKm * (paceUnit.metersPerUnit / 1000.0)).roundToInt()
                PaceField(
                    "Fastest allowed pace (m:ss per ${paceUnit.symbol})",
                    fastestSecPerUnit,
                    onChange = { enteredSecPerUnit ->
                        draft = draft.copy(
                            fastestPaceSecPerKm = (enteredSecPerUnit * (1000.0 / paceUnit.metersPerUnit)).roundToInt()
                        )
                    },
                    onBlankChanged = { blankFields["Fastest allowed pace"] = it }
                )
                val slowestSecPerUnit = (draft.slowestPaceSecPerKm * (paceUnit.metersPerUnit / 1000.0)).roundToInt()
                PaceField(
                    "Slowest allowed pace (m:ss per ${paceUnit.symbol})",
                    slowestSecPerUnit,
                    onChange = { enteredSecPerUnit ->
                        draft = draft.copy(
                            slowestPaceSecPerKm = (enteredSecPerUnit * (1000.0 / paceUnit.metersPerUnit)).roundToInt()
                        )
                    },
                    onBlankChanged = { blankFields["Slowest allowed pace"] = it }
                )
            }

            AccordionSection("Target") {
                SwitchRow("Use GPS (more accurate, uses noticeably more battery)", draft.useGpsForDistance) {
                    draft = draft.copy(useGpsForDistance = it)
                }
                DistanceUnitField(
                    "Distance target (optional, 0 = none)",
                    draft.distanceTargetMeters,
                    unit = targetDistanceUnit,
                    onUnitChange = { scope.launch { unitPreferencesRepository.setTargetDistanceUnit(it) } },
                    onMetersChange = { draft = draft.copy(distanceTargetMeters = it) }
                )
            }

            AccordionSection("Custom sounds") {
                Text("Each alert can play its own sound on the phone instead of the default.")
                Button(onClick = { pushHarderSoundPicker.launch(SUPPORTED_AUDIO_MIME_TYPES) }) {
                    Text("Push-harder alert (heart rate)")
                }
                Button(onClick = { breakAlertSoundPicker.launch(SUPPORTED_AUDIO_MIME_TYPES) }) {
                    Text("Break alert (heart rate)")
                }
                Button(onClick = { paceTooSlowSoundPicker.launch(SUPPORTED_AUDIO_MIME_TYPES) }) {
                    Text("Push-harder alert (pace)")
                }
                Button(onClick = { paceTooFastSoundPicker.launch(SUPPORTED_AUDIO_MIME_TYPES) }) {
                    Text("Ease-up alert (pace)")
                }
                Button(onClick = { targetReachedSoundPicker.launch(SUPPORTED_AUDIO_MIME_TYPES) }) {
                    Text("Target-reached alert")
                }
            }

            Button(
                onClick = {
                    // Only fields relevant to whichever alert types are currently enabled (and,
                    // within heart rate, the current threshold mode) are checked -- a stale blank
                    // flag from a hidden/disabled field shouldn't block Save.
                    val relevantLabels = buildSet {
                        add("Break length (seconds)")
                        if (draft.heartRateAlertsEnabled) {
                            if (draft.thresholdMode == ThresholdMode.BPM) {
                                add("Lower threshold (bpm)")
                                add("Upper threshold (bpm)")
                            } else {
                                add("Lower threshold (% of max HR)")
                                add("Upper threshold (% of max HR)")
                            }
                        }
                        if (draft.paceAlertsEnabled) {
                            add("Fastest allowed pace")
                            add("Slowest allowed pace")
                        }
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) { Text("Save") }
        }
    }
}

/**
 * Collapsible group of settings. Each section tracks its own expanded state independently (more
 * than one can be open at a time) -- there are enough categories now (General, Heart rate, Pace,
 * Target, Custom sounds) that showing everything flat at once made the screen unwieldy to scan.
 */
@Composable
private fun AccordionSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(if (expanded) "▲" else "▼")
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
        HorizontalDivider()
    }
}

/** A document picker pre-wired to save whatever audio file the user picks as `type`'s alert sound. */
@Composable
private fun rememberSoundPicker(type: AlertType, alertPlayer: AlertPlayer) =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { alertPlayer.setCustomSound(type, it) }
    }

/** Tap-to-reveal unit picker, matching the "value box + unit box" pattern of the mock. */
@Composable
private fun UnitDropdown(selected: DistanceUnit, options: List<DistanceUnit>, onSelect: (DistanceUnit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(selected.symbol)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { unit ->
                DropdownMenuItem(
                    text = { Text(unit.symbol) },
                    onClick = {
                        onSelect(unit)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * A whole-number field entered in whatever [unit] is currently selected, converted to/from the
 * canonical `meters` value the rest of the app (thresholds, sync, RunSummary) actually uses --
 * changing the display unit never changes what's stored.
 */
@Composable
private fun DistanceUnitField(
    label: String,
    meters: Float?,
    unit: DistanceUnit,
    onUnitChange: (DistanceUnit) -> Unit,
    onMetersChange: (Float?) -> Unit
) {
    val displayValue = meters?.let { (it / unit.metersPerUnit).roundToInt() } ?: 0
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NumberField(
            label,
            displayValue,
            optional = true,
            onChange = { entered -> onMetersChange(if (entered > 0) (entered * unit.metersPerUnit).toFloat() else null) },
            modifier = Modifier.weight(1f)
        )
        UnitDropdown(selected = unit, options = DistanceUnit.entries, onSelect = onUnitChange)
    }
}

/** Pace entry as "m:ss" text (e.g. "5:30"), rather than raw seconds, for the unit already shown in [label]. */
@Composable
private fun PaceField(
    label: String,
    secPerUnit: Int,
    onChange: (Int) -> Unit,
    onBlankChanged: (Boolean) -> Unit = {}
) {
    var text by remember(secPerUnit) {
        mutableStateOf("%d:%02d".format(secPerUnit / 60, secPerUnit % 60))
    }

    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            val match = PACE_TEXT_PATTERN.matchEntire(newText.trim())
            if (match != null) {
                val (min, sec) = match.destructured
                onChange(min.toInt() * 60 + sec.toInt())
            }
            onBlankChanged(newText.isBlank() || match == null)
        },
        label = { Text(label) },
        placeholder = { Text("m:ss") },
        isError = text.isBlank() || PACE_TEXT_PATTERN.matchEntire(text.trim()) == null,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun NumberField(
    label: String,
    value: Int,
    optional: Boolean = false,
    onChange: (Int) -> Unit,
    onBlankChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier.fillMaxWidth()
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
        modifier = modifier
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
