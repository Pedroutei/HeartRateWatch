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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tap target for the Tile's start/stop chip. A Tile can only launch an Activity or refresh
 * itself -- there's no "run a service, no UI" tile action -- so this activity does the actual
 * work of starting/stopping ExerciseSessionService.
 *
 * Stop stays fully invisible: it finishes itself immediately, in the same frame, via the
 * translucent/no-animation theme in the manifest, since stopping doesn't need anything to stay
 * open afterward. Start, though, now opens MainActivity for real (visible, stays open) instead of
 * also finishing invisibly -- confirmed via Logcat that Health Services doesn't just check
 * ACCESS_FINE_LOCATION once at exercise start, it re-verifies it periodically for as long as the
 * session runs, and that re-check needs an actual visible foreground Activity, not just
 * ExerciseSessionService's own foreground *service* status, to keep passing. Finishing this
 * activity invisibly (the original design) left no qualifying Activity a few seconds into a
 * tile-started run, and Health Services auto-ended the exercise itself
 * (AUTO_ENDING_PERMISSION_LOST) shortly after -- explaining why a tile-started run tracked heart
 * rate (no location dependency) but never distance, and MainActivity's own Start button (which
 * you keep open on screen for the run) never had the problem.
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
            lifecycleScope.launch {
                val type = ActivityModeStore(applicationContext).current()
                val intent = Intent(this@TileActionActivity, ExerciseSessionService::class.java)
                    .putExtra(ExerciseSessionService.EXTRA_ACTIVITY_TYPE, type.name)
                ContextCompat.startForegroundService(this@TileActionActivity, intent)
                // Waits (bounded) for ExerciseSessionService to actually report the session
                // active before opening MainActivity, so it opens already showing "Stop" instead
                // of flashing "Start" for a moment first.
                withTimeoutOrNull(5_000) {
                    HeartRateRepository.state.first { it.isActive }
                }
                TileService.getUpdater(applicationContext).requestUpdate(HeartRateTileService::class.java)
                val settings = SettingsStore(applicationContext).settingsFlow.first()
                launchStravaIfEnabled(this@TileActionActivity, settings)
                startActivity(Intent(this@TileActionActivity, MainActivity::class.java))
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
