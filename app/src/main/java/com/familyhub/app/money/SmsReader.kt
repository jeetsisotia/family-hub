package com.familyhub.app.money

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/** Reads bank and card alerts from the SMS inbox on this phone. Nothing leaves the phone except what you add. */
object SmsReader {
    fun canRead(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Bank senders use IDs like "VM-HDFCBK" or "AX-ICICIT"; messages from personal numbers are ignored. */
    private fun looksLikeBank(address: String): Boolean {
        val a = address.trim()
        if (a.isEmpty()) return false
        if (Regex("""^\+?[0-9 ]{7,}$""").matches(a)) return false
        return a.any { it.isLetter() }
    }

    fun read(ctx: Context, sinceMillis: Long, limit: Int = 2000): List<ParsedTxn> {
        if (!canRead(ctx)) return emptyList()
        val out = ArrayList<ParsedTxn>()
        try {
            ctx.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date"),
                "date >= ?",
                arrayOf(sinceMillis.toString()),
                "date DESC"
            )?.use { c ->
                val iAddr = c.getColumnIndex("address")
                val iBody = c.getColumnIndex("body")
                val iDate = c.getColumnIndex("date")
                var n = 0
                while (c.moveToNext() && n < limit) {
                    n++
                    val addr = if (iAddr >= 0) c.getString(iAddr).orEmpty() else ""
                    if (!looksLikeBank(addr)) continue
                    val body = if (iBody >= 0) c.getString(iBody).orEmpty() else ""
                    val date = if (iDate >= 0) c.getLong(iDate) else 0L
                    TxnParser.parse(body, addr, date, "SMS")?.let { out.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.w("SmsReader", "Could not read SMS", e)
        }
        return out.distinctBy { it.key }
    }
}
