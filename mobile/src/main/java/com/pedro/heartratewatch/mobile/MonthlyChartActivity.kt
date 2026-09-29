package com.pedro.heartratewatch.mobile

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pedro.heartratewatch.mobile.theme.PipBoyTheme
import com.pedro.heartratewatch.shared.DistanceUnit
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

private val GROUP_WIDTH = 64.dp
private val CHART_HEIGHT = 200.dp
private val MONTH_LABEL_FORMAT = DateTimeFormatter.ofPattern("MMM yy")

/**
 * Bar chart of total distance per month, running and stationary biking side by side. Built from
 * run history (see [monthlyTotals]), so it grows automatically as workouts come in and travels
 * with the run-history CSV export/import; the export button here writes the monthly totals
 * themselves as a spreadsheet-friendly CSV (always in km).
 */
class MonthlyChartActivity : ComponentActivity() {

    private val repository by lazy { RunHistoryRepository(applicationContext) }
    private val unitPreferences by lazy { UnitPreferencesRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PipBoyTheme {
                MonthlyChartScreen(repository, unitPreferences)
            }
        }
    }
}

@Composable
private fun MonthlyChartScreen(repository: RunHistoryRepository, unitPreferences: UnitPreferencesRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runs by repository.historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // Reuses the pace unit (km or miles) as the display unit for totals.
    val unit by unitPreferences.paceUnitFlow.collectAsStateWithLifecycle(initialValue = DistanceUnit.KILOMETERS)
    val totals = remember(runs) { monthlyTotals(runs) }
    val snackbarHostState = remember { SnackbarHostState() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                context.contentResolver.openOutputStream(uri)
                    ?.use { it.write(monthlyTotalsCsv(totals).toByteArray()) }
                    ?: error("Could not open the chosen file for writing")
            }
            snackbarHostState.showSnackbar(if (result.isSuccess) "Exported monthly totals" else "Export failed")
        }
    }

    // Deliberately not from the Pip-Boy color scheme (which is all shades of green, too close
    // together to tell the two series apart at a glance) -- fixed, unrelated hues instead so
    // Running vs. Stationary bike actually reads as two distinct colors in the chart.
    val runColor = Color(0xFF4FA3F7)
    val bikeColor = Color(0xFFF5A623)

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Text("Monthly distance", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))

            if (totals.isEmpty()) {
                Text("No workouts recorded yet.")
                return@Column
            }

            fun display(meters: Double) = meters / unit.metersPerUnit

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendSwatch(runColor, "Running")
                LegendSwatch(bikeColor, "Stationary bike")
            }
            Spacer(Modifier.height(8.dp))

            val maxValue = max(totals.maxOf { display(max(it.runMeters, it.bikeMeters)) }, 1.0)
            Text("Tallest bar: %.1f %s".format(maxValue, unit.symbol), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))

            Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Canvas(modifier = Modifier.width(GROUP_WIDTH * totals.size).height(CHART_HEIGHT)) {
                    val groupPx = GROUP_WIDTH.toPx()
                    val barPx = groupPx * 0.32f
                    totals.forEachIndexed { index, total ->
                        val left = index * groupPx + (groupPx - barPx * 2 - 4.dp.toPx()) / 2
                        listOf(display(total.runMeters) to runColor, display(total.bikeMeters) to bikeColor)
                            .forEachIndexed { barIndex, (value, color) ->
                                val barHeight = (value / maxValue).toFloat() * size.height
                                drawRect(
                                    color = color,
                                    topLeft = Offset(left + barIndex * (barPx + 4.dp.toPx()), size.height - barHeight),
                                    size = Size(barPx, barHeight)
                                )
                            }
                    }
                }
                Row {
                    totals.forEach {
                        Text(
                            it.month.format(MONTH_LABEL_FORMAT),
                            modifier = Modifier.width(GROUP_WIDTH),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Totals", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            totals.asReversed().forEach {
                Text(
                    "%s: run %.1f, bike %.1f, total %.1f %s".format(
                        it.month, display(it.runMeters), display(it.bikeMeters), display(it.totalMeters), unit.symbol
                    )
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "All time: run %.1f, bike %.1f, total %.1f %s".format(
                    display(totals.sumOf { it.runMeters }),
                    display(totals.sumOf { it.bikeMeters }),
                    display(totals.sumOf { it.totalMeters }),
                    unit.symbol
                ),
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { exportLauncher.launch("pulseguard_monthly_totals.csv") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Export monthly totals (CSV)") }
        }
    }
}

@Composable
private fun LegendSwatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(12.dp).background(color))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
