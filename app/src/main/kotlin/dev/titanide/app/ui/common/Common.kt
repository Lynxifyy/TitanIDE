package dev.titanide.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.titanide.core.designsystem.TitanColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun TitanTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Surface(color = TitanColors.CanvasElevated, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TitanColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = TitanColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                actions()
            }
        }
    }
}

/** A label/value line used in detail panels. */
@Composable
fun DetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TitanColors.TextMuted,
            modifier = Modifier.width(112.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = TitanColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun SpacerH(height: Int) = Spacer(Modifier.height(height.dp))

@Composable
fun SpacerW(width: Int) = Spacer(Modifier.width(width.dp))

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "${value.toInt()} ${units[index]}" else String.format(Locale.US, "%.1f %s", value, units[index])
}

fun formatRelativeTime(timestamp: Long): String {
    if (timestamp <= 0) return "never"
    val delta = System.currentTimeMillis() - timestamp
    if (delta < 0) return "just now"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d ago"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(timestamp))
    }
}

fun formatDuration(ms: Long): String {
    if (ms < 1000) return "${ms}ms"
    val seconds = ms / 1000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}

fun formatClock(timestamp: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
