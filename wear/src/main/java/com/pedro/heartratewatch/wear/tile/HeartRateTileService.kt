package com.pedro.heartratewatch.wear.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.wear.CalibrationStore
import com.pedro.heartratewatch.wear.EffortStatus
import com.pedro.heartratewatch.wear.ExercisePickerActivity
import com.pedro.heartratewatch.wear.HeartRateRepository
import com.pedro.heartratewatch.wear.SettingsStore
import com.pedro.heartratewatch.wear.TileActionActivity
import com.pedro.heartratewatch.wear.heartRateStatus
import com.pedro.heartratewatch.wear.paceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future

/**
 * A quick-glance Tile: bpm, pace, and distance for a run (bpm only for a bike ride), all at one
 * consistent size, colored green when within your thresholds and red with an up/down arrow when
 * too high/low (see EffortStatus) -- same thresholds ExerciseSessionService alerts on, so the
 * tile and the alerts always agree. "Change mode" opens ExercisePickerActivity; "Start"/"Stop"
 * taps TileActionActivity, an invisible activity that's the only way to trigger a service start
 * from a Tile (there's no "run a service" tile action, only "launch an activity" or "refresh the
 * tile"). ExerciseSessionService calls TileService.getUpdater(...).requestUpdate(...) whenever
 * the underlying data changes -- Tiles don't poll on their own, so without that this would only
 * ever show whatever it rendered the first time it was added to a watch face.
 *
 * NOTE FOR PEDRO: like ExerciseSessionService's Health Services calls, ProtoLayout's exact API
 * shape (ActionBuilders, ModifiersBuilders) has shifted across releases -- if this doesn't
 * compile, Alt+Enter is again the fastest way to the current method names.
 */
class HeartRateTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> = scope.future {
        val state = HeartRateRepository.state.value
        val settings = SettingsStore(applicationContext).settingsFlow.first()
        val calibration = CalibrationStore(applicationContext).latestFlow.first()
        val maxHrBpm = settings.manualMaxHrBpm ?: calibration?.bpm
        val isRun = state.activityType == ActivityType.RUN

        val readouts = LayoutElementBuilders.Column.Builder()
            .addContent(
                readoutText(
                    state.currentBpm?.let { "$it bpm" } ?: "-- bpm",
                    heartRateStatus(state.currentBpm, settings, maxHrBpm)
                )
            )
        if (isRun) {
            readouts
                .addContent(
                    readoutText(
                        state.currentPaceSecPerKm?.let { "%d:%02d /km".format(it / 60, it % 60) } ?: "-- /km",
                        paceStatus(state.currentPaceSecPerKm, settings)
                    )
                )
                .addContent(readoutText("%.0f m".format(state.distanceMeters), status = null))
        } else {
            readouts.addContent(readoutText("Stationary bike", status = null))
        }

        val startStopLabel = if (state.isActive) "Stop" else "Start"
        val startStopAction = if (state.isActive) TileActionActivity.ACTION_STOP else TileActionActivity.ACTION_START

        val layout = LayoutElementBuilders.Column.Builder()
            .addContent(readouts.build())
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .addContent(button("Change mode", ExercisePickerActivity::class.java.name))
                    .addContent(
                        button(
                            startStopLabel,
                            TileActionActivity::class.java.name,
                            extras = mapOf(TileActionActivity.EXTRA_ACTION to startStopAction)
                        )
                    )
                    .build()
            )
            .build()

        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(
            ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
        )

    /** A rounded, filled "chip" that reads as a tappable button and launches [activityClassName],
     * with [extras] passed through as string Intent extras -- e.g. telling TileActionActivity
     * which of start/stop this tile render actually meant, rather than making it re-derive that
     * itself from possibly-stale live state at tap time. */
    private fun button(
        label: String,
        activityClassName: String,
        extras: Map<String, String> = emptyMap()
    ): LayoutElementBuilders.Box {
        val activityBuilder = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(activityClassName)
        extras.forEach { (key, value) ->
            activityBuilder.addKeyToExtraMapping(
                key,
                ActionBuilders.AndroidStringExtra.Builder().setValue(value).build()
            )
        }
        val launch = activityBuilder.build()
        val clickable = ModifiersBuilders.Clickable.Builder()
            .setId(activityClassName)
            .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(launch).build())
            .build()

        val text = LayoutElementBuilders.Text.Builder()
            .setText(label)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(13f))
                    .setColor(argb(COLOR_NEUTRAL))
                    .build()
            )
            .build()

        return LayoutElementBuilders.Box.Builder()
            .addContent(text)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(clickable)
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(COLOR_BUTTON_BACKGROUND))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(16f)).build())
                            .build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(12f)).setEnd(dp(12f))
                            .setTop(dp(6f)).setBottom(dp(6f))
                            .build()
                    )
                    .build()
            )
            .build()
    }

    /** One bpm/pace/distance line -- all the same size so nothing looks accidentally more important. */
    private fun readoutText(value: String, status: EffortStatus?): LayoutElementBuilders.Text {
        val color = when (status) {
            EffortStatus.GOOD -> COLOR_GOOD
            EffortStatus.TOO_HIGH, EffortStatus.TOO_LOW -> COLOR_BAD
            null -> COLOR_NEUTRAL
        }
        val arrow = when (status) {
            EffortStatus.TOO_HIGH -> "▲ "
            EffortStatus.TOO_LOW -> "▼ "
            else -> ""
        }
        return LayoutElementBuilders.Text.Builder()
            .setText(arrow + value)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder().setSize(sp(READOUT_SIZE_SP)).setColor(argb(color)).build()
            )
            .build()
    }

    private companion object {
        // Bump whenever the layout changes -- resources (not the live data) are cached by
        // version, and this is what invalidates that cache.
        const val RESOURCES_VERSION = "3"
        const val READOUT_SIZE_SP = 18f
        const val COLOR_NEUTRAL = 0xFFFFFFFF.toInt()
        const val COLOR_GOOD = 0xFF4CAF50.toInt()
        const val COLOR_BAD = 0xFFF44336.toInt()
        const val COLOR_BUTTON_BACKGROUND = 0xFF3A3A3A.toInt()
    }
}
