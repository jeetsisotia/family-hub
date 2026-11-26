package com.familyhub.app.safety

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Family safety decisions in plain Kotlin (no Android code), so they can be tested on any computer:
 * when a low-battery alert is sent, what each SMS says, how often live location is uploaded, and when
 * a shared location counts as stale or expired. See tools/safety-test.
 */

/** Someone to alert. They do not need the app; alerts reach them by SMS from this phone's own SIM. */
data class EmergencyContact(
    val id: String,
    val name: String,
    val phone: String,
    val relation: String = "",
    /** Gets the low-battery SMS. */
    val lowBattery: Boolean = true,
    /** Gets the SOS SMS. */
    val sos: Boolean = true,
    /** Gets the SMS when a safe-arrival timer runs out. */
    val arrival: Boolean = true
)

/** Opt-in medical card. Never sent to AI; added to SMS only if [includeInSms] is turned on. */
data class EmergencyCard(
    val enabled: Boolean = false,
    val bloodGroup: String = "",
    val allergies: String = "",
    val notes: String = "",
    val includeInSms: Boolean = false
) {
    val isEmpty: Boolean get() = bloodGroup.isBlank() && allergies.isBlank() && notes.isBlank()
}

/** A place this phone saw, with when it was seen and how accurate it was. */
data class SeenLocation(val lat: Double, val lng: Double, val accuracyM: Float, val at: Long)

enum class BatteryDecision {
    /** Send the alert now. */
    SEND,
    /** Low, but an alert went out recently (cooldown). */
    WAIT,
    /** Charging or back up: the next low-battery episode may alert again. */
    RESET,
    /** Nothing to do. */
    NONE
}

enum class Freshness { LIVE, STALE, EXPIRED }

enum class ArrivalStage {
    /** Timer running. */
    RUNNING,
    /** Time is up: ask "Did you arrive safely?" */
    ASK,
    /** No answer after the grace period: alert contacts. */
    ALERT
}

object SafetyLogic {
    const val DEFAULT_THRESHOLD = 15
    const val MIN_THRESHOLD = 5
    const val MAX_THRESHOLD = 50
    /** The level must rise this far above the threshold (or the phone charge) before a new episode can alert. */
    const val RESET_MARGIN = 5
    /** At most one low-battery alert in this time, even across several episodes. */
    const val BATTERY_COOLDOWN_MS = 6 * 60 * 60_000L
    /** Automatic SMS (low battery, SOS, missed arrival) are limited to this many per hour. */
    const val MAX_AUTO_SMS_PER_HOUR = 10

    /** Live location: at most one upload a minute, only after moving 25 m, plus a sign of life every 5 minutes. */
    const val UPLOAD_MIN_INTERVAL_MS = 60_000L
    const val UPLOAD_HEARTBEAT_MS = 5 * 60_000L
    const val UPLOAD_MIN_MOVE_M = 25.0
    /** A share with no update for this long is shown as stale. */
    const val STALE_AFTER_MS = 12 * 60_000L
    /** "Until I stop" shares stay readable for this long after the last update, so a dead phone's share ends. */
    const val OPEN_SHARE_WINDOW_MS = 30 * 60_000L
    /** Durations offered for live sharing; null means until stopped. */
    val SHARE_DURATIONS: List<Pair<String, Long?>> = listOf(
        "15 minutes" to 15 * 60_000L,
        "1 hour" to 60 * 60_000L,
        "8 hours" to 8 * 60 * 60_000L,
        "Until I stop" to null
    )

    const val SOS_COUNTDOWN_S = 5
    const val SOS_SHARE_MS = 60 * 60_000L
    const val ARRIVAL_GRACE_MS = 5 * 60_000L

    private val TIME = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)

    /**
     * A phone number usable for SMS: digits with an optional leading +, 6 to 15 digits. Spaces, dashes and
     * brackets are dropped. Returns null when it cannot be a phone number.
     */
    fun cleanPhone(raw: String): String? {
        val t = raw.trim()
        val plus = t.startsWith("+")
        val digits = t.filter { it.isDigit() }
        if (digits.length < 6 || digits.length > 15) return null
        if (t.any { !(it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' || it == '.') }) return null
        return (if (plus) "+" else "") + digits
    }

    /** Same phone number, ignoring a +91 / 0 prefix on Indian numbers. */
    fun samePhone(a: String, b: String): Boolean {
        fun core(p: String): String {
            val d = p.filter { it.isDigit() }
            return if (d.length > 10) d.takeLast(10) else d
        }
        val x = core(a)
        return x.isNotEmpty() && x == core(b)
    }

    fun clampThreshold(v: Int): Int = v.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

    /**
     * Whether to send a low-battery alert. One alert per low-battery episode: the episode ends when the phone
     * charges or the level rises [RESET_MARGIN] points above the threshold. A cooldown stops repeats when the
     * phone keeps going on and off the charger.
     */
    fun batteryDecision(
        level: Int,
        charging: Boolean,
        threshold: Int,
        alertedThisEpisode: Boolean,
        lastAlertAt: Long,
        now: Long
    ): BatteryDecision {
        if (level < 0 || level > 100) return BatteryDecision.NONE
        val t = clampThreshold(threshold)
        if (charging || level >= t + RESET_MARGIN) {
            return if (alertedThisEpisode) BatteryDecision.RESET else BatteryDecision.NONE
        }
        if (level > t) return BatteryDecision.NONE
        if (alertedThisEpisode) return BatteryDecision.NONE
        if (lastAlertAt > 0 && now - lastAlertAt in 0 until BATTERY_COOLDOWN_MS) return BatteryDecision.WAIT
        return BatteryDecision.SEND
    }

    /** True if another automatic SMS may go out now, given when earlier ones were sent. */
    fun canSendAutoSms(sentTimes: List<Long>, now: Long, count: Int = 1): Boolean =
        sentTimes.count { now - it in 0 until 60 * 60_000L } + count <= MAX_AUTO_SMS_PER_HOUR

    fun mapLink(lat: Double, lng: Double): String =
        "https://maps.google.com/?q=" + String.format(Locale.US, "%.6f,%.6f", lat, lng)

    fun timeText(millis: Long, zone: ZoneId): String = TIME.format(Instant.ofEpochMilli(millis).atZone(zone))

    private fun locationLine(loc: SeenLocation?, zone: ZoneId): String =
        if (loc == null) "Location not available."
        else "Last location (" + timeText(loc.at, zone) + ", about " + loc.accuracyM.toInt().coerceAtLeast(1) +
            " m): " + mapLink(loc.lat, loc.lng)

    private fun cardLine(card: EmergencyCard?): String? {
        if (card == null || !card.enabled || !card.includeInSms || card.isEmpty) return null
        val parts = listOfNotNull(
            card.bloodGroup.trim().takeIf { it.isNotEmpty() }?.let { "Blood group $it" },
            card.allergies.trim().takeIf { it.isNotEmpty() }?.let { "Allergies: ${it.take(80)}" },
            card.notes.trim().takeIf { it.isNotEmpty() }?.let { it.take(80) }
        )
        return "Medical: " + parts.joinToString("; ") + "."
    }

    private fun who(name: String) = name.trim().ifEmpty { "A Family Hub user" }

    fun lowBatteryText(name: String, level: Int, now: Long, loc: SeenLocation?, zone: ZoneId): String =
        who(name) + "'s phone battery is at " + level + "% and not charging (" + timeText(now, zone) +
            "). They may become unreachable. " + locationLine(loc, zone) + " - sent automatically by Family Hub"

    fun sosText(name: String, now: Long, loc: SeenLocation?, card: EmergencyCard?, zone: ZoneId): String =
        listOfNotNull(
            "SOS from " + who(name) + " (" + timeText(now, zone) + "): I need help. Please call me.",
            locationLine(loc, zone),
            cardLine(card),
            "- Family Hub"
        ).joinToString(" ")

    fun arrivalText(name: String, destination: String, deadline: Long, loc: SeenLocation?, zone: ZoneId): String =
        who(name) + " set a safe-arrival timer" +
            (if (destination.isNotBlank()) " for " + destination.trim().take(60) else "") +
            " and has not confirmed arriving by " + timeText(deadline, zone) + ". Please check on them. " +
            locationLine(loc, zone) + " - sent automatically by Family Hub"

    /** Distance in metres between two points (haversine). */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Whether a new position should be uploaded for live sharing, to save battery and Firestore writes. */
    fun shouldUpload(last: SeenLocation?, lastUploadAt: Long, next: SeenLocation, now: Long): Boolean {
        if (last == null || lastUploadAt <= 0L) return true
        val since = now - lastUploadAt
        if (since < 0) return true
        if (since >= UPLOAD_HEARTBEAT_MS) return true
        if (since < UPLOAD_MIN_INTERVAL_MS) return false
        return distanceM(last.lat, last.lng, next.lat, next.lng) >= UPLOAD_MIN_MOVE_M
    }

    /** When a share stops being readable: its end time, or a rolling window for "until I stop". */
    fun expiresAt(now: Long, endAt: Long?): Long =
        if (endAt == null) now + OPEN_SHARE_WINDOW_MS else endAt

    fun freshness(now: Long, updatedAt: Long, expiresAt: Long): Freshness = when {
        expiresAt <= now -> Freshness.EXPIRED
        now - updatedAt > STALE_AFTER_MS -> Freshness.STALE
        else -> Freshness.LIVE
    }

    fun arrivalStage(now: Long, deadline: Long): ArrivalStage = when {
        now < deadline -> ArrivalStage.RUNNING
        now < deadline + ARRIVAL_GRACE_MS -> ArrivalStage.ASK
        else -> ArrivalStage.ALERT
    }

    /** "3 min ago", "2 h ago". */
    fun agoText(now: Long, then: Long): String {
        val s = ((now - then) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }

    /** Moves one contact up or down; order sets who is listed first. */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val m = list.toMutableList()
        val x = m.removeAt(from)
        m.add(to, x)
        return m
    }
}
