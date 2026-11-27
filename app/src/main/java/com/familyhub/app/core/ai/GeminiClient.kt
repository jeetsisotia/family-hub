package com.familyhub.app.core.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Calendar
import java.util.Locale

class AiException(message: String) : Exception(message)

/** An AI provider. Gemini is the only one today; others can be added behind the same calls. */
interface AiProvider {
    val id: String
    val displayName: String
    suspend fun chat(req: GeminiClient.ChatRequest): GeminiClient.Reply
    suspend fun listModels(key: String): List<String>
    /** Checks a key with one tiny request; returns the model that answered. */
    suspend fun test(key: String): String
}

/**
 * The signed-in person's AI connection: their own key (from [KeyVault]), chosen model, limits and usage, all kept
 * on this phone only. Nothing here is shared with other family members.
 */
object AiSession {
    @Volatile var appContext: Context? = null
        private set
    @Volatile var uid: String = ""
        private set
    @Volatile var key: String = ""
        private set

    fun start(ctx: Context, uid: String) {
        appContext = ctx.applicationContext
        if (this.uid == uid && key.isNotBlank()) return
        this.uid = uid
        key = KeyVault.load(ctx, uid).orEmpty()
    }

    fun setKey(ctx: Context, newKey: String?) {
        if (uid.isBlank()) return
        if (newKey.isNullOrBlank()) {
            KeyVault.clear(ctx, uid)
            key = ""
        } else {
            KeyVault.save(ctx, uid, newKey.trim())
            key = newKey.trim()
        }
    }

    fun signOut() {
        uid = ""
        key = ""
    }

    private fun prefs() = appContext?.getSharedPreferences("ai_settings_" + uid.hashCode().toString(16), Context.MODE_PRIVATE)

    /** Empty means automatic (the cheapest free model that works). */
    var model: String
        get() = prefs()?.getString("model", "").orEmpty()
        set(v) { prefs()?.edit()?.putString("model", v)?.apply() }

    var dailyLimit: Int
        get() = prefs()?.getInt("dailyLimit", 100) ?: 100
        set(v) { prefs()?.edit()?.putInt("dailyLimit", v.coerceIn(1, 5000))?.apply() }

    var monthlyLimit: Int
        get() = prefs()?.getInt("monthlyLimit", 1500) ?: 1500
        set(v) { prefs()?.edit()?.putInt("monthlyLimit", v.coerceIn(1, 100_000))?.apply() }

    /** Web search may cost money on a paid key; the person must turn it on. */
    var webSearchAllowed: Boolean
        get() = prefs()?.getBoolean("webSearch", false) ?: false
        set(v) { prefs()?.edit()?.putBoolean("webSearch", v)?.apply() }

    private fun dayKey(now: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        return String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    data class Usage(val today: Int, val month: Int, val tokensToday: Long, val tokensMonth: Long, val searchesMonth: Int)

    fun usage(now: Long = System.currentTimeMillis()): Usage {
        val p = prefs() ?: return Usage(0, 0, 0, 0, 0)
        val d = dayKey(now)
        val m = d.take(6)
        return Usage(
            p.getInt("req_$d", 0), p.getInt("req_$m", 0), p.getLong("tok_$d", 0), p.getLong("tok_$m", 0), p.getInt("web_$m", 0)
        )
    }

    fun record(tokens: Long, searched: Boolean, now: Long = System.currentTimeMillis()) {
        val p = prefs() ?: return
        val d = dayKey(now)
        val m = d.take(6)
        p.edit()
            .putInt("req_$d", p.getInt("req_$d", 0) + 1).putInt("req_$m", p.getInt("req_$m", 0) + 1)
            .putLong("tok_$d", p.getLong("tok_$d", 0) + tokens).putLong("tok_$m", p.getLong("tok_$m", 0) + tokens)
            .putInt("web_$m", p.getInt("web_$m", 0) + if (searched) 1 else 0)
            .apply()
    }

    /** Null if a request may go now; otherwise the message to show (limits set in AI settings). */
    fun limitMessage(now: Long = System.currentTimeMillis()): String? {
        val u = usage(now)
        return AiLimiter.usageLimit(u.today, u.month, dailyLimit, monthlyLimit) ?: AiLimiter.tryAcquire(now)
    }
}

/**
 * Google's Gemini API, called directly with the person's own key (never a key built into the app). Picks the chosen
 * model, or the cheapest working free model, and never switches to a paid-only model by itself.
 */
object GeminiClient : AiProvider {
    private const val API = "https://generativelanguage.googleapis.com/v1beta"
    // The lite model answers fastest and has been the most available on the free tier.
    private val PREFERRED = listOf("gemini-flash-lite-latest", "gemini-flash-latest")

    override val id = "gemini"
    override val displayName = "Google Gemini"

    @Volatile
    private var workingModel: String? = null

    data class Turn(val fromUser: Boolean, val text: String)

    /** One piece of a message: text, or a file (image, PDF or text) sent inline as base64. */
    data class Part(val text: String? = null, val mime: String? = null, val base64: String? = null)
    data class Msg(val fromUser: Boolean, val parts: List<Part>)
    data class Source(val title: String, val url: String)
    data class ChatRequest(
        val system: String,
        val messages: List<Msg>,
        val model: String = "",
        val webSearch: Boolean = false,
        val json: Boolean = false,
        val temperature: Double = 0.4
    )
    data class Reply(
        val text: String,
        val sources: List<Source>,
        val model: String,
        val tokens: Long,
        /** True when Google Search was actually used for this answer. */
        val searched: Boolean
    )

    private class ModelUnavailable(message: String) : Exception(message)

    fun isConfigured(): Boolean = AiSession.key.isNotBlank()

    /** Returns the model's text reply. With [json] = true the reply is a JSON object. */
    suspend fun generate(system: String, turns: List<Turn>, json: Boolean = false, temperature: Double = 0.4): String =
        chat(ChatRequest(system, turns.map { Msg(it.fromUser, listOf(Part(text = it.text))) }, AiSession.model, false, json, temperature)).text

    override suspend fun chat(req: ChatRequest): Reply = withContext(Dispatchers.IO) {
        val key = AiSession.key
        if (key.isBlank()) throw AiException("Add your own Gemini API key in AI settings to use the assistant.")
        AiSession.limitMessage()?.let { throw AiException(it) }
        val body = buildBody(req)

        val tried = LinkedHashSet<String>()
        var lastError: Exception? = null
        // A chosen model is used as is; "automatic" tries the free flash models.
        val order = if (req.model.isNotBlank()) listOf(req.model) else listOfNotNull(workingModel) + PREFERRED
        for (m in order) {
            if (!tried.add(m)) continue
            try {
                val r = call(key, m, body, req.webSearch)
                if (req.model.isBlank()) workingModel = m
                AiSession.record(r.tokens, r.searched)
                return@withContext r
            } catch (e: ModelUnavailable) {
                lastError = e
            }
        }
        if (req.model.isBlank()) {
            for (m in discoverModels(key)) {
                if (!tried.add(m)) continue
                try {
                    val r = call(key, m, body, req.webSearch)
                    workingModel = m
                    AiSession.record(r.tokens, r.searched)
                    return@withContext r
                } catch (e: ModelUnavailable) {
                    lastError = e
                }
            }
        }
        throw AiException(lastError?.message ?: "No AI model is available for this key right now.")
    }

    override suspend fun listModels(key: String): List<String> = withContext(Dispatchers.IO) { discoverModels(key, all = true) }

    override suspend fun test(key: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "Reply with OK")))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 5))
            .toString()
        var last: Exception? = null
        for (m in PREFERRED + discoverModels(key)) {
            try {
                call(key, m, body, false, allowEmpty = true)
                return@withContext m
            } catch (e: ModelUnavailable) {
                last = e
            }
        }
        throw AiException(last?.message ?: "The key works, but no free model answered.")
    }

    private fun open(key: String, url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("x-goog-api-key", key)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

    private fun call(key: String, model: String, body: String, webSearch: Boolean, allowEmpty: Boolean = false): Reply {
        val conn = open(key, "$API/models/$model:generateContent", "POST")
        try {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw errorFor(code, text, model, webSearch)
            }
            return parse(text, model, allowEmpty)
        } catch (e: UnknownHostException) {
            throw AiException("No internet connection.")
        } catch (e: SocketTimeoutException) {
            throw AiException("The AI took too long to answer. Please try again.")
        } finally {
            conn.disconnect()
        }
    }

    private fun errorFor(code: Int, text: String, model: String, webSearch: Boolean): Exception {
                val msg = try { JSONObject(text).optJSONObject("error")?.optString("message").orEmpty() } catch (e: Exception) { "" }
                Log.w("GeminiClient", "$model -> HTTP $code")
                val low = msg.lowercase()
                return when {
                    // Checked live (AI live check run #2): with the search tool, free keys can get a plain "quota
                    // exceeded" 429 while normal answers still work, so any search refusal is reported as such.
                    webSearch && (code == 429 || ((code == 400 || code == 403) && ("search" in low || "grounding" in low || "tool" in low))) ->
                        AiException("Web search is not available on this key's free quota right now (normal answers still work). Turn web search off and ask again, or try later.")
                    code == 404 || (code == 400 && ("not found" in low || "not supported" in low)) ->
                        ModelUnavailable("Model $model is not available for this key.")
                    code == 429 && "limit: 0" in msg -> ModelUnavailable("Model $model has no free quota on this key.")
                    code == 429 -> AiException("Google's limit for your key is used up for now. Please try again later.")
                    code == 400 && "api key" in low -> AiException("Google says this API key is not valid. Check it in AI settings.")
                    code == 401 || code == 403 -> AiException("Google refused this API key. Check it in AI settings (Google AI Studio).")
                    code >= 500 -> ModelUnavailable("Google's AI service is busy. Please try again shortly.")
                    else -> AiException("AI error $code: ${msg.take(160)}")
                }
    }

    /** Reads one full response (or the last streamed chunk set) into a reply. */
    private fun parse(text: String, model: String, allowEmpty: Boolean): Reply {
            val root = JSONObject(text)
            val cands = root.optJSONArray("candidates")
            val tokens = root.optJSONObject("usageMetadata")?.optLong("totalTokenCount", 0L) ?: 0L
            if (cands == null || cands.length() == 0) {
                if (allowEmpty) return Reply("", emptyList(), model, tokens, false)
                val reason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                throw AiException(if (reason.isNotBlank()) "The AI could not answer that ($reason)." else "The AI gave no answer.")
            }
            val cand = cands.getJSONObject(0)
            val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.optBoolean("thought", false)) continue
                sb.append(p.optString("text"))
            }
            // Sources come only from Google's grounding data, never from the model's own text.
            val sources = ArrayList<Source>()
            val gm = cand.optJSONObject("groundingMetadata")
            gm?.optJSONArray("groundingChunks")?.let { chunks ->
                for (i in 0 until chunks.length()) {
                    val web = chunks.getJSONObject(i).optJSONObject("web") ?: continue
                    val uri = web.optString("uri")
                    if (uri.startsWith("http")) sources.add(Source(web.optString("title").ifBlank { uri }, uri))
                }
            }
            val searched = gm != null && (sources.isNotEmpty() || (gm.optJSONArray("webSearchQueries")?.length() ?: 0) > 0)
            val out = sb.toString().trim()
            if (out.isEmpty() && !allowEmpty) throw AiException("The AI gave an empty answer. Please ask again.")
            return Reply(out, sources.distinctBy { it.url }, model, tokens, searched)
    }

    private fun buildBody(req: ChatRequest): String = JSONObject().apply {
        put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", req.system))))
        val contents = JSONArray()
        for (m in req.messages) {
            val parts = JSONArray()
            for (p in m.parts) {
                if (p.base64 != null && p.mime != null) {
                    parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", p.mime).put("data", p.base64)))
                } else if (!p.text.isNullOrEmpty()) parts.put(JSONObject().put("text", p.text))
            }
            if (parts.length() > 0) contents.put(JSONObject().put("role", if (m.fromUser) "user" else "model").put("parts", parts))
        }
        put("contents", contents)
        val cfg = JSONObject().put("temperature", req.temperature)
        if (req.json && !req.webSearch) cfg.put("responseMimeType", "application/json")
        put("generationConfig", cfg)
        if (req.webSearch) put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
    }.toString()

    /**
     * Like [chat], but the answer arrives piece by piece: [onText] gets the text so far. Cancelling the calling
     * coroutine (Stop) closes the connection at once.
     */
    suspend fun chatStream(req: ChatRequest, onText: (String) -> Unit): Reply = withContext(Dispatchers.IO) {
        val key = AiSession.key
        if (key.isBlank()) throw AiException("Add your own Gemini API key in AI settings to use the assistant.")
        AiSession.limitMessage()?.let { throw AiException(it) }
        val body = buildBody(req)
        val tried = LinkedHashSet<String>()
        var lastError: Exception? = null
        // The chosen model, or the free flash models first; other models are looked up only if those fail.
        val first = if (req.model.isNotBlank()) listOf(req.model) else listOfNotNull(workingModel) + PREFERRED
        for (round in 0..1) {
            val order = if (round == 0) first else if (req.model.isBlank()) discoverModels(key) else emptyList()
            for (m in order) {
                if (!tried.add(m)) continue
                try {
                    val r = stream(key, m, body, req.webSearch, onText)
                    if (req.model.isBlank()) workingModel = m
                    AiSession.record(r.tokens, r.searched)
                    return@withContext r
                } catch (e: ModelUnavailable) {
                    lastError = e
                }
            }
        }
        throw AiException(lastError?.message ?: "No AI model is available for this key right now.")
    }

    private suspend fun stream(key: String, model: String, body: String, webSearch: Boolean, onText: (String) -> Unit): Reply {
        val conn = open(key, "$API/models/$model:streamGenerateContent?alt=sse", "POST")
        val ctx = kotlin.coroutines.coroutineContext
        val stop = ctx[kotlinx.coroutines.Job]?.invokeOnCompletion { try { conn.disconnect() } catch (_: Exception) { } }
        try {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw errorFor(code, conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty(), model, webSearch)
            }
            val sb = StringBuilder()
            val sources = ArrayList<Source>()
            var tokens = 0L
            var searched = false
            var blocked = ""
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    ctx.ensureActive()
                    if (!line.startsWith("data:")) continue
                    val chunk = try { JSONObject(line.removePrefix("data:").trim()) } catch (e: Exception) { continue }
                    chunk.optJSONObject("usageMetadata")?.optLong("totalTokenCount", 0L)?.let { if (it > 0) tokens = it }
                    chunk.optJSONObject("promptFeedback")?.optString("blockReason")?.let { if (it.isNotBlank()) blocked = it }
                    val cand = chunk.optJSONArray("candidates")?.optJSONObject(0) ?: continue
                    val parts = cand.optJSONObject("content")?.optJSONArray("parts")
                    if (parts != null) for (i in 0 until parts.length()) {
                        val p = parts.getJSONObject(i)
                        if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
                    }
                    cand.optJSONObject("groundingMetadata")?.let { gm ->
                        gm.optJSONArray("groundingChunks")?.let { ch ->
                            for (i in 0 until ch.length()) {
                                val web = ch.getJSONObject(i).optJSONObject("web") ?: continue
                                val uri = web.optString("uri")
                                if (uri.startsWith("http")) sources.add(Source(web.optString("title").ifBlank { uri }, uri))
                            }
                        }
                        if (sources.isNotEmpty() || (gm.optJSONArray("webSearchQueries")?.length() ?: 0) > 0) searched = true
                    }
                    if (sb.isNotEmpty()) onText(sb.toString())
                }
            }
            val out = sb.toString().trim()
            if (out.isEmpty()) throw AiException(if (blocked.isNotBlank()) "The AI could not answer that ($blocked)." else "The AI gave an empty answer. Please ask again.")
            return Reply(out, sources.distinctBy { it.url }, model, tokens, searched)
        } catch (e: java.io.IOException) {
            ctx.ensureActive()
            if (e is UnknownHostException) throw AiException("No internet connection.")
            if (e is SocketTimeoutException) throw AiException("The AI took too long to answer. Please try again.")
            throw AiException("The connection was interrupted. Please try again.")
        } finally {
            stop?.dispose()
            conn.disconnect()
        }
    }

    /** Text models this key can use, cheapest flash models first; with [all], every text model. */
    private fun discoverModels(key: String, all: Boolean = false): List<String> {
        return try {
            val conn = open(key, "$API/models?pageSize=200", "GET")
            try {
                if (conn.responseCode !in 200..299) return emptyList()
                val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val arr = root.optJSONArray("models") ?: return emptyList()
                val names = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val name = m.optString("name").removePrefix("models/")
                    val methods = m.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                    if ("generateContent" !in methods) continue
                    if (!name.startsWith("gemini")) continue
                    if (!all && "flash" !in name) continue
                    if (listOf("image", "tts", "audio", "live", "embedding").any { it in name }) continue
                    names.add(name)
                }
                names.sortedWith(compareBy<String> { if ("lite" in it) 0 else if ("flash" in it) 1 else 2 }.thenByDescending { it })
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
