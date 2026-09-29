package com.pedro.heartratewatch.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pedro.heartratewatch.mobile.theme.PipBoyTheme
import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.RunSummary
import com.pedro.heartratewatch.shared.TrainingSettings
import com.pedro.heartratewatch.shared.formatDistance
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
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PipBoyTheme {
                LeaderboardScreen(repository, settingsRepository)
            }
        }
    }
}

@Composable
private fun LeaderboardScreen(repository: RunHistoryRepository, settingsRepository: SettingsRepository) {
    val allWorkouts by repository.historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by settingsRepository.settingsFlow.collectAsStateWithLifecycle(initialValue = TrainingSettings())

    // Bike rides have no splits or pace and would only pollute the running sections.
    val runs = allWorkouts.filter { it.activityType == ActivityType.RUN }
    val bikeRides = allWorkouts
        .filter { it.activityType == ActivityType.STATIONARY_BIKE && it.distanceMeters > 0f }
        .sortedByDescending { it.distanceMeters }
        .take(ENTRIES_PER_DISTANCE)

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Leaderboard", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Real recorded splits where available; entries marked (est.) are approximated from " +
                    "the run's average pace instead (older runs, or ones without full GPS data).",
                style = MaterialTheme.typography.bodySmall
            )

            LEADERBOARD_DISTANCES.forEach { (targetMeters, label, splitField) ->
                val entries = runs
                    .mapNotNull { run -> leaderboardEntry(run, targetMeters, splitField) }
                    .sortedBy { it.seconds }
                    .take(ENTRIES_PER_DISTANCE)

                LeaderboardSection(label) {
                    if (entries.isEmpty()) {
                        Text("No runs at least this long yet.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        entries.forEachIndexed { index, entry ->
                            val date =
                                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.run.startedAtMillis))
                            val suffix = if (entry.isEstimate) " (est.)" else ""
                            LeaderboardRow(
                                "${index + 1}. %d:%02d$suffix".format(entry.seconds / 60, entry.seconds % 60),
                                date
                            )
                        }
                    }
                }
            }

            LeaderboardSection("Longest stationary bike ride") {
                if (bikeRides.isEmpty()) {
                    Text("No bike rides with a distance entered yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    bikeRides.forEachIndexed { index, ride ->
                        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ride.startedAtMillis))
                        LeaderboardRow("${index + 1}. ${formatDistance(ride.distanceMeters, settings.distanceUnit)}", date)
                    }
                }
            }
        }
    }
}

/** A bordered panel for one leaderboard category, so the page reads as distinct sections instead
 * of one long flowing list. */
@Composable
private fun LeaderboardSection(title: String, content: @Composable () -> Unit) {
    OutlinedCard(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            content()
        }
    }
}

/** One leaderboard entry: the rank/value on the left, the date pushed to the right. */
@Composable
private fun LeaderboardRow(value: String, date: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(value, style = MaterialTheme.typography.bodyMedium)
        Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
