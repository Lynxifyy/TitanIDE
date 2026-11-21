package dev.titanide.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.titanide.app.LocalContainer
import dev.titanide.app.di.TitanContainer
import dev.titanide.app.testing.ViewModelTracker
import dev.titanide.app.testing.resetMainDispatcher
import dev.titanide.app.ui.workbench.WorkbenchScreen
import dev.titanide.app.ui.workbench.WorkbenchViewModel
import dev.titanide.core.designsystem.TitanTheme
import dev.titanide.core.model.Language
import dev.titanide.core.model.ProjectCategory
import dev.titanide.core.templates.CreateProjectOutcome
import dev.titanide.core.templates.CreateProjectSpec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

/**
 * A tab with unsaved edits used to swap its close button for a dot, which left the tab with no
 * way to close it at all. The dot is now a marker beside the button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EditorTabCloseRenderTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * viewModelScope dispatches on Dispatchers.Main, which in Robolectric is this thread's
     * looper, so each step is followed by pumping it rather than blocking on the result.
     */
    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `a dirty tab keeps a working close button`() {
        val container = TitanContainer(ApplicationProvider.getApplicationContext())
        val id = "tab-close"
        // Scaffolding is file I/O only, so blocking here does not starve the main looper.
        val project = runBlocking {
            val outcome = container.scaffolder.create(
                CreateProjectSpec(
                    name = "TabClose",
                    packageName = "dev.titanide.tabclose",
                    category = ProjectCategory.BLANK,
                    templateId = "blank.kotlin-jvm",
                    language = Language.KOTLIN,
                ),
                id,
                container.storage.projectDir(id),
            )
            container.projectRepository.register((outcome as CreateProjectOutcome.Success).project)
            (outcome as CreateProjectOutcome.Success).project
        }

        val path = "src/Dirty.kt"
        File(project.rootPath, path).apply { parentFile?.mkdirs() }.writeText("val a = 1\n")

        val viewModel = ViewModelTracker.track(WorkbenchViewModel(container))
        awaitUntil("the workbench to load the project") { viewModel.state.value.projectPath != null }

        viewModel.openFile(path)
        awaitUntil("the buffer to open") { viewModel.buffers.value[path] != null }
        viewModel.edit(path, "val a = 2\n")
        awaitUntil("the tab to become dirty") { viewModel.state.value.activeTab?.dirty == true }

        compose.setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                TitanTheme {
                    WorkbenchScreen(
                        viewModel = viewModel,
                        onOpenSettings = {},
                        onOpenProjects = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        val close = compose.onNodeWithContentDescription("Close Dirty.kt")
        close.assertHasClickAction()
        close.performClick()
        compose.waitForIdle()

        awaitUntil("the close to be held pending") { viewModel.pendingClose.value == path }
        viewModel.discardAndCloseTab()
        assertNull(viewModel.pendingClose.value)
    }
    @After
    fun clearTrackedViewModels() {
        ViewModelTracker.clearAll()
    }
}
