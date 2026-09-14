package com.pedro.heartratewatch.wear

import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.Wearable
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dev/testing convenience: keeps the screen from dimming into ambient mode so you're
        // not fighting the emulator while testing. This burns battery fast, so it's worth
        // removing (or making conditional) before this app is something you'd actually wear.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Wearable.getDataClient(this).addListener(dataChangedListener)
        setContent {
            PulseGuardTheme {
                RunScreen(calibrationStore)
            }
        }
    }

    override fun onDestroy() {
        Wearable.getDataClient(this).removeListener(dataChangedListener)
        super.onDestroy()
    }
}

@Composable
private fun RunScreen(calibrationStore: CalibrationStore) {
    val context = LocalContext.current
    val state by HeartRateRepository.state.collectAsState()
    val latestCalibration by calibrationStore.latestFlow.collectAsState(initial = null)

    val requiredPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= 36) {
                add("android.permission.health.READ_HEART_RATE")
            } else {
                add(Manifest.permission.BODY_SENSORS)
            }
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var hasPermissions by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) ==
                    PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> hasPermissions = results.values.all { it } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
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

        Text(text = state.currentBpm?.let { "$it bpm" } ?: "-- bpm")
        Text(text = "%.0f m".format(state.distanceMeters))

        if (state.onBreak) {
            Spacer(Modifier.height(8.dp))
            Text("Take a break: ${state.breakSecondsRemaining}s")
        }

        Spacer(Modifier.height(16.dp))

        Button(onClick = {
            val intent = Intent(context, ExerciseSessionService::class.java)
            if (state.isActive) {
                context.stopService(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        }) {
            Text(if (state.isActive) "Stop run" else "Start run")
        }

        if (!state.isActive) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                context.startActivity(Intent(context, CalibrationActivity::class.java))
            }) {
                Text("Calibrate max HR")
            }
            latestCalibration?.let {
                Spacer(Modifier.height(4.dp))
                Text("Max HR: ${it.bpm} bpm")
            }
        }
    }
}
