package dev.titanide.app.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.titanide.app.ui.ai.AiStudioScreen
import dev.titanide.app.ui.build.BuildScreen
import dev.titanide.app.ui.create.CreateProjectScreen
import dev.titanide.app.ui.extensions.ExtensionsScreen
import dev.titanide.app.ui.github.GitHubScreen
import dev.titanide.app.ui.github.GitHubRepositoryScreen
import dev.titanide.app.ui.git.GitScreen
import dev.titanide.app.ui.home.HomeScreen
import dev.titanide.app.ui.projects.ProjectDetailScreen
import dev.titanide.app.ui.projects.ProjectsScreen
import dev.titanide.app.ui.settings.SettingsScreen
import dev.titanide.app.ui.terminal.TerminalScreen
import dev.titanide.app.ui.tools.ApiTesterScreen
import dev.titanide.app.ui.tools.AssetStudioScreen
import dev.titanide.app.ui.tools.DatabaseViewerScreen
import dev.titanide.app.ui.tools.DocsScreen
import dev.titanide.app.ui.tools.ToolsScreen
import dev.titanide.app.ui.tools.UiPreviewScreen
import dev.titanide.app.ui.workbench.WorkbenchScreen
import dev.titanide.app.ui.workbench.WorkbenchViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.titanide.app.LocalContainer
import dev.titanide.core.designsystem.TitanColors

/**
 * The application shell. Navigation adapts to width: a bottom bar on phones, a rail on
 * tablets and foldables, so the same routes serve both without a second layout tree.
 */
@Composable
fun TitanRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val destination = TitanDestination.entries.firstOrNull { it.route == currentRoute }
    // The IDE workbench is full bleed: no bottom bar or rail, so the editor gets the space.
    val showChrome = destination != null && !destination.isFullBleed

    AdaptiveShell(
        navController = navController,
        currentRoute = currentRoute,
        showChrome = showChrome,
    ) {
        TitanNavHost(navController)
    }
}

@Composable
private fun AdaptiveShell(
    navController: NavHostController,
    currentRoute: String?,
    showChrome: Boolean,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val useRail = windowWidth >= 720.dp

    if (!showChrome) {
        Surface(color = TitanColors.Canvas, modifier = Modifier.fillMaxSize()) { content() }
        return
    }

    if (useRail) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail(containerColor = TitanColors.CanvasElevated) {
                TitanDestination.primary.forEach { destination ->
                    NavigationRailItem(
                        selected = currentRoute == destination.route,
                        onClick = { navController.navigateToPrimary(destination) },
                        icon = {
                            Icon(
                                imageVector = if (currentRoute == destination.route) {
                                    destination.filled
                                } else {
                                    destination.outlined
                                },
                                contentDescription = destination.label,
                            )
                        },
                        label = {
                            Text(
                                text = destination.label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
            Box(modifier = Modifier.fillMaxSize()) { content() }
        }
    } else {
        Scaffold(
            containerColor = TitanColors.Canvas,
            bottomBar = {
                NavigationBar(containerColor = TitanColors.CanvasElevated) {
                    TitanDestination.primary.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { navController.navigateToPrimary(destination) },
                            icon = {
                                Icon(
                                    imageVector = if (currentRoute == destination.route) {
                                        destination.filled
                                    } else {
                                        destination.outlined
                                    },
                                    contentDescription = destination.label,
                                )
                            },
                            label = {
                                Text(
                                    text = destination.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                content()
            }
        }
    }
}

private fun NavHostController.navigateToPrimary(destination: TitanDestination) {
    navigate(destination.route) {
        popUpTo(TitanDestination.HOME.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun TitanNavHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = TitanDestination.HOME.route,
        modifier = Modifier.fillMaxSize(),
    ) {
        primaryDestinations(navController)
        detailDestinations(navController)
    }
}

private fun NavGraphBuilder.primaryDestinations(navController: NavHostController) {
    composable(TitanDestination.HOME.route) {
        HomeScreen(
            onOpenProject = { navController.navigate(TitanRoutes.projectDetail(it)) },
            onCreateProject = { navController.navigate(TitanRoutes.CREATE_PROJECT) },
            onOpenEditor = { navController.navigate(TitanDestination.EDITOR.route) },
            onOpenBuilds = { navController.navigate(TitanRoutes.BUILD) },
            onOpenGitHub = { navController.navigate(TitanRoutes.GITHUB) },
            onOpenAi = { navController.navigate(TitanDestination.AI.route) },
            onOpenSettings = { navController.navigate(TitanDestination.SETTINGS.route) },
            onOpenTools = { navController.navigate(TitanRoutes.TOOLS) },
        )
    }
    composable(TitanDestination.PROJECTS.route) {
        ProjectsScreen(
            onCreateProject = { navController.navigate(TitanRoutes.CREATE_PROJECT) },
            onOpenProject = { navController.navigate(TitanRoutes.projectDetail(it)) },
            onOpenGitHub = { navController.navigate(TitanRoutes.GITHUB) },
        )
    }
    composable(TitanDestination.EDITOR.route) {
        val container = LocalContainer.current
        val workbench: WorkbenchViewModel = viewModel { WorkbenchViewModel(container) }
        WorkbenchScreen(
            viewModel = workbench,
            onOpenSettings = { navController.navigate(TitanDestination.SETTINGS.route) },
            onOpenProjects = { navController.navigate(TitanDestination.PROJECTS.route) },
            onExitWorkbench = { navController.navigate(TitanDestination.HOME.route) },
        )
    }
    composable(TitanDestination.AI.route) {
        AiStudioScreen(onOpenSettings = { navController.navigate(TitanDestination.SETTINGS.route) })
    }
    composable(TitanDestination.TERMINAL.route) { TerminalScreen() }
    composable(TitanDestination.EXTENSIONS.route) { ExtensionsScreen() }
    composable(TitanDestination.SETTINGS.route) { SettingsScreen() }
}

private fun NavGraphBuilder.detailDestinations(navController: NavHostController) {
    composable(TitanRoutes.CREATE_PROJECT) {
        CreateProjectScreen(
            onBack = { navController.popBackStack() },
            onCreated = { projectId ->
                navController.navigate(TitanRoutes.projectDetail(projectId)) {
                    popUpTo(TitanRoutes.CREATE_PROJECT) { inclusive = true }
                }
            },
        )
    }
    composable(
        route = "${TitanRoutes.PROJECT_DETAIL}/{projectId}",
        arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
    ) { entry ->
        val projectId = entry.arguments?.getString("projectId").orEmpty()
        ProjectDetailScreen(
            projectId = projectId,
            onBack = { navController.popBackStack() },
            onOpenEditor = { navController.navigate(TitanDestination.EDITOR.route) },
            onBuild = { navController.navigate(TitanRoutes.build(projectId)) },
            onGit = { navController.navigate(TitanRoutes.git(projectId)) },
        )
    }
    composable(
        route = TitanRoutes.BUILD,
        arguments = listOf(navArgument("projectId") { type = NavType.StringType; defaultValue = "" }),
    ) { entry ->
        BuildScreen(
            projectId = entry.arguments?.getString("projectId").orEmpty(),
            onBack = { navController.popBackStack() },
            onOpenAi = { navController.navigate(TitanDestination.AI.route) },
        )
    }
    composable(TitanRoutes.GITHUB) {
        GitHubScreen(
            onBack = { navController.popBackStack() },
            onOpenRepo = { navController.navigate(TitanRoutes.githubRepo(it)) },
            onOpenSettings = { navController.navigate(TitanDestination.SETTINGS.route) },
        )
    }
    composable(
        route = "${TitanRoutes.GITHUB_REPO}/{repoKey}",
        arguments = listOf(navArgument("repoKey") { type = NavType.StringType }),
    ) { entry ->
        val repoKey = entry.arguments?.getString("repoKey").orEmpty().replace(':', '/')
        GitHubRepositoryScreen(
            fullName = repoKey,
            onBack = { navController.popBackStack() },
            onImported = { projectId -> navController.navigate(TitanRoutes.projectDetail(projectId)) },
            onBuild = { navController.navigate(TitanRoutes.build(it)) },
        )
    }
    composable(
        route = "${TitanRoutes.GIT}/{projectId}",
        arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
    ) { entry ->
        GitScreen(
            projectId = entry.arguments?.getString("projectId").orEmpty(),
            onBack = { navController.popBackStack() },
        )
    }
    composable(TitanRoutes.TOOLS) {
        ToolsScreen(
            onBack = { navController.popBackStack() },
            onOpenApiTester = { navController.navigate(TitanRoutes.API_TESTER) },
            onOpenAssetStudio = { navController.navigate(TitanRoutes.ASSET_STUDIO) },
            onOpenDatabase = { navController.navigate(TitanRoutes.DATABASE_VIEWER) },
            onOpenPreview = { navController.navigate(TitanRoutes.UI_PREVIEW) },
            onOpenDocs = { navController.navigate(TitanRoutes.DOCS) },
        )
    }
    composable(TitanRoutes.API_TESTER) { ApiTesterScreen(onBack = { navController.popBackStack() }) }
    composable(TitanRoutes.ASSET_STUDIO) { AssetStudioScreen(onBack = { navController.popBackStack() }) }
    composable(TitanRoutes.DATABASE_VIEWER) { DatabaseViewerScreen(onBack = { navController.popBackStack() }) }
    composable(TitanRoutes.UI_PREVIEW) { UiPreviewScreen(onBack = { navController.popBackStack() }) }
    composable(TitanRoutes.DOCS) { DocsScreen(onBack = { navController.popBackStack() }) }
}
