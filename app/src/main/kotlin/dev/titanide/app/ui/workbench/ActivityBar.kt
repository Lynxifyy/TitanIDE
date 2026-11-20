package dev.titanide.app.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.workbench.Activity

/** Icon per activity. Kept here so the state model stays free of Compose types. */
private fun Activity.icon(): ImageVector = when (this) {
    Activity.EXPLORER -> Icons.Filled.FolderOpen
    Activity.SEARCH -> Icons.Filled.Search
    Activity.SOURCE_CONTROL -> Icons.Filled.AccountTree
    Activity.RUN_DEBUG -> Icons.Filled.BugReport
    Activity.EXTENSIONS -> Icons.Filled.Extension
    Activity.GITHUB -> Icons.Filled.Code
    Activity.AGENT -> Icons.Filled.SmartToy
}

/** Badge counts drawn on the activity bar, e.g. open problems. */
data class ActivityBadges(
    val problems: Int = 0,
    val sourceControl: Int = 0,
    val agentRunning: Boolean = false,
)

/**
 * The activity bar. Narrow rail on phones, labelled rail on tablets — the same widget at
 * two widths rather than two implementations.
 */
@Composable
fun ActivityBar(
    selected: Activity,
    expanded: Boolean,
    badges: ActivityBadges,
    onSelect: (Activity) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val width = if (expanded) WorkbenchMetrics.ActivityBarExpandedWidth else WorkbenchMetrics.ActivityBarWidth
    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(TitanColors.CanvasElevated),
        horizontalAlignment = if (expanded) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(6.dp))
        Activity.entries.forEach { activity ->
            ActivityBarItem(
                activity = activity,
                selected = activity == selected,
                expanded = expanded,
                badge = badgeFor(activity, badges),
                onClick = { onSelect(activity) },
            )
        }
        Spacer(Modifier.weight(1f))
        ActivityBarItem(
            activity = null,
            selected = false,
            expanded = expanded,
            badge = 0,
            icon = Icons.Filled.Settings,
            label = "Settings",
            onClick = onOpenSettings,
        )
        Spacer(Modifier.height(6.dp))
    }
}

private fun badgeFor(activity: Activity, badges: ActivityBadges): Int = when (activity) {
    Activity.SOURCE_CONTROL -> badges.sourceControl
    Activity.RUN_DEBUG -> badges.problems
    else -> 0
}

@Composable
private fun ActivityBarItem(
    activity: Activity?,
    selected: Boolean,
    expanded: Boolean,
    badge: Int,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    label: String? = null,
) {
    val resolvedIcon = icon ?: activity?.icon() ?: Icons.Filled.Settings
    val resolvedLabel = label ?: activity?.label.orEmpty()
    val tint: Color = if (selected) TitanColors.Primary else TitanColors.TextMuted

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clickable(onClick = onClick)
            .background(if (selected) TitanColors.Surface.copy(alpha = 0.7f) else Color.Transparent)
            .padding(horizontal = if (expanded) 12.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
    ) {
        // Selected indicator: a 2dp accent bar on the leading edge, as in VS Code.
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(if (selected) 20.dp else 0.dp)
                .background(if (selected) TitanColors.Primary else Color.Transparent),
        )
        Spacer(Modifier.width(if (expanded) 10.dp else 10.dp))
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(
                imageVector = resolvedIcon,
                contentDescription = resolvedLabel,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            if (badge > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(TitanColors.Primary)
                        .padding(horizontal = 3.dp),
                ) {
                    Text(
                        text = badge.coerceAtMost(99).toString(),
                        color = TitanColors.TextPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        if (expanded) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = resolvedLabel,
                color = if (selected) TitanColors.TextPrimary else TitanColors.TextMuted,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
