package dev.titanide.core.workbench

/**
 * Pure state model for the IDE workbench.
 *
 * Nothing here touches Android, so every rule about tabs, editor groups, panel sizes and
 * layout selection is testable on the JVM. The Compose layer reads this state and draws
 * it; it does not re-derive any of these rules.
 */

/** Activity bar entries, in the order VS Code presents them. */
enum class Activity(val id: String, val label: String) {
    EXPLORER("explorer", "Explorer"),
    SEARCH("search", "Search"),
    SOURCE_CONTROL("scm", "Source Control"),
    RUN_DEBUG("run", "Run and Debug"),
    EXTENSIONS("extensions", "Extensions"),
    GITHUB("github", "GitHub"),
    AGENT("agent", "OpenCode"),
    ;

    companion object {
        fun fromId(id: String?): Activity = entries.firstOrNull { it.id == id } ?: EXPLORER
    }
}

/** Which bottom tool window is showing. */
enum class BottomTool(val id: String, val label: String) {
    PROBLEMS("problems", "Problems"),
    OUTPUT("output", "Output"),
    TERMINAL("terminal", "Terminal"),
    DEBUG_CONSOLE("debug", "Debug Console"),
    BUILD("build", "Build"),
    LOGCAT("logcat", "Logcat"),
    TESTS("tests", "Tests"),
    ;

    companion object {
        fun fromId(id: String?): BottomTool? = entries.firstOrNull { it.id == id }
    }
}

/** A file open in an editor group. */
data class EditorTab(
    val id: String,
    val path: String,
    val title: String,
    val languageId: String = "plaintext",
    val dirty: Boolean = false,
    val pinned: Boolean = false,
    val preview: Boolean = false,
) {
    val isDirty: Boolean get() = dirty
}

/**
 * An editor group. VS Code allows several side by side; TITAN supports two, which is the
 * most a phone or tablet can show legibly.
 */
data class EditorGroup(
    val id: Int = 0,
    val tabs: List<EditorTab> = emptyList(),
    val activeTabId: String? = null,
) {
    val activeTab: EditorTab? get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.lastOrNull()

    fun open(tab: EditorTab): EditorGroup {
        val existing = tabs.firstOrNull { it.path == tab.path }
        if (existing != null) {
            return copy(activeTabId = existing.id)
        }
        return copy(tabs = tabs + tab, activeTabId = tab.id)
    }

    fun close(tabId: String): EditorGroup {
        val remaining = tabs.filterNot { it.id == tabId }
        val nextActive = if (activeTabId != tabId) {
            activeTabId
        } else {
            val index = tabs.indexOfFirst { it.id == tabId }
            remaining.getOrNull(index)?.id ?: remaining.lastOrNull()?.id
        }
        return copy(
            tabs = remaining,
            activeTabId = nextActive?.takeIf { id -> remaining.any { it.id == id } },
        )
    }

    fun activate(tabId: String): EditorGroup =
        if (tabs.any { it.id == tabId }) copy(activeTabId = tabId) else this

    fun markDirty(tabId: String, dirty: Boolean): EditorGroup =
        copy(tabs = tabs.map { if (it.id == tabId) it.copy(dirty = dirty) else it })

    fun closeOthers(keepId: String): EditorGroup =
        copy(tabs = tabs.filter { it.id == keepId || it.pinned }, activeTabId = keepId)

    fun closeAll(): EditorGroup {
        val pinned = tabs.filter { it.pinned }
        return copy(tabs = pinned, activeTabId = pinned.lastOrNull()?.id)
    }
}

/** Breadcrumb derived from the active file path, e.g. `app > src > main > Main.kt`. */
fun breadcrumbFor(path: String, rootName: String? = null): List<String> {
    val parts = path.split('/', '\\').filter { it.isNotBlank() }
    if (parts.isEmpty()) return listOfNotNull(rootName)
    return if (rootName != null && parts.first() != rootName) listOf(rootName) + parts else parts
}

/** Which layout the workbench should use for a given window size. */
enum class WorkbenchLayout {
    PHONE_PORTRAIT,
    PHONE_LANDSCAPE,
    TABLET,
    ;

    companion object {
        /**
         * Layout selection is width-first because width is what constrains a code editor.
         * A wide but short window still gets the landscape layout, since vertical room is
         * what is actually missing there.
         */
        fun forSize(widthDp: Int, heightDp: Int): WorkbenchLayout = when {
            // A tablet needs both dimensions: a 891x411 window is a phone held sideways,
            // not a tablet, even though it is wider than 840dp.
            widthDp >= 600 && heightDp >= 600 -> TABLET
            widthDp > heightDp -> PHONE_LANDSCAPE
            else -> PHONE_PORTRAIT
        }
    }
}

/** What each layout shows. Kept as data so the UI has no scattered size checks. */
data class LayoutPolicy(
    val showActivityBar: Boolean,
    val activityBarExpanded: Boolean,
    val showSidebar: Boolean,
    val sidebarWidthDp: Int,
    val showAgentPanel: Boolean,
    val agentPanelWidthDp: Int,
    val showBottomPanel: Boolean,
    val bottomPanelHeightDp: Int,
    val maxEditorGroups: Int,
) {
    companion object {
        fun of(layout: WorkbenchLayout): LayoutPolicy = when (layout) {
            WorkbenchLayout.PHONE_PORTRAIT -> LayoutPolicy(
                showActivityBar = true,
                activityBarExpanded = false,
                showSidebar = false, // sidebar is a drawer, opened on demand
                sidebarWidthDp = 280,
                showAgentPanel = false, // agent is a full-height overlay
                agentPanelWidthDp = 0,
                showBottomPanel = true,
                bottomPanelHeightDp = 220,
                maxEditorGroups = 1,
            )

            WorkbenchLayout.PHONE_LANDSCAPE -> LayoutPolicy(
                showActivityBar = true,
                activityBarExpanded = false,
                showSidebar = true,
                sidebarWidthDp = 220,
                showAgentPanel = false,
                agentPanelWidthDp = 0,
                showBottomPanel = true,
                bottomPanelHeightDp = 160,
                maxEditorGroups = 1,
            )

            WorkbenchLayout.TABLET -> LayoutPolicy(
                showActivityBar = true,
                activityBarExpanded = true,
                showSidebar = true,
                sidebarWidthDp = 280,
                showAgentPanel = true,
                agentPanelWidthDp = 340,
                showBottomPanel = true,
                bottomPanelHeightDp = 240,
                maxEditorGroups = 2,
            )
        }
    }
}

/** Status bar contents. Every field is either real state or null when unknown. */
data class StatusBarState(
    val gitBranch: String? = null,
    val gitDirtyCount: Int = 0,
    val buildState: String? = null,
    val errorCount: Int = 0,
    val warningCount: Int = 0,
    val cursorLine: Int = 1,
    val cursorColumn: Int = 1,
    val languageId: String? = null,
    val encoding: String = "UTF-8",
    val lineEnding: String = "LF",
    val deviceLabel: String? = null,
    val buildVariant: String? = null,
    val agentState: String? = null,
    val serverState: String? = null,
)

/**
 * The whole workbench snapshot. One immutable value replaced on every change, so Compose
 * recomposes predictably and restoring a session becomes a matter of persisting this.
 */
data class WorkbenchState(
    val layout: WorkbenchLayout = WorkbenchLayout.PHONE_PORTRAIT,
    val policy: LayoutPolicy = LayoutPolicy.of(WorkbenchLayout.PHONE_PORTRAIT),
    val activity: Activity = Activity.EXPLORER,
    val sidebarVisible: Boolean = false,
    val agentPanelVisible: Boolean = false,
    val bottomTool: BottomTool? = null,
    val bottomPanelExpanded: Boolean = false,
    val groups: List<EditorGroup> = listOf(EditorGroup(0)),
    val activeGroupIndex: Int = 0,
    val status: StatusBarState = StatusBarState(),
    val projectName: String? = null,
    val projectPath: String? = null,
    val androidViewMode: Boolean = true,
    val sidebarWidthDp: Int = 280,
    val bottomPanelHeightDp: Int = 220,
) {
    val activeGroup: EditorGroup get() = groups.getOrElse(activeGroupIndex) { groups.first() }
    val activeTab: EditorTab? get() = activeGroup.activeTab
    val openTabCount: Int get() = groups.sumOf { it.tabs.size }

    fun withLayout(widthDp: Int, heightDp: Int): WorkbenchState {
        val layout = WorkbenchLayout.forSize(widthDp, heightDp)
        if (layout == this.layout) return this
        val policy = LayoutPolicy.of(layout)

        val keptGroups = if (groups.size > policy.maxEditorGroups) {
            val keepIndex = activeGroupIndex.coerceIn(0, groups.lastIndex)
            val keep = groups[keepIndex]
            listOf(keep) + groups.filter { it.id != keep.id }.take(policy.maxEditorGroups - 1)
        } else {
            groups
        }

        return copy(
            layout = layout,
            policy = policy,
            groups = keptGroups,
            activeGroupIndex = keptGroups.indexOfFirst { it.id == groups.getOrNull(activeGroupIndex)?.id }
                .coerceAtLeast(0),
            sidebarVisible = policy.showSidebar && sidebarVisible,
            agentPanelVisible = policy.showAgentPanel && agentPanelVisible,
            sidebarWidthDp = policy.sidebarWidthDp,
            bottomPanelHeightDp = policy.bottomPanelHeightDp,
        )
    }

    // ------------------------------------------------------------ actions

    fun selectActivity(next: Activity): WorkbenchState {
        // Tapping the active activity toggles the sidebar, which is how VS Code behaves.
        // This keys off the current visibility rather than the layout policy, because on a
        // phone the sidebar is a drawer that starts closed and is opened on demand.
        if (next == activity) return copy(sidebarVisible = !sidebarVisible)
        return copy(activity = next, sidebarVisible = true)
    }

    fun openFile(path: String, languageId: String, title: String? = null, preview: Boolean = false): WorkbenchState {
        val tab = EditorTab(
            id = path,
            path = path,
            title = title ?: path.substringAfterLast('/'),
            languageId = languageId,
            preview = preview,
        )
        val group = activeGroup.open(tab)
        return copy(groups = groups.replacing(activeGroupIndex, group), sidebarVisible = false)
    }

    fun closeTab(tabId: String): WorkbenchState =
        copy(groups = groups.replacing(activeGroupIndex, activeGroup.close(tabId)))

    fun activateTab(tabId: String): WorkbenchState =
        copy(groups = groups.replacing(activeGroupIndex, activeGroup.activate(tabId)))

    fun setDirty(path: String, dirty: Boolean): WorkbenchState = copy(
        groups = groups.map { group ->
            if (group.tabs.any { it.path == path }) group.markDirty(path, dirty) else group
        },
    )

    fun showBottom(tool: BottomTool): WorkbenchState =
        copy(bottomTool = tool, bottomPanelExpanded = true)

    fun toggleBottom(): WorkbenchState =
        if (bottomPanelExpanded) copy(bottomPanelExpanded = false) else copy(bottomPanelExpanded = true)

    fun toggleAgentPanel(): WorkbenchState = copy(agentPanelVisible = !agentPanelVisible)

    fun splitEditor(): WorkbenchState {
        if (groups.size >= policy.maxEditorGroups) return this
        val newGroup = EditorGroup(id = (groups.maxOfOrNull { it.id } ?: 0) + 1)
        return copy(groups = groups + newGroup, activeGroupIndex = groups.size)
    }

    fun closeSplit(): WorkbenchState {
        if (groups.size <= 1) return this
        val kept = groups.filterIndexed { index, _ -> index != activeGroupIndex }
        return copy(groups = kept, activeGroupIndex = (activeGroupIndex - 1).coerceAtLeast(0))
    }
}

private fun List<EditorGroup>.replacing(index: Int, group: EditorGroup): List<EditorGroup> =
    mapIndexed { i, existing -> if (i == index) group else existing }

/** Maps a file extension to a language id used for highlighting and the status bar. */
object LanguageDetector {

    private val byExtension = mapOf(
        "kt" to "kotlin", "kts" to "kotlin",
        "java" to "java",
        "py" to "python",
        "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript",
        "ts" to "typescript", "tsx" to "typescript",
        "jsx" to "javascript",
        "c" to "c", "h" to "c",
        "cpp" to "cpp", "cc" to "cpp", "cxx" to "cpp", "hpp" to "cpp",
        "rs" to "rust",
        "go" to "go",
        "cs" to "csharp",
        "lua" to "lua",
        "php" to "php",
        "rb" to "ruby",
        "dart" to "dart",
        "swift" to "swift",
        "json" to "json",
        "yaml" to "yaml", "yml" to "yaml",
        "xml" to "xml",
        "html" to "html", "htm" to "html",
        "css" to "css", "scss" to "css",
        "sql" to "sql",
        "sh" to "shell", "bash" to "shell", "zsh" to "shell",
        "md" to "markdown",
        "toml" to "toml",
        "properties" to "properties",
        "txt" to "plaintext",
    )

    fun detect(fileName: String): String {
        val lower = fileName.lowercase()
        // `build.gradle.kts` must resolve to kotlin, not groovy, so check the longer
        // suffix first rather than trusting the last dot segment.
        if (lower.endsWith(".gradle.kts")) return "kotlin"
        if (lower.endsWith(".gradle")) return "groovy"
        val ext = lower.substringAfterLast('.', "")
        return byExtension[ext] ?: "plaintext"
    }

    /** Human label for the status bar. */
    fun displayName(languageId: String): String = when (languageId) {
        "kotlin" -> "Kotlin"
        "java" -> "Java"
        "python" -> "Python"
        "javascript" -> "JavaScript"
        "typescript" -> "TypeScript"
        "cpp" -> "C++"
        "c" -> "C"
        "csharp" -> "C#"
        "rust" -> "Rust"
        "go" -> "Go"
        "json" -> "JSON"
        "yaml" -> "YAML"
        "xml" -> "XML"
        "html" -> "HTML"
        "css" -> "CSS"
        "sql" -> "SQL"
        "shell" -> "Shell"
        "markdown" -> "Markdown"
        "groovy" -> "Groovy"
        "toml" -> "TOML"
        "properties" -> "Properties"
        "plaintext" -> "Plain Text"
        else -> languageId.replaceFirstChar { it.uppercase() }
    }
}
