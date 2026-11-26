package com.ninerouter.android.data

/** Removes credentials that providers sometimes echo back in error bodies. */
object Redact {
    private val secretPatterns = listOf(
        Regex("(?i)sk-[a-z0-9_-]{6,}"),
        Regex("(?i)(bearer\\s+)[a-z0-9._~+/-]+=*"),
        Regex("(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|authorization|secret|cookie)\\s*[:=]\\s*)[^\\s,;\"']+"),
        Regex("eyJ[a-zA-Z0-9_-]{8,}\\.[a-zA-Z0-9_-]{8,}\\.[a-zA-Z0-9_-]{8,}"),
    )

    fun text(input: String, max: Int = 400): String {
        if (input.isBlank()) return ""
        var output = input
        output = output.replace(secretPatterns[0], "sk-••••")
        output = output.replace(secretPatterns[1], "$1••••")
        output = output.replace(secretPatterns[2], "$1••••")
        output = output.replace(secretPatterns[3], "••••")
        return if (output.length > max) output.take(max) + "…" else output
    }
}
