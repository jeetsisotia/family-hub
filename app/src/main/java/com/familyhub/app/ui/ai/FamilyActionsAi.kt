package com.familyhub.app.ui.ai

import com.familyhub.app.core.ai.AiContext
import com.familyhub.app.core.ai.GeminiClient
import com.familyhub.app.ui.AppEnv

/**
 * "Add …" requests typed in the Gemini chat, turned into the same confirm cards as the FamilyHub actions screen.
 * Only today's date and the family members' names go to the model here (no Finance, Health or other records);
 * category changes and tidy-ups never reach the model at all (FinanceActions, AppCommands).
 */
object FamilyActionsAi {
    private val ADD = Regex(
        "^\\s*(?:please\\s+|pls\\s+)?(?:add|log|note down|remind me|create|schedule|set up|save|record)\\b|" +
            "\\b(?:spent|paid|kharch|kharcha)\\b.*\\d", RegexOption.IGNORE_CASE
    )

    fun looksLikeAdd(text: String): Boolean = ADD.containsMatchIn(text)

    /** The cards for an add request, or null if the AI could not be reached. */
    suspend fun suggest(env: AppEnv, text: String): ChatMsg {
        val system = AiContext.systemPrompt(env, AiContext.minimal(env))
        val reply = GeminiClient.generate(system, listOf(GeminiClient.Turn(true, text)), json = true)
        return FinanceActions.resolveInReply(env, parseAiReply(reply))
    }
}
