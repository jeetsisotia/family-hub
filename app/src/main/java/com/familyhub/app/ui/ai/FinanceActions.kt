package com.familyhub.app.ui.ai

import android.content.Context
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.money.FinanceMonth
import com.familyhub.app.money.Ledger
import com.familyhub.app.money.Recategorize
import com.familyhub.app.money.TxnFinder
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.modules.DAY_MS
import com.familyhub.app.ui.modules.lng
import com.familyhub.app.ui.modules.startOfDay
import com.familyhub.app.ui.modules.str
import com.familyhub.app.ui.money.CategoryAudit
import com.familyhub.app.ui.money.canEditMoney
import com.familyhub.app.ui.money.categoriesFor
import com.familyhub.app.ui.money.inr
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The assistant's Finance changes, shared by both AI screens (FamilyHub actions and the Gemini chat).
 *
 * - The transaction is found on the phone among the entries the person can see in Finance (TxnFinder), never taken
 *   from the AI model. Several matches are all shown, so the person picks the right one.
 * - Nothing changes until the person taps Change on a card that shows the real entry and the new category.
 * - The write goes through the same code as Finance → Transactions (Recategorize plan, CategoryAudit log, one
 *   Firestore update of category/subcategory/updatedAt). Permission and the entry are checked again just before.
 * - The card says "Changed" only after the server has the write and a fresh read from the server shows the new
 *   category. Undo uses the same log and is confirmed the same way.
 * - Works without web search and without the AI: the request is read on the phone.
 */
object FinanceActions {
    const val KIND = "recategorize"
    private const val MAX_CARDS = 8
    private const val SAVE_TIMEOUT_MS = 20_000L

    private val dayFmt = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)

    private fun kindOf(r: Rec) = if (Ledger.type(r) == "INCOME") FinanceMonth.Kind.INCOME else FinanceMonth.Kind.EXPENSE

    /** Every category the person can pick (built-in and the family's own, spending and income). */
    fun allCategories(env: AppEnv): List<String> {
        val items = env.data["items"].orEmpty()
        return (categoriesFor(items, FinanceMonth.Kind.EXPENSE) + categoriesFor(items, FinanceMonth.Kind.INCOME))
            .distinctBy { FinanceMonth.normName(it) }
    }

    /** Reads "change the category of … to …" on the phone. Null if the text is not a category change. */
    fun handle(text: String, env: AppEnv): AppCommands.Result? {
        val req = TxnFinder.parse(text, allCategories(env))
        if (req == null) {
            if (!TxnFinder.isIntent(text)) return null
            return AppCommands.Result(
                "I can change a transaction's category. Tell me which one and the new category, for example " +
                    "\"change the category of Swiggy ₹645 to Shopping\".", emptyList()
            )
        }
        return resolve(env, req, null)
    }

    /** Turns an AI-suggested change (kind "recategorize") into cards built from the real entries. */
    fun fromAi(env: AppEnv, a: AiAction): AppCommands.Result {
        val q = a.query.ifBlank { a.payee }.ifBlank { a.title }
        val req = TxnFinder.parse("change the category of $q to ${a.category}", allCategories(env))
            ?: TxnFinder.Request(q.lowercase(Locale.ROOT), a.category)
        return resolve(env, req.copy(amount = a.amount ?: req.amount), a.dateMillis)
    }

    private fun resolve(env: AppEnv, req: TxnFinder.Request, onDay: Long?): AppCommands.Result {
        if (!canEditMoney(env)) {
            return AppCommands.Result("Finance editing is turned off for you, so I can't change categories. Ask a family admin.", emptyList())
        }
        val cats = allCategories(env)
        val target = TxnFinder.resolveCategory(req.category, cats)
            ?: return AppCommands.Result(
                "There's no \"${req.category.trim()}\" category in Finance. Pick one of: " + cats.joinToString(", ") +
                    ". You can also add a new one in Finance → Categories.", emptyList()
            )
        val all = env.data["expenses"].orEmpty()
        var found = TxnFinder.match(all, req)
        if (onDay != null) found = found.filter { r -> r.lng("dateMillis")?.let { kotlin.math.abs(startOfDay(it) - startOfDay(onDay)) <= DAY_MS } == true }
        val what = listOfNotNull(req.query.takeIf { it.isNotBlank() }?.let { "\"$it\"" }, req.amount?.let { inr(it) }).joinToString(" ")
        if (found.isEmpty()) {
            val seen = all.count { Recategorize.categorisable(it) }
            return AppCommands.Result(
                "I couldn't find a transaction matching $what in Finance (I looked through $seen entries you can see). " +
                    "Nothing was changed. Try the name as it shows in Transactions, or add the amount, for example " +
                    "\"change the category of Swiggy ₹645 to Shopping\".", emptyList()
            )
        }
        val movable = found.filter { FinanceMonth.normName(FinanceMonth.categoryOf(it)) != FinanceMonth.normName(target) }
        if (movable.isEmpty()) {
            return AppCommands.Result(
                if (found.size == 1) "That transaction (${label(found[0])}) is already in $target. Nothing to change."
                else "All ${found.size} transactions matching $what are already in $target. Nothing to change.", emptyList()
            )
        }
        val cards = movable.take(MAX_CARDS).map { card(it, target) }
        val reply = if (movable.size == 1) {
            "Found it in Finance. Check the details and tap Change to move it to $target:"
        } else {
            "I found ${movable.size} transactions matching $what. Which one do you mean? Tap Change on the right one" +
                (if (movable.size > MAX_CARDS) " (showing the newest $MAX_CARDS; add the amount or date to narrow it)" else "") + ":"
        }
        return AppCommands.Result(reply, cards)
    }

    private fun label(r: Rec) = r.str("title").ifBlank { "untitled" } + ", " + inr(Ledger.amount(r))

    private fun card(r: Rec, target: String): AiAction {
        val old = FinanceMonth.categoryOf(r)
        val details = listOfNotNull(
            r.lng("dateMillis")?.let { dayFmt.format(Date(it)) },
            r.str("paymentMethod").takeIf { it.isNotBlank() && !it.equals("None", true) },
            Recategorize.sourceOf(r).takeIf { it.isNotBlank() },
            r.str("memberName").takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        return AiAction(
            kind = KIND,
            title = r.str("title").ifBlank { "(untitled)" },
            amount = Ledger.amount(r),
            category = old,
            value = target,
            dateText = details,
            collection = "expenses",
            refs = listOf(r)
        )
    }

    /** Replaces category changes suggested by the AI with cards for the real entries (or says none were found). */
    fun resolveInReply(env: AppEnv, m: ChatMsg): ChatMsg {
        val asked = m.actions.filter { it.kind == KIND }
        if (asked.isEmpty()) return m
        val others = m.actions.filter { it.kind != KIND }
        val results = asked.map { fromAi(env, it) }
        val text = (listOf(if (others.isNotEmpty()) m.text else "") + results.map { it.reply }).filter { it.isNotBlank() }.joinToString("\n\n")
        return m.copy(text = text, actions = others + results.flatMap { it.actions })
    }

    /** "Category: Dining → Shopping" line on the card. */
    fun changeLine(a: AiAction): String = "Category: ${a.category} → ${a.value}"

    /**
     * Applies a confirmed change. Returns the audit entry used for Undo. Throws with a plain message if it was not
     * saved, or was not confirmed by the server; the card then shows the message and never "Changed".
     */
    suspend fun apply(env: AppEnv, ctx: Context, a: AiAction): Long {
        if (!canEditMoney(env)) throw IllegalStateException("Finance editing is turned off for you. Ask a family admin.")
        val shown = a.refs.firstOrNull() ?: throw IllegalStateException("No transaction was chosen.")
        val id = shown.str("id")
        // Checked again just before writing, against the entries as they are now.
        val current = env.data["expenses"].orEmpty().firstOrNull { it.str("id") == id }
            ?: throw IllegalStateException("This transaction is no longer in Finance (deleted or moved). Nothing was changed.")
        if (!Recategorize.categorisable(current)) throw IllegalStateException("This entry can't have a category (deleted, a transfer or a card payment).")
        val nowCat = FinanceMonth.categoryOf(current)
        if (FinanceMonth.normName(nowCat) == FinanceMonth.normName(a.value)) throw IllegalStateException("It is already in ${a.value}.")
        if (FinanceMonth.normName(nowCat) != FinanceMonth.normName(a.category)) {
            throw IllegalStateException("Its category changed to $nowCat since I showed it. Nothing was changed; ask again.")
        }
        val plan = Recategorize.forIds(listOf(current), setOf(id), a.value)
        if (plan.count != 1) throw IllegalStateException("Nothing to change.")
        val at = CategoryAudit.add(ctx, "AI: ${a.title} → ${a.value}", plan)
        try {
            withTimeout(SAVE_TIMEOUT_MS) { env.repo.setFieldsMany(env.familyId, env.uid, "expenses", plan.changes.map { it.rec to it.fields }) }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Not confirmed: the server could not be reached. The change is waiting on this phone and will sync when you are online; check Finance before trying again.")
        } catch (e: Exception) {
            CategoryAudit.markUndone(ctx, at)
            throw IllegalStateException("Not saved: " + (e.message ?: "the server refused the change").take(120))
        }
        // A fresh read from the server, not the phone's copy, must show the new category.
        val saved = try {
            withTimeout(SAVE_TIMEOUT_MS) { env.repo.fetchOneFromServer(env.familyId, env.uid, "expenses", current) }
        } catch (e: Exception) {
            throw IllegalStateException("Saved, but the server could not be read back to confirm it. Check Finance.")
        }
        if (saved == null || FinanceMonth.normName(FinanceMonth.categoryOf(saved)) != FinanceMonth.normName(a.value)) {
            throw IllegalStateException("The server did not confirm the change. Check Finance.")
        }
        return at
    }

    /** Undo of a confirmed change: puts the old category back (only if nobody changed it again since), confirmed by the server. */
    suspend fun undo(env: AppEnv, ctx: Context, a: AiAction) {
        val at = a.undoAt ?: throw IllegalStateException("Nothing to undo.")
        val entry = CategoryAudit.list(ctx).firstOrNull { it.at == at } ?: throw IllegalStateException("The change log was cleared; change it back in Finance.")
        if (entry.undone) throw IllegalStateException("Already undone.")
        val changes = CategoryAudit.undoChanges(entry, env.data["expenses"].orEmpty())
        if (changes.isEmpty()) throw IllegalStateException("Nothing to undo: the transaction was changed again since.")
        try {
            withTimeout(SAVE_TIMEOUT_MS) { env.repo.setFieldsMany(env.familyId, env.uid, "expenses", changes) }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Undo not confirmed: the server could not be reached. Check Finance.")
        }
        CategoryAudit.markUndone(ctx, at)
        val (rec, fields) = changes.first()
        val back = (fields["category"] as? String).orEmpty()
        val saved = withTimeout(SAVE_TIMEOUT_MS) { env.repo.fetchOneFromServer(env.familyId, env.uid, "expenses", rec) }
        if (saved == null || FinanceMonth.normName((saved["category"] as? String).orEmpty()) != FinanceMonth.normName(back)) {
            throw IllegalStateException("The server did not confirm the undo. Check Finance.")
        }
    }

    /** The message shown under the card once the server confirmed the change. */
    fun resultText(a: AiAction): String =
        "Done: \"${a.title}\" (${a.amount?.let { inr(it) } ?: ""}) is now in ${a.value} (it was ${a.category}). " +
            "The server confirmed it, and Finance shows the new category. Tap Undo to put it back."
}
