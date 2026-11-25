package com.ninerouter.android.core

/** Port of the decision half of open-sse/services/accountFallback.js. */
data class FallbackDecision(
    val shouldFallback: Boolean,
    val cooldownMs: Long = 0L,
    val requestScoped: Boolean = false,
)

object Fallback {
    fun check(status: Int, errorText: String): FallbackDecision {
        val lower = errorText.lowercase()
        val quotaWords = listOf("rate limit", "quota", "overloaded", "capacity", "too many requests", "usage limit")
        if (quotaWords.any { lower.contains(it) }) {
            return FallbackDecision(true, 60_000L)
        }
        return when (status) {
            401, 403 -> FallbackDecision(true, 30_000L)
            402, 404 -> FallbackDecision(true, 0L)
            429 -> FallbackDecision(true, 60_000L)
            in 500..599 -> FallbackDecision(true, 2_000L)
            in 400..499 -> FallbackDecision(false, 0L, requestScoped = true)
            else -> FallbackDecision(true, 1_000L)
        }
    }
}
