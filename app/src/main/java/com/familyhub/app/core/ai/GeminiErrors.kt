package com.familyhub.app.core.ai

/**
 * Turns a Gemini API error into a plain message that says which kind of problem it is, so the person can tell an
 * invalid key from a network problem from Google's free-tier limits. Plain Kotlin, tested in
 * tools/ai-test. Nothing here logs or repeats the key.
 */
object GeminiErrors {
    enum class Kind { INVALID_KEY, KEY_REFUSED, API_DISABLED, RATE_LIMIT, DAILY_QUOTA, NO_FREE_QUOTA, WEB_SEARCH_QUOTA, MODEL_MISSING, PROVIDER, OTHER }

    data class Result(val kind: Kind, val message: String) {
        /** Another free model may still work (the caller tries the next one). */
        val tryNextModel: Boolean get() = kind == Kind.MODEL_MISSING || kind == Kind.NO_FREE_QUOTA || kind == Kind.PROVIDER
    }

    const val NETWORK = "Network problem: could not reach Google (no internet connection). Check Wi-Fi or mobile data and try again."
    const val TIMEOUT = "Network problem: Google did not answer in time. Check your connection and try again."
    const val INTERRUPTED = "Network problem: the connection was interrupted. Please try again."

    /** "42s" from Google's RetryInfo, if present. */
    private fun retryAfter(body: String): String? = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"").find(body)?.groupValues?.get(1)

    fun classify(code: Int, body: String, model: String, webSearch: Boolean): Result {
        fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.groupValues?.get(1).orEmpty()
        val msg = field("message")
        val status = field("status")
        val low = (msg + " " + body).lowercase()
        val wait = retryAfter(body)?.let { " Google says to wait about $it seconds." }.orEmpty()
        return when {
            // Checked live (AI live check run #2): with the search tool, free keys can get a plain "quota exceeded" 429
            // while normal answers still work, so any search refusal is reported as such.
            webSearch && (code == 429 || ((code == 400 || code == 403) && ("search" in low || "grounding" in low || "tool" in low))) ->
                Result(Kind.WEB_SEARCH_QUOTA, "Web search limit: web search is not available on this key's free quota right now (normal answers still work). Turn web search off and ask again, or try later.")
            code == 400 && ("api_key_invalid" in low || "api key not valid" in low || "api key" in low && "invalid" in low) ->
                Result(Kind.INVALID_KEY, "Invalid API key: Google says this key is not valid. Check or replace it in AI settings.")
            (code == 403 || code == 400) && ("service_disabled" in low || "has not been used" in low || "is disabled" in low) ->
                Result(Kind.API_DISABLED, "API not enabled: the Gemini API is turned off for this key's Google project. Create the key again in Google AI Studio.")
            code == 401 || code == 403 || status == "PERMISSION_DENIED" || status == "UNAUTHENTICATED" ->
                Result(Kind.KEY_REFUSED, "Key refused: Google did not accept this API key (it may be restricted or deleted). Check it in AI settings.")
            code == 404 || (code == 400 && ("not found" in low || "not supported" in low)) ->
                Result(Kind.MODEL_MISSING, "Model $model is not available for this key.")
            code == 429 && "limit: 0" in low ->
                Result(Kind.NO_FREE_QUOTA, "Model $model has no free quota on this key.")
            code == 429 && ("perday" in low || "per day" in low || "daily" in low) ->
                Result(Kind.DAILY_QUOTA, "Daily free limit reached: this key's free requests for today are used up at Google. They reset at midnight Pacific time (about 1:30 pm in India). This is Google's limit, not the app's.")
            code == 429 ->
                Result(Kind.RATE_LIMIT, "Rate limit: too many requests to Google on this key just now (free tier).$wait Please try again shortly.")
            code >= 500 ->
                Result(Kind.PROVIDER, "Google's AI service had a problem (error $code). Please try again shortly.")
            else -> Result(Kind.OTHER, "AI error $code: ${msg.take(160)}")
        }
    }
}
