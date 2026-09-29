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
 * Brief tap feedback for the tile's buttons: Start flashes green, Stop flashes red, Change mode
 * flashes blue. Tiles are pull-based (no built-in animation), so this is just plain state a tile
 * render can check, plus two explicit refresh requests -- one right away to show the flash color,
 * one after it should have expired to clear it. Process-lifetime singleton (like
 * HeartRateRepository) since whatever triggers a flash (TileActionActivity, ExercisePickerActivity)
 * finishes or gets covered almost immediately and can't be trusted to still be alive to clear it.
 */
object TileFlashState {

    enum class FlashColor { GREEN, RED, BLUE }

    private const val FLASH_DURATION_MS = 350L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var flashedAt = 0L
    @Volatile private var flashColor: FlashColor? = null

    fun activeColorOrNull(): FlashColor? {
        val color = flashColor ?: return null
        return if (System.currentTimeMillis() - flashedAt <= FLASH_DURATION_MS) color else null
    }

    fun trigger(context: Context, color: FlashColor) {
        flashColor = color
        flashedAt = System.currentTimeMillis()
        val updater = TileService.getUpdater(context.applicationContext)
        updater.requestUpdate(HeartRateTileService::class.java)
        scope.launch {
            delay(FLASH_DURATION_MS)
            updater.requestUpdate(HeartRateTileService::class.java)
        }
    }
}
