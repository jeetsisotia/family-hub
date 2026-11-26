package com.familyhub.app.money

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log

/** One event from a calendar on this phone (Google calendars synced from Gmail show up here too). */
data class PhoneEvent(
    val key: String,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val calendar: String,
    val account: String,
    val location: String
)

/** Reads upcoming events from the phone's calendars, read-only. Nothing leaves the phone except what you add. */
object CalendarReader {
    fun canRead(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun read(ctx: Context, fromMillis: Long, toMillis: Long, limit: Int = 500): List<PhoneEvent> {
        if (!canRead(ctx)) return emptyList()
        val out = ArrayList<PhoneEvent>()
        try {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, fromMillis)
                ContentUris.appendId(it, toMillis)
            }.build()
            ctx.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Instances.OWNER_ACCOUNT,
                    CalendarContract.Instances.EVENT_LOCATION
                ),
                null, null,
                CalendarContract.Instances.BEGIN + " ASC"
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(0)
                    val title = c.getString(1).orEmpty()
                    if (title.isBlank()) continue
                    val begin = c.getLong(2)
                    out.add(
                        PhoneEvent(
                            key = "cal-$id-$begin",
                            title = title.take(80),
                            begin = begin,
                            end = c.getLong(3),
                            allDay = c.getInt(4) == 1,
                            calendar = c.getString(5).orEmpty(),
                            account = c.getString(6).orEmpty(),
                            location = c.getString(7).orEmpty().take(80)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("CalendarReader", "Could not read calendars", e)
        }
        // Holiday and birthday calendars repeat what is already known; keep the person's own events first.
        return out.filter { !it.calendar.contains("holiday", true) }
    }
}
