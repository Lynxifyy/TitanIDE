package dev.titanide.app.ui.workbench

import dev.titanide.app.di.TitanContainer
import dev.titanide.app.testing.CloseContainersRule
import dev.titanide.app.testing.TestContainers
import dev.titanide.app.testing.ViewModelTracker
import dev.titanide.app.testing.resetMainDispatcher
import dev.titanide.core.model.Language
import dev.titanide.core.model.ProjectCategory
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Go-to-definition, find-references and go-to-symbol run against real project files in the
 * real container. The editor could highlight and complete, but nothing answered "where is
 * this declared", and no UI exposed an answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SymbolNavigationTest {

    @get:Rule
    val containers = CloseContainersRule()

    private lateinit var container: TitanContainer

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        container = TestContainers.create()
    }

    @After
    fun tearDown() {
        ViewModelTracker.clearAll()
        resetMainDispatcher()
    }

    private val service = """
        package demo

        class UserService {
            fun loadUser(id: String): String {
                return id
            }
        }
    """.trimIndent()

    private val caller = """
        package demo

        class Controller {
            private val service = UserService()

            fun handle(id: String) {
                service.loadUser(id)
            }
        }
    """.trimIndent()

    private fun projectWith(id: String, vararg files: Pair<String, String>): WorkbenchViewModel = runBlocking {
        val outcome = container.scaffolder.create(
            CreateProjectSpec(
                name = "NavDemo",
                packageName = "dev.titanide.nav",
                category = ProjectCategory.BLANK,
                templateId = "blank.kotlin-jvm",
                language = Language.KOTLIN,
            ),
            id,
            container.storage.projectDir(id),
        )
        container.projectRepository.register((outcome as CreateProjectOutcome.Success).project)
        files.forEach { (path, content) -> File(container.storage.projectDir(id), path).writeText(content) }

        val viewModel = ViewModelTracker.track(WorkbenchViewModel(container))
        withTimeout(10_000) { viewModel.project.first { it?.id == id } }
        viewModel.refreshFiles()
        viewModel
    }

    private fun WorkbenchViewModel.openAndSettle(path: String) {
        openFile(path)
        runBlocking {
            withTimeout(10_000) {
                buffers.first { it.containsKey(path) }
                state.first { it.activeTab?.path == path }
            }
        }
    }

    private fun WorkbenchViewModel.awaitIndex() = runBlocking {
        withTimeout(10_000) {
            // buildSymbolIndex is asynchronous; poll until it has indexed the project.
            while (symbolResults.value.isEmpty() && !hasIndex()) kotlinx.coroutines.delay(20)
        }
    }

    private fun WorkbenchViewModel.hasIndex(): Boolean {
        searchSymbols("UserService")
        return symbolResults.value.isNotEmpty()
    }

    @Test
    fun `go to definition opens the declaring file`() = runBlocking {
        val viewModel = projectWith("nav-def", "UserService.kt" to service, "Controller.kt" to caller)
        viewModel.openAndSettle("Controller.kt")

        val caret = caller.indexOf("UserService()")
        viewModel.setCaret("Controller.kt", caret)
        viewModel.goToDefinition()

        withTimeout(10_000) {
            viewModel.state.first { it.activeTab?.path == "UserService.kt" }
        }
        assertEquals(
            "the declaration lives in another file, so that file must open",
            "UserService.kt",
            viewModel.state.value.activeTab?.path,
        )
    }

    @Test
    fun `go to definition moves the caret within the same file`() = runBlocking {
        val viewModel = projectWith("nav-same-file", "Sample.kt" to sample)
        viewModel.openAndSettle("Sample.kt")

        val callSite = sample.indexOf("compute()")
        viewModel.setCaret("Sample.kt", callSite)
        viewModel.goToDefinition()

        val declarationLine = sample.lines().indexOfFirst { it.contains("fun compute") } + 1
        withTimeout(10_000) {
            viewModel.buffers.first { it.getValue("Sample.kt").caretLine == declarationLine }
        }
        assertEquals(declarationLine, viewModel.buffers.value.getValue("Sample.kt").caretLine)
    }

    @Test
    fun `find references lists uses across files`() = runBlocking {
        val viewModel = projectWith("nav-refs", "UserService.kt" to service, "Controller.kt" to caller)
        viewModel.openAndSettle("Controller.kt")

        viewModel.setCaret("Controller.kt", caller.indexOf("UserService()"))
        viewModel.findReferences()

        withTimeout(10_000) { viewModel.references.first { it.isNotEmpty() } }
        val references = viewModel.references.value
        assertEquals("the declaration and the use should both be listed", 2, references.size)
        assertTrue(
            "the declaring file must be among the results: ${references.map { it.filePath }}",
            references.any { it.filePath == "UserService.kt" },
        )
        assertEquals("the panel is titled with the symbol name", "UserService", viewModel.referenceTarget.value)
    }

    @Test
    fun `closing the references panel clears the results`() = runBlocking {
        val viewModel = projectWith("nav-close", "UserService.kt" to service, "Controller.kt" to caller)
        viewModel.openAndSettle("Controller.kt")
        viewModel.setCaret("Controller.kt", caller.indexOf("UserService()"))
        viewModel.findReferences()
        withTimeout(10_000) { viewModel.references.first { it.isNotEmpty() } }

        viewModel.closeReferences()

        assertNull("the panel must be dismissed", viewModel.referenceTarget.value)
        assertTrue("its results must be dropped", viewModel.references.value.isEmpty())
    }

    @Test
    fun `go to symbol finds a declaration by name`() = runBlocking {
        val viewModel = projectWith("nav-symbols", "UserService.kt" to service, "Controller.kt" to caller)

        viewModel.searchSymbols("loadUser")
        withTimeout(10_000) { viewModel.symbolResults.first { it.isNotEmpty() } }

        val hit = viewModel.symbolResults.value.first()
        assertEquals("loadUser", hit.symbol.name)
        assertEquals("UserService.kt", hit.symbol.filePath)
    }

    @Test
    fun `an unknown symbol reports rather than silently doing nothing`() = runBlocking {
        val viewModel = projectWith("nav-unknown", "Sample.kt" to sample)
        viewModel.openAndSettle("Sample.kt")

        viewModel.setCaret("Sample.kt", sample.indexOf("package"))
        viewModel.goToDefinition()

        withTimeout(10_000) {
            viewModel.state.first { true }
            while (viewModel.message.value == null) kotlinx.coroutines.delay(20)
        }
        assertTrue(
            "the user must be told nothing was found: ${viewModel.message.value}",
            viewModel.message.value?.contains("No declaration") == true,
        )
    }

    private val sample = """
        package demo

        class Sample {
            fun compute(): Int = 7

            fun run(): Int = compute()
        }
    """.trimIndent()
}
