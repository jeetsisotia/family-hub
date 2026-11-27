package com.familyhub.app.ui.ai

import com.familyhub.app.data.remote.Rec
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.modules.bool
import com.familyhub.app.ui.modules.lng
import com.familyhub.app.ui.modules.num
import com.familyhub.app.ui.modules.startOfDay
import com.familyhub.app.ui.modules.str
import com.familyhub.app.ui.money.refundTargets
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Jobs the assistant does on the phone, for any page, without sending anything to the AI:
 * duplicates, refunds, "not an expense", the emergency contact in My Profile, and acting on open tasks.
 * Every change is shown as a card and only happens when the person taps it.
 */
object AppCommands {

    data class Result(val reply: String, val actions: List<AiAction>)

    private val DUP = Regex("\\b(duplicate|duplicates|same entry|entered twice|saved twice)\\b")
    private val EMERGENCY = Regex("\\bemergency\\s+(contact|number|no\\.?|phone)\\b")
    private val REFUND = Regex("\\b(merge|match|link|adjust)\\b.*\\brefunds?\\b|\\brefunds?\\b.*\\b(merge|match|link|adjust)\\b")
    private val NOT_EXPENSE = Regex("\\bnot (an? )?(expense|spend|spending)\\b|\\bexclude\\b.*\\b(expense|spend|total)")
    private val TASKS = Regex(
        "\\b(act on|take action|help with|help me with|handle|finish|work on|check|show|list|what|which|pending|open)\\b.*\\btasks?\\b|" +
            "\\btasks?\\b.*\\b(added by|from) (the )?owner\\b"
    )
    private val ADDING = Regex("^\\s*(please\\s+)?(add|create|new|make|remind|set up|schedule)\\b")
    private val CHANGE = Regex("\\b(add|set|save|update|change|put)\\b")

    /** Kept on the phone: these health and ID kinds are never matched, changed or shown here. */
    private val PRIVATE_TYPES = setOf("medicine", "vital", "habit", "workout", "period", "health", "vault", "food")

    private val rupees: NumberFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply { maximumFractionDigits = 0 }
    private fun inr(v: Double) = rupees.format(v)
    private fun day(t: Long?) = t?.let { SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(it)) }.orEmpty()
    private fun norm(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun handle(text: String, env: AppEnv, page: String? = null): Result? {
        val s = text.lowercase(Locale.ROOT)
        return when {
            EMERGENCY.containsMatchIn(s) && CHANGE.containsMatchIn(s) -> emergency(text, env)
            REFUND.containsMatchIn(s) -> refunds(env)
            DUP.containsMatchIn(s) -> duplicates(env, page)
            NOT_EXPENSE.containsMatchIn(s) -> notExpense(text, env)
            TASKS.containsMatchIn(s) && !ADDING.containsMatchIn(s) -> tasks(s, env)
            // Spend, savings, EMIs, card bills, balances and dues, worked out on the phone.
            else -> MoneyAnswers.answer(text, env)
        }
    }

    // ---------- duplicates ----------

    private val DATE_KEYS = listOf("dateMillis", "startTimeMillis", "dueDateMillis", "dueMillis", "expiryDateMillis", "renewalDateMillis")

    private fun firstDate(r: Rec): Long? = DATE_KEYS.firstNotNullOfOrNull { r.lng(it) }?.let { startOfDay(it) }

    private fun dupKey(collection: String, r: Rec): String? {
        if (r.bool("isDeleted")) return null
        return when (collection) {
            // Same kind, amount and day: SMS imports and hand entries of one payment end up here.
            "expenses" -> {
                val amount = r.num("amountInr") ?: return null
                val d = r.lng("dateMillis")?.let { startOfDay(it) } ?: return null
                "${r.str("type").ifBlank { "EXPENSE" }}|${"%.2f".format(amount)}|$d"
            }
            "tasks" -> {
                if (r.bool("isCompleted")) return null
                val t = norm(r.str("title")).ifBlank { return null }
                "${r.str("type")}|$t"
            }
            "events" -> {
                val t = norm(r.str("title")).ifBlank { return null }
                "$t|${firstDate(r)}"
            }
            "items" -> {
                val type = r.str("type")
                if (type in PRIVATE_TYPES) return null
                val t = norm(r.str("title")).ifBlank { return null }
                "$type|$t|${firstDate(r)}|${r.num("amountInr") ?: r.num("amount") ?: ""}"
            }
            else -> null
        }
    }

    private fun collectionName(c: String) = when (c) {
        "expenses" -> "Finance"; "tasks" -> "Tasks and shopping"; "events" -> "Calendar"; else -> "Other lists"
    }

    private fun duplicates(env: AppEnv, page: String?): Result {
        val order = listOf("expenses", "tasks", "events", "items").let { all ->
            val first = when (page?.lowercase(Locale.ROOT)) {
                "finance", "money", "expenses" -> "expenses"
                "shopping", "household", "tasks" -> "tasks"
                "calendar" -> "events"
                else -> null
            }
            if (first == null) all else listOf(first) + (all - first)
        }
        val actions = ArrayList<AiAction>()
        for (c in order) {
            val recs = env.data[c].orEmpty()
            // Money entries use the stricter payment matcher (same direction and amount, within a day and a half, and the
            // same reference, card ending or shop). Other lists match on title and date.
            if (c == "expenses") {
                // Money uses the shared duplicate finder (also behind Finance → Duplicates): reasons, confidence and
                // the entry to keep. Removing sends the others to Recently deleted and can be undone there.
                for (g in com.familyhub.app.money.Duplicates.find(recs)) {
                    val sure = if (g.confidence == com.familyhub.app.money.Duplicates.Confidence.HIGH) "Very likely" else "Possible"
                    actions.add(
                        AiAction(
                            kind = "delete_dups",
                            title = g.keep.str("title").ifBlank { "(untitled)" },
                            amount = g.keep.num("amountInr"),
                            dateText = listOfNotNull(
                                day(firstDate(g.keep)).ifBlank { null }, sure,
                                g.reasons.drop(1).take(2).joinToString("; ").ifBlank { null },
                                g.cautions.firstOrNull()?.let { "Check: $it" },
                                "${g.records.size} entries, removes ${g.remove.size}"
                            ).joinToString(" • "),
                            collection = c,
                            refs = g.remove,
                            value = g.keep.str("id")
                        )
                    )
                    if (actions.size >= 12) break
                }
                if (actions.size >= 12) break
                continue
            }
            val groups = recs.groupBy { dupKey(c, it) }.filterKeys { it != null }.values.filter { it.size > 1 }
            for (g in groups.sortedByDescending { it.size }) {
                // Keep the entry with the most detail (a hand-made one usually has a category, notes or a member).
                val keep = g.maxByOrNull { r -> r.values.count { v -> v is String && v.isNotBlank() } } ?: g.first()
                val extra = g.filter { it !== keep }
                val amount = keep.num("amountInr")
                actions.add(
                    AiAction(
                        kind = "delete_dups",
                        title = keep.str("title").ifBlank { "(untitled)" } +
                            (if (g.map { norm(it.str("title")) }.distinct().size > 1) " / " + extra.joinToString(" / ") { it.str("title") } else ""),
                        amount = amount,
                        dateText = listOfNotNull(day(firstDate(keep)).ifBlank { null }, "${g.size} entries, removes ${extra.size}", collectionName(c))
                            .joinToString(" • "),
                        collection = c,
                        refs = extra
                    )
                )
                if (actions.size >= 12) break
            }
            if (actions.size >= 12) break
        }
        if (actions.isEmpty()) return Result("No duplicates found in Finance, tasks, shopping, calendar or your lists.", emptyList())
        return Result(
            "Found ${actions.size} set${if (actions.size == 1) "" else "s"} of possible duplicates. Each card keeps one entry " +
                "and removes the rest. Check the names before you tap Remove.",
            actions
        )
    }

    // ---------- refunds ----------

    private val REFUND_WORDS = Regex("refund|reversal|reversed|returned|cancelled|canceled|chargeback", RegexOption.IGNORE_CASE)

    private fun refunds(env: AppEnv): Result {
        val all = env.data["expenses"].orEmpty().filter { !it.bool("isDeleted") }
        val credits = all.filter { it.str("type") == "INCOME" && it.str("refundOf").isBlank() && REFUND_WORDS.containsMatchIn(it.str("title") + " " + it.str("notes")) }
        val actions = credits.mapNotNull { r ->
            val target = refundTargets(r, all).firstOrNull() ?: return@mapNotNull null
            AiAction(
                kind = "merge_refund",
                title = "${r.str("title")} into ${target.str("title")}",
                amount = r.num("amountInr"),
                dateText = "refund ${day(r.lng("dateMillis"))}, paid ${day(target.lng("dateMillis"))}",
                collection = "expenses",
                refs = listOf(target, r)
            )
        }.take(12)
        if (actions.isEmpty()) {
            return Result(
                "I couldn't find refund credits to match. Open a purchase in Finance and use Merge a refund to pick one yourself.",
                emptyList()
            )
        }
        return Result("These refunds match an earlier purchase. Merging counts only the net amount in spending and budgets:", actions)
    }

    // ---------- not an expense ----------

    private fun notExpense(text: String, env: AppEnv): Result {
        val s = text.lowercase(Locale.ROOT)
        val word = Regex("(?:mark|treat|set|exclude)\\s+(?:all\\s+|the\\s+|my\\s+)?(.+?)\\s+(?:as\\s+)?(?:not|from)\\b").find(s)?.groupValues?.get(1)?.trim()
            .orEmpty().removeSuffix(" entries").removeSuffix(" payments").removeSuffix(" transactions")
        val pattern: Regex = when {
            Regex("self|own account|my account|between").containsMatchIn(word) || (word.isBlank() && "self" in s) ->
                Regex("self|own a/?c|own account|to self|transfer to (my|own)")
            Regex("card bill|credit card|cc bill|card payment").containsMatchIn("$word $s") ->
                Regex("credit card (bill|payment)|cc (bill|payment)|card bill|card payment|payment received.*card")
            word.length >= 3 -> Regex(Regex.escape(word))
            else -> return Result(
                "Tell me which entries, for example \"mark self transfers as not an expense\" or \"mark rent from Papa as not an expense\". " +
                    "You can also tap any entry in Finance and choose Not an expense.",
                emptyList()
            )
        }
        val hits = env.data["expenses"].orEmpty().filter {
            !it.bool("isDeleted") && !it.bool("notExpense") &&
                pattern.containsMatchIn((it.str("title") + " " + it.str("notes")).lowercase(Locale.ROOT))
        }
        if (hits.isEmpty()) return Result("No entries matched \"${word.ifBlank { "self transfers" }}\".", emptyList())
        val total = hits.sumOf { it.num("amountInr") ?: 0.0 }
        return Result(
            "This leaves them in the list but out of spending, income and budgets:",
            listOf(
                AiAction(
                    kind = "not_expense",
                    title = "${hits.size} entr${if (hits.size == 1) "y" else "ies"} like \"${hits.first().str("title")}\"",
                    amount = total,
                    collection = "expenses",
                    refs = hits
                )
            )
        )
    }

    // ---------- emergency contact ----------

    private val RELATIONS = mapOf(
        "papa" to "Father", "dad" to "Father", "father" to "Father", "pitaji" to "Father",
        "mummy" to "Mother", "mom" to "Mother", "mum" to "Mother", "mother" to "Mother", "maa" to "Mother",
        "wife" to "Wife", "husband" to "Husband", "brother" to "Brother", "bhai" to "Brother", "sister" to "Sister", "didi" to "Sister",
        "son" to "Son", "daughter" to "Daughter", "friend" to "Friend", "uncle" to "Uncle", "aunty" to "Aunt", "aunt" to "Aunt",
        "doctor" to "Doctor", "neighbour" to "Neighbour", "neighbor" to "Neighbour"
    )

    private fun emergency(text: String, env: AppEnv): Result {
        val phone = Regex("(\\+?\\d[\\d\\s-]{8,14}\\d)").find(text)?.value?.filter { it.isDigit() || it == '+' }.orEmpty()
        if (phone.count { it.isDigit() } < 10) {
            return Result("Tell me the name and number, for example \"add emergency contact Papa 98765 43210\".", emptyList())
        }
        val words = text.replace(Regex("(\\+?\\d[\\d\\s-]{8,14}\\d)"), " ")
            .replace(Regex("(?i)\\b(add|set|save|update|change|my|the|an?|emergency|contact|number|no\\.?|phone|is|as|to|in|profile|with|for|and|of)\\b"), " ")
            .split(Regex("[\\s,:;.]+")).filter { it.isNotBlank() }
        val relationWord = words.firstOrNull { it.lowercase(Locale.ROOT) in RELATIONS }
        val relation = relationWord?.let { RELATIONS[it.lowercase(Locale.ROOT)] }.orEmpty()
        val name = words.filter { it != relationWord }.joinToString(" ").ifBlank { relationWord.orEmpty() }
            .split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
        if (name.isBlank()) return Result("Whose number is it? For example \"add emergency contact Papa $phone\".", emptyList())
        val slot = when {
            (env.userDoc["emergency1Phone"] as? String).isNullOrBlank() || (env.userDoc["emergency1Phone"] as? String)?.filter { it.isDigit() }
                ?.endsWith(phone.filter { it.isDigit() }.takeLast(10)) == true -> 1
            (env.userDoc["emergency2Phone"] as? String).isNullOrBlank() -> 2
            else -> 2
        }
        val replacing = slot == 2 && !(env.userDoc["emergency2Phone"] as? String).isNullOrBlank()
        return Result(
            "Saved on your phone and in My Profile, never sent to the AI." +
                if (replacing) " Both contacts are filled, so this replaces contact 2 (${env.userDoc["emergency2Name"] ?: ""})." else "",
            listOf(
                AiAction(
                    kind = "emergency", title = name, phone = phone, value = relation, count = slot
                )
            )
        )
    }

    // ---------- tasks ----------

    private fun tasks(s: String, env: AppEnv): Result {
        val ownerUid = env.family.ownerUid
        val ownerName = env.members.firstOrNull { it.uid == ownerUid }?.name.orEmpty()
        val onlyOwner = "owner" in s || (ownerName.isNotBlank() && ownerName.lowercase(Locale.ROOT) in s)
        val me = env.profile.name.lowercase(Locale.ROOT)
        val open = env.data["tasks"].orEmpty().filter { it.str("type").ifBlank { "TASK" } == "TASK" && !it.bool("isCompleted") }
            .map { it to "tasks" } +
            env.data["items"].orEmpty().filter { it.str("type") == "chore" && !it.bool("done") }.map { it to "items" }
        val mine = open.filter { (r, _) ->
            val who = (r.str("assignedToName") + r.str("assignedTo")).lowercase(Locale.ROOT)
            (!onlyOwner || r.str("createdByUid") == ownerUid) && (who.isBlank() || who.contains(me.substringBefore(' ')))
        }.sortedBy { (r, _) -> r.lng("dueDateMillis") ?: r.lng("dueMillis") ?: Long.MAX_VALUE }.take(8)
        if (mine.isEmpty()) {
            return Result(
                if (onlyOwner) "No open tasks from ${ownerName.ifBlank { "the family owner" }} for you." else "You have no open tasks. Nice!",
                emptyList()
            )
        }
        val actions = ArrayList<AiAction>()
        for ((r, c) in mine) {
            val title = r.str("title")
            guessOpen(title)?.let { actions.add(it) }
            val due = r.lng("dueDateMillis") ?: r.lng("dueMillis")
            actions.add(
                AiAction(
                    kind = "task_done", title = title,
                    dateText = listOfNotNull(due?.let { "due " + day(it) }, r.str("createdByName").takeIf { it.isNotBlank() }?.let { "from $it" })
                        .joinToString(" • "),
                    collection = c, refs = listOf(r)
                )
            )
        }
        val who = if (onlyOwner) " from ${ownerName.ifBlank { "the owner" }}" else ""
        return Result(
            "${mine.size} open task${if (mine.size == 1) "" else "s"}$who. Where I can, I'll open the right app with the details filled in; " +
                "you book, pay or send there. Tap Mark done when it's finished.",
            actions
        )
    }

    /** The app that helps with a task, if one fits. Nothing is booked or paid without the person. */
    fun guessOpen(title: String): AiAction? {
        val t = title.lowercase(Locale.ROOT)
        fun after(vararg words: String): String {
            for (w in words) {
                val m = Regex("\\b$w\\s+(.+)$", RegexOption.IGNORE_CASE).find(title)
                if (m != null) return m.groupValues[1].trim().trimEnd('.', '!')
            }
            return ""
        }
        val stripped = title.replace(Regex("(?i)^(please\\s+)?(buy|order|get|purchase|book|find|search|pay|call|apply for|apply)\\s+"), "").trim()
        return when {
            Regex("\\b(cab|taxi|uber|ride|airport drop|pickup)\\b").containsMatchIn(t) ->
                AiAction(kind = "open", title = title, service = "uber", place = after("to", "for"))
            Regex("\\bola\\b").containsMatchIn(t) -> AiAction(kind = "open", title = title, service = "ola", place = after("to"))
            Regex("\\b(flight|flights|fly)\\b").containsMatchIn(t) -> AiAction(kind = "open", title = title, service = "flights", query = stripped)
            Regex("\\b(train|irctc|tatkal)\\b").containsMatchIn(t) -> AiAction(kind = "open", title = title, service = "trains", query = stripped)
            Regex("\\b(hotel|homestay|stay)\\b").containsMatchIn(t) -> AiAction(kind = "open", title = title, service = "hotels", query = stripped)
            Regex("\\b(milk|vegetables?|fruits?|atta|bread|eggs|grocer(y|ies)|curd|paneer|rice|dal)\\b").containsMatchIn(t) ->
                AiAction(kind = "open", title = title, service = "blinkit", query = stripped)
            Regex("\\b(food|dinner|lunch|biryani|pizza)\\b").containsMatchIn(t) && Regex("\\b(order|get)\\b").containsMatchIn(t) ->
                AiAction(kind = "open", title = title, service = "swiggy", query = stripped)
            Regex("^\\s*(call|phone|ring)\\s+").containsMatchIn(t) -> AiAction(kind = "open", title = title, service = "call", payee = after("call", "phone", "ring"))
            Regex("\\b(whatsapp|message|text)\\s+\\w+").containsMatchIn(t) ->
                AiAction(kind = "open", title = title, service = "whatsapp", payee = after("whatsapp", "message", "text").substringBefore(' '))
            Regex("\\b(jobs?|apply)\\b").containsMatchIn(t) -> AiAction(kind = "open", title = title, service = "linkedin", query = stripped)
            Regex("\\b(buy|order|purchase)\\b").containsMatchIn(t) -> AiAction(kind = "open", title = title, service = "amazon", query = stripped)
            Regex("\\b(go to|visit|drop at|reach)\\b").containsMatchIn(t) ->
                AiAction(kind = "open", title = title, service = "maps", place = after("go to", "visit", "drop at", "reach"))
            Regex("\\b(pay|bill|recharge|renew|book|search|find|check)\\b").containsMatchIn(t) ->
                AiAction(kind = "open", title = title, service = "google", query = stripped)
            else -> null
        }
    }

    /**
     * Suggestions for the page the assistant was opened from. With [env], each one is offered only when it
     * would find something (uncategorised entries, suspected duplicates, open tasks, things due), and each uses
     * wording the assistant answers on the phone without a Gemini key.
     */
    fun suggestionsFor(page: String?, env: AppEnv? = null): List<String> {
        val p = page?.lowercase(Locale.ROOT) ?: return emptyList()
        val expenses = env?.data?.get("expenses").orEmpty()
        val uncategorised = expenses.count { com.familyhub.app.money.Recategorize.categorisable(it) && com.familyhub.app.money.Recategorize.uncategorised(it) }
        val dups = if (env != null) com.familyhub.app.money.Duplicates.find(expenses).size else 1
        val openTasks = env?.data?.get("tasks").orEmpty().count { it["isDeleted"] != true && it["isCompleted"] != true && (it["type"] as? String) != "SHOPPING" }
        fun ifAny(n: Int, s: String) = if (env == null || n > 0) s else null
        // Reminders and other free-form requests need the person's own Gemini key; data questions do not.
        val keyed = try { com.familyhub.app.core.ai.GeminiClient.isConfigured() } catch (_: Throwable) { false }
        return when (p) {
            "finance", "money", "expenses", "transactions" -> listOfNotNull(
                ifAny(uncategorised, "Categorise uncategorised transactions" + if (env != null) " ($uncategorised)" else ""),
                ifAny(dups, "Find duplicate transactions" + if (env != null) " ($dups suspected)" else ""),
                "How much did we spend this month?", "How much did we save this month?", "What's due this week?", "Merge refunds"
            )
            "money insights", "insights" -> listOfNotNull(
                "Who spent the most this month?", "How much did we save this month?",
                ifAny(uncategorised, "Categorise uncategorised transactions")
            )
            "health", "vitals", "workouts" -> {
                // Uses the person's own last weight, so one tap logs today's reading (parsed on the phone).
                val lastWeight = env?.data?.get("items").orEmpty()
                    .filter { it["type"] == "vital" && (it["kind"] as? String).orEmpty().startsWith("Weight") && it["isDeleted"] != true }
                    .maxByOrNull { (it["dateMillis"] as? Number)?.toLong() ?: 0L }?.get("value") as? String
                listOf(
                    if (lastWeight != null) "Weight $lastWeight kg today" else "Weight 63.5 kg and BP 118/76",
                    "Walked 30 minutes today",
                    "2 glasses of water"
                )
            }
            "periods", "period tracker" -> listOf("Start my periods from today")
            "medicines" -> {
                // Logging a dose is read on the phone (no key needed); a refill reminder needs the AI.
                val first = env?.data?.get("items").orEmpty()
                    .firstOrNull { it["type"] == "medicine" && it["isDeleted"] != true }?.get("title") as? String
                listOfNotNull(
                    first?.let { "Took $it today" },
                    if (keyed) "Remind me to refill my medicines next Saturday at 10 am" else null
                )
            }
            "documents" -> {
                // Documents that expire in the next 90 days: offer a renewal reminder 30 days ahead (or tomorrow).
                val now = System.currentTimeMillis()
                val day = 86_400_000L
                val fmt = java.text.SimpleDateFormat("d MMMM", Locale.ENGLISH)
                val soon = env?.data?.get("items").orEmpty()
                    .filter { it["type"] == "document" && it["isDeleted"] != true }
                    .mapNotNull { r -> (r["expiryDateMillis"] as? Number)?.toLong()?.takeIf { it in now..(now + 90 * day) }?.let { r to it } }
                    .sortedBy { it.second }.take(2)
                (if (keyed) soon else emptyList()).map { (r, exp) ->
                    "Remind me to renew my ${(r["title"] as? String).orEmpty().ifBlank { "document" }} on ${fmt.format(java.util.Date(maxOf(now + day, exp - 30 * day)))}"
                } + listOf("Find duplicates on this page")
            }
            "my profile", "profile" -> listOf("Add emergency contact Papa 98765 43210")
            "household", "tasks" -> listOfNotNull(ifAny(openTasks, "Help with my open tasks" + if (env != null) " ($openTasks)" else ""), "Find duplicate tasks")
            "shopping" -> listOf("Add milk, eggs and bread to shopping", "Find duplicate items")
            "calendar" -> listOf("What's due this week?", "Find duplicate events")
            "subscriptions", "cards", "loans", "credit cards", "loans & emi", "loans & emis", "due calendar", "accounts" ->
                listOf("What's due this week?", "Which EMIs are pending?", "When is my next card bill due?", "What's my bank balance?")
            else -> listOf("Find duplicates on this page")
        }
    }
}
