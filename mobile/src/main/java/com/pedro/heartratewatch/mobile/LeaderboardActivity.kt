package com.pedro.heartratewatch.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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

private data class LeaderboardEntry(val run: RunSummary, val seconds: Int, val isEstimate: Boolean)

/** Standard distances shown (meters -> display label -> the RunSummary field holding its real split). */
private val LEADERBOARD_DISTANCES = listOf(
    Triple(1_000f, "Fastest 1 km", RunSummary::best1kmSeconds),
    Triple(5_000f, "Fastest 5 km", RunSummary::best5kmSeconds),
    Triple(10_000f, "Fastest 10 km", RunSummary::best10kmSeconds)
)
private const val ENTRIES_PER_DISTANCE = 5

/**
 * Best time at each standard distance. Prefers the real split ExerciseSessionService records
 * (bestSplitSeconds, a sliding window over the whole run's distance samples) -- falls back to
 * estimating from the run's *average* pace (avgPaceSecPerKm * distanceKm) only for runs recorded
 * before split-tracking existed, or ones where the real split is missing for some other reason
 * (e.g. GPS was off). Estimated entries are marked "(est.)" since they can be off from a true
 * split, e.g. a fast-starting run's real 5 km would be quicker than its whole-run average implies.
 */
class LeaderboardActivity : ComponentActivity() {

    private val repository by lazy { RunHistoryRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LeaderboardScreen(repository)
            }
        }
    }
}

@Composable
private fun LeaderboardScreen(repository: RunHistoryRepository) {
    val runs by repository.historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Leaderboard", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Real recorded splits where available; entries marked (est.) are approximated from " +
                "the run's average pace instead (older runs, or ones without full GPS data).",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))

        LEADERBOARD_DISTANCES.forEach { (targetMeters, label, splitField) ->
            val entries = runs
                .mapNotNull { run -> leaderboardEntry(run, targetMeters, splitField) }
                .sortedBy { it.seconds }
                .take(ENTRIES_PER_DISTANCE)

            Text(label, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (entries.isEmpty()) {
                Text("No runs at least this long yet.")
            } else {
                entries.forEachIndexed { index, entry ->
                    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.run.startedAtMillis))
                    val suffix = if (entry.isEstimate) " (est.)" else ""
                    Text("${index + 1}. %d:%02d$suffix -- $date".format(entry.seconds / 60, entry.seconds % 60))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun leaderboardEntry(
    run: RunSummary,
    targetMeters: Float,
    splitField: (RunSummary) -> Int?
): LeaderboardEntry? {
    splitField(run)?.let { return LeaderboardEntry(run, it, isEstimate = false) }
    if (run.distanceMeters < targetMeters) return null
    val avgPace = run.avgPaceSecPerKm ?: return null
    return LeaderboardEntry(run, (avgPace * (targetMeters / 1000f)).toInt(), isEstimate = true)
}
