package dev.titanide.app.ui.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.titanide.app.di.TitanContainer
import dev.titanide.app.extensions.TitanCapability
import dev.titanide.core.data.db.ExtensionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A built-in extension: shipped inside TITAN, no download, no untrusted code.
 *
 * TITAN has no third-party plugin runtime yet, so the catalogue lists only what is actually
 * compiled into the app. Claiming a downloadable marketplace would be a lie; the toggle
 * state is real and persisted, and each entry names the capability it controls.
 */
data class BuiltInExtension(
    val id: String,
    val name: String,
    val publisher: String,
    val version: String,
    val description: String,
    val kind: String,
    val capabilities: List<String>,
    val alwaysOn: Boolean = false,
) {
    /** What the toggle changes, so the switch never reads as decoration. */
    val effect: String
        get() = TitanCapability.forExtension(id).joinToString(" and ") { it.label }.ifEmpty { description }
}

data class ExtensionsUiState(
    val catalogue: List<BuiltInExtension> = BUILT_INS,
    val enabled: Map<String, Boolean> = emptyMap(),
    val loading: Boolean = true,
    val message: String? = null,
) {
    companion object {
        val BUILT_INS = listOf(
            BuiltInExtension(
                id = "titan.codeguard",
                name = "TITAN CodeGuard",
                publisher = "TITAN",
                version = "1.0.0",
                description = "Four-level static analysis: typo detection, syntax validation, " +
                    "semantic checks, and AI repair suggestions.",
                kind = "linter",
                capabilities = listOf("Read open buffers", "Report diagnostics"),
                alwaysOn = true,
            ),
            BuiltInExtension(
                id = "titan.languages",
                name = "Language Pack",
                publisher = "TITAN",
                version = "1.0.0",
                description = "Tokenizers and keywords for Kotlin, Java, Python, TypeScript, " +
                    "JavaScript, C, C++, Rust, Go, C#, Lua, PHP, Ruby, Dart, SQL and shell.",
                kind = "language",
                capabilities = listOf("Syntax highlighting", "Symbol extraction"),
                alwaysOn = true,
            ),
            BuiltInExtension(
                id = "titan.templates",
                name = "Project Templates",
                publisher = "TITAN",
                version = "1.0.0",
                description = "Scaffolders for Android, game mods, app extensions and blank " +
                    "projects across 20+ languages.",
                kind = "template",
                capabilities = listOf("Write new project directories"),
                alwaysOn = true,
            ),
            BuiltInExtension(
                id = "titan.git",
                name = "Git and GitHub",
                publisher = "TITAN",
                version = "1.0.0",
                description = "JGit-backed version control with clone, commit, branch, push, " +
                    "pull, diff, plus the GitHub REST integration.",
                kind = "integration",
                capabilities = listOf("Network access", "Read and write project files"),
                alwaysOn = true,
            ),
            BuiltInExtension(
                id = "titan.remote-build",
                name = "Remote Build Backends",
                publisher = "TITAN",
                version = "1.0.0",
                description = "Dispatch builds to a remote agent or GitHub Actions when the " +
                    "device cannot run the toolchain locally.",
                kind = "build-system",
                capabilities = listOf("Network access", "Read build logs"),
                alwaysOn = false,
            ),
            BuiltInExtension(
                id = "titan.ai",
                name = "TITAN Intelligence",
                publisher = "TITAN",
                version = "1.0.0",
                description = "AI chat, inline editing, refactoring and the autonomous agent. " +
                    "Requires a provider you configure.",
                kind = "ai-provider",
                capabilities = listOf("Network access", "Send code to your chosen provider"),
                alwaysOn = false,
            ),
            BuiltInExtension(
                id = "titan.tools",
                name = "Developer Tools",
                publisher = "TITAN",
                version = "1.0.0",
                description = "HTTP request tester, asset browser, database viewer, UI preview " +
                    "and documentation browser.",
                kind = "tool",
                capabilities = listOf("Read project files", "Network access for API testing"),
                alwaysOn = false,
            ),
        )
    }
}

class ExtensionsViewModel(private val container: TitanContainer) : ViewModel() {

    private val _state = MutableStateFlow(ExtensionsUiState())
    val state: StateFlow<ExtensionsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.database.extensionDao().observeAll().collect { stored ->
                val byId = stored.associateBy { it.id }
                val enabled = ExtensionsUiState.BUILT_INS.associate { ext ->
                    ext.id to (if (ext.alwaysOn) true else byId[ext.id]?.enabled ?: true)
                }
                _state.update { it.copy(enabled = enabled, loading = false) }
            }
        }
    }

    /**
     * Persists the toggle. Always-on extensions are core code and cannot be disabled.
     *
     * An extension with no stored row shows as enabled because that is what the capability
     * gate answers for it: a fresh install keeps every feature, and the switch must not claim
     * otherwise.
     */
    fun setEnabled(extension: BuiltInExtension, enabled: Boolean) {
        if (extension.alwaysOn) {
            _state.update { it.copy(message = "${extension.name} is built into TITAN and cannot be disabled") }
            return
        }
        viewModelScope.launch {
            container.database.extensionDao().upsert(
                ExtensionEntity(
                    id = extension.id,
                    name = extension.name,
                    version = extension.version,
                    publisher = extension.publisher,
                    description = extension.description,
                    kind = extension.kind,
                    enabled = enabled,
                    installedAt = System.currentTimeMillis(),
                    manifestJson = """{"id":"${extension.id}","capabilities":${extension.capabilities.size}}""",
                ),
            )
            _state.update {
                it.copy(
                    message = "${extension.name} ${if (enabled) "enabled" else "disabled"}",
                )
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }
}
