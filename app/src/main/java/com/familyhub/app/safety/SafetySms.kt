package com.familyhub.app.safety

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import java.util.UUID

/**
 * Sends alert SMS from this phone's own SIM (no SMS gateway, no service). Carrier charges may apply.
 * Every attempt is written to the alert log with its result: Sent, Delivered (if the network reports it),
 * Failed or Not sent (with the reason).
 */
object SafetySms {
    const val ACTION_SENT = "com.familyhub.app.safety.SMS_SENT"
    const val ACTION_DELIVERED = "com.familyhub.app.safety.SMS_DELIVERED"
    const val EXTRA_ID = "log_id"

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** Null when SMS can be sent now; otherwise the reason it cannot. */
    fun problem(ctx: Context): String? {
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return "this device cannot send SMS"
        if (!hasPermission(ctx)) return "SMS permission is off"
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return "no phone service"
        return when (tm.simState) {
            TelephonyManager.SIM_STATE_READY -> null
            TelephonyManager.SIM_STATE_ABSENT -> "no SIM card"
            TelephonyManager.SIM_STATE_PIN_REQUIRED, TelephonyManager.SIM_STATE_PUK_REQUIRED -> "the SIM is locked"
            else -> "the SIM is not ready"
        }
    }

    @Suppress("DEPRECATION")
    private fun manager(ctx: Context): SmsManager? = try {
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
    } catch (e: Exception) { null }

    /**
     * Sends [text] to each contact. [kind] is low_battery, sos, arrival or test. [automatic] alerts obey the
     * hourly limit. [simulate] writes the log without sending anything (used by "Send a test").
     * Returns how many messages were handed to the phone's SMS app.
     */
    fun send(
        ctx: Context,
        kind: String,
        contacts: List<EmergencyContact>,
        text: String,
        automatic: Boolean,
        simulate: Boolean = false
    ): Int {
        val now = System.currentTimeMillis()
        val targets = contacts.mapNotNull { c -> SafetyLogic.cleanPhone(c.phone)?.let { c to it } }.distinctBy { it.second }
        if (targets.isEmpty()) return 0
        if (simulate) {
            targets.forEach { (c, phone) ->
                SafetyStore.addLog(ctx, AlertLogEntry(UUID.randomUUID().toString(), now, kind, c.name, phone, "Test only, not sent"))
            }
            return 0
        }
        val blocked = problem(ctx)
            ?: if (automatic && !SafetyLogic.canSendAutoSms(SafetyStore.autoSmsTimes(ctx), now, targets.size)) "hourly limit reached" else null
        if (blocked != null) {
            targets.forEach { (c, phone) ->
                SafetyStore.addLog(ctx, AlertLogEntry(UUID.randomUUID().toString(), now, kind, c.name, phone, "Not sent: $blocked"))
            }
            return 0
        }
        val sm = manager(ctx)
        var handed = 0
        val sentTimes = ArrayList<Long>()
        targets.forEach { (c, phone) ->
            val id = UUID.randomUUID().toString()
            SafetyStore.addLog(ctx, AlertLogEntry(id, now, kind, c.name, phone, "Sending"))
            try {
                if (sm == null) throw IllegalStateException("no SMS service")
                val parts = sm.divideMessage(text)
                val sent = statusIntent(ctx, ACTION_SENT, id)
                val delivered = statusIntent(ctx, ACTION_DELIVERED, id)
                val sentList = ArrayList<PendingIntent>()
                val deliveredList = ArrayList<PendingIntent>()
                repeat(parts.size) {
                    sentList.add(sent)
                    deliveredList.add(delivered)
                }
                sm.sendMultipartTextMessage(phone, null, parts, sentList, deliveredList)
                handed++
                sentTimes.add(now)
            } catch (e: Exception) {
                Log.w("SafetySms", "SMS failed", e)
                SafetyStore.updateLog(ctx, id, "Failed: " + (e.message ?: "could not send").take(60))
            }
        }
        if (automatic && sentTimes.isNotEmpty()) SafetyStore.addAutoSmsTimes(ctx, sentTimes)
        return handed
    }

    private fun statusIntent(ctx: Context, action: String, id: String): PendingIntent {
        val i = Intent(ctx, SmsStatusReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id)
        val code = (action + id).hashCode()
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * The newest place known to this phone: a fresh last-known fix when location is allowed, otherwise the
     * place saved the last time the app saw one. Only used when location features are on.
     */
    @SuppressLint("MissingPermission")
    fun bestLocation(ctx: Context): SeenLocation? {
        if (!SafetyStore.locationOn(ctx)) return null
        val saved = SafetyStore.lastLocation(ctx)
        val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return saved
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return saved
        val fresh: Location? = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { pr -> try { lm.getLastKnownLocation(pr) } catch (e: Exception) { null } }
            .maxByOrNull { it.time }
        if (fresh != null) {
            val s = SeenLocation(fresh.latitude, fresh.longitude, if (fresh.hasAccuracy()) fresh.accuracy else 0f, fresh.time)
            SafetyStore.saveLocation(ctx, s)
            if (saved == null || s.at > saved.at) return s
        }
        return saved
    }
}

/** Records whether each alert SMS was sent and, if the network reports it, delivered. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(SafetySms.EXTRA_ID) ?: return
        val status = when (intent.action) {
            SafetySms.ACTION_SENT -> when (resultCode) {
                Activity.RESULT_OK -> "Sent"
                SmsManager.RESULT_ERROR_NO_SERVICE -> "Failed: no signal"
                SmsManager.RESULT_ERROR_RADIO_OFF -> "Failed: airplane mode or radio off"
                SmsManager.RESULT_ERROR_NULL_PDU -> "Failed: message error"
                else -> "Failed: the network refused it"
            }
            SafetySms.ACTION_DELIVERED -> if (resultCode == Activity.RESULT_OK) "Delivered" else return
            else -> return
        }
        SafetyStore.updateLog(ctx, id, status)
    }
}
