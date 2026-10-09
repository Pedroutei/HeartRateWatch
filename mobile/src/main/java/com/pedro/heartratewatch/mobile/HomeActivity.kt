package com.pedro.heartratewatch.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pedro.heartratewatch.mobile.theme.PipBoyTheme

/**
 * What the app opens to: pick Run (the existing run-tracking screen, MainActivity) or Workout
 * (the gym log, WorkoutActivity).
 */
class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PipBoyTheme { HomeScreen() }
        }
    }
}

@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Pulse Guard", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HomeTile("Run", Modifier.weight(1f)) {
                    context.startActivity(Intent(context, MainActivity::class.java))
                }
                HomeTile("Workout", Modifier.weight(1f)) {
                    context.startActivity(Intent(context, WorkoutActivity::class.java))
                }
            }
        }
    }
}

@Composable
private fun HomeTile(label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedCard(modifier = modifier.aspectRatio(1f).clickable(onClick = onClick)) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleLarge)
        }
    }
}
