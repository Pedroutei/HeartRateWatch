package com.pedro.heartratewatch.wear

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import com.pedro.heartratewatch.wear.theme.PulseGuardTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Guided max-HR test: warm up, build effort, push to a genuine max effort, then cool down. The
 * peak bpm seen during the build/max phases is offered up as a calibrated max HR to save (see
 * CalibrationStore). Reuses ExerciseSessionService for the actual sensor session (started with
 * EXTRA_SUPPRESS_ALERTS so the deliberate push past your normal upper threshold doesn't trigger
 * a "break!" alert), rather than duplicating Health Services setup here.
 */
private enum class CalibrationPhase(val seconds: Int, val instruction: String, val tracksPeak: Boolean) {
    WARMUP(5 * 60, "Warm up at an easy pace", tracksPeak = false),
    BUILD(3 * 60, "Build to a hard effort", tracksPeak = true),
    MAX_EFFORT(2 * 60, "Push as hard as you safely can", tracksPeak = true),
    COOLDOWN(3 * 60, "Slow down and recover", tracksPeak = false)
}

class CalibrationActivity : ComponentActivity() {

    private val calibrationStore by lazy { CalibrationStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Same reasoning as MainActivity: a 13-minute guided test is not something you want the
        // screen dimming out on partway through.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            PulseGuardTheme {
                CalibrationScreen(calibrationStore, onDone = { finish() })
            }
        }
    }
}

@Composable
private fun CalibrationScreen(store: CalibrationStore, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by HeartRateRepository.state.collectAsState()

    var started by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var phaseIndex by remember { mutableStateOf(0) }
    var secondsRemaining by remember { mutableStateOf(0) }
    var peakBpm by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(state.currentBpm, phaseIndex, started, finished) {
        val bpm = state.currentBpm
        val phase = CalibrationPhase.entries.getOrNull(phaseIndex)
        if (started && !finished && bpm != null && phase?.tracksPeak == true) {
            if (peakBpm == null || bpm > peakBpm!!) peakBpm = bpm
        }
    }

    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect

        val startIntent = Intent(context, ExerciseSessionService::class.java)
            .putExtra(ExerciseSessionService.EXTRA_SUPPRESS_ALERTS, true)
        ContextCompat.startForegroundService(context, startIntent)

        for ((index, phase) in CalibrationPhase.entries.withIndex()) {
            phaseIndex = index
            for (remaining in phase.seconds downTo 0) {
                secondsRemaining = remaining
                delay(1000)
            }
        }

        context.stopService(Intent(context, ExerciseSessionService::class.java))
        finished = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            !started -> {
                Text("Max HR calibration")
                Spacer(Modifier.height(8.dp))
                Text("This is a maximal-effort test (about 13 min). Stop immediately if you feel dizzy, chest pain, or unusually unwell.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = { started = true }) { Text("Start") }
            }

            finished -> {
                Text(peakBpm?.let { "Peak: $it bpm" } ?: "No reading captured")
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDone) { Text("Discard") }
                    Button(
                        enabled = peakBpm != null,
                        onClick = {
                            peakBpm?.let { bpm -> scope.launch { store.recordResult(bpm) } }
                            onDone()
                        }
                    ) { Text("Save") }
                }
            }

            else -> {
                val phase = CalibrationPhase.entries[phaseIndex]
                Text(phase.instruction)
                Spacer(Modifier.height(8.dp))
                Text("%d:%02d".format(secondsRemaining / 60, secondsRemaining % 60))
                Spacer(Modifier.height(8.dp))
                Text(state.currentBpm?.let { "$it bpm" } ?: "-- bpm")
                peakBpm?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("Peak so far: $it bpm")
                }
            }
        }
    }
}
