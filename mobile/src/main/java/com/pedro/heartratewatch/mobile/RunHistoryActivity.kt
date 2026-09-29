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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.RunSummary
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class RunHistoryActivity : ComponentActivity() {

    private val repository by lazy { RunHistoryRepository(applicationContext) }
    private val unitPreferences by lazy { UnitPreferencesRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                RunHistoryScreen(repository, unitPreferences)
            }
        }
    }
}

@Composable
private fun RunHistoryScreen(repository: RunHistoryRepository, unitPreferences: UnitPreferencesRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runs by repository.historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // The km/miles preference doubles as the default unit when typing in a bike distance.
    val distanceUnit by unitPreferences.paceUnitFlow.collectAsStateWithLifecycle(initialValue = DistanceUnit.KILOMETERS)
    val snackbarHostState = remember { SnackbarHostState() }
    var editingDistanceFor by remember { mutableStateOf<RunSummary?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                val csv = repository.exportCsv()
                context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                    ?: error("Could not open the chosen file for writing")
            }
            snackbarHostState.showSnackbar(
                if (result.isSuccess) "Exported ${runs.size} workout(s)" else "Export failed"
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not open the chosen file for reading")
                repository.importRuns(repository.parseCsv(text))
            }
            snackbarHostState.showSnackbar(
                result.fold(
                    onSuccess = { added -> "Imported $added new workout(s)" },
                    onFailure = { "Import failed -- not a run history CSV?" }
                )
            )
        }
    }

    editingDistanceFor?.let { run ->
        DistanceDialog(
            run = run,
            defaultUnit = distanceUnit,
            onConfirm = { meters ->
                scope.launch { repository.updateDistance(run.startedAtMillis, meters) }
                editingDistanceFor = null
            },
            onDismiss = { editingDistanceFor = null }
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp)) {
            Text("Run history", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { context.startActivity(Intent(context, LeaderboardActivity::class.java)) }) {
                    Text("Leaderboard")
                }
                Button(onClick = { context.startActivity(Intent(context, MonthlyChartActivity::class.java)) }) {
                    Text("Monthly chart")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportLauncher.launch("pulseguard_runs.csv") }) {
                    Text("Export")
                }
                Button(onClick = { importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) }) {
                    Text("Import")
                }
            }
            Spacer(Modifier.height(16.dp))

            if (runs.isEmpty()) {
                Text("No workouts recorded yet -- finish one on your watch to see it here.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(runs, key = { it.startedAtMillis }) { run ->
                        RunRow(
                            run,
                            onEditDistance = { editingDistanceFor = run },
                            onDiscard = {
                                scope.launch {
                                    repository.removeRun(run.startedAtMillis)
                                    val result = snackbarHostState.showSnackbar(
                                        "Workout discarded",
                                        actionLabel = "Undo",
                                        duration = SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        repository.addRun(run)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RunRow(run: RunSummary, onEditDistance: () -> Unit, onDiscard: () -> Unit) {
    val isBike = run.activityType == ActivityType.STATIONARY_BIKE
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(run.startedAtMillis)),
                style = MaterialTheme.typography.titleSmall
            )
            Button(onClick = onDiscard) { Text("Discard") }
        }
        if (isBike) Text("Stationary bike", style = MaterialTheme.typography.labelLarge)
        Text("Duration: %d:%02d".format(run.durationSeconds / 60, run.durationSeconds % 60))
        Text("Avg ${run.avgBpm} bpm, max ${run.maxBpm} bpm, min ${run.minBpm} bpm")
        if (isBike) {
            Text(
                if (run.distanceMeters > 0f) "Distance: %.2f km".format(run.distanceMeters / 1000)
                else "Distance: not entered yet"
            )
            Button(onClick = onEditDistance) {
                Text(if (run.distanceMeters > 0f) "Edit distance" else "Enter distance")
            }
        } else {
            Text("Distance: %.0f m".format(run.distanceMeters))
            run.avgPaceSecPerKm?.let { Text("Avg pace: %d:%02d /km".format(it / 60, it % 60)) }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
    }
}

/** Typed-in distance for a stationary bike ride, entered as whatever the bike's display shows. */
@Composable
private fun DistanceDialog(
    run: RunSummary,
    defaultUnit: DistanceUnit,
    onConfirm: (meters: Float) -> Unit,
    onDismiss: () -> Unit
) {
    var unit by remember { mutableStateOf(defaultUnit) }
    var text by remember {
        mutableStateOf(
            if (run.distanceMeters > 0f) {
                String.format(Locale.US, "%.2f", run.distanceMeters / defaultUnit.metersPerUnit)
            } else ""
        )
    }
    // Accept a decimal comma as well as a point, since the keypad follows the phone's locale.
    val value = text.replace(',', '.').toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bike distance") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Distance") },
                    isError = text.isNotBlank() && value == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                UnitDropdown(selected = unit, options = PACE_UNITS, onSelect = { unit = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = value != null && value > 0,
                onClick = { onConfirm((value!! * unit.metersPerUnit).toFloat()) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
