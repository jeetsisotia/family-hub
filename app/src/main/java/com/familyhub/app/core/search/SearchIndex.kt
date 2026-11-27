package com.familyhub.app.core.search

import java.util.Locale

/**
 * Universal search over everything this phone already holds for the person (records they may see, never other
 * members' "Only me" ones). Runs on the phone: no AI, no network. Every word typed must appear somewhere in a
 * record; titles that start with the words rank first, then title matches, then matches elsewhere, then newer.
 */
object SearchIndex {

    data class Doc(
        val id: String,
        val section: String,
        val route: String,
        val title: String,
        val subtitle: String = "",
        val text: String = "",
        val dateMillis: Long? = null,
        val amount: Double? = null
    )

    data class Hit(val doc: Doc, val score: Int, val titleRanges: List<IntRange>, val subtitleRanges: List<IntRange>)

    private fun norm(s: String) = s.lowercase(Locale.ROOT)

    /** The words of a query; "₹1,200" and "1200" both become "1200" so amounts can be found. */
    fun tokens(query: String): List<String> =
        norm(query).replace(Regex("[₹,]"), "").split(Regex("\\s+")).map { it.trim('.', '?', '!', '"', '\'') }.filter { it.isNotBlank() }.distinct()

    private fun amountText(a: Double?): String = when {
        a == null -> ""
        a == Math.floor(a) -> a.toLong().toString()
        else -> String.format(Locale.ROOT, "%.2f", a)
    }

    /** Where each word appears in [text] (for bold highlighting), merged and in order. */
    fun ranges(text: String, words: List<String>): List<IntRange> {
        val t = norm(text)
        val out = mutableListOf<IntRange>()
        for (w in words) {
            var i = t.indexOf(w)
            while (i >= 0 && w.isNotEmpty()) { out.add(i until i + w.length); i = t.indexOf(w, i + w.length) }
        }
        val sorted = out.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        for (r in sorted) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) merged[merged.size - 1] = last.first..maxOf(last.last, r.last) else merged.add(r)
        }
        return merged
    }

    fun search(query: String, docs: List<Doc>, section: String? = null, limit: Int = 80): List<Hit> {
        val words = tokens(query)
        if (words.isEmpty()) return emptyList()
        val q = words.joinToString(" ")
        return docs.asSequence()
            .filter { section == null || it.section == section }
            .mapNotNull { d ->
                val title = norm(d.title)
                val hay = title + " \u0001 " + norm(d.subtitle) + " \u0001 " + norm(d.text) + " \u0001 " + amountText(d.amount)
                if (!words.all { hay.contains(it) }) return@mapNotNull null
                val titleWords = title.split(Regex("[^\\p{L}\\p{N}]+"))
                var score = when {
                    title == q -> 100
                    title.startsWith(q) -> 80
                    words.all { w -> titleWords.any { it.startsWith(w) } } -> 60
                    words.all { title.contains(it) } -> 40
                    words.any { title.contains(it) } -> 25
                    else -> 10
                }
                if (norm(d.subtitle).contains(q)) score += 5
                Hit(d, score, ranges(d.title, words), ranges(d.subtitle, words))
            }
            .sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.doc.dateMillis ?: 0L }.thenBy { it.doc.title })
            .take(limit)
            .toList()
    }

    /** Live suggestions while typing: titles that start with (then contain) what was typed, no repeats. */
    fun suggestions(query: String, docs: List<Doc>, max: Int = 6): List<String> {
        val q = norm(query).trim()
        if (q.length < 2) return emptyList()
        val starts = docs.filter { norm(it.title).startsWith(q) }.map { it.title }
        val inside = docs.filter { !norm(it.title).startsWith(q) && norm(it.title).contains(q) }.map { it.title }
        return (starts + inside).distinctBy { norm(it) }.filter { norm(it) != q }.take(max)
    }

    /** Recent searches: newest first, no repeats (case ignored), at most [max]. */
    fun remember(recent: List<String>, query: String, max: Int = 10): List<String> {
        val q = query.trim()
        if (q.length < 2) return recent
        return (listOf(q) + recent.filter { norm(it) != norm(q) }).take(max)
    }

    /** Sections that have hits, in a stable order, with counts (for the filter chips). */
    fun sections(hits: List<Hit>, order: List<String>): List<Pair<String, Int>> {
        val counts = hits.groupingBy { it.doc.section }.eachCount()
        return (order.filter { it in counts } + counts.keys.filter { it !in order }.sorted()).map { it to counts.getValue(it) }
    }
}

/** A search handed to the screen a result opens, so it shows the record straight away. Taken once. */
object SearchSeed {
    private val seeds = mutableMapOf<String, String>()
    fun put(route: String, query: String) { seeds[route] = query }
    fun take(route: String): String = seeds.remove(route).orEmpty()
}
