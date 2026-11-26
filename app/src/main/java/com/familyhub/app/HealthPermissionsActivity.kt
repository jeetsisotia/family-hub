package com.familyhub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.FamilyHubTheme

/** Shown by Health Connect when someone asks why Family Hub wants to read steps. */
class HealthPermissionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FamilyHubTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("How Family Hub uses Health Connect", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Family Hub reads your daily step totals from Health Connect so the steps counted by Google Fit, " +
                                "your watch or other apps show on the Health screen and in the notification bar.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "Step totals stay on your phone and in your own private activity log. If you turn on \"Share steps with family\", " +
                                "only today's and this week's totals are shown to your family. Nothing is sent to the AI, sold or used for ads. " +
                                "Family Hub does not write anything to Health Connect.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "You can stop this at any time in Health Connect settings or in Family Hub's Health screen.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = { finish() }) { Text("Close") }
                    }
                }
            }
        }
    }
}
