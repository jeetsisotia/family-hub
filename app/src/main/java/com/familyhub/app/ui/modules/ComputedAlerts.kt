package com.familyhub.app.ui.modules

import com.familyhub.app.data.remote.Rec
import com.familyhub.app.ui.health.PeriodMath
import com.familyhub.app.ui.money.CardCycle

/**
 * Alerts that are worked out rather than typed in: the next period and credit card bills.
 * Period records are private, so only their owner ever gets this alert.
 */
fun computedAlerts(data: Map<String, List<Rec>>): List<AlertItem> {
    val items = data["items"].orEmpty()
    val out = ArrayList<AlertItem>()

    val periods = items.filter { it.str("type") == "period" }
    val p = PeriodMath.predict(periods)
    if (p != null) {
        val days = daysUntil(p.nextStart)
        if (days in -10..3) {
            out.add(AlertItem(if (days < 0) "Period is late" else "Period expected", "Period", p.nextStart, "periods", days < 0))
        }
    }

    for (card in items.filter { it.str("type") == "card" }) {
        val c = CardCycle.of(card) ?: continue
        val days = daysUntil(c.billDue)
        val due = if (days < -3) c.nextDue else c.billDue
        val left = daysUntil(due)
        if (left in -3..7) {
            out.add(AlertItem(card.str("title") + " bill", "Card bill due", due, "cards", left < 0))
        }
    }
    return out
}

/** Typed-in alerts plus worked-out ones, soonest first. */
fun allAlerts(data: Map<String, List<Rec>>): List<AlertItem> =
    (buildAlerts(data) + computedAlerts(data)).sortedBy { it.dateMillis }
