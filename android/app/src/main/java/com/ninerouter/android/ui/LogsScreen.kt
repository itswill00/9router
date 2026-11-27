package com.ninerouter.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninerouter.android.core.ConsoleLog

private fun consoleColor(level: ConsoleLog.Level): Color = when (level) {
    ConsoleLog.Level.LOG -> Color(0xFF4ADE80)
    ConsoleLog.Level.INFO -> Color(0xFF60A5FA)
    ConsoleLog.Level.WARN -> Color(0xFFFACC15)
    ConsoleLog.Level.ERROR -> Color(0xFFF87171)
    ConsoleLog.Level.DEBUG -> Color(0xFFC084FC)
}

/** Console Log — the native port of the upstream black terminal panel. */
@Composable
fun LogsScreen() {
    val lines by ConsoleLog.lines.collectAsState()
    val listState = rememberLazyListState()
    val total = remember(lines) { lines.size }

    LaunchedEffect(total) {
        if (lines.isNotEmpty()) {
            listState.scrollToItem(lines.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (lines.isNotEmpty()) {
                PulsingDot(NineRouterColors.Success, size = 5.dp)
                Spacer(Modifier.size(6.dp))
                Text(
                    "Following",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.Success,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "$total/${ConsoleLog.MAX_LINES}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.TextFaint,
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { ConsoleLog.clear() }) {
                Text(
                    "Clear",
                    style = MaterialTheme.typography.labelSmall,
                    color = NineRouterColors.BrandOrange,
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            color = Color.Black,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
        ) {
            if (lines.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
                    Text(
                        "No console logs yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NineRouterColors.TextMuted,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    itemsIndexed(
                        lines,
                        key = { _, line -> line.seq },
                    ) { _, line ->
                        Text(
                            line.text,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = NineRouterFonts.Mono,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            ),
                            color = consoleColor(line.level),
                            translate = false,
                        )
                    }
                }
            }
        }
    }
}
