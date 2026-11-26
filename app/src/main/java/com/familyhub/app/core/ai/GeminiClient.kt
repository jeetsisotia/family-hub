package com.familyhub.app.core.ai

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await

class AiException(message: String) : Exception(message)

/** Calls the authenticated Firebase backend. The Gemini key is never packaged in the APK. */
object GeminiClient {
    data class Turn(val fromUser: Boolean, val text: String)

    fun isConfigured(): Boolean = true

    suspend fun generate(
        system: String,
        turns: List<Turn>,
        json: Boolean = false,
        temperature: Double = 0.4
    ): String {
        if (turns.isEmpty()) throw AiException("Please enter a message first.")
        val payload = hashMapOf<String, Any>(
            "system" to system.take(12_000),
            "turns" to turns.takeLast(14).map {
                mapOf("role" to if (it.fromUser) "user" else "model", "text" to it.text.take(6_000))
            },
            "json" to json,
            "temperature" to temperature.coerceIn(0.0, 1.0)
        )
        return try {
            val result = FirebaseFunctions.getInstance()
                .getHttpsCallable("generateFamilyHubAI")
                .call(payload)
                .await()
            val data = result.data as? Map<*, *>
            val answer = data?.get("text") as? String
            if (answer.isNullOrBlank()) throw AiException("The AI gave an empty answer. Please try again.")
            answer.trim()
        } catch (e: FirebaseFunctionsException) {
            val message = when (e.code) {
                FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                    "Please sign in again to use the AI assistant."
                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
                    "The AI usage limit has been reached. Please try again tomorrow."
                FirebaseFunctionsException.Code.UNAVAILABLE,
                FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                    "The AI service is temporarily unavailable. Please try again."
                else -> e.message ?: "The AI service could not complete your request."
            }
            throw AiException(message)
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            throw AiException(e.message ?: "Could not connect to the AI service.")
        }
    }
}
