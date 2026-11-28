package com.familyhub.app.family

import java.time.Instant
import java.time.ZoneId

/** Memories: albums, "on this day", reactions. Pure, unit-tested in tools/memory-test. */
object MemoryMath {
    val REACTIONS = listOf("❤️", "😂", "😮", "🎉", "🙏")
    const val MAX_PHOTOS = 12

    data class Mem(val id: String, val title: String, val dateMillis: Long?, val album: String)

    /** Memories from earlier years on today's day and month (newest year first). */
    fun onThisDay(list: List<Mem>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Pair<Mem, Int>> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return list.mapNotNull { m ->
            val d = m.dateMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: return@mapNotNull null
            val years = today.year - d.year
            if (years >= 1 && d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth) m to years else null
        }.sortedBy { it.second }
    }

    /** Album name to how many memories it holds; memories without an album are not counted. */
    fun albums(list: List<Mem>): List<Pair<String, Int>> =
        list.map { it.album.trim() }.filter { it.isNotBlank() }
            .groupBy { it.lowercase() }.map { (_, v) -> v.first() to v.size }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })

    /** One reaction per person: emoji to count, in the fixed order, only those used. */
    fun reactionCounts(byPerson: Map<String, String>): List<Pair<String, Int>> {
        val c = byPerson.values.groupingBy { it }.eachCount()
        return (REACTIONS + c.keys.filter { it !in REACTIONS }).mapNotNull { e -> c[e]?.let { e to it } }
    }

    fun yearsAgoText(n: Int) = if (n == 1) "1 year ago" else "$n years ago"

    /** Comment text cleaned for saving: trimmed, at most 500 characters, null if empty. */
    fun cleanComment(t: String): String? = t.trim().take(500).ifBlank { null }

    fun canAddPhotos(current: Int, adding: Int) = current + adding <= MAX_PHOTOS
}
