package com.ninerouter.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ninerouter.android.data.Store
import kotlinx.coroutines.delay

/**
 * Explicit persisted-state subscription. The caller must read `.value` in its
 * own composition scope (usually with `by`), otherwise only this helper
 * recomposes and the screen stays stale.
 */
@Composable
fun storeRevision(): State<Int> = Store.version.collectAsState()

/**
 * Store revision plus a visible-screen heartbeat. Returns a stable State so
 * the screen that reads `.value` is the one invalidated by each tick.
 */
@Composable
fun liveRevision(intervalMs: Long = 2_000L): State<Long> {
    val version by Store.version.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val state = remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(intervalMs)
            tick += 1
        }
    }
    LaunchedEffect(version, tick) {
        state.value = version.toLong() * 1_000_000L + (tick % 1_000_000)
    }
    return state
}

/** Current time, refreshed while the screen is visible (for countdowns). */
@Composable
fun liveNow(intervalMs: Long = 1_000L): State<Long> {
    val state = remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(intervalMs)
            state.value = System.currentTimeMillis()
        }
    }
    return state
}
