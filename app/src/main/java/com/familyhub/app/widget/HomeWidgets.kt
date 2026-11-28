package com.familyhub.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.familyhub.app.MainActivity
import com.familyhub.app.R
import com.familyhub.app.ui.modules.AlertItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home screen widgets. The app writes a small summary on the phone whenever its data changes; the widgets only
 * read that summary, so they work without signing in again and never fetch anything themselves.
 * Privacy: health items are never shown, and "Private widgets" (Settings) shows counts only.
 */
object HomeWidgets {
    private const val PREFS = "home_widgets"
    private val HEALTH = setOf("medicines", "vitals", "health", "period", "health_hub", "food", "workouts")

    fun isPrivate(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("private", false)
    fun setPrivate(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("private", on).apply()
        refresh(ctx)
    }

    /** Lines for the family overview: overdue first, then what is due soonest (health excluded). */
    fun overviewLines(alerts: List<AlertItem>, now: Long, max: Int = 4): Pair<String, List<String>> {
        val list = alerts.filter { it.route !in HEALTH }.sortedWith(compareBy<AlertItem>({ !it.overdue }, { it.dateMillis }))
        val overdue = list.count { it.overdue }
        val week = list.count { !it.overdue && it.dateMillis - now <= 7 * 86_400_000L }
        val fmt = SimpleDateFormat("d MMM", Locale.getDefault())
        val head = when {
            list.isEmpty() -> "Nothing due. All clear."
            overdue > 0 -> "$overdue overdue · $week due this week"
            else -> "$week due this week"
        }
        return head to list.take(max).map { a -> (if (a.overdue) "⚠ " else "• ") + a.title.take(40) + " · " + fmt.format(Date(a.dateMillis)) }
    }

    /** Shopping items still to buy (not bought, not marked unavailable). */
    fun shoppingLines(items: List<Map<String, Any?>>, max: Int = 6): Pair<String, List<String>> {
        val pending = items.filter { com.familyhub.app.family.ShoppingLists.state(it) == com.familyhub.app.family.ShoppingLists.State.PENDING }
        val head = if (pending.isEmpty()) "Nothing to buy" else "${pending.size} to buy"
        return head to pending.take(max).map { r ->
            val q = (r["quantity"] as? String).orEmpty().ifBlank { (r["quantity"] as? Number)?.toString().orEmpty() }
            "• " + (r["title"] as? String).orEmpty().take(36) + if (q.isNotBlank()) " ($q)" else ""
        }
    }

    /** Called by the app when its data changes. */
    fun publish(ctx: Context, alerts: List<AlertItem>, data: Map<String, List<Map<String, Any?>>>) {
        val (oh, ol) = overviewLines(alerts, System.currentTimeMillis())
        val shop = com.familyhub.app.ui.modules.ShoppingModule
        val (sh, sl) = shoppingLines(com.familyhub.app.ui.modules.recordsFor(shop, data[shop.collection].orEmpty()))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("overviewHead", oh).putString("overview", ol.joinToString("\n"))
            .putString("shopHead", sh).putString("shop", sl.joinToString("\n"))
            .putLong("at", System.currentTimeMillis()).apply()
        refresh(ctx)
    }

    fun refresh(ctx: Context) {
        val m = AppWidgetManager.getInstance(ctx)
        m.getAppWidgetIds(ComponentName(ctx, FamilyOverviewWidget::class.java)).forEach { render(ctx, m, it, shopping = false) }
        m.getAppWidgetIds(ComponentName(ctx, ShoppingWidget::class.java)).forEach { render(ctx, m, it, shopping = true) }
    }

    fun render(ctx: Context, m: AppWidgetManager, id: Int, shopping: Boolean) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val private = p.getBoolean("private", false)
        val head = p.getString(if (shopping) "shopHead" else "overviewHead", null) ?: "Open Family Hub to set up"
        val body = if (private) "" else p.getString(if (shopping) "shop" else "overview", "").orEmpty()
        val v = RemoteViews(ctx.packageName, R.layout.widget_family)
        v.setTextViewText(R.id.widget_title, if (shopping) "Shopping" else "Family Hub")
        v.setTextViewText(R.id.widget_head, head)
        v.setTextViewText(R.id.widget_body, body)
        v.setViewVisibility(R.id.widget_body, if (body.isBlank()) View.GONE else View.VISIBLE)
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_ROUTE, if (shopping) "shopping" else "alerts")
        }
        v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(ctx, if (shopping) 5102 else 5101, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        m.updateAppWidget(id, v)
    }
}

class FamilyOverviewWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) { ids.forEach { HomeWidgets.render(ctx, m, it, shopping = false) } }
}

class ShoppingWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) { ids.forEach { HomeWidgets.render(ctx, m, it, shopping = true) } }
}
