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
                // active, rather than finishing immediately after just requesting the service
                // start. Suspected cause of a reported bug: GPS-based distance never tracked for
                // a tile-started run (heart rate did), while starting the exact same run from
                // MainActivity's own button worked fine -- the difference being MainActivity
                // stays visibly foregrounded for the whole startExerciseAsync() call, while this
                // invisible relay activity was finishing (and vanishing) before that async Health
                // Services call necessarily completed. Keeping this activity alive until the
                // session is confirmed active closes that gap; if it doesn't fix the bug, this
                // wait is harmless (translucent theme, no visible flash either way).
                withTimeoutOrNull(5_000) {
                    HeartRateRepository.state.first { it.isActive }
                }
                // The tile's own start/stop label and color need to flip to "Stop"/red right away --
                // it won't otherwise refresh until ExerciseSessionService's own first update.
                TileService.getUpdater(applicationContext).requestUpdate(HeartRateTileService::class.java)
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
