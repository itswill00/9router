package com.ninerouter.android.core

/**
 * In-memory combo routing state, matching the full 9Router behaviour:
 * round-robin starts at a moving member, and Smart Router keeps one model per
 * conversation so upstream prompt caches stay warm.
 */
object ComboRotation {
    private data class State(var index: Int = 0, var uses: Int = 0)
    private val states = mutableMapOf<String, State>()

    @Synchronized
    fun order(models: List<String>, key: String, stickyLimit: Int): List<String> {
        if (models.size <= 1) return models
        val state = states.getOrPut(key) { State() }
        val start = Math.floorMod(state.index, models.size)
        val rotated = models.drop(start) + models.take(start)
        state.uses += 1
        if (state.uses >= stickyLimit.coerceAtLeast(1)) {
            state.index = (start + 1) % models.size
            state.uses = 0
        }
        return rotated
    }

    @Synchronized
    fun reset(key: String? = null) {
        if (key == null) states.clear() else states.remove(key)
    }
}

object SmartSessions {
    private val locks = LinkedHashMap<String, String>()

    @Synchronized
    fun get(combo: String, session: String): String? = locks["$combo:$session"]

    @Synchronized
    fun put(combo: String, session: String, model: String) {
        locks["$combo:$session"] = model
        if (locks.size > 500) {
            val oldest = locks.keys.firstOrNull() ?: return
            locks.remove(oldest)
        }
    }

    @Synchronized
    fun clear(combo: String, session: String? = null) {
        if (session == null) {
            locks.keys.removeAll { it.startsWith("$combo:") }
        } else {
            locks.remove("$combo:$session")
        }
    }
}
