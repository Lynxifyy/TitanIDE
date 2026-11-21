package dev.titanide.app.ui.workbench

import androidx.test.core.app.ApplicationProvider
import dev.titanide.app.di.TitanContainer
import dev.titanide.core.templates.CreateProjectOutcome
import dev.titanide.core.templates.CreateProjectSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Find/replace and jump-to-line run against real files through the real container. A project
 * search hit used to open the file at the top, and there was no in-file find at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FindAndRevealTest {

    private lateinit var container: TitanContainer

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        container = TitanContainer(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val sample = """
        fun alpha() {
            val count = 1
        }

        fun beta() {
            val count = 2
        }
    """.trimIndent()

    private fun projectWith(id: String, path: String, content: String): WorkbenchViewModel = runBlocking {
        val outcome = container.scaffolder.create(
            CreateProjectSpec(
                name = "FindDemo",
                packageName = "dev.titanide.find",
                category = dev.titanide.core.model.ProjectCategory.BLANK,
                templateId = "blank.kotlin-jvm",
                language = dev.titanide.core.model.Language.KOTLIN,
            ),
            id,
            container.storage.projectDir(id),
        )
        container.projectRepository.register((outcome as CreateProjectOutcome.Success).project)
        File(container.storage.projectDir(id), path).writeText(content)

        val viewModel = WorkbenchViewModel(container)
        withTimeout(10_000) { viewModel.project.first { it?.id == id } }
        viewModel.openFile(path)
        // openFile publishes the buffer just before the tab, so wait for both or the
        // find helpers below see no active tab.
        withTimeout(10_000) {
            viewModel.buffers.first { it.containsKey(path) }
            viewModel.state.first { it.activeTab?.path == path }
        }
        viewModel
    }

    @Test
    fun `a search hit puts the caret on the matched line`() = runBlocking {
        val viewModel = projectWith("find-reveal", "Sample.kt", sample)
        val line = sample.lines().indexOfFirst { it.contains("fun beta") } + 1

        val before = viewModel.revealRequests.value
        viewModel.openFileAtLine("Sample.kt", line)

        withTimeout(10_000) {
            viewModel.buffers.first { it.getValue("Sample.kt").caretLine == line }
        }
        assertEquals(
            "the caret must land on the matched line, not the top of the file",
            line,
            viewModel.buffers.value.getValue("Sample.kt").caretLine,
        )
        assertTrue("the editor must be asked to scroll to it", viewModel.revealRequests.value > before)
    }

    @Test
    fun `find counts every occurrence and steps through them`() = runBlocking {
        val viewModel = projectWith("find-steps", "Sample.kt", sample)
        viewModel.openFind()

        viewModel.setFindQuery("count")
        val state = viewModel.find.value
        assertEquals("both declarations must be found", 2, state.count)
        assertEquals(0, state.activeIndex)

        viewModel.findNext()
        assertEquals(1, viewModel.find.value.activeIndex)
        // Wraps rather than dead-ending on the last match.
        viewModel.findNext()
        assertEquals(0, viewModel.find.value.activeIndex)

        viewModel.findPrevious()
        assertEquals("previous must wrap backwards", 1, viewModel.find.value.activeIndex)
    }

    @Test
    fun `replace current rewrites one occurrence and keeps the rest`() = runBlocking {
        val viewModel = projectWith("find-replace-one", "Sample.kt", sample)
        viewModel.openFind(replaceMode = true)
        viewModel.setFindQuery("count")
        viewModel.setFindReplacement("total")

        viewModel.replaceCurrent()

        val content = viewModel.buffers.value.getValue("Sample.kt").content
        assertEquals(1, Regex("\\btotal\\b").findAll(content).count())
        assertEquals("the second occurrence must survive", 1, Regex("\\bcount\\b").findAll(content).count())
        assertTrue("the edit must be dirty so auto-save picks it up", viewModel.buffers.value.getValue("Sample.kt").dirty)
    }

    @Test
    fun `replace all rewrites every occurrence`() = runBlocking {
        val viewModel = projectWith("find-replace-all", "Sample.kt", sample)
        viewModel.openFind(replaceMode = true)
        viewModel.setFindQuery("val ")
        viewModel.setFindReplacement("var ")

        viewModel.replaceAll()

        val content = viewModel.buffers.value.getValue("Sample.kt").content
        assertEquals(0, Regex("\\bval\\b").findAll(content).count())
        assertEquals(2, Regex("\\bvar\\b").findAll(content).count())
    }

    @Test
    fun `a replacement containing dollar signs is inserted literally`() = runBlocking {
        val viewModel = projectWith("find-replace-literal", "Money.kt", "val price = AMOUNT\n")
        viewModel.openFind(replaceMode = true)
        viewModel.setFindQuery("AMOUNT")
        viewModel.setFindReplacement("\$1,000")

        viewModel.replaceAll()

        assertEquals(
            "the replacement is literal text, not a regex group reference",
            "val price = \$1,000\n",
            viewModel.buffers.value.getValue("Money.kt").content,
        )
    }

    @Test
    fun `closing find clears the match list`() = runBlocking {
        val viewModel = projectWith("find-close", "Sample.kt", sample)
        viewModel.openFind()
        viewModel.setFindQuery("count")
        assertEquals(2, viewModel.find.value.count)

        viewModel.closeFind()

        assertEquals(0, viewModel.find.value.count)
        assertTrue(!viewModel.find.value.open)
    }
}
