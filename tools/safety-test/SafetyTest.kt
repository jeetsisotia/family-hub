import com.familyhub.app.safety.*
import java.time.ZoneId

// Checks for SafetyLogic (family safety decisions). Made-up names, numbers and places only.
// Nothing here sends an SMS or touches a phone.

private var failures = 0
private var checks = 0

private fun check(name: String, ok: Boolean, detail: Any? = null) {
    checks++
    if (!ok) {
        failures++
        println("FAIL: $name" + (if (detail != null) " -> $detail" else ""))
    }
}

fun main() {
    val zone = ZoneId.of("Asia/Kolkata")
    val now = 1_795_700_000_000L // 26 Nov 2026, 7:03 PM IST
    val h = 60 * 60_000L

    // ---- phone numbers ----
    check("phone: spaces and dashes removed", SafetyLogic.cleanPhone(" 98765-43210 ") == "9876543210")
    check("phone: + kept", SafetyLogic.cleanPhone("+91 98765 43210") == "+919876543210")
    check("phone: too short", SafetyLogic.cleanPhone("12345") == null)
    check("phone: letters refused", SafetyLogic.cleanPhone("98765abc10") == null)
    check("phone: blank refused", SafetyLogic.cleanPhone("") == null)
    check("phone: same with +91", SafetyLogic.samePhone("+91 98765 43210", "9876543210"))
    check("phone: different", !SafetyLogic.samePhone("9876543210", "9876543211"))

    // ---- low battery: one alert per episode ----
    val t = 15
    check("battery: 40% does nothing", SafetyLogic.batteryDecision(40, false, t, false, 0, now) == BatteryDecision.NONE)
    check("battery: 15% not charging sends", SafetyLogic.batteryDecision(15, false, t, false, 0, now) == BatteryDecision.SEND)
    check("battery: 9% not charging sends", SafetyLogic.batteryDecision(9, false, t, false, 0, now) == BatteryDecision.SEND)
    check("battery: 16% does nothing", SafetyLogic.batteryDecision(16, false, t, false, 0, now) == BatteryDecision.NONE)
    check("battery: low but charging never sends", SafetyLogic.batteryDecision(5, true, t, false, 0, now) == BatteryDecision.NONE)
    check("battery: already alerted this episode, no repeat",
        SafetyLogic.batteryDecision(10, false, t, true, now - 10 * 60_000L, now) == BatteryDecision.NONE)
    check("battery: charging ends the episode", SafetyLogic.batteryDecision(12, true, t, true, now - h, now) == BatteryDecision.RESET)
    check("battery: back to 20% ends the episode", SafetyLogic.batteryDecision(20, false, t, true, now - h, now) == BatteryDecision.RESET)
    check("battery: 18% (inside the margin) keeps the episode",
        SafetyLogic.batteryDecision(18, false, t, true, now - h, now) == BatteryDecision.NONE)
    check("battery: new episode inside the 6 h cooldown waits",
        SafetyLogic.batteryDecision(14, false, t, false, now - 2 * h, now) == BatteryDecision.WAIT)
    check("battery: new episode after the cooldown sends",
        SafetyLogic.batteryDecision(14, false, t, false, now - 7 * h, now) == BatteryDecision.SEND)
    check("battery: bad level ignored", SafetyLogic.batteryDecision(-1, false, t, false, 0, now) == BatteryDecision.NONE)
    check("battery: threshold kept within 5..50", SafetyLogic.clampThreshold(2) == 5 && SafetyLogic.clampThreshold(90) == 50)
    check("battery: threshold 2 acts as 5", SafetyLogic.batteryDecision(6, false, 2, false, 0, now) == BatteryDecision.NONE &&
        SafetyLogic.batteryDecision(5, false, 2, false, 0, now) == BatteryDecision.SEND)

    // Simulated discharge: one alert from 30% to 1%, then charge to 80% and drain again 8 hours later.
    run {
        var alerted = false
        var last = 0L
        var sent = 0
        var clock = now
        fun step(level: Int, charging: Boolean) {
            when (SafetyLogic.batteryDecision(level, charging, t, alerted, last, clock)) {
                BatteryDecision.SEND -> { sent++; alerted = true; last = clock }
                BatteryDecision.RESET -> alerted = false
                else -> {}
            }
            clock += 15 * 60_000L
        }
        for (l in 30 downTo 1) step(l, false)
        check("battery sim: one alert for one discharge", sent == 1, sent)
        for (l in 1..80 step 5) step(l, true)
        clock += 8 * h
        for (l in 80 downTo 1) step(l, false)
        check("battery sim: second discharge after charging alerts once more", sent == 2, sent)
        // Plugged in and out around the threshold: cooldown blocks repeats.
        val before = sent
        // 16 h after the last alert: the first unplug alerts once, then the cooldown blocks the rest.
        repeat(6) { step(14, true); step(14, false) }
        check("battery sim: charger on/off around the threshold alerts at most once", sent - before == 1, sent - before)
    }

    // ---- SMS limit ----
    check("sms limit: 9 in the last hour allows 1 more",
        SafetyLogic.canSendAutoSms(List(9) { now - it * 60_000L }, now))
    check("sms limit: 10 in the last hour blocks",
        !SafetyLogic.canSendAutoSms(List(10) { now - it * 60_000L }, now))
    check("sms limit: old ones do not count",
        SafetyLogic.canSendAutoSms(List(20) { now - 2 * h - it * 60_000L }, now))
    check("sms limit: a batch of 3 counts as 3",
        !SafetyLogic.canSendAutoSms(List(8) { now - it * 60_000L }, now, 3))

    // ---- message text ----
    val loc = SeenLocation(12.9715987, 77.5945627, 18.4f, now - 4 * 60_000L)
    val low = SafetyLogic.lowBatteryText("Asha", 14, now, loc, zone)
    check("low battery text: name and level", low.contains("Asha") && low.contains("14%"), low)
    check("low battery text: map link with 6 decimals", low.contains("https://maps.google.com/?q=12.971599,77.594563"), low)
    check("low battery text: location time and accuracy", low.contains("about 18 m") && low.contains("26 Nov, 6:59 PM"), low)
    check("low battery text: says automatic", low.contains("automatically"), low)
    check("low battery text: no location", SafetyLogic.lowBatteryText("Asha", 14, now, null, zone).contains("Location not available"))
    check("low battery text: fits 2 SMS", low.length <= 306, low.length)
    check("map link: decimal point even in other locales", run {
        val old = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.GERMANY)
        val s = SafetyLogic.mapLink(1.5, 2.25)
        java.util.Locale.setDefault(old)
        s == "https://maps.google.com/?q=1.500000,2.250000"
    })

    val card = EmergencyCard(enabled = true, bloodGroup = "B+", allergies = "Penicillin", notes = "", includeInSms = false)
    val sos = SafetyLogic.sosText("Asha", now, loc, card, zone)
    check("sos text: help and location", sos.contains("SOS from Asha") && sos.contains("maps.google.com"), sos)
    check("sos text: medical card left out unless turned on", !sos.contains("B+") && !sos.contains("Penicillin"), sos)
    val sos2 = SafetyLogic.sosText("Asha", now, loc, card.copy(includeInSms = true), zone)
    check("sos text: medical card included when turned on", sos2.contains("Blood group B+") && sos2.contains("Penicillin"), sos2)
    val sos3 = SafetyLogic.sosText("Asha", now, loc, card.copy(enabled = false, includeInSms = true), zone)
    check("sos text: card off means not included", !sos3.contains("B+"), sos3)
    check("sos text: blank name", SafetyLogic.sosText(" ", now, null, null, zone).contains("A Family Hub user"))

    val arr = SafetyLogic.arrivalText("Asha", "Home", now, loc, zone)
    check("arrival text", arr.contains("for Home") && arr.contains("has not confirmed") && arr.contains("maps.google.com"), arr)

    // ---- live location ----
    val a = SeenLocation(12.9716, 77.5946, 10f, now)
    val near = SeenLocation(12.97161, 77.59461, 10f, now) // about 1.5 m
    val far = SeenLocation(12.9720, 77.5946, 10f, now)    // about 44 m
    check("distance: ~44 m", SafetyLogic.distanceM(a.lat, a.lng, far.lat, far.lng) in 40.0..48.0,
        SafetyLogic.distanceM(a.lat, a.lng, far.lat, far.lng))
    check("distance: Bengaluru to Mysuru ~ 127 km",
        SafetyLogic.distanceM(12.9716, 77.5946, 12.2958, 76.6394) in 120_000.0..135_000.0)
    check("upload: first fix always", SafetyLogic.shouldUpload(null, 0, a, now))
    check("upload: not within a minute even if moved", !SafetyLogic.shouldUpload(a, now - 30_000L, far, now))
    check("upload: after a minute only if moved 25 m", SafetyLogic.shouldUpload(a, now - 70_000L, far, now) &&
        !SafetyLogic.shouldUpload(a, now - 70_000L, near, now))
    check("upload: heartbeat every 5 minutes when still", SafetyLogic.shouldUpload(a, now - 5 * 60_000L, near, now))
    check("upload: clock moved back", SafetyLogic.shouldUpload(a, now + 60_000L, near, now))
    // 8 hours standing still: about 96 heartbeat writes, far below the free 20,000 a day.
    run {
        var lastUp = 0L
        var last: SeenLocation? = null
        var writes = 0
        var clock = now
        while (clock < now + 8 * h) {
            val p = SeenLocation(12.9716, 77.5946, 10f, clock)
            if (SafetyLogic.shouldUpload(last, lastUp, p, clock)) { writes++; lastUp = clock; last = p }
            clock += 30_000L
        }
        check("upload: 8 h still ~ 96 writes", writes in 90..100, writes)
    }
    // 1 hour driving (a fix every 30 s, moving): at most one write a minute.
    run {
        var lastUp = 0L
        var last: SeenLocation? = null
        var writes = 0
        var clock = now
        var lat = 12.9716
        while (clock < now + h) {
            lat += 0.002
            val p = SeenLocation(lat, 77.5946, 10f, clock)
            if (SafetyLogic.shouldUpload(last, lastUp, p, clock)) { writes++; lastUp = clock; last = p }
            clock += 30_000L
        }
        check("upload: 1 h moving <= 61 writes", writes <= 61, writes)
    }

    check("expiry: fixed end", SafetyLogic.expiresAt(now, now + h) == now + h)
    check("expiry: until stopped is a 30-minute rolling window", SafetyLogic.expiresAt(now, null) == now + 30 * 60_000L)
    check("fresh: just updated", SafetyLogic.freshness(now, now - 60_000L, now + h) == Freshness.LIVE)
    check("fresh: 13 minutes old is stale", SafetyLogic.freshness(now, now - 13 * 60_000L, now + h) == Freshness.STALE)
    check("fresh: past its end is expired", SafetyLogic.freshness(now, now - 60_000L, now - 1) == Freshness.EXPIRED)
    check("durations: 15 min, 1 h, 8 h, until stopped",
        SafetyLogic.SHARE_DURATIONS.map { it.second } == listOf(15 * 60_000L, h, 8 * h, null))

    // ---- safe arrival ----
    check("arrival: running", SafetyLogic.arrivalStage(now, now + 60_000L) == ArrivalStage.RUNNING)
    check("arrival: ask at the deadline", SafetyLogic.arrivalStage(now, now) == ArrivalStage.ASK)
    check("arrival: still asking 4 min later", SafetyLogic.arrivalStage(now + 4 * 60_000L, now) == ArrivalStage.ASK)
    check("arrival: alert after 5 min grace", SafetyLogic.arrivalStage(now + 5 * 60_000L, now) == ArrivalStage.ALERT)

    // ---- misc ----
    check("ago: minutes", SafetyLogic.agoText(now, now - 3 * 60_000L) == "3 min ago")
    check("ago: just now", SafetyLogic.agoText(now, now - 5_000L) == "just now")
    check("move: down", SafetyLogic.move(listOf("a", "b", "c"), 0, 1) == listOf("b", "a", "c"))
    check("move: out of range unchanged", SafetyLogic.move(listOf("a", "b"), 1, 2) == listOf("a", "b"))

    println(if (failures == 0) "ALL PASSED ($checks checks)" else "$failures of $checks FAILED")
    if (failures > 0) kotlin.system.exitProcess(1)
}
