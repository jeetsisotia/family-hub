package com.familyhub.app.calling

/**
 * Rules of a family group call (mesh: each pair of people connects directly, at most [MAX_PEOPLE]).
 * Kept free of Android so it can be tested on its own.
 */
object GroupCallMath {
    /** A phone sends its picture and voice to every other person, so more than 4 overloads mobile networks. */
    const val MAX_PEOPLE = 4
    const val RING_MS = 60_000L

    /** In each pair exactly one person starts the connection: the one whose id sorts first. */
    fun isOfferer(me: String, peer: String): Boolean = me < peer

    fun signalId(from: String, to: String, kind: String): String = "${from}__${to}__$kind"

    /** People in the call I should start a connection to now (joined, not me, not connected yet, I am the offerer). */
    fun toOffer(me: String, joined: Collection<String>, connected: Collection<String>): List<String> =
        joined.filter { it != me && it !in connected && isOfferer(me, it) }.sorted()

    /** People who left: their connections should be closed. */
    fun gone(joined: Collection<String>, connected: Collection<String>): List<String> = connected.filter { it !in joined }

    /** Whether an invitation still rings for this person. */
    fun rings(me: String, hostUid: String, invited: Collection<String>, joined: Collection<String>, state: String, startedAt: Long, now: Long, dismissed: Set<String>, callId: String): Boolean =
        me != hostUid && me in invited && me !in joined && state == "open" && callId !in dismissed && now - startedAt in 0..RING_MS

    /** Who may be invited: other active members, at most 3 (the host is the 4th). */
    fun canStart(host: String, invited: List<String>): String? = when {
        invited.isEmpty() -> "Choose at least one person to call."
        host in invited -> "You are already in the call."
        invited.distinct().size + 1 > MAX_PEOPLE -> "A group call can have up to $MAX_PEOPLE people (you and 3 others)."
        else -> null
    }

    enum class Quality(val label: String) { GOOD("Good connection"), FAIR("Fair connection"), POOR("Poor connection"), UNKNOWN("") }

    /** Network quality from the round-trip time and the share of packets lost. */
    fun quality(rttMs: Double?, lossFraction: Double?): Quality = when {
        rttMs == null && lossFraction == null -> Quality.UNKNOWN
        (rttMs ?: 0.0) > 600 || (lossFraction ?: 0.0) > 0.10 -> Quality.POOR
        (rttMs ?: 0.0) > 300 || (lossFraction ?: 0.0) > 0.03 -> Quality.FAIR
        else -> Quality.GOOD
    }

    /** The loudest person speaking (audio level 0..1), if anyone is clearly speaking. */
    fun activeSpeaker(levels: Map<String, Double>, threshold: Double = 0.02): String? =
        levels.filter { it.value >= threshold }.maxByOrNull { it.value }?.key

    /** Grid columns for n video tiles on a phone screen. */
    fun columns(n: Int): Int = if (n <= 2) 1 else 2
}
