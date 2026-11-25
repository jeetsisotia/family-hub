package com.familyhub.app.core.ai

import android.util.Base64
import android.util.Log
import com.familyhub.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class AiException(message: String) : Exception(message)

/**
 * Talks to Google's Gemini API directly. The key comes from the build (GitHub secret GEMINI_API_KEY).
 * It tries the newest "flash" model first and, if Google has retired a name, asks which models exist.
 */
object GeminiClient {
    private const val API = "https://generativelanguage.googleapis.com/v1beta"
    // The lite model answers fastest and has been the most available on the free tier.
    private val PREFERRED = listOf("gemini-flash-lite-latest", "gemini-flash-latest")

    @Volatile
    private var workingModel: String? = null

    data class Turn(val fromUser: Boolean, val text: String)

    private class ModelUnavailable(message: String) : Exception(message)

    private val apiKey: String by lazy {
        try {
            val enc = BuildConfig.GEMINI_KEY_ENC
            if (enc.isBlank()) "" else String(Base64.decode(enc, Base64.DEFAULT)).reversed().trim()
        } catch (e: Exception) {
            ""
        }
    }

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    /** Returns the model's text reply. With [json] = true the reply is a JSON object. */
    suspend fun generate(system: String, turns: List<Turn>, json: Boolean = false, temperature: Double = 0.4): String =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) throw AiException("The AI assistant is not set up in this version of the app.")
            val body = JSONObject().apply {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                val contents = JSONArray()
                for (t in turns) {
                    contents.put(
                        JSONObject()
                            .put("role", if (t.fromUser) "user" else "model")
                            .put("parts", JSONArray().put(JSONObject().put("text", t.text)))
                    )
                }
                put("contents", contents)
                val cfg = JSONObject().put("temperature", temperature)
                if (json) cfg.put("responseMimeType", "application/json")
                put("generationConfig", cfg)
            }.toString()

            val tried = LinkedHashSet<String>()
            var lastError: Exception? = null
            val first = listOfNotNull(workingModel) + PREFERRED
            for (m in first) {
                if (!tried.add(m)) continue
                try {
                    val out = call(m, body)
                    workingModel = m
                    return@withContext out
                } catch (e: ModelUnavailable) {
                    lastError = e
                }
            }
            for (m in discoverModels()) {
                if (!tried.add(m)) continue
                try {
                    val out = call(m, body)
                    workingModel = m
                    return@withContext out
                } catch (e: ModelUnavailable) {
                    lastError = e
                }
            }
            throw AiException(lastError?.message ?: "No AI model is available right now.")
        }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 45_000
            setRequestProperty("x-goog-api-key", apiKey)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

    private fun call(model: String, body: String): String {
        val conn = open("$API/models/$model:generateContent", "POST")
        try {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = try { JSONObject(text).optJSONObject("error")?.optString("message").orEmpty() } catch (e: Exception) { "" }
                Log.w("GeminiClient", "$model -> HTTP $code $msg")
                when {
                    code == 404 || (code == 400 && ("not found" in msg.lowercase() || "not supported" in msg.lowercase())) ->
                        throw ModelUnavailable("Model $model is not available.")
                    code == 429 && "limit: 0" in msg -> throw ModelUnavailable("Model $model has no free quota.")
                    code == 429 -> throw AiException("The free AI limit is used up for the moment. Please try again in a minute.")
                    code == 401 || code == 403 -> throw AiException("Google refused the AI key. Ask Jeet to check the Gemini key.")
                    code >= 500 -> throw ModelUnavailable("Google's AI service is busy. Please try again shortly.")
                    else -> throw AiException("AI error $code: ${msg.take(160)}")
                }
            }
            val root = JSONObject(text)
            val cands = root.optJSONArray("candidates")
            if (cands == null || cands.length() == 0) {
                val reason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                throw AiException(if (reason.isNotBlank()) "The AI could not answer that ($reason)." else "The AI gave no answer.")
            }
            val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.optBoolean("thought", false)) continue
                sb.append(p.optString("text"))
            }
            val out = sb.toString().trim()
            if (out.isEmpty()) throw AiException("The AI gave an empty answer. Please ask again.")
            return out
        } catch (e: UnknownHostException) {
            throw AiException("No internet connection.")
        } catch (e: SocketTimeoutException) {
            throw AiException("The AI took too long to answer. Please try again.")
        } finally {
            conn.disconnect()
        }
    }

    /** Flash models this key can use, newest first. */
    private fun discoverModels(): List<String> {
        return try {
            val conn = open("$API/models?pageSize=200", "GET")
            try {
                if (conn.responseCode !in 200..299) return emptyList()
                val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val arr = root.optJSONArray("models") ?: return emptyList()
                val names = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                    val name = m.optString("name").removePrefix("models/")
                    if ("generateContent" in methods && "flash" in name && "image" !in name && "tts" !in name &&
                        "audio" !in name && "live" !in name && "embedding" !in name
                    ) names.add(name)
                }
                names.sortedWith(compareBy<String> { "preview" in it || "exp" in it }.thenByDescending { it })
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
