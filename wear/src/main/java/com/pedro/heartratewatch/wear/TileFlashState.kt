package com.pedro.heartratewatch.wear

import android.content.Context
import androidx.wear.tiles.TileService
import com.pedro.heartratewatch.wear.tile.HeartRateTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tap feedback for the tile's buttons: Start blinks green, Stop blinks red, Change mode blinks
 * blue. Tiles are pull-based (no built-in animation), so an actual on/off blink is simulated by
 * having onTileRequest check plain time-based state (activeColorOrNull) that alternates between
 * the color and normal every PHASE_MS, paired with one explicit refresh request per phase
 * boundary -- Tiles never repaint on their own, so each toggle needs its own requestUpdate.
 *
 * Rapid-fire requestUpdate calls appear to get coalesced by the platform (undocumented, found by
 * testing on-device): whichever call the system actually services just renders whatever the real
 * state is at that moment, and any others queued around the same time are silently dropped rather
 * than each getting their own render. Two things follow from that:
 *  - trigger() cancels any still-running previous blink before starting a new one, so spamming
 *    Start/Stop on the tile doesn't leave overlapping sequences fighting over the same handful of
 *    render slots (which was dropping blinks entirely).
 *  - refreshAfterBlink() lets a *different*, functionally-necessary refresh that fires right after
 *    a blink-triggering action (resetting stats once Stop's service actually stops, updating the
 *    readout layout after a mode change) wait until the blink would already be done, instead of
 *    firing immediately and winning the coalescing race before the blink is ever visible at all.
 *
 * Process-lifetime singleton (like HeartRateRepository) since whatever triggers a blink
 * (TileActionActivity, ExercisePickerActivity) finishes or gets covered almost immediately and
 * can't be trusted to still be alive to drive the rest of the sequence itself.
 */
object TileFlashState {

    enum class FlashColor { GREEN, RED, BLUE }

    private const val PHASE_MS = 200L
    private const val BLINK_COUNT = 2 // on/off pairs
    private const val SETTLE_MS = 900L // a bit more than PHASE_MS * BLINK_COUNT * 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var blinkJob: Job? = null

    @Volatile private var startedAt = 0L
    @Volatile private var flashColor: FlashColor? = null

    /** The color to show right now, or null for a normal background -- alternates on/off as the
     * blink plays out, then settles permanently to null once it's done. */
    fun activeColorOrNull(): FlashColor? {
        val color = flashColor ?: return null
        val phase = ((System.currentTimeMillis() - startedAt) / PHASE_MS).toInt()
        if (phase >= BLINK_COUNT * 2) return null
        return if (phase % 2 == 0) color else null
    }

    fun trigger(context: Context, color: FlashColor) {
        blinkJob?.cancel()
        flashColor = color
        startedAt = System.currentTimeMillis()
        val updater = TileService.getUpdater(context.applicationContext)
        updater.requestUpdate(HeartRateTileService::class.java)
        blinkJob = scope.launch {
            repeat(BLINK_COUNT * 2) {
                delay(PHASE_MS)
                updater.requestUpdate(HeartRateTileService::class.java)
            }
        }
    }

    /** For a refresh that's needed regardless of any blink (e.g. Stop always has to zero out the
     * tile's stats) but would otherwise race a blink just triggered by the same tap -- waits until
     * that blink's own sequence would already be finished before asking the tile to redraw. */
    fun refreshAfterBlink(context: Context) {
        val updater = TileService.getUpdater(context.applicationContext)
        scope.launch {
            delay(SETTLE_MS)
            updater.requestUpdate(HeartRateTileService::class.java)
        }
    }
}
