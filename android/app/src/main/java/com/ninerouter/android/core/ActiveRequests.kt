package com.ninerouter.android.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * In-flight chat requests, mirroring the desktop usage tracker.
 *
 * The topology treats a provider as active while a request is running and drops
 * it after [TIMEOUT_MS] even if a cancellation callback never arrived, so a
 * crashed stream cannot light the graph forever.
 */
object ActiveRequests {
    const val TIMEOUT_MS = 60_000L
    const val ERROR_TTL_MS = 10_000L

    data class Entry(
        val provider: String,
        val model: String,
        val account: String,
        val count: Int,
        val firstSeen: Long,
    )

    private data class Counter(
        var count: Int,
        val firstSeen: Long,
        val provider: String,
        val model: String,
        val account: String,
    )

    private val lock = Any()
    private val counters = LinkedHashMap<String, Counter>()
    private var lastErrorProvider: String = ""
    private var lastErrorAt: Long = 0L
    private val _active = MutableStateFlow<List<Entry>>(emptyList())
    val active: StateFlow<List<Entry>> = _active.asStateFlow()

    fun begin(
        provider: String,
        model: String,
        account: String,
        now: Long = System.currentTimeMillis(),
    ): String {
        val providerKey = provider.lowercase(Locale.US)
        val key = "$providerKey|$model|$account"
        synchronized(lock) {
            val current = counters[key]
            if (current == null) {
                counters[key] = Counter(1, now, providerKey, model, account)
            } else {
                current.count += 1
            }
            publishLocked(now)
        }
        return key
    }

    fun end(
        key: String,
        error: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ) {
        synchronized(lock) {
            val current = counters[key] ?: return
            current.count -= 1
            if (current.count <= 0) counters.remove(key)
            if (error && current.provider.isNotBlank()) {
                lastErrorProvider = current.provider
                lastErrorAt = now
            }
            publishLocked(now)
        }
    }

    /** Expire stuck entries and return the current snapshot. */
    fun snapshot(now: Long = System.currentTimeMillis()): List<Entry> {
        synchronized(lock) {
            val expired = counters.filterValues { now - it.firstSeen >= TIMEOUT_MS }.keys
            if (expired.isNotEmpty()) {
                expired.forEach { counters.remove(it) }
                publishLocked(now)
            }
            return _active.value
        }
    }

    fun errorProvider(now: Long = System.currentTimeMillis()): String {
        synchronized(lock) {
            return if (lastErrorProvider.isNotBlank() && now - lastErrorAt < ERROR_TTL_MS) {
                lastErrorProvider
            } else {
                ""
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            counters.clear()
            lastErrorProvider = ""
            lastErrorAt = 0L
            _active.value = emptyList()
        }
    }

    private fun publishLocked(now: Long) {
        val entries = counters.values
            .filter { it.count > 0 && now - it.firstSeen < TIMEOUT_MS }
            .map { Entry(it.provider, it.model, it.account, it.count, it.firstSeen) }
        _active.value = entries
    }
}
