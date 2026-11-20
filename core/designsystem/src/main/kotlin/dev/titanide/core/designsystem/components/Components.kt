package dev.titanide.core.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.titanide.core.designsystem.TitanColors

@Composable
fun TitanCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, label = "cardScale")

    Surface(
        modifier = modifier
            .scale(scale)
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick) else Modifier),
        shape = RoundedCornerShape(14.dp),
        color = TitanColors.Surface,
        border = BorderStroke(1.dp, accent?.copy(alpha = 0.35f) ?: TitanColors.OutlineFaint),
    ) {
        content()
    }
}

/** Compact metric tile used across Home and build summaries. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = TitanColors.Secondary,
    caption: String? = null,
) {
    TitanCard(modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = TitanColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, color = TitanColors.TextPrimary, maxLines = 1)
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.bodySmall, color = TitanColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = TitanColors.TextPrimary)
        action?.invoke()
    }
}

/** Monogram chip standing in for logos we cannot ship for licensing reasons. */
@Composable
fun MonogramBadge(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
    size: Int = 44,
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.08f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.take(2).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
    }
}

@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** Thin horizontal meter used for storage, success rate, coverage. */
@Composable
fun ProgressMeter(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = TitanColors.Primary,
    height: Int = 6,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(height.dp))
            .background(TitanColors.SurfaceHigh),
    ) {
        Box(
            Modifier
                .fillMaxWidth(clamped)
                .height(height.dp)
                .clip(RoundedCornerShape(height.dp))
                .background(Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.65f)))),
        )
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = TitanColors.TextMuted.copy(alpha = 0.6f), modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = TitanColors.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = TitanColors.TextMuted, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}
