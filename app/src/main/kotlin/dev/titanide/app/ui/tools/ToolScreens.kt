package dev.titanide.app.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.titanide.app.LocalContainer
import dev.titanide.app.ui.common.TitanTopBar
import dev.titanide.app.ui.common.formatBytes
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.designsystem.components.EmptyState
import dev.titanide.core.designsystem.components.StatusPill
import dev.titanide.core.designsystem.components.TitanCard
import dev.titanide.core.model.Project

/** Shared project picker used by every tool screen. */
@Composable
private fun ToolProjectHeader(state: ToolState, viewModel: ToolViewModel) {
    if (state.projects.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.projects.take(4).forEach { project ->
            FilterChip(
                selected = state.project?.id == project.id,
                onClick = { viewModel.selectProject(project.id) },
                label = { Text(project.name.take(12), style = MaterialTheme.typography.labelSmall, maxLines = 1) },
            )
        }
    }
}

@Composable
fun AssetStudioScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val viewModel: ToolViewModel = viewModel { ToolViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TitanTopBar(
            title = "Asset Studio",
            subtitle = state.project?.name ?: "no project",
            onBack = onBack,
        )
        ToolProjectHeader(state, viewModel)

        if (state.assets.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Image,
                title = "No assets found",
                message = if (state.loading) {
                    "Scanning project for images, fonts, vectors and resources..."
                } else {
                    "This project has no images, fonts, drawables, JSON or XML resources."
                },
            )
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.assets) { asset ->
                    Surface(
                        onClick = { viewModel.previewAsset(asset.path) },
                        color = TitanColors.Surface,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(asset.kind, TitanColors.Secondary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                asset.path,
                                style = MaterialTheme.typography.bodySmall,
                                color = TitanColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                formatBytes(asset.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = TitanColors.TextMuted,
                            )
                        }
                    }
                }
            }
        }

        state.assetPreview?.let { preview ->
            TitanCard(modifier = Modifier.padding(14.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text(preview.first, style = MaterialTheme.typography.labelSmall, color = TitanColors.Secondary)
                    Text(
                        preview.second,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                        ),
                        color = TitanColors.TextPrimary,
                        modifier = Modifier.height(180.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

@Composable
fun DatabaseViewerScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val viewModel: ToolViewModel = viewModel { ToolViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TitanTopBar(title = "Database Viewer", subtitle = "TITAN local database", onBack = onBack)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TitanCard {
                Column(Modifier.padding(12.dp)) {
                    Text("Tables", style = MaterialTheme.typography.titleSmall, color = TitanColors.TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Read-only view of the app's Room database. User project databases are not " +
                            "reachable from here because each project lives in app-private storage " +
                            "and may use a different engine.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TitanColors.TextMuted,
                    )
                }
            }

            state.tables.forEach { table ->
                Surface(
                    onClick = { viewModel.openTable(table.name) },
                    color = TitanColors.Surface,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(
                            Icons.Filled.Storage,
                            contentDescription = null,
                            tint = TitanColors.Secondary,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            table.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = TitanColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${table.rows} rows",
                            style = MaterialTheme.typography.labelSmall,
                            color = TitanColors.TextMuted,
                        )
                    }
                }
            }

            state.tableRows?.let { rows ->
                TitanCard {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            state.openTable ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = TitanColors.Secondary,
                        )
                        Spacer(Modifier.height(4.dp))
                        rows.forEach { row ->
                            Text(
                                row,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.5.sp,
                                ),
                                color = TitanColors.TextMuted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UiPreviewScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val viewModel: ToolViewModel = viewModel { ToolViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TitanTopBar(title = "UI Preview", subtitle = state.project?.name ?: "no project", onBack = onBack)
        ToolProjectHeader(state, viewModel)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TitanCard {
                Column(Modifier.padding(12.dp)) {
                    Text("Preview limits on device", style = MaterialTheme.typography.titleSmall, color = TitanColors.TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Rendering a project's Compose previews requires its classes to be loaded in " +
                            "this app's classloader, which only works for code that ships with TITAN. " +
                            "Instead this screen lists the layouts and previews the project declares so " +
                            "you can inspect their structure.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TitanColors.TextMuted,
                    )
                }
            }

            if (state.previews.isEmpty()) {
                Text(
                    if (state.loading) "Scanning for layouts and previews..." else "No layouts or @Preview functions found.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TitanColors.TextMuted,
                )
            } else {
                state.previews.forEach { preview ->
                    Surface(
                        onClick = { viewModel.previewAsset(preview.path) },
                        color = TitanColors.Surface,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusPill(preview.kind, TitanColors.Secondary)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    preview.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TitanColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (preview.detail.isNotBlank()) {
                                Text(
                                    preview.detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TitanColors.TextMuted,
                                )
                            }
                        }
                    }
                }
            }

            state.assetPreview?.let { preview ->
                TitanCard {
                    Column(Modifier.padding(10.dp)) {
                        Text(preview.first, style = MaterialTheme.typography.labelSmall, color = TitanColors.Secondary)
                        Text(
                            preview.second,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            ),
                            color = TitanColors.TextPrimary,
                            modifier = Modifier.height(220.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DocsScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val viewModel: ToolViewModel = viewModel { ToolViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TitanTopBar(title = "Documentation", subtitle = state.project?.name ?: "no project", onBack = onBack)
        ToolProjectHeader(state, viewModel)

        if (state.loading && state.documents.isEmpty()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Reading documentation...", style = MaterialTheme.typography.labelSmall, color = TitanColors.TextMuted)
            }
            return@Column
        }

        if (state.documents.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Storage,
                title = "No documentation",
                message = "This project has no README, docs folder, or markdown files.",
            )
            return@Column
        }

        Row(Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.width(150.dp)) {
                items(state.documents) { doc ->
                    Surface(
                        onClick = { viewModel.openDocument(doc) },
                        color = if (state.openDocument == doc) TitanColors.Surface else TitanColors.Canvas,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            doc.substringAfterLast('/'),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.openDocument == doc) TitanColors.TextPrimary else TitanColors.TextMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(10.dp)) {
                Text(
                    state.documentContent.ifBlank { "Select a document" },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    color = TitanColors.TextPrimary,
                )
            }
        }
    }
}
