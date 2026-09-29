package com.pedro.heartratewatch.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.tiles.TileService
import com.pedro.heartratewatch.wear.tile.HeartRateTileService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Invisible tap target for the Tile's start/stop chip. A Tile can only launch an Activity or
 * refresh itself -- there's no "run a service, no UI" tile action -- so this activity does the
 * actual work (start/stop ExerciseSessionService using whatever ActivityModeStore currently has
 * selected) and finishes itself immediately, in the same frame, so it's never actually seen. The
 * translucent/no-animation theme in the manifest is what keeps this from flashing on screen.
 *
 * If sensor permissions aren't granted (e.g. right after a fresh install), starting the session
 * would just throw a SecurityException with no way for the Tile to show that -- so this opens
 * MainActivity's own "Grant access" screen instead of trying and silently failing.
 */
class TileActionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (HeartRateRepository.state.value.isActive) {
            stopService(Intent(this, ExerciseSessionService::class.java))
            finish()
        } else if (!hasRequiredWearPermissions(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } else {
            lifecycleScope.launch {
                val type = ActivityModeStore(applicationContext).current()
                val intent = Intent(this@TileActionActivity, ExerciseSessionService::class.java)
                    .putExtra(ExerciseSessionService.EXTRA_ACTIVITY_TYPE, type.name)
                ContextCompat.startForegroundService(this@TileActionActivity, intent)
                // The tile's own start/stop label needs to flip to "Stop" right away -- it won't
                // otherwise refresh until ExerciseSessionService's own first update.
                TileService.getUpdater(applicationContext).requestUpdate(HeartRateTileService::class.java)
                val settings = SettingsStore(applicationContext).settingsFlow.first()
                launchStravaIfEnabled(this@TileActionActivity, settings)
                finish()
            }
        }
    }
}
