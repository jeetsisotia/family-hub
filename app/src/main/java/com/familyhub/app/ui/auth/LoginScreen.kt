package com.familyhub.app.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.AccentCard
import com.familyhub.app.core.theme.AppLogo
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.core.theme.glass
import kotlinx.coroutines.delay

private data class Feature(val label: String, val icon: ImageVector)

private val features = listOf(
    Feature("Calendar", Icons.Rounded.CalendarMonth),
    Feature("Finance", Icons.Rounded.AccountBalanceWallet),
    Feature("Reminders", Icons.Rounded.NotificationsActive),
    Feature("Documents", Icons.Rounded.FolderShared),
    Feature("Health", Icons.Rounded.Favorite),
    Feature("Travel", Icons.Rounded.FlightTakeoff)
)

@Composable
fun LoginScreen(
    isLoading: Boolean,
    errorMessage: String?,
    onGoogleSignInClick: () -> Unit,
    onOpenLegalDoc: (String) -> Unit,
    enterDelayMillis: Int = 0
) {
    // Everything slides and fades in one after another when the screen opens.
    var started by remember { mutableStateOf(false) }
    val startDelay = remember { enterDelayMillis }
    LaunchedEffect(Unit) {
        delay(startDelay.toLong())
        started = true
    }
    val float = rememberInfiniteTransition(label = "float")
    val bob = float.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Reverse),
        label = "bob"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVisibility(
                visible = started,
                enter = fadeIn(tween(500)) + scaleIn(tween(650), initialScale = 0.6f)
            ) {
                AppLogo(
                    size = 124.dp,
                    modifier = Modifier.graphicsLayer { translationY = bob.value * density }
                )
            }
            Spacer(modifier = Modifier.height(30.dp))
            AnimatedVisibility(
                visible = started,
                enter = fadeIn(tween(500, delayMillis = 150)) +
                    slideInVertically(tween(500, delayMillis = 150)) { it / 2 }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Family Hub",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Your family, connected.\nPlans, money, documents and reminders in one place.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(modifier = Modifier.height(26.dp))
            AnimatedVisibility(
                visible = started,
                enter = fadeIn(tween(600, delayMillis = 320)) +
                    slideInVertically(tween(600, delayMillis = 320)) { it / 2 }
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    features.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { FeaturePill(it) }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = started,
            enter = fadeIn(tween(600, delayMillis = 480)) +
                slideInVertically(tween(600, delayMillis = 480)) { it }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (errorMessage != null) {
                    GlassCard(
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                AccentCard(
                    onClick = { if (!isLoading) onGoogleSignInClick() },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().height(60.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.5.dp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Signing in...", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        } else {
                            Icon(Icons.Rounded.AccountCircle, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Continue with Google", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Private to your family",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                    )
                }
                Row(horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { onOpenLegalDoc("privacy") }) {
                        Text("Privacy Policy", style = MaterialTheme.typography.labelMedium)
                    }
                    Text("•", modifier = Modifier.align(Alignment.CenterVertically))
                    TextButton(onClick = { onOpenLegalDoc("terms") }) {
                        Text("Terms of Service", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun FeaturePill(feature: Feature) {
    Row(
        modifier = Modifier
            .glass(RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = feature.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(feature.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
