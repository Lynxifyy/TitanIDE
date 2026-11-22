package dev.titanide.app.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.titanide.app.LocalContainer
import dev.titanide.app.extensions.CapabilityGate
import dev.titanide.app.extensions.TitanCapability
import dev.titanide.app.ui.common.TitanTopBar
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.designsystem.components.TitanCard

private data class ToolEntry(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val open: () -> Unit,
)

@Composable
fun ToolsScreen(
    onBack: () -> Unit,
    onOpenApiTester: () -> Unit,
    onOpenAssetStudio: () -> Unit,
    onOpenDatabase: () -> Unit,
    onOpenPreview: () -> Unit,
    onOpenDocs: () -> Unit,
    gate: CapabilityGate? = null,
) {
    val capabilities = gate ?: LocalContainer.current.capabilityGate
    val enabled = capabilities.isEnabled(TitanCapability.DEV_TOOLS)
    val tools = listOf(
        ToolEntry(
            "API Tester",
            "Send real HTTP requests with headers and bodies, then inspect the response.",
            Icons.Filled.Send,
            onOpenApiTester,
        ),
        ToolEntry(
            "Asset Studio",
            "Browse images, fonts, vectors and resource files in the open project.",
            Icons.Filled.Image,
            onOpenAssetStudio,
        ),
        ToolEntry(
            "Database Viewer",
            "Inspect TITAN's local tables and row counts.",
            Icons.Filled.Storage,
            onOpenDatabase,
        ),
        ToolEntry(
            "UI Preview",
            "List the layouts and Compose previews a project declares.",
            Icons.Filled.PhoneAndroid,
            onOpenPreview,
        ),
        ToolEntry(
            "Documentation",
            "Read the project's README and markdown docs in place.",
            Icons.Filled.Description,
            onOpenDocs,
        ),
    )

    Column(Modifier.fillMaxSize()) {
        TitanTopBar(title = "Developer tools", subtitle = "Project utilities", onBack = onBack)
        if (!enabled) {
            TitanCard {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "${TitanCapability.DEV_TOOLS.label} is switched off in Extensions",
                        style = MaterialTheme.typography.titleSmall,
                        color = TitanColors.TextPrimary,
                    )
                    Text(
                        "Turn the Developer Tools extension back on to use these utilities.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TitanColors.TextMuted,
                    )
                }
            }
            return@Column
        }
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(tools) { tool ->
                TitanCard(onClick = tool.open) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(tool.icon, contentDescription = null, tint = TitanColors.Secondary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tool.title, style = MaterialTheme.typography.titleSmall, color = TitanColors.TextPrimary)
                            Text(tool.description, style = MaterialTheme.typography.labelSmall, color = TitanColors.TextMuted)
                        }
                    }
                }
            }
        }
    }
}
