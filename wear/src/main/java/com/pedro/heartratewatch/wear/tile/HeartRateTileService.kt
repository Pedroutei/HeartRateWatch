package com.pedro.heartratewatch.wear.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.weight
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
import com.pedro.heartratewatch.shared.DistanceUnit
import com.pedro.heartratewatch.shared.formatDistance
import com.pedro.heartratewatch.shared.formatPace
import com.pedro.heartratewatch.wear.ActivityModeStore
import com.pedro.heartratewatch.wear.CalibrationStore
import com.pedro.heartratewatch.wear.DashboardStats
import com.pedro.heartratewatch.wear.DashboardStatsStore
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
import java.text.DateFormat
import java.util.Date

/**
 * A quick-glance Tile: a full-width "Change mode" bar (always blue) along the top, bpm/pace/
 * distance centered in the middle (bpm only for a bike ride, colored green/red with an up/down
 * arrow per EffortStatus -- same thresholds ExerciseSessionService alerts on, so the tile and the
 * alerts always agree), and a full-width Start/Stop bar along the bottom, colored green while it
 * reads "Start" and red while it reads "Stop". The bar colors are permanent, not a tap animation --
 * an earlier attempt at a brief flash-on-tap turned out unreliable, since the platform appears to
 * coalesce rapid-fire tile requestUpdate calls (undocumented, found through on-device testing),
 * so a timed flash often silently failed to render at all. Solid, state-based color has no timing
 * to get wrong. "Change mode" opens ExercisePickerActivity; "Start"/"Stop" taps TileActionActivity,
 * an invisible activity that's the only way to trigger a service start from a Tile (there's no
 * "run a service" tile action, only "launch an activity" or "refresh the tile"). ExerciseSessionService
 * calls TileService.getUpdater(...).requestUpdate(...) whenever the underlying data changes --
 * Tiles don't poll on their own, so without that this would only ever show whatever it rendered
 * the first time it was added to a watch face.
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

        // Before a session's started there's nothing live to show yet -- a glance at streak/this
        // month/last workout (the same numbers the phone's own home screen shows) is more useful
        // than a frozen "-- bpm / -- /km / 0.00 km". The watch has no run history of its own, only
        // whatever the phone last pushed to DashboardStatsStore (see SettingsSyncListenerService).
        val middleContent = if (state.isActive) {
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
                            state.currentPaceSecPerKm?.let { formatPace(it, settings.paceUnit) }
                                ?: "-- /${settings.paceUnit.symbol}",
                            paceStatus(state.currentPaceSecPerKm, settings)
                        )
                    )
                    .addContent(
                        readoutText(formatDistance(state.distanceMeters, settings.distanceUnit), status = null)
                    )
            } else {
                readouts.addContent(readoutText("Stationary bike", status = null))
            }
            readouts.build()
        } else {
            val stats = DashboardStatsStore(applicationContext).statsFlow.first()
            dashboardStatsRow(stats, settings.distanceUnit)
        }

        val startStopLabel = if (state.isActive) "Stop" else "Start"
        val startStopAction = if (state.isActive) TileActionActivity.ACTION_STOP else TileActionActivity.ACTION_START

        val layoutBuilder = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHeight(expand())

        // Changing mode only affects what the next Start will begin -- meaningless mid-session,
        // so it's hidden while a session's active rather than just left there doing nothing useful.
        if (!state.isActive) {
            layoutBuilder.addContent(
                barButton("Change mode", ExercisePickerActivity::class.java.name, background = COLOR_CHANGE_MODE)
            )
        }

        val layout = layoutBuilder
            .addContent(
                LayoutElementBuilders.Box.Builder()
                    .setWidth(expand())
                    .setHeight(expand())
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(middleContent)
                    .build()
            )
            .addContent(
                barButton(
                    startStopLabel,
                    TileActionActivity::class.java.name,
                    extras = mapOf(TileActionActivity.EXTRA_ACTION to startStopAction),
                    background = if (state.isActive) COLOR_STOP else COLOR_START
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
     * making it re-derive that itself from possibly-stale live state at tap time. */
    private fun barButton(
        label: String,
        activityClassName: String,
        background: Int,
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

    /** Idle-state row: streak / this month / last workout, matching the phone home screen's own
     * DashboardStatsRow -- three narrow columns fit better on the round face than the wider text
     * a live readout uses, hence the smaller sizes here. */
    private fun dashboardStatsRow(stats: DashboardStats, distanceUnit: DistanceUnit): LayoutElementBuilders.Row {
        val lastWorkout = stats.lastWorkoutMillis?.let {
            DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it))
        } ?: "--"
        return LayoutElementBuilders.Row.Builder()
            .setWidth(expand())
            .addContent(statColumn(if (stats.streakDays > 0) "${stats.streakDays}d" else "--", "Streak"))
            .addContent(statColumn(formatDistance(stats.thisMonthMeters.toFloat(), distanceUnit), "This month"))
            .addContent(statColumn(lastWorkout, "Last workout"))
            .build()
    }

    private fun statColumn(value: String, label: String): LayoutElementBuilders.Column =
        LayoutElementBuilders.Column.Builder()
            .setWidth(weight(1f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(value)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder().setSize(sp(13f)).setColor(argb(COLOR_NEUTRAL)).build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(label)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder().setSize(sp(9f)).setColor(argb(COLOR_GOOD)).build()
                    )
                    .build()
            )
            .build()

    private companion object {
        // Bump whenever the layout changes -- resources (not the live data) are cached by
        // version, and this is what invalidates that cache.
        const val RESOURCES_VERSION = "5"
        const val READOUT_SIZE_SP = 18f
        // Pip-Boy palette -- matches PulseGuardTheme/PipBoyTheme's green-on-black CRT look.
        // COLOR_NEUTRAL used to be plain white; a saturated blue for "Change mode" (still used by
        // ExercisePickerActivity's own Compose screen, which just follows PulseGuardTheme like
        // everything else) would clash badly against a monochrome-green screen, so the bar is a
        // dim, unsaturated green instead -- distinct from the bright COLOR_START fill by
        // brightness alone rather than by hue, which reads as "same system, lower emphasis"
        // instead of an unrelated color breaking the CRT look.
        const val COLOR_NEUTRAL = 0xFF4FFF8A.toInt()
        const val COLOR_GOOD = 0xFF4CAF50.toInt()
        const val COLOR_BAD = 0xFFF44336.toInt()
        // Permanent bar fills, one per action -- darker than the plain COLOR_GOOD/COLOR_BAD
        // readout colors so the bar text stays readable on top.
        const val COLOR_START = 0xFF2E7D32.toInt()
        const val COLOR_STOP = 0xFFC62828.toInt()
        const val COLOR_CHANGE_MODE = 0xFF1B4D33.toInt()
    }
}
