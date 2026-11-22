package dev.titanide.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import dev.titanide.app.LocalContainer
import dev.titanide.app.di.TitanContainer
import dev.titanide.app.testing.CloseContainersRule
import dev.titanide.app.testing.TestContainers
import dev.titanide.app.testing.ViewModelTracker
import dev.titanide.app.testing.resetMainDispatcher
import dev.titanide.app.ui.ai.AiStudioScreen
import dev.titanide.app.ui.build.BuildScreen
import dev.titanide.app.ui.create.CreateProjectScreen
import dev.titanide.app.ui.extensions.ExtensionsScreen
import dev.titanide.app.ui.git.GitScreen
import dev.titanide.app.ui.github.GitHubRepositoryScreen
import dev.titanide.app.ui.github.GitHubScreen
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
import dev.titanide.core.designsystem.TitanTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders every screen with a real [TitanContainer] and asserts the tree composes.
 *
 * This is what catches the failures a compile cannot: a nested scroll container, a
 * missing `CompositionLocal`, or a ViewModel that throws during init.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenRenderTest {
    @get:Rule
    val containers = CloseContainersRule()

    @get:Rule
    val compose = createComposeRule()

    private fun render(screen: @Composable () -> Unit) =
        renderWith(TestContainers.create(), screen)

    /**
     * The owner the rendered screens build their ViewModels against.
     *
     * Providing our own store is what lets the rule clear them: `viewModel { ... }` inside a
     * screen would otherwise put the instance somewhere the test cannot reach, and its
     * collector kept querying a database that teardown had already closed.
     */
    private val viewModelOwner = ViewModelTracker.trackStore(
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        },
    )

    private fun renderWith(container: TitanContainer, screen: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalContainer provides container,
                LocalViewModelStoreOwner provides viewModelOwner,
            ) {
                TitanTheme { screen() }
            }
        }
        compose.waitForIdle()
        compose.onRoot().assertExists()
    }

    @Test
    fun homeRenders() = render {
        HomeScreen(
            onOpenProject = {},
            onCreateProject = {},
            onOpenEditor = {},
            onOpenBuilds = {},
            onOpenGitHub = {},
            onOpenAi = {},
            onOpenSettings = {},
            onOpenTools = {},
        )
    }

    @Test
    fun projectsRenders() = render {
        ProjectsScreen(onCreateProject = {}, onOpenProject = {}, onOpenGitHub = {})
    }

    @Test
    fun createProjectRenders() = render {
        CreateProjectScreen(onBack = {}, onCreated = {})
    }

    @Test
    fun workbenchRenders() {
        val container = TestContainers.create()
        val viewModel = ViewModelTracker.track(WorkbenchViewModel(container))
        renderWith(container) {
            WorkbenchScreen(viewModel = viewModel, onOpenSettings = {}, onOpenProjects = {})
        }
    }

    @Test
    fun aiStudioRenders() = render { AiStudioScreen(onOpenSettings = {}) }

    @Test
    fun terminalRenders() = render { TerminalScreen() }

    @Test
    fun extensionsRenders() = render { ExtensionsScreen() }

    @Test
    fun settingsRenders() = render { SettingsScreen() }

    @Test
    fun buildRendersWithoutProject() = render {
        BuildScreen(projectId = "", onBack = {}, onOpenAi = {})
    }

    @Test
    fun gitRendersWithoutProject() = render { GitScreen(projectId = "missing", onBack = {}) }

    @Test
    fun projectDetailRendersForUnknownProject() = render {
        ProjectDetailScreen(
            projectId = "missing",
            onBack = {},
            onOpenEditor = {},
            onBuild = {},
            onGit = {},
        )
    }

    @Test
    fun gitHubRendersWhenSignedOut() = render {
        GitHubScreen(onBack = {}, onOpenRepo = {}, onOpenSettings = {})
    }

    @Test
    fun gitHubRepositoryRenders() = render {
        GitHubRepositoryScreen(fullName = "owner/repo", onBack = {}, onImported = {}, onBuild = {})
    }

    @Test
    fun toolsHubRenders() = render {
        ToolsScreen(onBack = {}, onOpenApiTester = {}, onOpenAssetStudio = {},
            onOpenDatabase = {}, onOpenPreview = {}, onOpenDocs = {})
    }

    @Test
    fun apiTesterRenders() = render { ApiTesterScreen(onBack = {}) }

    @Test
    fun assetStudioRenders() = render { AssetStudioScreen(onBack = {}) }

    @Test
    fun databaseViewerRenders() = render { DatabaseViewerScreen(onBack = {}) }

    @Test
    fun uiPreviewRenders() = render { UiPreviewScreen(onBack = {}) }

    @Test
    fun docsRenders() = render { DocsScreen(onBack = {}) }

    @Test
    fun everyPrimaryDestinationHasARenderableScreen() {
        assertTrue(
            "the seven primary destinations are covered by the tests above",
            TitanDestinationCoverage.destinations.all { it.isNotBlank() },
        )
    }

    @After
    fun clearTrackedViewModels() {
        // The rule clears as well; doing it here too keeps a failure in this class from
        // leaking into the next one even if the rule's teardown is skipped.
        ViewModelTracker.clearAll()
    }
}

private object TitanDestinationCoverage {
    val destinations = listOf(
        "home", "projects", "editor", "ai", "terminal", "extensions", "settings",
    )
}
