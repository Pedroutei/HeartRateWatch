package com.pedro.heartratewatch.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pedro.heartratewatch.mobile.theme.PipBoyTheme
import com.pedro.heartratewatch.shared.AlertType
import com.pedro.heartratewatch.shared.DistanceUnit
import com.pedro.heartratewatch.shared.PACE_UNITS
import com.pedro.heartratewatch.shared.ThresholdMode
import com.pedro.heartratewatch.shared.TrainingSettings
import com.pedro.heartratewatch.shared.formatDistance
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

class MainActivity : ComponentActivity() {

    private val settingsRepository by lazy { SettingsRepository(applicationContext) }
    private val alertPlayer by lazy { AlertPlayer(applicationContext) }
    private val calibrationRepository by lazy { CalibrationRepository(applicationContext) }
    private val themePreferenceRepository by lazy { ThemePreferenceRepository(applicationContext) }
    private val runHistoryRepository by lazy { RunHistoryRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PipBoyTheme {
                SettingsScreen(
                    settingsRepository,
                    alertPlayer,
                    calibrationRepository,
                    themePreferenceRepository,
                    runHistoryRepository
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    repository: SettingsRepository,
    alertPlayer: AlertPlayer,
    calibrationRepository: CalibrationRepository,
    themePreferenceRepository: ThemePreferenceRepository,
    runHistoryRepository: RunHistoryRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by repository.settingsFlow.collectAsStateWithLifecycle(initialValue = TrainingSettings())
    val latestCalibration by calibrationRepository.latestFlow.collectAsStateWithLifecycle(initialValue = null)
    val runs by runHistoryRepository.historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var draft by remember(saved) { mutableStateOf(saved) }
    // Units live on draft/TrainingSettings like everything else on this screen now (they're
    // synced to the watch too) -- edited here and only actually persisted on Save, same as every
    // other field, rather than writing through immediately. They used to write through a
    // separate, unsynced store, so that was safe; now that they share storage with the rest of
    // this draft, an instant write would refresh `saved` and reset any other unsaved edit on this
    // screen out from under the user.
    val paceUnit = draft.paceUnit
    val targetDistanceUnit = draft.distanceUnit
    // Tracks which *required* fields are currently left blank/invalid, keyed by label, so Save
    // can refuse and say which one(s) still need a value instead of silently reusing old ones.
    val blankFields = remember { mutableStateMapOf<String, Boolean>() }
    val snackbarHostState = remember { SnackbarHostState() }

    // AlertType.entries is fixed at compile time, so looping over it to register one launcher
    // per type (rather than the six near-identical named vals this used to be) is safe -- same
    // reasoning as ExercisePickerActivity's ActivityType.entries.forEach.
    val soundPickers = AlertType.entries.associateWith { rememberSoundPicker(it, alertPlayer) }

    // Its own separate store (see ThemePreferenceRepository), so this writes through immediately
    // on toggle rather than going through draft/Save like the rest of this screen.
    val useLightTheme by themePreferenceRepository.useLightThemeFlow.collectAsStateWithLifecycle(initialValue = false)

    // Settings itself starts collapsed (see the "Settings" header below); which single accordion
    // is open inside it is tracked here rather than by each AccordionSection independently, so
    // opening one closes whichever other one was already open instead of stacking up.
    var settingsExpanded by remember { mutableStateOf(false) }
    var expandedInnerSection by remember { mutableStateOf<String?>("General") }

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
                "Pulse Guard",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
            )
            HorizontalDivider()

            DashboardStatsRow(
                streakDays = currentStreakDays(runs),
                thisMonth = formatDistance(thisMonthDistanceMeters(runs).toFloat(), saved.distanceUnit),
                lastWorkout = runs.maxByOrNull { it.startedAtMillis }
                    ?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.startedAtMillis)) }
                    ?: "--"
            )
            HorizontalDivider()

            MenuRow("Leaderboard") { context.startActivity(Intent(context, LeaderboardActivity::class.java)) }
            MenuRow("Monthly Stats") { context.startActivity(Intent(context, MonthlyChartActivity::class.java)) }
            MenuRow("Run History") { context.startActivity(Intent(context, RunHistoryActivity::class.java)) }

            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { settingsExpanded = !settingsExpanded }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Settings ${if (settingsExpanded) "▲" else "▼"}",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            HorizontalDivider()

            if (!settingsExpanded) return@Column

            AccordionSection(
                "General",
                expanded = expandedInnerSection == "General",
                onToggle = { expandedInnerSection = if (expandedInnerSection == "General") null else "General" }
            ) {
                SwitchRow("Light theme", useLightTheme) {
                    scope.launch { themePreferenceRepository.setUseLightTheme(it) }
                }
                NumberField(
                    "Break length (seconds)",
                    draft.breakTimerSeconds,
                    onChange = { draft = draft.copy(breakTimerSeconds = it) },
                    onBlankChanged = { blankFields["Break length (seconds)"] = it }
                )
                NumberField(
                    "Warmup period (seconds, no push-harder alerts while ramping up)",
                    draft.warmupSeconds,
                    onChange = { draft = draft.copy(warmupSeconds = it) },
                    onBlankChanged = { blankFields["Warmup period (seconds)"] = it }
                )
                SwitchRow(
                    "Launch Strava when starting a run (if installed on the watch)",
                    draft.launchStravaOnStart
                ) {
                    draft = draft.copy(launchStravaOnStart = it)
                }
            }

            AccordionSection(
                "Heart rate",
                expanded = expandedInnerSection == "Heart rate",
                onToggle = { expandedInnerSection = if (expandedInnerSection == "Heart rate") null else "Heart rate" }
            ) {
                // Independent of the Pace section's toggle below -- either, both, or neither can
                // be on.
                SwitchRow("Enable heart rate alerts", draft.heartRateAlertsEnabled) {
                    draft = draft.copy(heartRateAlertsEnabled = it)
                }
                Text(
                    latestCalibration?.let {
                        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.recordedAtMillis))
                        "Calibrated max HR: ${it.bpm} bpm ($date)"
                    } ?: "No max HR calibration yet."
                )
                Button(onClick = { scope.launch { calibrationRepository.requestCalibrationOnWatch() } }) {
                    Text("Start calibration on watch")
                }
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

            AccordionSection(
                "Pace",
                expanded = expandedInnerSection == "Pace",
                onToggle = { expandedInnerSection = if (expandedInnerSection == "Pace") null else "Pace" }
            ) {
                SwitchRow("Enable pace alerts", draft.paceAlertsEnabled) {
                    draft = draft.copy(paceAlertsEnabled = it)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pace unit")
                    UnitDropdown(
                        selected = paceUnit,
                        options = PACE_UNITS,
                        onSelect = { draft = draft.copy(paceUnit = it) }
                    )
                }
                val fastestSecPerUnit = (draft.fastestPaceSecPerKm * (paceUnit.metersPerUnit / 1000.0)).roundToInt()
                PaceField(
                    "Fastest allowed pace (per ${paceUnit.symbol})",
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
                    "Slowest allowed pace (per ${paceUnit.symbol})",
                    slowestSecPerUnit,
                    onChange = { enteredSecPerUnit ->
                        draft = draft.copy(
                            slowestPaceSecPerKm = (enteredSecPerUnit * (1000.0 / paceUnit.metersPerUnit)).roundToInt()
                        )
                    },
                    onBlankChanged = { blankFields["Slowest allowed pace"] = it }
                )
            }

            AccordionSection(
                "Target",
                expanded = expandedInnerSection == "Target",
                onToggle = { expandedInnerSection = if (expandedInnerSection == "Target") null else "Target" }
            ) {
                DistanceUnitField(
                    "Distance target (optional, 0 = none)",
                    draft.distanceTargetMeters,
                    unit = targetDistanceUnit,
                    onUnitChange = { draft = draft.copy(distanceUnit = it) },
                    onMetersChange = { draft = draft.copy(distanceTargetMeters = it) }
                )
            }

            AccordionSection(
                "Custom sounds",
                expanded = expandedInnerSection == "Custom sounds",
                onToggle = {
                    expandedInnerSection = if (expandedInnerSection == "Custom sounds") null else "Custom sounds"
                }
            ) {
                Text("Each alert can play its own sound on the phone instead of the default.")
                AlertType.entries.forEach { type ->
                    Button(onClick = { soundPickers.getValue(type).launch(SUPPORTED_AUDIO_MIME_TYPES) }) {
                        Text(type.soundPickerLabel())
                    }
                }
            }

            Button(
                onClick = {
                    // Only fields relevant to whichever alert types are currently enabled (and,
                    // within heart rate, the current threshold mode) are checked -- a stale blank
                    // flag from a hidden/disabled field shouldn't block Save.
                    val relevantLabels = buildSet {
                        add("Break length (seconds)")
                        add("Warmup period (seconds)")
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
 * Collapsible group of settings. `expanded`/`onToggle` are owned by the caller rather than kept
 * internally, so SettingsScreen can enforce that only one of General/Heart rate/Pace/Target/
 * Custom sounds is ever open at once -- opening one closes whichever other one was open, instead
 * of them stacking up independently.
 */
@Composable
private fun AccordionSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
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

/** Button label for the "Custom sounds" picker list -- kept out of :shared since it's UI text,
 * not a model concern (same reasoning as ActivityType.displayName() on :wear). */
private fun AlertType.soundPickerLabel(): String = when (this) {
    AlertType.LOW_HR -> "Push-harder alert (heart rate)"
    AlertType.HIGH_HR -> "Break alert (heart rate)"
    AlertType.PACE_TOO_SLOW -> "Push-harder alert (pace)"
    AlertType.PACE_TOO_FAST -> "Ease-up alert (pace)"
    AlertType.TARGET_REACHED -> "Target-reached alert"
    AlertType.HALFWAY -> "Halfway-there alert"
}

/** Tap-to-reveal unit picker, matching the "value box + unit box" pattern of the mock. */
@Composable
internal fun UnitDropdown(selected: DistanceUnit, options: List<DistanceUnit>, onSelect: (DistanceUnit) -> Unit) {
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
/** Pace entered as separate Min/Sec number boxes -- no colon to type, just digits in each,
 * reusing NumberField (and its digit-only filtering) for both. */
@Composable
private fun PaceField(
    label: String,
    secPerUnit: Int,
    onChange: (Int) -> Unit,
    onBlankChanged: (Boolean) -> Unit = {}
) {
    val minutes = secPerUnit / 60
    val seconds = secPerUnit % 60
    var minBlank by remember { mutableStateOf(false) }
    var secBlank by remember { mutableStateOf(false) }

    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NumberField(
                "Min",
                minutes,
                onChange = { newMinutes -> onChange(newMinutes * 60 + seconds) },
                onBlankChanged = {
                    minBlank = it
                    onBlankChanged(minBlank || secBlank)
                },
                modifier = Modifier.weight(1f)
            )
            NumberField(
                "Sec",
                seconds,
                onChange = { newSeconds -> onChange(minutes * 60 + newSeconds) },
                onBlankChanged = {
                    secBlank = it
                    onBlankChanged(minBlank || secBlank)
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
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
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            // Actively strips anything non-digit (not just hinting a numeric keyboard) so pasted
            // or externally-typed text can't leave garbage in the field that silently fails to
            // parse -- the keyboard type alone doesn't stop that.
            val filtered = newText.filter { it.isDigit() }
            text = filtered
            val parsed = filtered.toIntOrNull()
            when {
                parsed != null -> onChange(parsed)
                filtered.isBlank() && optional -> onChange(0)
            }
            onBlankChanged(filtered.isBlank() && !optional)
        },
        label = { Text(label) },
        isError = text.isBlank() && !optional,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            focusManager.clearFocus()
            keyboardController?.hide()
        }),
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

/** One row in the top-level nav menu (Leaderboard/Monthly Stats/Run History). */
@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(">", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** At-a-glance home screen stats, derived straight from run history -- no separate stored state,
 * so they can never drift from what Run History/the leaderboard/the monthly chart themselves show. */
@Composable
private fun DashboardStatsRow(streakDays: Int, thisMonth: String, lastWorkout: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        StatItem("Streak", if (streakDays > 0) "$streakDays d" else "--")
        StatItem("This month", thisMonth)
        StatItem("Last workout", lastWorkout)
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
