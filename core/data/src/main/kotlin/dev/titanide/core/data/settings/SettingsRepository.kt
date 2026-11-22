package dev.titanide.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "titan_settings")

data class EditorSettings(
    val fontSize: Int = 13,
    val lineSpacingPercent: Int = 150,
    val tabWidth: Int = 4,
    val wordWrap: Boolean = false,
    val showLineNumbers: Boolean = true,
    val autoSave: Boolean = true,
    val autoSaveDelayMs: Long = 1500,
    val codeGuardEnabled: Boolean = true,
    val highlightCurrentLine: Boolean = true,
) {
    /**
     * Line height in sp, derived from the percentage rather than stored.
     *
     * It used to be stored as an absolute height, which cannot represent a percentage exactly:
     * 175% of a 9sp font is 15.75sp, so the value came back as 15 and the setting showed 166%.
     * Deriving it means the percentage the user picked is the value that comes back.
     */
    val lineHeight: Int get() = (fontSize * lineSpacingPercent / 100).coerceAtLeast(fontSize)
}

data class AiSettings(
    val providerId: String = "none",
    val model: String = "",
    val endpoint: String = "",
    val temperature: Double = 0.2,
    val maxTokens: Int = 4096,
    val allowProjectContext: Boolean = false,
)

/**
 * Connection to an OpenCode agent server. [remoteProjectDir] is the path of the project on
 * the machine running the server; it cannot be inferred from the Android sandbox path, so
 * the user states it once and the agent panel shows it back to them.
 */
data class OpenCodeSettings(
    val serverUrl: String = "",
    val remoteProjectDir: String = "",
    val autoConnect: Boolean = false,
)

data class BuildSettings(
    val defaultTargetId: String = "local",
    val remoteAgentUrl: String = "",
    val githubOwner: String = "",
    val githubRepo: String = "",
    val githubWorkflow: String = "titan-build.yml",
    val keepLogsDays: Int = 30,
)

data class TerminalSettings(
    val fontSize: Int = 13,
    val maxSessions: Int = 4,
)

data class TitanSettings(
    val editor: EditorSettings = EditorSettings(),
    val ai: AiSettings = AiSettings(),
    val build: BuildSettings = BuildSettings(),
    val terminal: TerminalSettings = TerminalSettings(),
    val openCode: OpenCodeSettings = OpenCodeSettings(),
    val onboarded: Boolean = false,
)

/** Typed, reactive app settings. Every write is atomic through DataStore. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val fontSize = intPreferencesKey("editor_font_size")
        val lineSpacing = intPreferencesKey("editor_line_spacing_percent")
        val legacyLineHeight = intPreferencesKey("editor_line_height")
        val tabWidth = intPreferencesKey("editor_tab_width")
        val wordWrap = booleanPreferencesKey("editor_word_wrap")
        val lineNumbers = booleanPreferencesKey("editor_line_numbers")
        val autoSave = booleanPreferencesKey("editor_auto_save")
        val autoSaveDelay = longPreferencesKey("editor_auto_save_delay")
        val codeGuard = booleanPreferencesKey("editor_code_guard")

        val currentLine = booleanPreferencesKey("editor_current_line")

        val aiProvider = stringPreferencesKey("ai_provider")
        val aiModel = stringPreferencesKey("ai_model")
        val aiEndpoint = stringPreferencesKey("ai_endpoint")
        val aiTemperature = stringPreferencesKey("ai_temperature")
        val aiMaxTokens = intPreferencesKey("ai_max_tokens")
        val aiAllowContext = booleanPreferencesKey("ai_allow_context")

        val buildTarget = stringPreferencesKey("build_target")
        val remoteUrl = stringPreferencesKey("build_remote_url")
        val ghOwner = stringPreferencesKey("build_gh_owner")
        val ghRepo = stringPreferencesKey("build_gh_repo")
        val ghWorkflow = stringPreferencesKey("build_gh_workflow")
        val keepLogs = intPreferencesKey("build_keep_logs")

        val ocUrl = stringPreferencesKey("opencode_url")
        val ocDir = stringPreferencesKey("opencode_remote_dir")
        val ocAutoConnect = booleanPreferencesKey("opencode_auto_connect")

        val termFontSize = intPreferencesKey("terminal_font_size")
        val termMaxSessions = intPreferencesKey("terminal_max_sessions")

        val onboarded = booleanPreferencesKey("onboarded")
    }

    val settings: Flow<TitanSettings> = context.dataStore.data.map { prefs ->
        val fontSize = prefs[Keys.fontSize] ?: 13
        TitanSettings(
            editor = EditorSettings(
                fontSize = fontSize,
                lineSpacingPercent = prefs[Keys.lineSpacing] ?: legacyPercent(prefs[Keys.legacyLineHeight], fontSize),
                tabWidth = prefs[Keys.tabWidth] ?: 4,
                wordWrap = prefs[Keys.wordWrap] ?: false,
                showLineNumbers = prefs[Keys.lineNumbers] ?: true,
                autoSave = prefs[Keys.autoSave] ?: true,
                autoSaveDelayMs = prefs[Keys.autoSaveDelay] ?: 1500,
                codeGuardEnabled = prefs[Keys.codeGuard] ?: true,
                highlightCurrentLine = prefs[Keys.currentLine] ?: true,
            ),
            ai = AiSettings(
                providerId = prefs[Keys.aiProvider] ?: "none",
                model = prefs[Keys.aiModel].orEmpty(),
                endpoint = prefs[Keys.aiEndpoint].orEmpty(),
                temperature = prefs[Keys.aiTemperature]?.toDoubleOrNull() ?: 0.2,
                maxTokens = prefs[Keys.aiMaxTokens] ?: 4096,
                allowProjectContext = prefs[Keys.aiAllowContext] ?: false,
            ),
            build = BuildSettings(
                defaultTargetId = prefs[Keys.buildTarget] ?: "local",
                remoteAgentUrl = prefs[Keys.remoteUrl].orEmpty(),
                githubOwner = prefs[Keys.ghOwner].orEmpty(),
                githubRepo = prefs[Keys.ghRepo].orEmpty(),
                githubWorkflow = prefs[Keys.ghWorkflow] ?: "titan-build.yml",
                keepLogsDays = prefs[Keys.keepLogs] ?: 30,
            ),
            terminal = TerminalSettings(
                fontSize = prefs[Keys.termFontSize] ?: 13,
                maxSessions = prefs[Keys.termMaxSessions] ?: 4,
            ),
            openCode = OpenCodeSettings(
                serverUrl = prefs[Keys.ocUrl].orEmpty(),
                remoteProjectDir = prefs[Keys.ocDir].orEmpty(),
                autoConnect = prefs[Keys.ocAutoConnect] ?: false,
            ),
            onboarded = prefs[Keys.onboarded] ?: false,
        )
    }

    suspend fun updateEditor(block: (EditorSettings) -> EditorSettings) {
        val current = settingsSnapshot().editor
        val next = block(current)
        val fontSize = next.fontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        context.dataStore.edit { prefs ->
            prefs[Keys.fontSize] = fontSize
            prefs[Keys.lineSpacing] = next.lineSpacingPercent
                .coerceIn(MIN_LINE_SPACING_PERCENT, MAX_LINE_SPACING_PERCENT)
            prefs[Keys.tabWidth] = next.tabWidth.coerceIn(2, 8)
            prefs[Keys.wordWrap] = next.wordWrap
            prefs[Keys.lineNumbers] = next.showLineNumbers
            prefs[Keys.autoSave] = next.autoSave
            prefs[Keys.autoSaveDelay] = next.autoSaveDelayMs.coerceIn(300, 10_000)
            prefs[Keys.codeGuard] = next.codeGuardEnabled
            prefs[Keys.currentLine] = next.highlightCurrentLine
        }
    }

    suspend fun updateAi(block: (AiSettings) -> AiSettings) {
        val next = block(settingsSnapshot().ai)
        context.dataStore.edit { prefs ->
            prefs[Keys.aiProvider] = next.providerId
            prefs[Keys.aiModel] = next.model
            prefs[Keys.aiEndpoint] = next.endpoint
            prefs[Keys.aiTemperature] = next.temperature.toString()
            prefs[Keys.aiMaxTokens] = next.maxTokens
            prefs[Keys.aiAllowContext] = next.allowProjectContext
        }
    }

    suspend fun updateBuild(block: (BuildSettings) -> BuildSettings) {
        val next = block(settingsSnapshot().build)
        context.dataStore.edit { prefs ->
            prefs[Keys.buildTarget] = next.defaultTargetId
            prefs[Keys.remoteUrl] = next.remoteAgentUrl
            prefs[Keys.ghOwner] = next.githubOwner
            prefs[Keys.ghRepo] = next.githubRepo
            prefs[Keys.ghWorkflow] = next.githubWorkflow
            prefs[Keys.keepLogs] = next.keepLogsDays
        }
    }

    /**
     * Turns the old absolute height into a percentage, so a value saved before the setting
     * became percentage-based keeps the spacing it looked like.
     */
    private fun legacyPercent(legacyHeight: Int?, fontSize: Int): Int {
        if (legacyHeight == null || fontSize <= 0) return DEFAULT_LINE_SPACING_PERCENT
        return (legacyHeight * 100 / fontSize)
            .coerceIn(MIN_LINE_SPACING_PERCENT, MAX_LINE_SPACING_PERCENT)
    }

    companion object {
        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 32
        const val MIN_LINE_SPACING_PERCENT = 100
        const val MAX_LINE_SPACING_PERCENT = 200
        const val DEFAULT_LINE_SPACING_PERCENT = 150
    }

    suspend fun updateOpenCode(block: (OpenCodeSettings) -> OpenCodeSettings) {
        val next = block(settingsSnapshot().openCode)
        context.dataStore.edit { prefs ->
            prefs[Keys.ocUrl] = next.serverUrl.trim()
            prefs[Keys.ocDir] = next.remoteProjectDir.trim()
            prefs[Keys.ocAutoConnect] = next.autoConnect
        }
    }

    suspend fun updateTerminal(block: (TerminalSettings) -> TerminalSettings) {
        val next = block(settingsSnapshot().terminal)
        context.dataStore.edit { prefs ->
            prefs[Keys.termFontSize] = next.fontSize
            prefs[Keys.termMaxSessions] = next.maxSessions
        }
    }

    suspend fun setOnboarded(value: Boolean) {
        context.dataStore.edit { it[Keys.onboarded] = value }
    }

    private suspend fun settingsSnapshot(): TitanSettings = settings.first()
}
