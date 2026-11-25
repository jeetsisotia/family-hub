package com.familyhub.app.work

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.familyhub.app.data.remote.PRIVATE_FLAG
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.health.StepService
import com.familyhub.app.health.StepTracker
import com.familyhub.app.money.SmsReader
import com.familyhub.app.ui.modules.AlertItem
import com.familyhub.app.data.model.FamilyMember
import com.familyhub.app.ui.modules.allAlerts
import com.familyhub.app.ui.modules.daysUntil
import com.familyhub.app.ui.modules.memberAlerts
import com.familyhub.app.ui.modules.profileAlerts
import com.familyhub.app.ui.modules.str
import com.familyhub.app.ui.money.amountOf
import com.familyhub.app.ui.money.budgetMonthStart
import com.familyhub.app.ui.money.categoryBudgets
import com.familyhub.app.ui.money.inRange
import com.familyhub.app.ui.money.isSpend
import com.familyhub.app.ui.money.monthStartDay
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Runs about once a day (and after a restart):
 * - posts one notification for anything due within three days or overdue by up to three days
 *   (bills, EMIs, renewals, expiries, events, tasks, period, card bills),
 * - sets the exact-time alarms for medicines and reminders,
 * - tells you about new bank SMS that are not in Finance yet.
 */
class ReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_REFRESH_ONLY = "refreshOnly"
        private const val NOTIFICATION_ID = 1001
        private const val SMS_NOTIFICATION_ID = 1002
        private const val BUDGET_NOTIFICATION_ID = 1003
        private const val PREFS = "family_hub_worker"
        private const val KEY_SMS_CHECKED = "smsCheckedAt"
        private val COLLECTIONS = listOf("items", "events", "expenses", "tasks", "trips")
    }

    override suspend fun doWork(): Result {
        val user = FirebaseAuth.getInstance().currentUser ?: return Result.success()
        val ctx = applicationContext
        return try {
            val db = FirebaseFirestore.getInstance()
            val profile = db.collection("users").document(user.uid).get().await()
            val familyId = profile.getString("activeFamilyId").orEmpty()

            val data = HashMap<String, List<Rec>>()
            for (c in COLLECTIONS) {
                val list = ArrayList<Rec>()
                if (familyId.isNotBlank()) {
                    db.collection("families").document(familyId).collection(c).get().await().documents.forEach { d ->
                        val m = HashMap<String, Any?>()
                        d.data?.let { m.putAll(it) }
                        m["id"] = d.id
                        list.add(m)
                    }
                }
                db.collection("users").document(user.uid).collection(c).get().await().documents.forEach { d ->
                    val m = HashMap<String, Any?>()
                    d.data?.let { m.putAll(it) }
                    m["id"] = d.id
                    m[PRIVATE_FLAG] = true
                    list.add(m)
                }
                data[c] = list
            }

            AlarmScheduler.scheduleAll(ctx, data["items"].orEmpty(), familyId, user.uid)
            if (StepTracker.isEnabled(ctx)) StepService.sync(ctx)

            if (!inputData.getBoolean(KEY_REFRESH_ONLY, false)) {
                val profileData: Rec = profile.data ?: emptyMap()
                val members = if (familyId.isBlank()) emptyList() else
                    db.collection("families").document(familyId).collection("members").get().await()
                        .documents.mapNotNull { d -> try { d.toObject(FamilyMember::class.java) } catch (e: Exception) { null } }
                val soon = (allAlerts(data) + profileAlerts(profileData) + memberAlerts(members, user.uid))
                    .filter { daysUntil(it.dateMillis) in -3..3 }
                    .sortedBy { it.dateMillis }
                if (soon.isNotEmpty()) notifyDue(soon)
                checkBankSms(data["expenses"].orEmpty())
                val familyBudget = if (familyId.isBlank()) 0.0 else
                    db.collection("families").document(familyId).get().await().getDouble("monthlyBudget") ?: 0.0
                checkBudgets(data["expenses"].orEmpty(), profileData, familyBudget)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun notifyDue(alerts: List<AlertItem>) {
        val ctx = applicationContext
        if (!Notifier.canPost(ctx)) return
        Notifier.ensureChannels(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val first = alerts.first()
        val lines = alerts.take(6).joinToString("\n") { "${it.title}: ${it.label}" }
        val notification = NotificationCompat.Builder(ctx, Notifier.CH_GENERAL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Family Hub: ${alerts.size} reminder(s)")
            .setContentText("${first.title}: ${first.label}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
            .setContentIntent(Notifier.openApp(ctx, "alerts", NOTIFICATION_ID))
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    /** Warns once a month when spending passes 80% and 100% of the family budget, and when a category budget is used up. */
    private fun checkBudgets(expenses: List<Rec>, profileData: Rec, familyBudget: Double) {
        val ctx = applicationContext
        if (!Notifier.canPost(ctx)) return
        val now = System.currentTimeMillis()
        val from = budgetMonthStart(now, monthStartDay(profileData), 0)
        val spends = expenses.filter { isSpend(it) && inRange(it, from, now) }
        val total = spends.sumOf { amountOf(it) }
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val messages = ArrayList<String>()
        if (familyBudget > 0) {
            for (level in listOf(100, 80)) {
                val key = "budget-$from-$level"
                if (total >= familyBudget * level / 100.0 && !prefs.getBoolean(key, false)) {
                    prefs.edit().putBoolean(key, true).putBoolean("budget-$from-80", true).apply()
                    messages.add(
                        if (level == 100) "Family budget used up: ₹%,.0f spent of ₹%,.0f.".format(total, familyBudget)
                        else "%d%% of the family budget used: ₹%,.0f of ₹%,.0f.".format((total / familyBudget * 100).toInt(), total, familyBudget)
                    )
                    break
                }
            }
        }
        for ((cat, limit) in categoryBudgets(profileData)) {
            val used = spends.filter { it.str("category").ifBlank { "Other" } == cat }.sumOf { amountOf(it) }
            val key = "cat-$from-$cat"
            if (used >= limit && !prefs.getBoolean(key, false)) {
                prefs.edit().putBoolean(key, true).apply()
                messages.add("$cat budget crossed: ₹%,.0f of ₹%,.0f.".format(used, limit))
            }
        }
        if (messages.isEmpty()) return
        Notifier.ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, Notifier.CH_GENERAL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Budget alert")
            .setContentText(messages.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(messages.joinToString("\n")))
            .setContentIntent(Notifier.openApp(ctx, "insights", BUDGET_NOTIFICATION_ID))
            .setAutoCancel(true)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(BUDGET_NOTIFICATION_ID, n)
    }

    /** Counts bank alerts received since the last check that are not in Finance yet. */
    private fun checkBankSms(expenses: List<Rec>) {
        val ctx = applicationContext
        if (!SmsReader.canRead(ctx) || !Notifier.canPost(ctx)) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val since = prefs.getLong(KEY_SMS_CHECKED, now - 2 * 86_400_000L)
        prefs.edit().putLong(KEY_SMS_CHECKED, now).apply()
        val known = expenses.mapNotNull { it["sourceKey"] as? String }.toSet()
        val fresh = SmsReader.read(ctx, since).filter { it.key !in known && !it.isTransfer }
        if (fresh.isEmpty()) return
        Notifier.ensureChannels(ctx)
        val spent = fresh.filter { !it.isCredit }.sumOf { it.amount }
        val text = if (spent > 0) "₹%,.0f spent in %d new transaction(s). Tap to review and add.".format(spent, fresh.size)
        else "${fresh.size} new transaction(s) found. Tap to review and add."
        val n = NotificationCompat.Builder(ctx, Notifier.CH_GENERAL)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("New bank transactions")
            .setContentText(text)
            .setContentIntent(Notifier.openApp(ctx, "import", SMS_NOTIFICATION_ID))
            .setAutoCancel(true)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(SMS_NOTIFICATION_ID, n)
    }
}
