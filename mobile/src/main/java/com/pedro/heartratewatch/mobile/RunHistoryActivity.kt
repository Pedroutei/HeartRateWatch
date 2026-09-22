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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pedro.heartratewatch.shared.RunSummary
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class RunHistoryActivity : ComponentActivity() {

    private val repository by lazy { RunHistoryRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                RunHistoryScreen(repository)
            }
        }
    }
}

@Composable
private fun RunHistoryScreen(repository: RunHistoryRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runs by repository.historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val snackbarHostState = remember { SnackbarHostState() }

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
                if (result.isSuccess) "Exported ${runs.size} run(s)" else "Export failed"
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
                    onSuccess = { added -> "Imported $added new run(s)" },
                    onFailure = { "Import failed -- not a run history CSV?" }
                )
            )
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp)) {
            Text("Run history", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { context.startActivity(Intent(context, LeaderboardActivity::class.java)) }) {
                    Text("Leaderboard")
                }
                Button(onClick = { exportLauncher.launch("pulseguard_runs.csv") }) {
                    Text("Export")
                }
                Button(onClick = { importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) }) {
                    Text("Import")
                }
            }
            Spacer(Modifier.height(16.dp))

            if (runs.isEmpty()) {
                Text("No runs recorded yet -- finish a run on your watch to see it here.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(runs, key = { it.startedAtMillis }) { run ->
                        RunRow(run, onDiscard = {
                            scope.launch {
                                repository.removeRun(run.startedAtMillis)
                                val result = snackbarHostState.showSnackbar(
                                    "Run discarded",
                                    actionLabel = "Undo",
                                    duration = SnackbarDuration.Short
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    repository.addRun(run)
                                }
                            }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun RunRow(run: RunSummary, onDiscard: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(run.startedAtMillis)),
                style = MaterialTheme.typography.titleSmall
            )
            Button(onClick = onDiscard) { Text("Discard") }
        }
        Text("Duration: %d:%02d".format(run.durationSeconds / 60, run.durationSeconds % 60))
        Text("Avg ${run.avgBpm} bpm, max ${run.maxBpm} bpm, min ${run.minBpm} bpm")
        Text("Distance: %.0f m".format(run.distanceMeters))
        run.avgPaceSecPerKm?.let { Text("Avg pace: %d:%02d /km".format(it / 60, it % 60)) }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
    }
}
