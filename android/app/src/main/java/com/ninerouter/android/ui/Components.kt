package com.ninerouter.android.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
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
        shape = NineRouterShapes.large,
        border = BorderStroke(1.dp, NineRouterColors.BorderSubtle),
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(14.dp), content = content)
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
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "primaryPress",
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp).scale(scale),
        interactionSource = interaction,
        colors = ButtonDefaults.buttonColors(
            containerColor = NineRouterColors.BrandOrange,
            contentColor = NineRouterColors.Bg,
        ),
        shape = NineRouterShapes.small,
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "secondaryPress",
    )
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp).scale(scale),
        interactionSource = interaction,
        shape = NineRouterShapes.small,
        border = BorderStroke(1.dp, NineRouterColors.BorderStrong),
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
fun ToggleRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    trailing: (@Composable () -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = NineRouterColors.TextMain)
            if (description != null) {
                Spacer(Modifier.height(1.dp))
                Text(description, style = MaterialTheme.typography.labelSmall, color = NineRouterColors.TextMuted)
            }
        }
        trailing?.invoke()
        if (trailing != null) Spacer(Modifier.width(6.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(
        targetValue = if (selected) NineRouterColors.BrandOrange.copy(alpha = 0.16f) else NineRouterColors.Surface2,
        animationSpec = tween(220),
        label = "chipBackground",
    )
    val content by animateColorAsState(
        targetValue = if (selected) NineRouterColors.BrandOrange else NineRouterColors.TextMuted,
        animationSpec = tween(220),
        label = "chipContent",
    )
    Surface(
        color = background,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun DividerLine(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(NineRouterColors.BorderSubtle),
    )
}

@Composable
fun Pill(
    text: String,
    color: Color = NineRouterColors.TextMuted,
    background: Color = NineRouterColors.Surface2,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = background,
        shape = NineRouterShapes.pill,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun StatusDot(color: Color, size: Dp = 7.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** A dot with an outward pulsing ring — used for live/running states. */
@Composable
fun PulsingDot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val ringScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 2.1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "ringScale",
    )
    val ringAlpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "ringAlpha",
    )
    Box(modifier.size(size * 2.2f), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .scale(ringScale)
                .background(color.copy(alpha = ringAlpha), CircleShape)
        )
        Box(Modifier.size(size).background(color, CircleShape))
    }
}

/** Three bouncing dots used while a response is streaming. */
@Composable
fun TypingDots(
    color: Color = NineRouterColors.TextMuted,
    dotSize: Dp = 6.dp,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val transition = rememberInfiniteTransition(label = "typing$index")
            val lift by transition.animateFloat(
                initialValue = 0f,
                targetValue = -1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(460, delayMillis = index * 140, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "lift$index",
            )
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .offset(y = (lift * 3f).dp)
                    .size(dotSize)
                    .background(color.copy(alpha = 0.35f + 0.65f * (1f + lift)), CircleShape)
            )
        }
    }
}

@Composable
fun MonoText(
    text: String,
    color: Color = NineRouterColors.TextMain,
    maxLines: Int = 1,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
        color = color,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier,
        translate = false,
    )
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = NineRouterSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = NineRouterColors.Surface2,
            shape = CircleShape,
            border = BorderStroke(1.dp, NineRouterColors.Border),
        ) {
            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = NineRouterColors.TextSubtle,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        Spacer(Modifier.height(NineRouterSpacing.sm))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = NineRouterColors.TextMain,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = NineRouterColors.TextMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = NineRouterSpacing.lg),
        )
        if (action != null) {
            Spacer(Modifier.height(NineRouterSpacing.md))
            action()
        }
    }
}

@Composable
fun ProviderIcon(id: String, name: String, size: Dp = 40.dp) {
    val context = LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, id) {
        value = withContext(Dispatchers.IO) {
            try {
                context.assets.open("providers/$id.png").use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            } catch (_: Exception) {
                null
            }
        }
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
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
fun ConnectedBadge(count: Int) {    val active = count > 0
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

@Composable
fun NoticeBanner(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.08f),
        shape = NineRouterShapes.medium,
        border = BorderStroke(1.dp, color.copy(alpha = 0.24f)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
            StatusDot(color, 6.dp, Modifier.padding(top = 5.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}

/** Material "content copy" glyph, kept locally so we only depend on material-icons-core. */
val CopyIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ContentCopy",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(16f, 1f)
            horizontalLineTo(4f)
            curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(2f)
            verticalLineTo(3f)
            horizontalLineToRelative(12f)
            verticalLineTo(1f)
            close()
            moveTo(19f, 5f)
            horizontalLineTo(8f)
            curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
            verticalLineToRelative(14f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(11f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(7f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            close()
            moveTo(19f, 21f)
            horizontalLineTo(8f)
            verticalLineTo(7f)
            horizontalLineToRelative(11f)
            verticalLineToRelative(14f)
            close()
        }
    }.build()
}
