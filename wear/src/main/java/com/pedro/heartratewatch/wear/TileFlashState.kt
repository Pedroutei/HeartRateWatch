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
 * Tap feedback for the tile's buttons: Start blinks green, Stop blinks red, Change mode blinks
 * blue. Tiles are pull-based (no built-in animation), so an actual on/off blink is simulated by
 * having onTileRequest check plain time-based state (activeColorOrNull) that alternates between
 * the color and normal every PHASE_MS, paired with one explicit refresh request per phase
 * boundary -- Tiles never repaint on their own, so each toggle needs its own requestUpdate.
 * Process-lifetime singleton (like HeartRateRepository) since whatever triggers a blink
 * (TileActionActivity, ExercisePickerActivity) finishes or gets covered almost immediately and
 * can't be trusted to still be alive to drive the rest of the sequence itself.
 *
 * NOTE FOR PEDRO: if this doesn't visibly blink on the actual watch, the system may be throttling
 * rapid-fire requestUpdate calls (undocumented as far as I could tell) -- try raising PHASE_MS.
 */
object TileFlashState {

    enum class FlashColor { GREEN, RED, BLUE }

    private const val PHASE_MS = 180L
    private const val BLINK_COUNT = 2 // on/off pairs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
        flashColor = color
        startedAt = System.currentTimeMillis()
        val updater = TileService.getUpdater(context.applicationContext)
        updater.requestUpdate(HeartRateTileService::class.java)
        scope.launch {
            repeat(BLINK_COUNT * 2) {
                delay(PHASE_MS)
                updater.requestUpdate(HeartRateTileService::class.java)
            }
        }
    }
}
