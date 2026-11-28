package com.familyhub.app.ui.family

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.money.Challenges
import com.familyhub.app.money.Ledger
import com.familyhub.app.ui.AppEnv
import kotlinx.coroutines.launch
import java.util.Locale

private fun rupees(v: Double) = "₹" + String.format(Locale("en", "IN"), "%,.0f", kotlin.math.abs(v))

/** Progress of each savings challenge: on track or behind, this step's amount, and one tap to record it. */
@Composable
fun ChallengesOverview(env: AppEnv) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = remember(env.data) { env.data["items"].orEmpty().filter { it["type"] == "challenge" && it["isDeleted"] != true } }
    if (list.isEmpty()) return
    val now = System.currentTimeMillis()
    // Days with spending, from Finance (for "No-spend days").
    val spendDays = remember(env.data) {
        env.data["expenses"].orEmpty().filter { Ledger.isSpend(it) && it["isDeleted"] != true }
            .mapNotNull { (it["dateMillis"] as? Number)?.toLong()?.let(Challenges::startOfDay) }.toSet()
    }
    var busy by remember { mutableStateOf("") }
    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Challenge progress", fontWeight = FontWeight.SemiBold)
            list.forEach { c ->
                val title = (c["title"] as? String).orEmpty().ifBlank { Challenges.kind(c) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (Challenges.kind(c) == Challenges.NO_SPEND) {
                        val ns = Challenges.noSpend(c, spendDays, now)
                        LinearProgressIndicator(progress = { (ns.days.toFloat() / ns.goal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("${ns.days} of ${ns.goal} days with no spending · current run ${ns.streak} ${if (ns.streak == 1) "day" else "days"}", style = MaterialTheme.typography.bodySmall)
                        Text("Worked out from Finance: any spending on a day breaks it.", style = MaterialTheme.typography.labelSmall)
                    } else {
                        val target = Challenges.target(c)
                        val saved = Challenges.saved(c)
                        val diff = Challenges.versusPlan(c, now)
                        val step = Challenges.step(c, now)
                        val unit = if (Challenges.kind(c) == Challenges.DAILY) "Day" else "Week"
                        LinearProgressIndicator(progress = { if (target > 0) (saved / target).toFloat().coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                        Text("${rupees(saved)} of ${rupees(target)} · $unit $step of ${Challenges.length(c)}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            when {
                                step == 0 -> "Starts soon."
                                diff >= 0 -> "On track" + if (diff > 0) " (${rupees(diff)} ahead)" else ""
                                else -> "${rupees(diff)} behind plan"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (diff >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        val amount = Challenges.stepAmount(c, step.coerceAtLeast(1))
                        if (!Challenges.finished(c, now) && step > 0 && amount > 0) {
                            FilledTonalButton(
                                enabled = busy.isEmpty(),
                                onClick = {
                                    val id = (c["id"] as? String).orEmpty(); busy = id
                                    val fields = Challenges.saveFields(c, System.currentTimeMillis(), env.profile.name, amount, "$unit $step")
                                    scope.launch {
                                        try {
                                            env.repo.setFieldsScoped(env.familyId, env.uid, "items", c, fields)
                                            Toast.makeText(ctx, if (fields["done"] == true) "Challenge complete! 🎉" else "Saved ${rupees(amount)} for $unit $step.", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) { Toast.makeText(ctx, "Not saved: ${e.message?.take(60)}", Toast.LENGTH_LONG).show() }
                                        busy = ""
                                    }
                                }
                            ) { Text("I saved ${rupees(amount)} for $unit $step") }
                        }
                    }
                }
            }
        }
    }
}
