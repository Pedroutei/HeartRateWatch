package com.pedro.heartratewatch.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Invisible tap target for the Tile's start/stop chip. A Tile can only launch an Activity or
 * refresh itself -- there's no "run a service, no UI" tile action -- so this activity does the
 * actual work (start/stop ExerciseSessionService using whatever ActivityModeStore currently has
 * selected) and finishes itself immediately, in the same frame, so it's never actually seen. The
 * translucent/no-animation theme in the manifest is what keeps this from flashing on screen.
 *
 * Which action to take (start vs. stop) is decided once, by HeartRateTileService, at the moment
 * it renders the button label -- and passed in via EXTRA_ACTION -- rather than re-derived here
 * from HeartRateRepository.state.value.isActive. That in-memory state doesn't survive the app's
 * process dying (e.g. a reinstall, or Android reclaiming it), so a stale tile could still be
 * showing "Stop" from before that happened; re-checking isActive here would then disagree with
 * what the tile displayed and silently fall through to the start branch (and from there, to the
 * permission check below) even though the user tapped Stop. Trusting the tile's own label instead
 * means Stop always just stops -- it never touches the permission/MainActivity path.
 *
 * If sensor permissions aren't granted (e.g. right after a fresh install), starting the session
 * would just throw a SecurityException with no way for the Tile to show that -- so this opens
 * MainActivity's own "Grant access" screen instead of trying and silently failing.
 */
class TileActionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent.getStringExtra(EXTRA_ACTION) == ACTION_STOP) {
            stopService(Intent(this, ExerciseSessionService::class.java))
            // Also flips the tile's own label back to "Start" right away, same as the start path.
            TileFlashState.trigger(this, TileFlashState.FlashColor.RED)
            lifecycleScope.launch {
                // Brings Strava back to the foreground so you can hit its own Finish/stop -- same
                // toggle as starting, since the point of turning it on is having Strava tracking
                // alongside PulseGuard for the whole run, not just at the start of it.
                val settings = SettingsStore(applicationContext).settingsFlow.first()
                launchStravaIfEnabled(this@TileActionActivity, settings)
                finish()
            }
        } else if (!hasRequiredWearPermissions(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } else {
            // The tile's own start/stop label needs to flip to "Stop" right away -- it won't
            // otherwise refresh until ExerciseSessionService's own first update.
            TileFlashState.trigger(this, TileFlashState.FlashColor.GREEN)
            lifecycleScope.launch {
                val type = ActivityModeStore(applicationContext).current()
                val intent = Intent(this@TileActionActivity, ExerciseSessionService::class.java)
                    .putExtra(ExerciseSessionService.EXTRA_ACTIVITY_TYPE, type.name)
                ContextCompat.startForegroundService(this@TileActionActivity, intent)
                val settings = SettingsStore(applicationContext).settingsFlow.first()
                launchStravaIfEnabled(this@TileActionActivity, settings)
                finish()
            }
        }
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
    }
}
