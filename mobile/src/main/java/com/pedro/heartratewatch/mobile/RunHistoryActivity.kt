package com.pedro.heartratewatch.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pedro.heartratewatch.shared.RunSummary
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
    val runs by repository.historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Run history", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        if (runs.isEmpty()) {
            Text("No runs recorded yet -- finish a run on your watch to see it here.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(runs) { run -> RunRow(run) }
            }
        }
    }
}

@Composable
private fun RunRow(run: RunSummary) {
    Column {
        Text(
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(run.startedAtMillis)),
            style = MaterialTheme.typography.titleSmall
        )
        Text("Duration: %d:%02d".format(run.durationSeconds / 60, run.durationSeconds % 60))
        Text("Avg ${run.avgBpm} bpm, max ${run.maxBpm} bpm, min ${run.minBpm} bpm")
        Text("Distance: %.0f m".format(run.distanceMeters))
        run.avgPaceSecPerKm?.let { Text("Avg pace: %d:%02d /km".format(it / 60, it % 60)) }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
    }
}
