package com.pedro.heartratewatch.wear.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
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
import com.pedro.heartratewatch.wear.TileFlashState
import com.pedro.heartratewatch.wear.heartRateStatus
import com.pedro.heartratewatch.wear.paceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future

/**
 * A quick-glance Tile: a full-width "Change mode" bar along the top, bpm/pace/distance centered
 * in the middle (bpm only for a bike ride, colored green/red with an up/down arrow per
 * EffortStatus -- same thresholds ExerciseSessionService alerts on, so the tile and the alerts
 * always agree), and a full-width Start/Stop bar along the bottom. "Change mode" opens
 * ExercisePickerActivity; "Start"/"Stop" taps TileActionActivity, an invisible activity that's the
 * only way to trigger a service start from a Tile (there's no "run a service" tile action, only
 * "launch an activity" or "refresh the tile"). Both bars also briefly flash a color when tapped
 * (green/red/blue -- see TileFlashState) as tap feedback, since otherwise a translucent invisible
 * activity gives zero visual acknowledgement that the tap landed. ExerciseSessionService calls
 * TileService.getUpdater(...).requestUpdate(...) whenever the underlying data changes -- Tiles
 * don't poll on their own, so without that this would only ever show whatever it rendered the
 * first time it was added to a watch face.
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
        // While a session is active, state.activityType (set by ExerciseSessionService) is what's
        // actually running. Otherwise it's just left over from whatever last ran, so the readout
        // layout instead follows ActivityModeStore -- "what Start will begin next" -- which is
        // what ExercisePickerActivity just updated.
        val displayType = if (state.isActive) {
            state.activityType
        } else {
            ActivityModeStore(applicationContext).current()
        }
        val isRun = displayType == ActivityType.RUN

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
        val flash = TileFlashState.activeColorOrNull()

        val layout = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .addContent(
                barButton(
                    "Change mode",
                    ExercisePickerActivity::class.java.name,
                    background = if (flash == TileFlashState.FlashColor.BLUE) COLOR_FLASH_BLUE else COLOR_BUTTON_BACKGROUND
                )
            )
            .addContent(
                LayoutElementBuilders.Box.Builder()
                    .setWidth(expand())
                    .setHeight(expand())
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(readouts.build())
                    .build()
            )
            .addContent(
                barButton(
                    startStopLabel,
                    TileActionActivity::class.java.name,
                    extras = mapOf(TileActionActivity.EXTRA_ACTION to startStopAction),
                    background = when (flash) {
                        TileFlashState.FlashColor.GREEN -> COLOR_FLASH_GREEN
                        TileFlashState.FlashColor.RED -> COLOR_FLASH_RED
                        else -> COLOR_BUTTON_BACKGROUND
                    }
                )
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

    /** A full-width tappable bar (spanning the tile like a chord across the round face) that
     * launches [activityClassName], with [extras] passed through as string Intent extras -- e.g.
     * telling TileActionActivity which of start/stop this tile render actually meant, rather than
     * making it re-derive that itself from possibly-stale live state at tap time. [background]
     * lets the caller show a brief flash color instead of the normal neutral fill. */
    private fun barButton(
        label: String,
        activityClassName: String,
        extras: Map<String, String> = emptyMap(),
        background: Int = COLOR_BUTTON_BACKGROUND
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
                    .setSize(sp(14f))
                    .setColor(argb(COLOR_NEUTRAL))
                    .build()
            )
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(clickable)
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(background))
                            .build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setTop(dp(10f)).setBottom(dp(10f))
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
        const val RESOURCES_VERSION = "4"
        const val READOUT_SIZE_SP = 18f
        const val COLOR_NEUTRAL = 0xFFFFFFFF.toInt()
        const val COLOR_GOOD = 0xFF4CAF50.toInt()
        const val COLOR_BAD = 0xFFF44336.toInt()
        const val COLOR_BUTTON_BACKGROUND = 0xFF3A3A3A.toInt()
        // Brief tap-feedback fills for the bars -- see TileFlashState. Darker than the plain
        // COLOR_GOOD/COLOR_BAD readout colors so white button text stays readable on top.
        const val COLOR_FLASH_GREEN = 0xFF2E7D32.toInt()
        const val COLOR_FLASH_RED = 0xFFC62828.toInt()
        const val COLOR_FLASH_BLUE = 0xFF1565C0.toInt()
    }
}
