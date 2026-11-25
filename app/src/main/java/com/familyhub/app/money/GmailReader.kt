package com.familyhub.app.money

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.text.Html
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class GmailAuthException(message: String) : Exception(message)

/**
 * Reads bank alert emails with read-only Gmail access that the person grants on the Google screen.
 * Only the subject and the short preview of matching emails are read; nothing is stored except what is imported.
 */
object GmailReader {
    private const val SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
    private const val API = "https://gmail.googleapis.com/gmail/v1/users/me"

    sealed class Auth {
        data class Token(val token: String) : Auth()
        data class NeedsConsent(val result: AuthorizationResult) : Auth()
    }

    private fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Returns a token straight away if access was granted before, otherwise the consent screen to show. */
    suspend fun authorize(activity: Activity): Auth {
        val result = Identity.getAuthorizationClient(activity).authorize(request()).await()
        val token = result.accessToken
        return if (result.hasResolution() || token.isNullOrBlank()) Auth.NeedsConsent(result) else Auth.Token(token)
    }

    fun tokenFromConsent(context: Context, data: Intent?): String? = try {
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken
    } catch (e: Exception) {
        null
    }

    private fun get(url: String, token: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        try {
            val code = conn.responseCode
            if (code == 401 || code == 403) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw GmailAuthException(
                    if ("SERVICE_DISABLED" in err || "has not been used" in err) "Gmail access is not switched on for this app yet."
                    else "Gmail access expired. Connect Gmail again."
                )
            }
            if (code !in 200..299) throw Exception("Gmail returned error $code")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun decode(s: String): String = Html.fromHtml(s).toString()

    /** Bank and card alert emails from the last [days] days, newest first. */
    suspend fun fetch(token: String, days: Int, max: Int = 120): List<ParsedTxn> = withContext(Dispatchers.IO) {
        val q = "newer_than:${days}d -category:promotions " +
            "(debited OR credited OR spent OR \"transaction alert\" OR \"payment received\" OR \"txn\" OR \"upi\")"
        val list = get("$API/messages?maxResults=$max&q=" + URLEncoder.encode(q, "UTF-8"), token)
        val msgs = list.optJSONArray("messages") ?: return@withContext emptyList()
        val out = ArrayList<ParsedTxn>()
        for (i in 0 until msgs.length()) {
            val id = msgs.getJSONObject(i).optString("id")
            if (id.isBlank()) continue
            val m = try {
                get("$API/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject", token)
            } catch (e: GmailAuthException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            val headers = m.optJSONObject("payload")?.optJSONArray("headers")
            var from = ""
            var subject = ""
            if (headers != null) {
                for (h in 0 until headers.length()) {
                    val o = headers.getJSONObject(h)
                    when (o.optString("name").lowercase()) {
                        "from" -> from = o.optString("value")
                        "subject" -> subject = o.optString("value")
                    }
                }
            }
            val sender = from.substringAfter('<', from).substringBefore('>').substringAfter('@', from)
            val text = decode(subject) + ". " + decode(m.optString("snippet"))
            val date = m.optString("internalDate").toLongOrNull() ?: continue
            TxnParser.parse(text, sender, date, "Email")?.let { out.add(it.copy(key = "email-$id")) }
        }
        out
    }
}
