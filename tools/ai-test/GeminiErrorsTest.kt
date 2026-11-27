// Gemini error messages: each kind of failure gets its own plain message (run by the Unit tests workflow).
import com.familyhub.app.core.ai.GeminiErrors as G
import com.familyhub.app.core.ai.GeminiErrors.Kind as K
var f = 0
fun check(n: String, c: Boolean, d: Any? = null) { if (c) println("PASS $n") else { f++; println("FAIL $n $d") } }
fun main() {
    fun k(code: Int, body: String, web: Boolean = false) = G.classify(code, body, "m", web)
    check("invalid key", k(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""").kind == K.INVALID_KEY)
    check("key refused 403", k(403, """{"error":{"code":403,"message":"Method doesn't allow unregistered callers","status":"PERMISSION_DENIED"}}""").kind == K.KEY_REFUSED)
    check("API disabled", k(403, """{"error":{"code":403,"message":"Generative Language API has not been used in project 123 before or it is disabled.","status":"PERMISSION_DENIED","details":[{"reason":"SERVICE_DISABLED"}]}}""").kind == K.API_DISABLED)
    val rl = k(429, """{"error":{"code":429,"message":"You exceeded your current quota. Quota exceeded for metric: generate_content_free_tier_requests, limit: 15, model: x GenerateRequestsPerMinutePerProjectPerModel-FreeTier","status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"42s"}]}}""")
    check("per-minute rate limit with wait", rl.kind == K.RATE_LIMIT && "42 seconds" in rl.message, rl)
    check("daily quota", k(429, """{"error":{"code":429,"message":"Quota exceeded for metric: generate_content_free_tier_requests, limit: 250, GenerateRequestsPerDayPerProjectPerModel-FreeTier","status":"RESOURCE_EXHAUSTED"}}""").kind == K.DAILY_QUOTA)
    check("no free quota on a model -> next model", k(429, """{"error":{"code":429,"message":"Quota exceeded ... limit: 0, model: gemini-pro","status":"RESOURCE_EXHAUSTED"}}""").let { it.kind == K.NO_FREE_QUOTA && it.tryNextModel })
    check("web search quota", k(429, """{"error":{"code":429,"message":"quota","status":"RESOURCE_EXHAUSTED"}}""", web = true).kind == K.WEB_SEARCH_QUOTA)
    check("model missing -> next model", k(404, """{"error":{"code":404,"message":"models/gemini-2.0-flash is not found","status":"NOT_FOUND"}}""").let { it.kind == K.MODEL_MISSING && it.tryNextModel })
    check("server error -> next model", k(503, "{}").let { it.kind == K.PROVIDER && it.tryNextModel })
    check("quota is never called an invalid key", k(429, """{"error":{"message":"api key quota"}}""").kind != K.INVALID_KEY)
    check("network message says network", G.NETWORK.startsWith("Network problem") && G.TIMEOUT.startsWith("Network problem"))
    println(if (f == 0) "ALL PASSED" else "$f FAILED")
}
