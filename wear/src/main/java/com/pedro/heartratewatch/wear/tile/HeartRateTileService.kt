package com.pedro.heartratewatch.wear.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
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
import com.pedro.heartratewatch.wear.ActivityModeStore
import com.pedro.heartratewatch.wear.CalibrationStore
import com.pedro.heartratewatch.wear.EffortStatus
import com.pedro.heartratewatch.wear.ExercisePickerActivity
import com.pedro.heartratewatch.wear.HeartRateRepository
import com.pedro.heartratewatch.wear.SettingsStore
import com.pedro.heartratewatch.wear.TileActionActivity
import com.pedro.heartratewatch.wear.displayName
import com.pedro.heartratewatch.wear.heartRateStatus
import com.pedro.heartratewatch.wear.paceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future

/**
 * A quick-glance Tile: current bpm and (for a run) pace, colored green when within your
 * thresholds and red with an up/down arrow when too high/low (see EffortStatus) -- same
 * thresholds ExerciseSessionService alerts on, so the tile and the alerts always agree. "Change
 * mode" opens ExercisePickerActivity; "Start"/"Stop" taps TileActionActivity, an invisible
 * activity that's the only way to trigger a service start from a Tile (there's no "run a
 * service" tile action, only "launch an activity" or "refresh the tile").
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
        val selectedType = ActivityModeStore(applicationContext).current()
        val maxHrBpm = settings.manualMaxHrBpm ?: calibration?.bpm

        val bpmText = textElement(
            state.currentBpm?.let { "$it bpm" } ?: "-- bpm",
            heartRateStatus(state.currentBpm, settings, maxHrBpm),
            sizeSp = 28f
        )
        val secondLine = if (state.activityType == ActivityType.RUN) {
            textElement(
                state.currentPaceSecPerKm?.let { "%d:%02d /km".format(it / 60, it % 60) } ?: "-- /km",
                paceStatus(state.currentPaceSecPerKm, settings),
                sizeSp = 16f
            )
        } else {
            textElement("Stationary bike", status = null, sizeSp = 16f)
        }

        val startStopLabel = when {
            state.isActive && state.activityType == ActivityType.STATIONARY_BIKE -> "Stop ride"
            state.isActive -> "Stop run"
            else -> "Start ${selectedType.displayName().lowercase()}"
        }

        val layout = LayoutElementBuilders.Column.Builder()
            .addContent(bpmText)
            .addContent(secondLine)
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .addContent(chip("Change mode", ExercisePickerActivity::class.java.name))
                    .addContent(chip(startStopLabel, TileActionActivity::class.java.name))
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

    /** A tappable text "chip" that launches [activityClassName] in this app. */
    private fun chip(label: String, activityClassName: String): LayoutElementBuilders.Text {
        val launch = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(activityClassName)
            .build()
        val clickable = ModifiersBuilders.Clickable.Builder()
            .setId(activityClassName)
            .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(launch).build())
            .build()

        return LayoutElementBuilders.Text.Builder()
            .setText(label)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(13f))
                    .setColor(argb(COLOR_NEUTRAL))
                    .build()
            )
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(clickable).build())
            .build()
    }

    private fun textElement(value: String, status: EffortStatus?, sizeSp: Float): LayoutElementBuilders.Text {
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
                LayoutElementBuilders.FontStyle.Builder().setSize(sp(sizeSp)).setColor(argb(color)).build()
            )
            .build()
    }

    private companion object {
        const val RESOURCES_VERSION = "2"
        const val COLOR_NEUTRAL = 0xFFFFFFFF.toInt()
        const val COLOR_GOOD = 0xFF4CAF50.toInt()
        const val COLOR_BAD = 0xFFF44336.toInt()
    }
}
