package com.familyhub.app.ui.modules

import com.familyhub.app.data.model.FamilyMember
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
    // Medicines running out (5 or fewer doses left).
    for (m in items.filter { it.str("type") == "medicine" }) {
        val stock = m.num("stock") ?: continue
        val end = m.lng("endMillis")
        if (end != null && daysUntil(end) < 0) continue
        if (stock <= 5) {
            out.add(AlertItem("Refill " + m.str("title"), "${stock.toInt()} left", System.currentTimeMillis(), "medicines", stock <= 0))
        }
    }
    return out
}

/** Typed-in alerts plus worked-out ones, soonest first. */
fun allAlerts(data: Map<String, List<Rec>>): List<AlertItem> =
    (buildAlerts(data) + computedAlerts(data)).sortedBy { it.dateMillis }

/** Passport and driving licence expiry, birthday and anniversary from your own profile. */
fun profileAlerts(userDoc: Rec): List<AlertItem> {
    val out = ArrayList<AlertItem>()
    for ((key, label) in listOf("idPassportExpiry" to "Passport", "idDlExpiry" to "Driving licence")) {
        val t = userDoc.lng(key) ?: continue
        val days = daysUntil(t)
        if (days in -30..90) out.add(AlertItem("$label expires", "Renew $label", t, "profile", days < 0))
    }
    return out
}

/** Birthdays and anniversaries of family members who chose to share them (and your own). */
fun memberAlerts(members: List<FamilyMember>, myUid: String): List<AlertItem> {
    val out = ArrayList<AlertItem>()
    for (m in members.filter { it.status == "ACTIVE" }) {
        val me = m.uid == myUid
        m.dobMillis?.let { dob ->
            val next = nextOccurrence(dob, "YEARLY")
            if (daysUntil(next) in 0..7) {
                out.add(AlertItem(if (me) "Your birthday" else "${m.name.substringBefore(' ')}'s birthday", "Birthday", next, "family", false))
            }
        }
        m.anniversaryMillis?.let { a ->
            val next = nextOccurrence(a, "YEARLY")
            if (daysUntil(next) in 0..7) {
                out.add(AlertItem(if (me) "Your anniversary" else "${m.name.substringBefore(' ')}'s anniversary", "Anniversary", next, "family", false))
            }
        }
    }
    return out
}
