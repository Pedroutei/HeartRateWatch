package com.pedro.heartratewatch.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Invisible tap target for the Tile's start/stop chip. A Tile can only launch an Activity or
 * refresh itself -- there's no "run a service, no UI" tile action -- so this activity does the
 * actual work (start/stop ExerciseSessionService using whatever ActivityModeStore currently has
 * selected) and finishes itself immediately, in the same frame, so it's never actually seen. The
 * translucent/no-animation theme in the manifest is what keeps this from flashing on screen.
 */
class TileActionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (HeartRateRepository.state.value.isActive) {
            stopService(Intent(this, ExerciseSessionService::class.java))
            finish()
        } else {
            lifecycleScope.launch {
                val type = ActivityModeStore(applicationContext).current()
                val intent = Intent(this@TileActionActivity, ExerciseSessionService::class.java)
                    .putExtra(ExerciseSessionService.EXTRA_ACTIVITY_TYPE, type.name)
                ContextCompat.startForegroundService(this@TileActionActivity, intent)
                finish()
            }
        }
    }
}
