package com.familyhub.app.ui.money

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.GlassCard
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.common.ScreenTopBar
import com.familyhub.app.ui.common.SectionTitle
import com.familyhub.app.ui.common.formatDate
import com.familyhub.app.ui.modules.daysUntil
import com.familyhub.app.ui.modules.num
import com.familyhub.app.ui.modules.str

private val cardGradients = listOf(
    listOf(Color(0xFF1C1C1E), Color(0xFF48484A)),
    listOf(Color(0xFF0A3D91), Color(0xFF2F7BFF)),
    listOf(Color(0xFF5B1F8C), Color(0xFFB04FE0)),
    listOf(Color(0xFF0B6E4F), Color(0xFF2FBF71)),
    listOf(Color(0xFF8C2F1F), Color(0xFFE5603B))
)

/**
 * Each credit card's billing cycle: spends since the last statement, the bill already generated,
 * when it is due and how much of the limit is used. Spends are Finance entries paid with that card.
 */
@Composable
fun CardsScreen(env: AppEnv) {
    val now = remember { System.currentTimeMillis() }
    val expenses = env.data["expenses"].orEmpty()
    val cards = env.data["items"].orEmpty().filter { it.str("type") == "card" }.sortedBy { it.str("title") }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Card Bills", onBack = env.onBack) {
            TextButton(onClick = { env.navigate("card_list") }) { Text("Manage cards") }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (cards.isEmpty()) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Rounded.CreditCard, contentDescription = null, modifier = Modifier.size(36.dp))
                            Text("Track every card's billing cycle", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Add a card with its statement date and due date. When you add an expense, choose \"Credit card\" " +
                                    "and pick the card, or import bank SMS and the card is matched by its last 4 digits."
                            )
                            Button(onClick = { env.navigate("card_list") }) {
                                Icon(Icons.Rounded.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add a card")
                            }
                        }
                    }
                }
            }
            val totalDue: Double = cards.mapNotNull { c ->
                val cy = CardCycle.of(c, now)
                if (cy != null && daysUntil(cy.billDue, now) >= 0) {
                    cardSpends(expenses, c, cy.billedStart, cy.billedEnd).sumOf { amountOf(it) }
                } else {
                    null
                }
            }.sum()
            if (cards.isNotEmpty()) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Bills still to pay", style = MaterialTheme.typography.labelLarge)
                                Text(inr(totalDue), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            }
                            Text("${cards.size} card(s)", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            items(cards, key = { (it["_private"] == true).toString() + it.str("id") }) { c ->
                val i = cards.indexOf(c)
                val cy = CardCycle.of(c, now)
                val limit = c.num("limitInr") ?: 0.0
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Brush.linearGradient(cardGradients[i % cardGradients.size]))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.str("title"), color = Color.White, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(
                            if (c.str("last4").isNotBlank()) "•••• " + c.str("last4") else c.str("bank"),
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                    if (cy == null) {
                        Text("Add the statement and due dates to see this card's cycle.", color = Color.White)
                    } else {
                        val current = cardSpends(expenses, c, cy.currentStart, cy.currentEnd).sumOf { amountOf(it) }
                        val billed = cardSpends(expenses, c, cy.billedStart, cy.billedEnd).sumOf { amountOf(it) }
                        val dueIn = daysUntil(cy.billDue, now)
                        Text("Spent this cycle (${formatDate(cy.currentStart)} – ${formatDate(cy.currentEnd)})",
                            color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                        Text(inr(current), color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        if (limit > 0) {
                            val used = ((current + if (dueIn >= 0) billed else 0.0) / limit).toFloat()
                            LinearProgressIndicator(
                                progress = { used.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = if (used > 0.8f) Color(0xFFFF9F0A) else Color.White,
                                trackColor = Color.White.copy(alpha = 0.25f)
                            )
                            Text("%.0f%% of %s limit used".format(used * 100, inr(limit)),
                                color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 4.dp))
                        Row {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Last bill", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                                Text(inr(billed), color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Due", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                                Text(
                                    when {
                                        dueIn > 1 -> "${formatDate(cy.billDue)} (in $dueIn days)"
                                        dueIn == 1 -> "Tomorrow"
                                        dueIn == 0 -> "Today"
                                        else -> "Next: ${formatDate(cy.nextDue)}"
                                    },
                                    color = if (dueIn in 0..3) Color(0xFFFFD60A) else Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = {
                                InsightsLink.pendingCard = c.str("title")
                                env.navigate("insights")
                            }) { Text("See spends") }
                        }
                    }
                }
            }
            if (cards.isNotEmpty()) {
                item { SectionTitle("How it works") }
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "A cycle runs from the day after one statement date to the next. Spends count toward the card " +
                                "when the expense's \"Card\" is set to it. The bill is due on the due date after the statement.",
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
