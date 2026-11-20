package dev.titanide.app.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.titanide.core.designsystem.TitanColors

/**
 * Small shared pieces of the workbench chrome. These are deliberately dense: an IDE spends
 * its pixels on code, so panel chrome is 24-32dp tall with 11-12sp labels, matching the
 * information density of a desktop IDE rather than a typical Android app.
 */
object WorkbenchMetrics {
    val ActivityBarWidth = 48.dp
    val ActivityBarExpandedWidth = 176.dp
    val StatusBarHeight = 22.dp
    val PanelHeaderHeight = 30.dp
    val TabHeight = 34.dp
    val RowHeight = 22.dp
    val SectionHeaderHeight = 24.dp
    val DividerThickness = 1.dp
    val PanelPadding = 8.dp
}

/** Section header used by every sidebar view and the bottom panel. */
@Composable
fun PanelHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: RowScopeActions = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(WorkbenchMetrics.PanelHeaderHeight)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = TitanColors.TextMuted,
            letterSpacing = 0.8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = actions,
        )
    }
}

typealias RowScopeActions = @Composable RowScope.() -> Unit

/** A 24dp square icon button sized for the dense chrome. */
@Composable
fun DenseIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = TitanColors.TextMuted,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else TitanColors.TextMuted.copy(alpha = 0.4f),
            modifier = Modifier.size(14.dp),
        )
    }
}

/** Vertical hairline used between panels. */
@Composable
fun VerticalDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(WorkbenchMetrics.DividerThickness)
            .fillMaxHeight()
            .background(TitanColors.OutlineFaint),
    )
}

@Composable
fun HorizontalDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(WorkbenchMetrics.DividerThickness)
            .fillMaxWidth()
            .background(TitanColors.OutlineFaint),
    )
}

/** Monospaced text used in logs, diffs and the agent transcript. */
@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TitanColors.TextPrimary,
    size: Int = 11,
    maxLines: Int = Int.MAX_VALUE,
    weight: FontWeight = FontWeight.Normal,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = size.sp,
        lineHeight = (size + 4).sp,
        fontWeight = weight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A short label chip, e.g. a build status or language tag. */
@Composable
fun StatusChip(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** Empty-state block used when a panel has nothing to show. */
@Composable
fun PanelEmptyState(
    icon: ImageVector,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TitanColors.TextMuted.copy(alpha = 0.5f),
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = TitanColors.TextPrimary.copy(alpha = 0.85f),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = TitanColors.TextMuted,
        )
    }
}

/** Compact text field used by search and the agent composer. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = androidx.compose.ui.text.TextStyle(
            color = TitanColors.TextPrimary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(TitanColors.Primary),
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(TitanColors.Surface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = TitanColors.TextMuted.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                        )
                    }
                    inner()
                }
                trailing?.invoke()
            }
        },
    )
}
