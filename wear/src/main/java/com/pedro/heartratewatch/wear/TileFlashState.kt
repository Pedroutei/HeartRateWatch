package com.pedro.heartratewatch.wear

import android.content.Context
import androidx.wear.tiles.TileService
import com.pedro.heartratewatch.wear.tile.HeartRateTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One-shot tap feedback for the tile's buttons: Start flashes green, Stop flashes red, Change
 * mode flashes blue. An earlier version tried to blink on/off across several precisely-timed
 * requestUpdate calls, but rapid-fire requestUpdate calls appear to get coalesced by the platform
 * (undocumented, found by testing on-device) -- whichever call the system actually services just
 * renders the real state at that moment, and others queued around the same time get dropped. A
 * multi-phase blink needs most of those calls to each land their own separate render, so it kept
 * missing phases and often showed no color change at all.
 *
 * This is simpler and far more robust: it just remembers "show this color once", consumed (and
 * cleared) by whichever render actually happens first -- trigger()'s own immediate request, or
 * any other refresh already about to happen right after anyway (Stop's stats reset, a mode
 * change, a session actually starting). As long as at least one render lands before something
 * else overwrites the pending color, the flash is guaranteed to show exactly once, regardless of
 * how many of the requestUpdate calls around it get coalesced away.
 */
object TileFlashState {

    enum class FlashColor { GREEN, RED, BLUE }

    // Safety net only, in case nothing else happens to refresh the tile soon after trigger() --
    // clears a still-pending flash rather than leaving it showing indefinitely.
    private const val MAX_VISIBLE_MS = 1200L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var pendingColor: FlashColor? = null

    /** Called once per tile render: returns the color to show (if any) and clears it, so it's
     * only ever shown on the first render that actually happens after trigger(). */
    fun consumeColorOrNull(): FlashColor? {
        val color = pendingColor
        pendingColor = null
        return color
    }

    fun trigger(context: Context, color: FlashColor) {
        pendingColor = color
        val updater = TileService.getUpdater(context.applicationContext)
        updater.requestUpdate(HeartRateTileService::class.java)
        scope.launch {
            delay(MAX_VISIBLE_MS)
            // Only clear it if it's still THIS trigger's color -- a newer trigger() call (e.g.
            // spamming Start/Stop) already replaced it, and that one owns clearing itself now.
            if (pendingColor == color) {
                pendingColor = null
                updater.requestUpdate(HeartRateTileService::class.java)
            }
        }
    }
}
