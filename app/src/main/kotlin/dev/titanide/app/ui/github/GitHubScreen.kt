package dev.titanide.app.ui.github

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.titanide.app.LocalContainer
import dev.titanide.app.ui.common.TitanTopBar
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.designsystem.components.EmptyState
import dev.titanide.core.designsystem.components.MonogramBadge
import dev.titanide.core.designsystem.components.StatusPill
import dev.titanide.core.designsystem.components.TitanCard
import dev.titanide.core.model.GitRepository

@Composable
fun GitHubScreen(
    onBack: () -> Unit,
    onOpenRepo: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val container = LocalContainer.current
    val viewModel: GitHubViewModel = viewModel { GitHubViewModel(container) }
    val state by viewModel.list.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TitanTopBar(
            title = "GitHub",
            subtitle = state.login?.let { "@$it" } ?: "not signed in",
            onBack = onBack,
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "GitHub settings")
                }
            },
        )

        if (!state.signedIn) {
            EmptyState(
                icon = Icons.Filled.Cloud,
                title = "GitHub is not connected",
                message = "Sign in to browse your repositories, clone them into TITAN, and run their tests.",
                action = {
                    Button(
                        onClick = onOpenSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = TitanColors.Primary),
                    ) { Text("Open settings") }
                },
            )
            return@Column
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            placeholder = { Text("Filter repositories, or search GitHub") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                Button(
                    onClick = viewModel::searchAll,
                    enabled = state.query.isNotBlank() && !state.searching,
                    shape = RoundedCornerShape(8.dp),
                ) { Text("Search", style = MaterialTheme.typography.labelSmall) }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RepoSort.entries.forEach { sort ->
                FilterChip(
                    selected = state.sort == sort,
                    onClick = { viewModel.setSort(sort) },
                    label = { Text(sort.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
            FilterChip(
                selected = state.onlyPrivate,
                onClick = viewModel::togglePrivateOnly,
                label = { Text("Private", style = MaterialTheme.typography.labelSmall) },
            )
        }

        state.error?.let {
            Text(
                it,
                color = TitanColors.Error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }

        Box(Modifier.weight(1f)) {
            when {
                state.loading || state.searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TitanColors.Primary)
                }
                state.visible.isEmpty() -> EmptyState(
                    icon = Icons.Filled.Cloud,
                    title = "No repositories",
                    message = if (state.query.isBlank()) {
                        "This account has no repositories yet."
                    } else {
                        "No repository matches \"${state.query}\"."
                    },
                )
                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.visible, key = { it.id }) { repo ->
                        RepoCard(repo) { onOpenRepo(repo.fullName) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoCard(repo: GitRepository, onClick: () -> Unit) {
    TitanCard(onClick = onClick) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MonogramBadge(repo.name, TitanColors.Primary, size = 40)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        repo.fullName,
                        style = MaterialTheme.typography.titleSmall,
                        color = TitanColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (repo.isPrivate) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = "Private",
                            tint = TitanColors.Warning,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
                repo.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = TitanColors.TextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    repo.language?.let { StatusPill(it, TitanColors.Secondary) }
                    Text(
                        repo.defaultBranch,
                        style = MaterialTheme.typography.labelSmall,
                        color = TitanColors.TextMuted,
                    )
                    Icon(Icons.Filled.Star, contentDescription = null, tint = TitanColors.Warning, modifier = Modifier.size(10.dp))
                    Text(
                        repo.stars.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = TitanColors.TextMuted,
                    )
                }
            }
        }
    }
}
