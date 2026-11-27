package com.familyhub.app.family

import java.util.Calendar
import java.util.Locale

/**
 * Phase 4: the family planner part of the assistant, worked out on the phone from the person's own data (no AI call,
 * nothing sent anywhere). Plain Kotlin, tested in tools/phase4-test.
 */
object Planner {
    enum class Ask { SCHEDULE, BILLS, WEEK_SUMMARY, CATEGORY_UP, RECURRING, SUGGEST_REMINDERS, SHOPPING_LIST, WEEKLY_BRIEF }

    /** Which planner question this is, or null for anything else (the normal assistant handles it). */
    fun classify(text: String): Ask? {
        val s = text.lowercase(Locale.ROOT)
        return when {
            Regex("\\b(weekly|week'?s) (family )?brief\\b|\\bbrief (me|for the week)\\b").containsMatchIn(s) -> Ask.WEEKLY_BRIEF
            Regex("\\bsuggest(ed)? reminders?\\b|\\bwhat (should|do) i (need to )?(be )?remind").containsMatchIn(s) -> Ask.SUGGEST_REMINDERS
            Regex("\\b(create|make|start|new)\\b.*\\bshopping list\\b|\\bshopping list for\\b").containsMatchIn(s) -> Ask.SHOPPING_LIST
            Regex("\\b(scheduled|planned|happening|on the calendar|plans?|agenda|schedule)\\b").containsMatchIn(s) &&
                Regex("\\b(today|tomorrow|weekend|this week|next week|kal|aaj)\\b").containsMatchIn(s) -> Ask.SCHEDULE
            Regex("\\b(upcoming|pending|due|unpaid)\\b.*\\bbills?\\b|\\bbills? (due|pending|coming)\\b|\\bshow (our |my )?bills\\b").containsMatchIn(s) -> Ask.BILLS
            Regex("\\b(summar(y|ise|ize)|what happened|recap)\\b.*\\b(week|family activit)").containsMatchIn(s) -> Ask.WEEK_SUMMARY
            Regex("\\bwhich categor(y|ies)\\b.*\\b(increase|went up|more|higher|grew)\\b|\\bcategor(y|ies) increased\\b").containsMatchIn(s) -> Ask.CATEGORY_UP
            Regex("\\b(show|list|what are)\\b.*\\brecurring\\b|\\brecurring (expenses|payments)\\b").containsMatchIn(s) -> Ask.RECURRING
            else -> null
        }
    }

    private fun startOfDay(t: Long, c: Calendar): Long { c.timeInMillis = t; c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); return c.timeInMillis }

    /** The days a schedule question is about: today, tomorrow, this weekend (Sat–Sun), this week or next week. */
    fun range(text: String, now: Long, c: Calendar = Calendar.getInstance()): Triple<Long, Long, String> {
        val s = text.lowercase(Locale.ROOT)
        val day = 86_400_000L
        val today = startOfDay(now, c)
        return when {
            "tomorrow" in s || Regex("\\bkal\\b").containsMatchIn(s) -> Triple(today + day, today + 2 * day - 1, "tomorrow")
            "weekend" in s -> {
                c.timeInMillis = today
                val dow = c.get(Calendar.DAY_OF_WEEK)
                val toSat = when (dow) { Calendar.SATURDAY -> 0; Calendar.SUNDAY -> -1; else -> Calendar.SATURDAY - dow }
                val sat = today + toSat * day
                Triple(maxOf(sat, today), sat + 2 * day - 1, "this weekend")
            }
            "next week" in s -> { c.timeInMillis = today; val toMon = (Calendar.MONDAY - c.get(Calendar.DAY_OF_WEEK) + 7) % 7; val mon = today + (if (toMon == 0) 7 else toMon) * day; Triple(mon, mon + 7 * day - 1, "next week") }
            "week" in s -> Triple(today, today + 7 * day - 1, "this week")
            else -> Triple(today, today + day - 1, "today")
        }
    }

    data class Suggestion(val title: String, val dateMillis: Long, val why: String)

    private val SPEND_ON = Regex(
        "\\b(?:spen[dt]|spending|expenses?|kharcha|kharch)\\s+(?:on|for|in|par|pe)\\s+" +
            "([\\p{L}][\\p{L} &-]{1,30}?)(?=\\s+(?:this|last|is|so|pichle|pichhle|in)\\b|\\s+(?:mahine|month|week)\\b|\\s*[?.!,]|\\s*$)"
    )
    // Hinglish: "grocery pe kitna kharcha hua", "transport mein kitne gaye"
    private val TOPIC_KITNA = Regex("(?:^|\\b)([\\p{L}][\\p{L} ]{1,30}?)\\s+(?:pe|par|mein|me|ke liye|ka|ki)\\s+(?:kitna|kitne|kitni)\\b")
    // "grocery spending this month", "transport expenses last month"
    private val TOPIC_FIRST = Regex("(?:^|\\b)([\\p{L}]{3,20}(?: [\\p{L}]{3,20})?)\\s+(?:spending|spend|expenses?|kharcha)\\s+(?:this|last|is|pichle|pichhle)\\b")
    private val NOT_TOPIC = setOf("it", "that", "this", "things", "stuff", "everything", "all", "average", "total", "overall", "monthly",
        "family", "household", "money", "maine", "humne", "hum", "aaj", "kal", "did", "have", "has", "had", "we", "i", "you", "much", "how", "how much")
    private val LEAD = Regex("^(?:how much |how much did |did |have |has |the |our |my |total |family |humara |hamara |mera |meri |mere |maine |humne |" +
        "is mahine |iss mahine |pichle mahine |pichhle mahine |this month |last month |aaj |is |iss )+")

    private fun cleanTopic(raw: String?): String? {
        var t = raw?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        var prev: String
        do { prev = t; t = t.replace(LEAD, "").trim() } while (t != prev)
        t = t.removeSuffix(" ka").removeSuffix(" ki").removeSuffix(" ke").trim()
        return t.takeIf { it.length >= 3 && it !in NOT_TOPIC && it.split(' ').none { w -> w in setOf("did", "have", "spend", "spent") } }
    }

    /** The thing a spending question is about: "how much did we spend on groceries this month?" → "groceries". */
    fun spendTopic(text: String): String? {
        val s = text.lowercase(Locale.ROOT)
        return cleanTopic(SPEND_ON.find(s)?.groupValues?.get(1))
            ?: cleanTopic(TOPIC_KITNA.find(s)?.groupValues?.get(1))
            ?: cleanTopic(TOPIC_FIRST.find(s)?.groupValues?.get(1))
    }

    private fun stem(w: String): String {
        val n = norm(w)
        return when {
            n.endsWith("ies") && n.length > 4 -> n.dropLast(3) + "y"
            n.endsWith("es") && n.length > 4 && (n.endsWith("ches") || n.endsWith("shes") || n.endsWith("xes")) -> n.dropLast(2)
            n.endsWith("s") && n.length > 3 && !n.endsWith("ss") -> n.dropLast(1)
            else -> n
        }
    }

    /** A phrase reduced word by word ("Food Shopping" → "foodshopping", "groceries" → "grocery"). */
    private fun stemPhrase(p: String): String = p.lowercase(Locale.ROOT).split(Regex("[^\\p{L}0-9]+")).filter { it.isNotBlank() }.joinToString("") { stem(it) }

    /**
     * Everyday words for the built-in categories. A word leads to its category only when that category is really
     * used or defined by the family; nothing new is made up, and unrelated categories are never joined.
     */
    val SYNONYMS: Map<String, List<String>> = mapOf(
        "Groceries" to listOf("grocery", "groceries", "grocery shopping", "food shopping", "supermarket", "super market", "kirana", "ration",
            "sabzi", "sabji", "vegetables", "fruits and vegetables", "provisions", "daily needs"),
        "Transport" to listOf("transport", "transportation", "commute", "commuting", "cab", "taxi", "auto", "metro", "bus", "local travel"),
        "Fuel" to listOf("fuel", "petrol", "diesel", "cng"),
        "Dining" to listOf("dining", "eating out", "restaurant", "food delivery", "takeaway", "dine out"),
        "Health" to listOf("health", "medical", "medicine", "doctor", "pharmacy", "dawai", "hospital"),
        "Utilities" to listOf("utilities", "utility", "electricity", "bijli", "water bill", "gas bill", "internet", "broadband", "wifi",
            "recharge", "mobile bill", "phone bill"),
        "Rent" to listOf("rent", "kiraya", "house rent"),
        "Education" to listOf("education", "school fees", "tuition", "college fees"),
        "Entertainment" to listOf("entertainment", "movie", "ott"),
        "Shopping" to listOf("shopping", "clothes", "clothing", "online shopping"),
        "Insurance" to listOf("insurance", "premium"),
        "EMI" to listOf("emi", "loan emi"),
        "Travel" to listOf("travel", "trip", "holiday", "vacation", "flight", "hotel")
    )

    /** A category or subcategory as stored on Finance entries. */
    data class CatRef(val field: String, val name: String, val parent: String = "") {
        val label get() = if (field == "subcategory" && parent.isNotBlank()) "$parent › $name" else name
    }

    /**
     * The stored categories (or subcategories) the person meant. One hit: use it. Several: ask which one. None: say so.
     * "grocery", "Food shopping" and "supermarket" all lead to "Groceries" (or a "Grocery" subcategory) when the family has one.
     */
    fun findCategories(topic: String, stored: Collection<CatRef>): List<CatRef> {
        val t = stemPhrase(topic)
        if (t.length < 3) return emptyList()
        val canon = SYNONYMS.filter { (_, words) -> words.any { stemPhrase(it) == t } }.keys.map { stemPhrase(it) }
        val wanted = (setOf(t) + canon).toSet()
        val refs = stored.distinct()
        fun parts(n: String) = n.split(Regex("\\s*(?:&|/|,|-|\\band\\b)\\s*")).map { it.trim() }.filter { it.isNotBlank() }
        val exact = refs.filter { stemPhrase(it.name) in wanted }
        val partHits = refs.filter { r -> r !in exact && parts(r.name).size > 1 && parts(r.name).any { stemPhrase(it) in wanted } }
        val hits = (exact + partHits).ifEmpty {
            refs.filter { r -> val n = stemPhrase(r.name); n.length >= 4 && t.length >= 4 && (n.startsWith(t) || t.startsWith(n)) }
        }
        // A subcategory inside a category that already matched adds nothing (its entries are counted there).
        val cats = hits.filter { it.field == "category" }.map { stemPhrase(it.name) }.toSet()
        return hits.filterNot { it.field == "subcategory" && stemPhrase(it.parent) in cats }
            .distinctBy { it.field + "|" + stemPhrase(it.parent) + "|" + stemPhrase(it.name) }
    }

    /** Kept for older callers: the single category name, or null when none or several match. */
    fun matchCategory(topic: String, categories: Collection<String>): String? =
        findCategories(topic, categories.map { CatRef("category", it) }).singleOrNull()?.name

    private fun norm(t: String) = t.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /**
     * Reminders worth setting (the person confirms each one): documents expiring, services and warranty dates,
     * subscription renewals, events and tasks in the next two weeks. Ones that already have a reminder are skipped.
     */
    fun suggestReminders(items: List<Map<String, Any?>>, events: List<Map<String, Any?>>, tasks: List<Map<String, Any?>>, now: Long): List<Suggestion> {
        val day = 86_400_000L
        val horizon = now + 45 * day
        val existing = items.filter { it["type"] == "reminder" && it["done"] != true }.map { norm((it["title"] as? String).orEmpty()) }
        fun has(title: String) = existing.any { e -> e.isNotEmpty() && (norm(title) in e || e in norm(title)) }
        val out = ArrayList<Suggestion>()
        fun add(title: String, due: Long?, before: Int, why: String) {
            if (due == null || due < now - day || due > horizon || has(title)) return
            out += Suggestion(title, maxOf(now, due - before * day), why)
        }
        for (r in items) {
            if (r["isDeleted"] == true) continue
            val t = (r["title"] as? String).orEmpty()
            when (r["type"]) {
                "document" -> if ((r["renewalStatus"] as? String) != "Renewed") add("Renew $t", (r["expiryDateMillis"] as? Number)?.toLong(), DocExpiry.windowDays(r).coerceAtMost(30), "expires soon")
                "maintenance" -> { add("$t service", (r["nextDueMillis"] as? Number)?.toLong(), 3, "service due"); add("$t warranty ends", (r["warrantyExpiryMillis"] as? Number)?.toLong(), 14, "warranty ending") }
                "vehicle" -> { add("$t service", (r["serviceDueMillis"] as? Number)?.toLong(), 3, "service due"); add("$t insurance", (r["insuranceExpiryMillis"] as? Number)?.toLong(), 10, "insurance renewal"); add("$t PUC", (r["pucExpiryMillis"] as? Number)?.toLong(), 5, "PUC renewal") }
                "subscription" -> if (Subscriptions.isLive(r)) add("$t renews", (r["renewalMillis"] as? Number)?.toLong(), 2, "renewal")
            }
        }
        events.filter { it["isDeleted"] != true }.forEach { e -> (e["startTimeMillis"] as? Number)?.toLong()?.takeIf { it in now..(now + 14 * day) }?.let { add((e["title"] as? String).orEmpty(), it, 1, "event") } }
        tasks.filter { it["isCompleted"] != true && it["type"] == "TASK" }.forEach { tk -> add((tk["title"] as? String).orEmpty(), (tk["dueDateMillis"] as? Number)?.toLong(), 1, "task due") }
        return out.distinctBy { norm(it.title) }.sortedBy { it.dateMillis }.take(10)
    }

    data class ShoppingAsk(val listName: String, val items: List<String>)

    /** "Create a shopping list for tomorrow: milk, eggs and bread" -> list "Tomorrow" with three items. */
    fun shoppingList(text: String): ShoppingAsk? {
        val s = text.trim()
        val m = Regex("(?i)shopping list(?: for ([^:]+?))?\\s*(?::|with|-)\\s*(.+)$").find(s) ?: return null
        val name = m.groupValues[1].trim().trimEnd('.').ifBlank { "Shopping" }.replaceFirstChar { it.uppercase() }
        val items = m.groupValues[2].split(Regex(",|\\band\\b|;")).map { it.trim().trimEnd('.') }.filter { it.isNotBlank() }
        return if (items.isEmpty()) null else ShoppingAsk(name, items.take(30))
    }

    /** Sections of the weekly family brief the person can switch on or off. */
    val BRIEF_SECTIONS = listOf("Events", "Tasks", "Bills", "Documents", "Maintenance", "Activity", "Money")
}
