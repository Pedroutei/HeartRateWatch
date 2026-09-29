package com.pedro.heartratewatch.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.wear.theme.PulseGuardTheme
import kotlinx.coroutines.launch

/**
 * "Change mode" screen: a plain list of ActivityType.entries, so a future third/fourth activity
 * type (outdoor biking, etc.) shows up here automatically -- nothing here is hardcoded to just
 * run/bike. Tapping an entry saves it as the selected mode (ActivityModeStore) and closes.
 */
class ExercisePickerActivity : ComponentActivity() {

    private val activityModeStore by lazy { ActivityModeStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Tapping "Change mode" on the tile launches this activity directly, so this is the
        // earliest point to give the tile's button a quick blue flash before this screen covers it.
        TileFlashState.trigger(this, TileFlashState.FlashColor.BLUE)
        setContent {
            PulseGuardTheme {
                ExercisePickerScreen(activityModeStore, onDone = {
                    // The tile's readouts (pace/distance vs. just bpm) reflect whatever mode is
                    // currently selected, not just an active session's mode -- without this it'd
                    // keep showing the old mode's layout until a session of the new type starts.
                    // Waits until the blue blink triggered above would be done, same reasoning as
                    // ExerciseSessionService.onDestroy() -- an immediate refresh here would win the
                    // platform's coalescing of rapid requestUpdate calls and the blink would never
                    // actually render.
                    TileFlashState.refreshAfterBlink(applicationContext)
                    finish()
                })
            }
        }
    }
}

@Composable
private fun ExercisePickerScreen(store: ActivityModeStore, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Choose exercise")
        ActivityType.entries.forEach { type ->
            Button(onClick = {
                scope.launch {
                    store.select(type)
                    onDone()
                }
            }) {
                Text(type.displayName())
            }
        }
    }
}
