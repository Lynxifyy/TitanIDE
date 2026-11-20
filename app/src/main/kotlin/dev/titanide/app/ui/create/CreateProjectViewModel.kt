package dev.titanide.app.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.titanide.app.di.TitanContainer
import dev.titanide.core.model.GameDefinition
import dev.titanide.core.model.Language
import dev.titanide.core.model.ProjectCategory
import dev.titanide.core.model.ProjectTemplate
import dev.titanide.core.templates.CreateProjectOutcome
import dev.titanide.core.templates.CreateProjectSpec
import dev.titanide.core.templates.TemplateCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** One step of the wizard. Category is fixed first because it shapes every later step. */
enum class WizardStep { CATEGORY, TEMPLATE, GAME, CONFIGURE, REVIEW }

data class CreateState(
    val step: WizardStep = WizardStep.CATEGORY,
    val category: ProjectCategory? = null,
    val selectedGame: GameDefinition? = null,
    val template: ProjectTemplate? = null,
    val name: String = "",
    val packageName: String = "com.example.app",
    val applicationId: String = "",
    val minSdk: Int = 26,
    val targetSdk: Int = 36,
    val compileSdk: Int = 36,
    val language: Language? = null,
    val customLocation: String = "",
    val creating: Boolean = false,
    val error: String? = null,
    val createdProjectId: String? = null,
    val notes: List<String> = emptyList(),
) {
    val templates: List<ProjectTemplate>
        get() = category?.let { cat ->
            TemplateCatalog.all.filter { it.category == cat }
                .let { list -> if (selectedGame != null) list.filter { it.gameId == selectedGame.id } else list }
        }.orEmpty()

    val games: List<GameDefinition> get() = TemplateCatalog.games

    val availableLanguages: List<Language>
        get() = template?.languages.orEmpty()

    /** Null when the form is valid, otherwise the reason it is not. */
    fun validationError(): String? {
        if (template == null) return "Choose a template"
        if (name.isBlank()) return "Project name is required"
        if (!name.matches(Regex("[A-Za-z][A-Za-z0-9 _-]*"))) {
            return "Project name must start with a letter and contain only letters, digits, spaces, - or _"
        }
        if (template.buildSystem.id == "gradle" && !isValidPackage(packageName)) {
            return "Package name must look like com.example.app"
        }
        val required = template.minSdk
        if (required != null && minSdk < required) {
            return "This template needs minSdk $required or higher"
        }
        if (minSdk > targetSdk) return "minSdk cannot exceed targetSdk"
        return null
    }

    private fun isValidPackage(value: String): Boolean {
        if (value.isBlank()) return false
        val parts = value.split('.')
        if (parts.size < 2) return false
        return parts.all { part ->
            part.isNotEmpty() &&
                part.first().isLetter() &&
                part.all { it.isLetterOrDigit() || it == '_' } &&
                part.first().lowercaseChar() == part.first()
        }
    }
}

class CreateProjectViewModel(private val container: TitanContainer) : ViewModel() {

    private val _state = MutableStateFlow(CreateState())
    val state: StateFlow<CreateState> = _state.asStateFlow()

    fun selectCategory(category: ProjectCategory) {
        _state.value = _state.value.copy(
            category = category,
            step = WizardStep.TEMPLATE,
            template = null,
            selectedGame = null,
            error = null,
        )
    }

    fun selectGame(game: GameDefinition?) {
        _state.value = _state.value.copy(selectedGame = game, template = null)
    }

    fun selectTemplate(template: ProjectTemplate) {
        val derivedPackage = suggestPackage(template, _state.value.name)
        _state.value = _state.value.copy(
            step = WizardStep.CONFIGURE,
            template = template,
            language = template.languages.firstOrNull(),
            packageName = if (_state.value.packageName.isBlank() || _state.value.packageName == "com.example.app") {
                derivedPackage
            } else {
                _state.value.packageName
            },
            applicationId = _state.value.applicationId.ifBlank { derivedPackage },
            minSdk = template.minSdk ?: _state.value.minSdk,
            targetSdk = template.targetSdk ?: _state.value.targetSdk,
            error = null,
        )
    }

    fun back() {
        val current = _state.value
        _state.value = when (current.step) {
            WizardStep.CATEGORY -> current
            WizardStep.TEMPLATE -> current.copy(step = WizardStep.CATEGORY, selectedGame = null)
            WizardStep.GAME -> current.copy(step = WizardStep.TEMPLATE)
            WizardStep.CONFIGURE -> current.copy(
                step = if (current.category == ProjectCategory.GAME) WizardStep.TEMPLATE else WizardStep.TEMPLATE,
                template = null,
            )
            WizardStep.REVIEW -> current.copy(step = WizardStep.CONFIGURE)
        }
    }

    fun openGamePicker() {
        _state.value = _state.value.copy(step = WizardStep.GAME)
    }

    fun setName(value: String) {
        val trimmed = value.take(60)
        _state.value = _state.value.copy(
            name = trimmed,
            packageName = if (_state.value.applicationId.isBlank()) {
                suggestPackage(_state.value.template, trimmed)
            } else {
                _state.value.packageName
            },
        )
    }

    fun setPackageName(value: String) {
        _state.value = _state.value.copy(packageName = value.trim())
    }

    fun setApplicationId(value: String) {
        _state.value = _state.value.copy(applicationId = value.trim())
    }

    fun setMinSdk(value: Int) {
        _state.value = _state.value.copy(minSdk = value)
    }

    fun setTargetSdk(value: Int) {
        _state.value = _state.value.copy(targetSdk = value)
    }

    fun setLanguage(language: Language) {
        _state.value = _state.value.copy(language = language)
    }

    fun setLocation(value: String) {
        _state.value = _state.value.copy(customLocation = value)
    }

    fun review() {
        val error = _state.value.validationError()
        if (error != null) {
            _state.value = _state.value.copy(error = error)
            return
        }
        _state.value = _state.value.copy(step = WizardStep.REVIEW, error = null)
    }

    /** Writes the project to disk. Nothing is registered unless the generator succeeded. */
    fun create() {
        val current = _state.value
        val template = current.template ?: return
        val validation = current.validationError()
        if (validation != null) {
            _state.value = current.copy(error = validation)
            return
        }

        _state.value = current.copy(creating = true, error = null)
        viewModelScope.launch {
            val projectId = UUID.randomUUID().toString()
            val targetDir = File(container.storage.projectDir(projectId).absolutePath)
            val spec = CreateProjectSpec(
                name = current.name.trim(),
                packageName = current.packageName.trim(),
                category = template.category,
                templateId = template.id,
                minSdk = current.minSdk,
                targetSdk = current.targetSdk,
                compileSdk = current.compileSdk,
                applicationId = current.applicationId.ifBlank { current.packageName }.trim(),
                language = current.language ?: template.languages.firstOrNull(),
                customLocation = current.customLocation.ifBlank { null },
            )

            when (val outcome = container.scaffolder.create(spec, projectId, targetDir)) {
                is CreateProjectOutcome.Success -> {
                    container.projectRepository.register(outcome.project)
                    _state.value = _state.value.copy(
                        creating = false,
                        createdProjectId = outcome.project.id,
                        notes = outcome.notes,
                    )
                }
                is CreateProjectOutcome.Failure -> {
                    _state.value = _state.value.copy(creating = false, error = outcome.reason)
                }
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun suggestPackage(template: ProjectTemplate?, name: String): String {
        val slug = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "")
            .ifBlank { "app" }
        return if (template?.buildSystem?.id == "gradle") "com.titan.$slug" else "com.example.$slug"
    }
}
