package dev.titanide.app.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

/** The seven primary destinations. Order is the navigation order in the rail and bar. */
enum class TitanDestination(
    val route: String,
    val label: String,
    val filled: ImageVector,
    val outlined: ImageVector,
    val isPrimary: Boolean = true,
    /** Full-bleed destinations draw no app chrome; the IDE needs every pixel. */
    val isFullBleed: Boolean = false,
) {
    HOME("home", "Home", Icons.Filled.Home, Icons.Outlined.Home),
    PROJECTS("projects", "Projects", Icons.Filled.Folder, Icons.Outlined.Folder),
    EDITOR("editor", "Editor", Icons.Filled.Code, Icons.Outlined.Code, isFullBleed = true),
    AI("ai", "AI Studio", Icons.Filled.Psychology, Icons.Outlined.Psychology),
    TERMINAL("terminal", "Terminal", Icons.Filled.Terminal, Icons.Outlined.Terminal),
    EXTENSIONS("extensions", "Extensions", Icons.Filled.Extension, Icons.Outlined.Extension),
    SETTINGS("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
    ;

    companion object {
        val primary: List<TitanDestination> = entries.filter { it.isPrimary }

        fun fromRoute(route: String?): TitanDestination =
            entries.firstOrNull { it.route == route } ?: HOME
    }
}

/** Secondary routes that are pushed on top of the primary shell. */
object TitanRoutes {
    const val CREATE_PROJECT = "create"
    const val BUILD = "build?projectId={projectId}"
    const val GITHUB = "github"
    const val GITHUB_REPO = "github/repo"
    const val PROJECT_DETAIL = "project"
    const val API_TESTER = "tools/api"
    const val ASSET_STUDIO = "tools/assets"
    const val DATABASE_VIEWER = "tools/database"
    const val UI_PREVIEW = "tools/preview"
    const val DOCS = "tools/docs"
    const val GIT = "git"
    const val TOOLS = "tools"

    const val EDITOR_ROUTE = "editor"
    const val EDITOR_WITH_PROJECT = "editor?projectId={projectId}"

    fun projectDetail(id: String) = "$PROJECT_DETAIL/$id"
    fun editor(projectId: String? = null) =
        if (projectId.isNullOrEmpty()) EDITOR_ROUTE else "$EDITOR_ROUTE?projectId=$projectId"
    fun githubRepo(fullName: String) = "$GITHUB_REPO/${fullName.replace('/', ':')}"
    fun build(projectId: String) = "$BUILD/$projectId"
    fun git(projectId: String) = "$GIT/$projectId"
}
