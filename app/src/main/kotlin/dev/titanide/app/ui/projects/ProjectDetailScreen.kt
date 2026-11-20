package dev.titanide.app.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.titanide.app.LocalContainer
import dev.titanide.app.ui.common.DetailRow
import dev.titanide.app.ui.common.TitanTopBar
import dev.titanide.app.ui.common.formatRelativeTime
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.designsystem.components.MonogramBadge
import dev.titanide.core.designsystem.components.StatusPill
import dev.titanide.core.designsystem.components.TitanCard

@Composable
fun ProjectDetailScreen(
    projectId: String,
    onBack: () -> Unit,
    onOpenEditor: () -> Unit,
    onBuild: (String) -> Unit,
    onGit: (String) -> Unit,
) {
    val container = LocalContainer.current
    val viewModel: ProjectDetailViewModel = viewModel { ProjectDetailViewModel(container, projectId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TitanTopBar(
            title = state.project?.name ?: "Project",
            subtitle = state.project?.packageName,
            onBack = onBack,
        )

        val project = state.project
        if (project == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.loading) {
                    CircularProgressIndicator(color = TitanColors.Primary)
                } else {
                    Text("Project not found", color = TitanColors.TextMuted)
                }
            }
            return@Column
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonogramBadge(project.name, TitanColors.Primary, size = 52)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(project.name, style = MaterialTheme.typography.titleMedium, color = TitanColors.TextPrimary)
                    Text(
                        "Opened ${formatRelativeTime(project.lastOpenedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TitanColors.TextMuted,
                    )
                }
                StatusPill(project.category.title, TitanColors.Secondary)
            }

            TitanCard {
                Column(Modifier.padding(14.dp)) {
                    DetailRow("Category", project.category.title)
                    DetailRow("Template", project.templateId)
                    DetailRow("Languages", project.languages.joinToString(", ") { it.displayName })
                    DetailRow("Build system", project.buildSystem.displayName)
                    project.sdkConfig?.let { sdk ->
                        DetailRow("Package", sdk.applicationId)
                        DetailRow("SDK", "min ${sdk.minSdk} · target ${sdk.targetSdk} · compile ${sdk.compileSdk}")
                        DetailRow("Kotlin", sdk.kotlinVersion)
                    }
                    DetailRow("Files", state.fileCount.toString())
                    DetailRow("Size", dev.titanide.app.ui.common.formatBytes(state.sizeBytes))
                    DetailRow("Git", project.gitBranch ?: "not initialised")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        viewModel.openEditor()
                        onOpenEditor()
                    },
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TitanColors.Primary),
                ) {
                    Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Edit")
                }
                OutlinedButton(
                    onClick = { onBuild(project.id) },
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Build")
                }
                OutlinedButton(
                    onClick = { onGit(project.id) },
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Filled.Cloud, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Git")
                }
            }

            if (state.buildHistory.isNotEmpty()) {
                Text("Recent builds", style = MaterialTheme.typography.titleSmall, color = TitanColors.TextPrimary)
                state.buildHistory.take(6).forEach { record ->
                    TitanCard {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(
                                record.status,
                                if (record.status == "SUCCEEDED") TitanColors.Success else TitanColors.Error,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(record.kind, style = MaterialTheme.typography.bodySmall, color = TitanColors.TextPrimary)
                                Text(
                                    formatRelativeTime(record.startedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TitanColors.TextMuted,
                                )
                            }
                            if (record.testsTotal > 0) {
                                Text(
                                    "${record.testsPassed}/${record.testsTotal}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TitanColors.TextMuted,
                                )
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = TitanColors.Error, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Delete project", color = TitanColors.Error)
            }
        }
    }

    val current = state.project
    if (confirmDelete && current != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${current.name}?") },
            text = { Text("This removes every file in the project directory and cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete { onBack() }
                }) { Text("Delete", color = TitanColors.Error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
