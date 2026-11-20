package dev.titanide.app.ui.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.titanide.app.LocalContainer
import dev.titanide.app.ui.common.TitanTopBar
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.designsystem.components.MonogramBadge
import dev.titanide.core.designsystem.components.StatusPill
import dev.titanide.core.designsystem.components.TitanCard

@Composable
fun ExtensionsScreen() {
    val container = LocalContainer.current
    val viewModel: ExtensionsViewModel = viewModel { ExtensionsViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TitanTopBar(
            title = "Extensions",
            subtitle = "${state.catalogue.count { state.enabled[it.id] == true }} of ${state.catalogue.size} active",
        )

        state.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = TitanColors.Secondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                TitanCard {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Built-in extensions",
                            style = MaterialTheme.typography.titleSmall,
                            color = TitanColors.TextPrimary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "These modules ship inside TITAN. There is no third-party plugin runtime " +
                                "yet: installing arbitrary extension code on Android without a sandbox " +
                                "would be unsafe, so the catalogue lists only what is actually compiled in.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TitanColors.TextMuted,
                        )
                    }
                }
            }
            items(state.catalogue, key = { it.id }) { extension ->
                ExtensionCard(
                    extension = extension,
                    enabled = state.enabled[extension.id] ?: extension.alwaysOn,
                    onToggle = { viewModel.setEnabled(extension, it) },
                )
            }
        }
    }
}

@Composable
private fun ExtensionCard(
    extension: BuiltInExtension,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    TitanCard {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            MonogramBadge(extension.name, TitanColors.Primary, size = 38)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        extension.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = TitanColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (extension.alwaysOn) {
                        StatusPill("built-in", TitanColors.Secondary)
                    }
                }
                Text(
                    "${extension.publisher} · v${extension.version} · ${extension.kind}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TitanColors.TextMuted,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    extension.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TitanColors.TextMuted,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Permissions: ${extension.capabilities.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TitanColors.Warning,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                enabled = !extension.alwaysOn,
            )
        }
    }
}
