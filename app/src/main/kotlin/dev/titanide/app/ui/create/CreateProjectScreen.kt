package dev.titanide.app.ui.create

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.titanide.app.LocalContainer
import dev.titanide.app.ui.common.DetailRow
import dev.titanide.app.ui.common.TitanTopBar
import dev.titanide.core.designsystem.TitanColors
import dev.titanide.core.designsystem.components.MonogramBadge
import dev.titanide.core.designsystem.components.StatusPill
import dev.titanide.core.designsystem.components.TitanCard
import dev.titanide.core.model.GameDefinition
import dev.titanide.core.model.ProjectCategory
import dev.titanide.core.model.ProjectTemplate

@Composable
fun CreateProjectScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val container = LocalContainer.current
    val viewModel: CreateProjectViewModel = viewModel { CreateProjectViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.createdProjectId) {
        state.createdProjectId?.let(onCreated)
    }

    Column(Modifier.fillMaxSize()) {
        TitanTopBar(
            title = when (state.step) {
                WizardStep.CATEGORY -> "What are you building?"
                WizardStep.TEMPLATE -> state.category?.title ?: "Choose a template"
                WizardStep.GAME -> "Choose a game"
                WizardStep.CONFIGURE -> "Configure project"
                WizardStep.REVIEW -> "Review"
            },
            subtitle = when (state.step) {
                WizardStep.CATEGORY -> "Pick one of four starting points"
                WizardStep.TEMPLATE -> "Templates TITAN can scaffold"
                WizardStep.GAME -> "Pick the target game"
                WizardStep.CONFIGURE -> state.template?.name
                WizardStep.REVIEW -> "Confirm and create"
            },
            onBack = if (state.step == WizardStep.CATEGORY) onBack else viewModel::back,
        )

        state.error?.let { message ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TitanColors.Error.copy(alpha = 0.12f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = TitanColors.Error, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(message, style = MaterialTheme.typography.bodySmall, color = TitanColors.Error)
            }
        }

        Box(Modifier.weight(1f)) {
            when (state.step) {
                WizardStep.CATEGORY -> CategoryStep(onSelect = viewModel::selectCategory)
                WizardStep.GAME -> GameStep(
                    games = state.games,
                    selected = state.selectedGame,
                    onSelect = viewModel::selectGame,
                    onContinue = { _state -> Unit },
                )
                WizardStep.TEMPLATE -> TemplateStep(
                    templates = state.templates,
                    selectedGame = state.selectedGame,
                    showGamePicker = state.category == ProjectCategory.GAME,
                    onOpenGamePicker = viewModel::openGamePicker,
                    onSelect = viewModel::selectTemplate,
                )
                WizardStep.CONFIGURE -> ConfigureStep(state, viewModel)
                WizardStep.REVIEW -> ReviewStep(state, viewModel)
            }
        }
    }
}

@Composable
private fun CategoryStep(onSelect: (ProjectCategory) -> Unit) {
    val entries = listOf(
        Triple(ProjectCategory.ANDROID_APP, Icons.Filled.PhoneAndroid, TitanColors.Success),
        Triple(ProjectCategory.GAME, Icons.Filled.SportsEsports, TitanColors.Secondary),
        Triple(ProjectCategory.EXTENSION, Icons.Filled.Extension, TitanColors.Warning),
        Triple(ProjectCategory.BLANK, Icons.Filled.Code, TitanColors.Primary),
    )
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(entries) { (category, icon, accent) ->
            CategoryCard(category, icon, accent) { onSelect(category) }
        }
    }
}

@Composable
private fun CategoryCard(category: ProjectCategory, icon: ImageVector, accent: Color, onClick: () -> Unit) {
    TitanCard(onClick = onClick, accent = accent) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(category.title, style = MaterialTheme.typography.titleSmall, color = TitanColors.TextPrimary)
                Text(
                    category.tagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = TitanColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun GameStep(
    games: List<GameDefinition>,
    selected: GameDefinition?,
    onSelect: (GameDefinition?) -> Unit,
    onContinue: (Any) -> Unit,
) {
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("All games") },
            )
        }
        items(games, key = { it.id }) { game ->
            TitanCard(
                onClick = { onSelect(game) },
                accent = Color(game.accent),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    MonogramBadge(game.monogram, Color(game.accent), size = 42)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(game.name, style = MaterialTheme.typography.titleSmall, color = TitanColors.TextPrimary)
                        Text(
                            game.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = TitanColors.TextMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${game.frameworks.size} supported formats",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(game.accent),
                        )
                    }
                    if (selected?.id == game.id) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = Color(game.accent))
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateStep(
    templates: List<ProjectTemplate>,
    selectedGame: GameDefinition?,
    showGamePicker: Boolean,
    onOpenGamePicker: () -> Unit,
    onSelect: (ProjectTemplate) -> Unit,
) {
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showGamePicker) {
            item {
                OutlinedButton(onClick = onOpenGamePicker, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.SportsEsports, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(selectedGame?.let { "Game: ${it.name}" } ?: "Filter by game")
                }
            }
        }
        items(templates, key = { it.id }) { template ->
            TemplateCard(template) { onSelect(template) }
        }
    }
}

@Composable
private fun TemplateCard(template: ProjectTemplate, onClick: () -> Unit) {
    TitanCard(onClick = onClick) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    template.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = TitanColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (!template.isSupportedOnDevice) {
                    StatusPill("needs desktop tooling", TitanColors.Warning)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                template.description,
                style = MaterialTheme.typography.bodySmall,
                color = TitanColors.TextMuted,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                template.languages.take(3).forEach { language ->
                    StatusPill(language.displayName, TitanColors.Secondary)
                }
                StatusPill(template.buildSystem.displayName, TitanColors.Primary)
            }
            if (template.requirements.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                template.requirements.forEach { requirement ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = TitanColors.Warning,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            requirement.removePrefix(ProjectTemplate.UNSUPPORTED_PREFIX),
                            style = MaterialTheme.typography.labelSmall,
                            color = TitanColors.TextMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigureStep(state: CreateState, viewModel: CreateProjectViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::setName,
            label = { Text("Project name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        if (state.template?.buildSystem?.id == "gradle") {
            OutlinedTextField(
                value = state.packageName,
                onValueChange = viewModel::setPackageName,
                label = { Text("Package name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = state.packageName.isNotBlank() && state.validationError()?.contains("Package") == true,
            )
            OutlinedTextField(
                value = state.applicationId,
                onValueChange = viewModel::setApplicationId,
                label = { Text("Application ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }

        if (state.availableLanguages.size > 1) {
            Text("Language", style = MaterialTheme.typography.labelMedium, color = TitanColors.TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.availableLanguages.forEach { language ->
                    FilterChip(
                        selected = state.language == language,
                        onClick = { viewModel.setLanguage(language) },
                        label = { Text(language.displayName) },
                    )
                }
            }
        }

        if (state.template?.minSdk != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.minSdk.toString(),
                    onValueChange = { it.toIntOrNull()?.let(viewModel::setMinSdk) },
                    label = { Text("Min SDK") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = state.targetSdk.toString(),
                    onValueChange = { it.toIntOrNull()?.let(viewModel::setTargetSdk) },
                    label = { Text("Target SDK") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }

        OutlinedTextField(
            value = state.customLocation,
            onValueChange = viewModel::setLocation,
            label = { Text("Location") },
            placeholder = { Text("Default: app projects folder") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = {
                Text(
                    "Leave empty to use the app's projects folder.",
                    style = MaterialTheme.typography.labelSmall,
                )
            },
        )

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = viewModel::review,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TitanColors.Primary),
        ) { Text("Review") }
    }
}

@Composable
private fun ReviewStep(state: CreateState, viewModel: CreateProjectViewModel) {
    val template = state.template ?: return
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TitanCard {
            Column(Modifier.padding(14.dp)) {
                DetailRow("Name", state.name)
                DetailRow("Template", template.name)
                DetailRow("Category", template.category.title)
                DetailRow("Languages", template.languages.joinToString(", ") { it.displayName })
                DetailRow("Build system", template.buildSystem.displayName)
                if (template.buildSystem.id == "gradle") {
                    DetailRow("Package", state.packageName)
                    DetailRow("Application ID", state.applicationId.ifBlank { state.packageName })
                    DetailRow("SDK", "min ${state.minSdk} · target ${state.targetSdk}")
                    DetailRow("Location", state.customLocation.ifBlank { "App projects folder" })
                }
            }
        }

        if (template.requirements.isNotEmpty()) {
            TitanCard(accent = TitanColors.Warning) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = TitanColors.Warning, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Before you build this", style = MaterialTheme.typography.titleSmall, color = TitanColors.TextPrimary)
                    }
                    Spacer(Modifier.height(6.dp))
                    template.requirements.forEach { requirement ->
                        Text(
                            "· ${requirement.removePrefix(ProjectTemplate.UNSUPPORTED_PREFIX)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TitanColors.TextMuted,
                        )
                    }
                }
            }
        }

        Button(
            onClick = viewModel::create,
            enabled = !state.creating,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TitanColors.Primary),
        ) {
            if (state.creating) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = TitanColors.TextPrimary)
                Spacer(Modifier.width(10.dp))
                Text("Creating...")
            } else {
                Text("Create project", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
