package com.ninerouter.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ninerouter.android.data.Store
import kotlinx.coroutines.delay

/** Subscribe explicitly to persisted state changes. */
@Composable
fun storeRevision(): Int = Store.version.collectAsState().value

/**
 * Persisted-state revision plus a visible-screen heartbeat. Data-heavy screens
 * use this so external gateway traffic, cooldown windows and relative times
 * update without leaving the tab or restarting the app.
 */
@Composable
fun liveRevision(intervalMs: Long = 2_000L): Long {
    val version = Store.version.collectAsState().value
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(intervalMs)
            tick += 1
        }
    }
    return version.toLong() * 1_000_000L + (tick % 1_000_000)
}

/** Current time, refreshed while the screen is visible (for countdowns). */
@Composable
fun liveNow(intervalMs: Long = 1_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(intervalMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}
