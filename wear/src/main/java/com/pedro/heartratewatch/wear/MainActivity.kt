package com.pedro.heartratewatch.wear

import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.Wearable
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.TrainingSettings
import com.pedro.heartratewatch.shared.formatDistance
import com.pedro.heartratewatch.shared.formatPace
import com.pedro.heartratewatch.wear.theme.PulseGuardTheme

class MainActivity : ComponentActivity() {

    // Live (code-registered) listener, as opposed to the manifest-declared
    // SettingsSyncListenerService: this one only works while the app is actually running, but
    // unlike the manifest listener it isn't subject to Android's restriction on other processes
    // cold-starting a background service -- see the comment on SettingsSyncListenerService for
    // why we ended up needing both. Delegates to the same parsing/apply logic either way.
    private val dataChangedListener = DataClient.OnDataChangedListener { dataEvents ->
        SettingsSyncListenerService.handleDataEvents(applicationContext, dataEvents)
    }

    private val calibrationStore by lazy { CalibrationStore(applicationContext) }
    private val settingsStore by lazy { SettingsStore(applicationContext) }
    private val activityModeStore by lazy { ActivityModeStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Wearable.getDataClient(this).addListener(dataChangedListener)
        setContent {
            PulseGuardTheme {
                RunScreen(calibrationStore, settingsStore, activityModeStore)
            }
        }
    }

    override fun onDestroy() {
        Wearable.getDataClient(this).removeListener(dataChangedListener)
        super.onDestroy()
    }
}

@Composable
private fun RunScreen(
    calibrationStore: CalibrationStore,
    settingsStore: SettingsStore,
    activityModeStore: ActivityModeStore
) {
    val context = LocalContext.current
    val state by HeartRateRepository.state.collectAsState()
    val latestCalibration by calibrationStore.latestFlow.collectAsState(initial = null)
    val settings by settingsStore.settingsFlow.collectAsState(initial = TrainingSettings())
    val selectedType by activityModeStore.selectedFlow.collectAsState(initial = ActivityType.RUN)

    // ACCESS_FINE_LOCATION is requested unconditionally (not just when Settings' GPS toggle is
    // currently on) -- that toggle syncs in from the phone at any time without re-showing this
    // screen, so ExerciseSessionService would otherwise hit a SecurityException the first time
    // GPS gets enabled after this permission screen was already passed. See Permissions.kt --
    // shared with TileActionActivity, which needs the exact same check before starting a session.
    val requiredPermissions = remember { requiredWearPermissions() }

    var hasPermissions by remember { mutableStateOf(hasRequiredWearPermissions(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> hasPermissions = results.values.all { it } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Extra top/bottom padding beyond the sides -- on a round screen, the first/last
            // item in a scrolling list sits right where the bezel's curve cuts into the content
            // area, so it needs more clearance than a flat rectangular screen would.
            .padding(horizontal = 16.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!hasPermissions) {
            Text("PulseGuard needs sensor access to track your heart rate.")
            Spacer(Modifier.height(8.dp))
            Button(onClick = { permissionLauncher.launch(requiredPermissions.toTypedArray()) }) {
                Text("Grant access")
            }
            return@Column
        }

        val maxHrBpm = settings.manualMaxHrBpm ?: latestCalibration?.bpm
        StatusText(
            state.currentBpm?.let { "$it bpm" } ?: "-- bpm",
            heartRateStatus(state.currentBpm, settings, maxHrBpm)
        )
        if (state.activityType == ActivityType.RUN) {
            Text(text = formatDistance(state.distanceMeters, settings.distanceUnit))
            StatusText(
                state.currentPaceSecPerKm?.let { formatPace(it, settings.paceUnit) } ?: "-- /${settings.paceUnit.symbol}",
                paceStatus(state.currentPaceSecPerKm, settings)
            )
        }

        if (state.onBreak) {
            Spacer(Modifier.height(8.dp))
            Text("Take a break: ${state.breakSecondsRemaining}s")
        }

        Spacer(Modifier.height(16.dp))

        if (state.isActive) {
            Button(onClick = {
                context.stopService(Intent(context, ExerciseSessionService::class.java))
            }) {
                Text(if (state.activityType == ActivityType.STATIONARY_BIKE) "Stop ride" else "Stop run")
            }
        } else {
            Button(onClick = {
                val intent = Intent(context, ExerciseSessionService::class.java)
                    .putExtra(ExerciseSessionService.EXTRA_ACTIVITY_TYPE, selectedType.name)
                ContextCompat.startForegroundService(context, intent)
            }) {
                Text("Start ${selectedType.displayName().lowercase()}")
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = {
                context.startActivity(Intent(context, ExercisePickerActivity::class.java))
            }) {
                Text("Change mode")
            }
        }

        latestCalibration?.let {
            Spacer(Modifier.height(8.dp))
            Text("Max HR: ${it.bpm} bpm")
        }
    }
}

@Composable
private fun StatusText(value: String, status: EffortStatus?) {
    val (color, arrow) = when (status) {
        EffortStatus.GOOD -> Color(0xFF4CAF50) to ""
        EffortStatus.TOO_HIGH -> Color(0xFFF44336) to "▲ "
        EffortStatus.TOO_LOW -> Color(0xFFF44336) to "▼ "
        null -> Color.Unspecified to ""
    }
    Text(text = arrow + value, color = color)
}
