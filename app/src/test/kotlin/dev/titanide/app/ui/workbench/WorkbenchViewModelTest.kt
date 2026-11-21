package dev.titanide.app.ui.workbench

import androidx.test.core.app.ApplicationProvider
import dev.titanide.app.di.TitanContainer
import dev.titanide.core.model.Diagnostic
import dev.titanide.core.model.Language
import dev.titanide.core.model.ProjectCategory
import dev.titanide.core.model.Severity
import dev.titanide.core.templates.CreateProjectOutcome
import dev.titanide.core.templates.CreateProjectSpec
import dev.titanide.core.workbench.Activity
import dev.titanide.core.workbench.BottomTool
import dev.titanide.core.workbench.ProjectNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Drives [WorkbenchViewModel] against the real container: real scaffolding, real files on
 * disk, real CodeGuard analysis. Nothing is mocked, so a regression in any of those layers
 * surfaces here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkbenchViewModelTest {

    private val container = TitanContainer(ApplicationProvider.getApplicationContext())

    /**
     * viewModelScope runs on Dispatchers.Main, which is the same thread the tests block on
     * while waiting for results. Pointing Main at a real background dispatcher lets the
     * view model's coroutines actually run.
     */
    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(Dispatchers.Default)
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun scaffoldProject(id: String, name: String = "BenchDemo"): String = runBlocking {
        val outcome = container.scaffolder.create(
            CreateProjectSpec(
                name = name,
                packageName = "dev.titanide.bench",
                category = ProjectCategory.BLANK,
                templateId = "blank.kotlin-jvm",
                language = Language.KOTLIN,
            ),
            id,
            container.storage.projectDir(id),
        )
        val success = outcome as CreateProjectOutcome.Success
        container.projectRepository.register(success.project)
        id
    }

    private fun WorkbenchViewModel.awaitProject(id: String) = runBlocking {
        withTimeout(10_000) { project.first { it?.id == id } }
    }

    /** The first real Kotlin file the view model exposes for the loaded project. */
    private suspend fun WorkbenchViewModel.firstKotlinFile(): String =
        withTimeout(10_000) {
            projectTree.first { tree -> tree.flattened().any { !it.isDirectory && it.path.endsWith(".kt") } }
                .flattened().first { !it.isDirectory && it.path.endsWith(".kt") }.path
        }

    @Test
    fun `loads the project and lists its real files`() = runBlocking {
        val id = scaffoldProject("bench-load")
        val viewModel = WorkbenchViewModel(container)
        viewModel.awaitProject(id)

        val tree = withTimeout(10_000) { viewModel.androidTree.first { it.isNotEmpty() } }
        assertTrue("the real project tree should not be empty", tree.isNotEmpty())
        assertTrue(
            "an Android-style view should expose source files",
            tree.flattened().any { it.name.endsWith(".kt") },
        )
    }

    @Test
    fun `opening a file loads its real content from disk`() = runBlocking {
        val id = scaffoldProject("bench-open")
        val viewModel = WorkbenchViewModel(container)
        viewModel.awaitProject(id)

        val path = viewModel.firstKotlinFile()
        viewModel.openFile(path)
        val buffer = withTimeout(10_000) { viewModel.buffers.first { it.containsKey(path) } }[path]

        assertNotNull(buffer)
        val onDisk = File(container.storage.projectDir(id), path).readText()
        assertEquals("the buffer must match the file on disk", onDisk, buffer!!.content)
        assertFalse("a freshly opened file is not dirty", buffer.dirty)
        assertEquals("the tab should be active", path, viewModel.state.value.activeTab?.path)
    }

    @Test
    fun `editing marks the buffer dirty and saving writes through to disk`() = runBlocking {
        val id = scaffoldProject("bench-save")
        val viewModel = WorkbenchViewModel(container)
        viewModel.awaitProject(id)

        val path = viewModel.firstKotlinFile()
        viewModel.openFile(path)
        withTimeout(10_000) { viewModel.buffers.first { it.containsKey(path) } }

        val marker = "// titan-save-marker\n"
        viewModel.edit(path, marker + viewModel.buffers.value.getValue(path).content)
        assertTrue("the buffer should be dirty after an edit", viewModel.buffers.value.getValue(path).dirty)

        viewModel.save(path)
        withTimeout(10_000) { viewModel.buffers.first { entry -> entry[path]?.dirty == false } }

        val onDisk = File(container.storage.projectDir(id), path).readText()
        assertTrue("the marker must reach the file on disk", onDisk.startsWith(marker))
    }

    @Test
    fun `auto save persists the edit without an explicit save`() = runBlocking {
        val id = scaffoldProject("bench-autosave")
        val viewModel = WorkbenchViewModel(container)
        viewModel.awaitProject(id)

        val path = viewModel.firstKotlinFile()
        viewModel.openFile(path)
        withTimeout(10_000) { viewModel.buffers.first { it.containsKey(path) } }

        val marker = "// titan-autosave\n"
        viewModel.edit(path, marker + viewModel.buffers.value.getValue(path).content)

        withTimeout(15_000) { viewModel.buffers.first { entry -> entry[path]?.dirty == false } }
        val onDisk = File(container.storage.projectDir(id), path).readText()
        assertTrue("auto save must persist to disk", onDisk.startsWith(marker))
    }

    /**
     * The workbench debounced auto-save with its own hardcoded delay, so a user who raised the
     * delay in settings still had files written at the old pace.
     */
    @Test
    fun `auto save waits the delay configured in settings`() = runBlocking {
        val id = scaffoldProject("bench-autosave-delay")
        container.settings.updateEditor { it.copy(autoSave = true, autoSaveDelayMs = 3_000) }
        val viewModel = WorkbenchViewModel(container)
        viewModel.awaitProject(id)

        val path = viewModel.firstKotlinFile()
        viewModel.openFile(path)
        withTimeout(10_000) { viewModel.buffers.first { it.containsKey(path) } }

        val marker = "// titan-delay\n"
        val original = viewModel.buffers.value.getValue(path).content
        viewModel.edit(path, marker + original)

        // The old hardcoded delay was 1400 ms, so by now it had already written the file.
        Thread.sleep(2_100)
        assertFalse(
            "auto save must wait the delay from settings, not a hardcoded one",
            File(container.storage.projectDir(id), path).readText().startsWith(marker),
        )
        withTimeout(15_000) { viewModel.buffers.first { entry -> entry[path]?.dirty == false } }
        assertTrue(
            "the edit must still reach disk once the configured delay elapses",
            File(container.storage.projectDir(id), path).readText().startsWith(marker),
        )
    }

    @Test
    fun `undo reverts the last edit`() = runBlocking {
        val id = scaffoldProject("bench-undo")
        val viewModel = WorkbenchViewModel(container)
        viewModel.awaitProject(id)

        val path = viewModel.firstKotlinFile()
        viewModel.openFile(path)
        val original = withTimeout(10_000) { viewModel.buffers.first { it.containsKey(path) } }
            .getValue(path).content

        viewModel.edit(path, original + "\n// extra line\n")
        assertTrue(viewModel.buffers.value.getValue(path).content.endsWith("// extra line\n"))

        viewModel.undo()
        assertEquals("undo must restore the original text", original, viewModel.buffers.value.getValue(path).content)
    }

    @Test
    fun `code guard reports a real problem in the open buffer`() = runBlocking {
        val id = scaffoldProject("bench-guard")
        val viewModel = WorkbenchViewModel(container)
        viewModel.awaitProject(id)

        val path = viewModel.firstKotlinFile()
        viewModel.openFile(path)
        withTimeout(10_000) { viewModel.buffers.first { it.containsKey(path) } }

        // An unclosed brace is something the analyzer must see in real source.
        viewModel.edit(path, "fun broken() {\n    val x = 1\n")

        val diagnostics = withTimeout(10_000) { viewModel.diagnostics.first { it.isNotEmpty() } }
        assertTrue(
            "an unbalanced brace should produce a diagnostic",
            diagnostics.any { it.severity == Severity.ERROR || it.severity == Severity.WARNING },
        )
    }

    @Test
    fun `search finds text in files that are not open`() = runBlocking {
        val id = scaffoldProject("bench-search")
        val viewModel = WorkbenchViewModel(container)
        viewModel.awaitProject(id)
        withTimeout(10_000) { viewModel.projectTree.first { it.isNotEmpty() } }

        viewModel.search("fun")
        val hits = withTimeout(10_000) { viewModel.searchHits.first { it.isNotEmpty() } }

        assertTrue("search should find real occurrences", hits.isNotEmpty())
        assertTrue("every hit must carry a path", hits.all { it.path.isNotBlank() })
        assertTrue("every hit must carry a line number", hits.all { it.line >= 1 })
    }

    @Test
    fun `creating a file writes it and opens it`() = runBlocking {
        val id = scaffoldProject("bench-create")
        val viewModel = WorkbenchViewModel(container)
        viewModel.awaitProject(id)

        viewModel.createFile("src/NewFile.kt")
        val buffer = withTimeout(10_000) {
            viewModel.buffers.first { it.containsKey("src/NewFile.kt") }
        }["src/NewFile.kt"]

        assertNotNull(buffer)
        assertTrue(File(container.storage.projectDir(id), "src/NewFile.kt").isFile)
        assertEquals(Language.KOTLIN, buffer!!.language)
    }

    @Test
    fun `deleting a file removes it from disk and closes its tab`() = runBlocking {
        val id = scaffoldProject("bench-delete")
        val viewModel = WorkbenchViewModel(container)
        viewModel.awaitProject(id)

        viewModel.createFile("src/Doomed.kt")
        // createFile opens the file asynchronously; wait for the tab, not just the buffer.
        withTimeout(10_000) {
            viewModel.state.first { it.activeTab?.path == "src/Doomed.kt" }
        }

        viewModel.deleteFile("src/Doomed.kt")
        withTimeout(10_000) { viewModel.buffers.first { !it.containsKey("src/Doomed.kt") } }

        assertFalse(File(container.storage.projectDir(id), "src/Doomed.kt").exists())
        assertNull(viewModel.state.value.activeTab)
    }

    @Test
    fun `activity and bottom tool selection drive the state`() {
        val viewModel = WorkbenchViewModel(container)

        viewModel.selectActivity(Activity.SEARCH)
        assertEquals(Activity.SEARCH, viewModel.state.value.activity)

        viewModel.selectBottomTool(BottomTool.PROBLEMS)
        assertEquals(BottomTool.PROBLEMS, viewModel.state.value.bottomTool)
        assertTrue("selecting a tool opens the panel", viewModel.state.value.bottomPanelExpanded)

        viewModel.toggleBottomPanel()
        assertFalse(viewModel.state.value.bottomPanelExpanded)
    }

    @Test
    fun `layout adapts to the reported window size`() {
        val viewModel = WorkbenchViewModel(container)

        viewModel.onSizeChanged(412, 915)
        assertFalse("a phone should not show a persistent sidebar", viewModel.state.value.policy.showSidebar)

        viewModel.onSizeChanged(1280, 800)
        val tablet = viewModel.state.value.policy
        assertTrue("a tablet should show a persistent sidebar", tablet.showSidebar)
        assertTrue("a tablet should show the agent panel", tablet.showAgentPanel)
    }

    @Test
    fun `closing a tab drops its buffer`() = runBlocking {
        val id = scaffoldProject("bench-close")
        val viewModel = WorkbenchViewModel(container)
        viewModel.awaitProject(id)

        viewModel.createFile("src/Temp.kt")
        withTimeout(10_000) { viewModel.buffers.first { it.containsKey("src/Temp.kt") } }
        // openFile publishes the buffer and the tab separately, so awaiting the buffer does not
        // imply the tab exists yet.
        withTimeout(10_000) { viewModel.state.first { it.activeTab?.path == "src/Temp.kt" } }
        assertNotNull(viewModel.state.value.activeTab)

        viewModel.closeTab("src/Temp.kt")
        assertFalse(viewModel.buffers.value.containsKey("src/Temp.kt"))
        assertNull(viewModel.state.value.activeTab)
    }

    /**
     * Undo history belongs to the file it was recorded against. A single shared stack means undo
     * in a second file applies the first file's edit offsets to the second file's text, which
     * rewrites text the user never touched in that file.
     */
    @Test
    fun `undo in one file does not apply another file's edit`() = runBlocking {
        val id = scaffoldProject("bench-undo-scope")
        val viewModel = WorkbenchViewModel(container)
        viewModel.awaitProject(id)

        val files = withTimeout(10_000) {
            viewModel.projectTree.first { tree ->
                tree.flattened().count { !it.isDirectory && it.path.endsWith(".kt") } >= 2
            }
        }.flattened().filter { !it.isDirectory && it.path.endsWith(".kt") }.map { it.path }
        val first = files[0]
        val second = files[1]

        viewModel.openFile(first)
        withTimeout(10_000) { viewModel.buffers.first { it.containsKey(first) } }
        // A long edit, so its offsets are far past the end of the shorter second file.
        viewModel.edit(first, viewModel.buffers.value.getValue(first).content + "\n" + "x".repeat(400) + "\n")

        viewModel.openFile(second)
        withTimeout(10_000) { viewModel.buffers.first { it.containsKey(second) } }
        withTimeout(10_000) { viewModel.state.first { it.activeTab?.path == second } }
        val before = viewModel.buffers.value.getValue(second).content

        viewModel.undo()
        assertEquals(
            "undo in a file with no edits of its own must leave that file alone",
            before,
            viewModel.buffers.value.getValue(second).content,
        )
    }

    /**
     * Closing a tab is the only thing that discards a buffer, and the buffer is the only copy of
     * unsaved edits. Closing a dirty tab therefore asks first, and only the confirmed path drops
     * the work.
     */
    @Test
    fun `closing a dirty tab asks before discarding the edits`() = runBlocking {
        val id = scaffoldProject("bench-close-dirty")
        val viewModel = WorkbenchViewModel(container)
        viewModel.awaitProject(id)

        val path = viewModel.firstKotlinFile()
        viewModel.openFile(path)
        withTimeout(10_000) { viewModel.buffers.first { it.containsKey(path) } }
        viewModel.edit(path, viewModel.buffers.value.getValue(path).content + "\n")
        withTimeout(10_000) { viewModel.state.first { it.activeTab?.dirty == true } }

        viewModel.closeTab(path)
        assertEquals("the close must be held pending", path, viewModel.pendingClose.value)
        assertTrue("the tab must still be open", viewModel.buffers.value.containsKey(path))

        viewModel.cancelPendingClose()
        assertNull("keeping the edits clears the prompt", viewModel.pendingClose.value)
        assertTrue("the tab must survive a cancelled close", viewModel.buffers.value.containsKey(path))

        viewModel.closeTab(path)
        assertEquals(path, viewModel.pendingClose.value)
        viewModel.discardAndCloseTab()
        assertFalse("the confirmed close drops the buffer", viewModel.buffers.value.containsKey(path))
    }

    /** A clean tab holds nothing that is not already on disk, so it closes without a prompt. */
    @Test
    fun `closing a clean tab does not ask`() = runBlocking {
        val id = scaffoldProject("bench-close-clean")
        val viewModel = WorkbenchViewModel(container)
        viewModel.awaitProject(id)

        val path = viewModel.firstKotlinFile()
        viewModel.openFile(path)
        withTimeout(10_000) { viewModel.buffers.first { it.containsKey(path) } }

        viewModel.closeTab(path)
        assertNull("a clean close must not prompt", viewModel.pendingClose.value)
        assertFalse(viewModel.buffers.value.containsKey(path))
    }

    /**
     * The diagnostics panel follows the active tab. It used to be a single list that only an
     * analysis pass could replace, so switching to a file that was not re-analysed showed the
     * previous file's errors under the new file's name.
     */
    @Test
    fun `switching tabs swaps in that file's diagnostics`() = runBlocking {
        val id = scaffoldProject("bench-diag-switch")
        val viewModel = WorkbenchViewModel(container)
        viewModel.awaitProject(id)

        val files = withTimeout(10_000) {
            viewModel.projectTree.first { tree ->
                tree.flattened().count { !it.isDirectory && it.path.endsWith(".kt") } >= 2
            }
        }.flattened().filter { !it.isDirectory && it.path.endsWith(".kt") }.map { it.path }
        val first = files[0]
        val second = files[1]

        viewModel.openFile(first)
        withTimeout(10_000) { viewModel.buffers.first { it.containsKey(first) } }
        // An unbalanced brace is a CodeGuard error, so this file reports a diagnostic.
        viewModel.edit(first, viewModel.buffers.value.getValue(first).content + "\nfun broken() {\n")
        val firstDiags = withTimeout(10_000) {
            viewModel.diagnostics.first { list -> list.any { it.severity == Severity.ERROR } }
        }
        assertTrue("the edited file should report an error", firstDiags.isNotEmpty())

        viewModel.openFile(second)
        withTimeout(10_000) { viewModel.buffers.first { it.containsKey(second) } }
        withTimeout(10_000) { viewModel.diagnostics.first { it.isEmpty() } }
        assertEquals(
            "the clean file must not show the other file's errors",
            0,
            viewModel.diagnostics.value.size,
        )

        viewModel.activateTab(first)
        val back = withTimeout(10_000) { viewModel.diagnostics.first { it.isNotEmpty() } }
        assertEquals("returning to the broken file must bring its errors back", firstDiags.size, back.size)
    }

    /**
     * Analysis is debounced, but a slow analysis that has already started is not cancelled by
     * the next edit, so two can overlap. The older one used to publish last, leaving an error on
     * screen after the line was fixed. The analyser is injected so the first call is much slower
     * than the second, which makes the ordering deterministic rather than a race.
     */
    @Test
    fun `a stale analysis cannot overwrite newer diagnostics`() = runBlocking {
        val id = scaffoldProject("bench-diag-stale")
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val viewModel = WorkbenchViewModel(container) { _, _, path ->
            if (calls.getAndIncrement() == 0) {
                // Slow enough to still be running when the second analysis finishes.
                Thread.sleep(1_500)
                listOf(
                    Diagnostic(
                        filePath = path,
                        line = 1,
                        column = 1,
                        severity = Severity.ERROR,
                        message = "stale",
                        source = "test",
                    ),
                )
            } else {
                emptyList()
            }
        }
        viewModel.awaitProject(id)

        val path = viewModel.firstKotlinFile()
        viewModel.openFile(path)
        withTimeout(10_000) { viewModel.buffers.first { it.containsKey(path) } }

        // Past the 350 ms debounce, so the slow analysis has started, then edit again.
        viewModel.edit(path, viewModel.buffers.value.getValue(path).content + "\n")
        kotlinx.coroutines.delay(600)
        viewModel.edit(path, viewModel.buffers.value.getValue(path).content + "\n")

        withTimeout(10_000) {
            viewModel.diagnostics.first { list -> list.none { it.severity == Severity.ERROR } }
        }
        // Outlive the slow analysis so its late result has every chance to land.
        kotlinx.coroutines.delay(1_500)
        assertEquals(
            "the slow analysis of older text must not replace the newer clean result",
            0,
            viewModel.diagnostics.value.count { it.severity == Severity.ERROR },
        )
    }
}

/** Depth-first flattening used by assertions. */
internal fun List<ProjectNode>.flattened(): List<ProjectNode> =
    flatMap { node -> listOf(node) + node.children.flattened() }
