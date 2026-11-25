package com.ninerouter.android.core

object Formats {
    const val OPENAI = "openai"
    const val OPENAI_RESPONSES = "openai-responses"
    const val CLAUDE = "claude"
    const val GEMINI = "gemini"
    const val KIRO = "kiro"
    const val CURSOR = "cursor"
    const val COMMANDCODE = "commandcode"

    fun normalize(value: String?): String = when (value?.lowercase()) {
        "anthropic", "claude" -> CLAUDE
        "openai-responses", "responses" -> OPENAI_RESPONSES
        "google", "gemini-cli", "vertex" -> GEMINI
        else -> value ?: OPENAI
    }

    fun supported(value: String): Boolean = value in setOf(
        OPENAI, OPENAI_RESPONSES, CLAUDE, GEMINI, KIRO, CURSOR, COMMANDCODE
    )
}
