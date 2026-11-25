package com.ninerouter.android.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = NineRouterColors.Surface,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(12.dp), content = content)
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = NineRouterColors.TextMain,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(1.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NineRouterColors.TextMuted)
            }
        }
        action?.invoke()
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = NineRouterColors.BrandOrange,
            contentColor = NineRouterColors.Bg,
        ),
        shape = RoundedCornerShape(10.dp),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = NineRouterColors.TextMain),
    ) { Text(text) }
}

@Composable
fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = NineRouterColors.TextMuted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = NineRouterColors.TextMain)
    }
}

@Composable
fun ProviderIcon(id: String, name: String, size: Dp = 40.dp) {
    val context = LocalContext.current
    val bitmap = remember(id) {
        try {
            context.assets.open("providers/$id.png").use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        } catch (_: Exception) {
            null
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = name,
            modifier = Modifier.size(size).clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Fit,
        )
    } else {
        Surface(color = NineRouterColors.Surface3, shape = RoundedCornerShape(10.dp)) {
            Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = NineRouterColors.TextMain, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ConnectedBadge(count: Int) {
    val active = count > 0
    Surface(
        color = if (active) NineRouterColors.Success.copy(alpha = 0.12f) else NineRouterColors.Surface2,
        shape = RoundedCornerShape(999.dp),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(6.dp).background(
                    if (active) NineRouterColors.Success else NineRouterColors.TextSubtle,
                    RoundedCornerShape(50),
                )
            )
            Spacer(Modifier.width(5.dp))
            Text(
                if (active) "$count Connected" else "No connections",
                style = MaterialTheme.typography.labelSmall,
                color = if (active) NineRouterColors.Success else NineRouterColors.TextMuted,
            )
        }
    }
}
