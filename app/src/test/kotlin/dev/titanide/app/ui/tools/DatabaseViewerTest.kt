package dev.titanide.app.ui.tools

import dev.titanide.app.testing.CloseContainersRule
import dev.titanide.app.testing.TestContainers
import dev.titanide.app.testing.ViewModelTracker
import dev.titanide.app.testing.resetMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The database viewer listed a row count for five tables and could only open two of them, so
 * a table with rows in it reported "No rows" - the count above contradicted the contents
 * below. Every listed table has to open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DatabaseViewerTest {

    @get:Rule
    val containers = CloseContainersRule()

    private val container = TestContainers.create()

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(Dispatchers.Default)
    }

    @After
    fun restoreMainDispatcher() {
        ViewModelTracker.clearAll()
        resetMainDispatcher()
    }

    private fun viewModel(): ToolViewModel {
        val viewModel = ViewModelTracker.track(ToolViewModel(container))
        runBlocking { withTimeout(10_000) { viewModel.state.first { !it.loading } } }
        return viewModel
    }

    private fun ToolViewModel.openAndAwait(table: String): List<String> {
        openTable(table)
        return runBlocking {
            withTimeout(10_000) { state.first { it.openTable == table && it.tableRows != null } }.tableRows!!
        }
    }

    @Test
    fun `every table that is counted can also be opened`() {
        // Give the two tables the viewer used to skip real rows to show.
        runBlocking {
            container.database.httpRequestDao().upsert(
                dev.titanide.core.data.db.HttpRequestEntity(
                    id = "req-1",
                    name = "GET example",
                    method = "GET",
                    url = "https://example.test/ping",
                    headersJson = "",
                    body = "",
                    updatedAt = 1L,
                ),
            )
            container.database.extensionDao().upsert(
                dev.titanide.core.data.db.ExtensionEntity(
                    id = "titan.ai",
                    name = "TITAN Intelligence",
                    version = "1.0.0",
                    publisher = "TITAN",
                    description = "",
                    kind = "ai-provider",
                    enabled = true,
                    installedAt = 1L,
                    manifestJson = "{}",
                ),
            )
        }
        val viewModel = viewModel()
        val counted = runBlocking { withTimeout(10_000) { viewModel.state.first { it.tables.isNotEmpty() } }.tables }

        counted.forEach { table ->
            val rows = viewModel.openAndAwait(table.name)
            if (table.rows == 0) {
                assertTrue(
                    "table ${table.name} is empty and must say so, got: $rows",
                    rows.size == 1 && rows.first().contains("empty", ignoreCase = true),
                )
            } else {
                assertEquals(
                    "table ${table.name} reports ${table.rows} rows, so opening it must show " +
                        "that many, got: $rows",
                    table.rows,
                    rows.size,
                )
            }
        }
    }

    @Test
    fun `an empty table says it is empty rather than claiming no rows exist`() {
        val viewModel = viewModel()

        val rows = viewModel.openAndAwait("http_requests")

        assertTrue(
            "an empty table must be described as empty, got: $rows",
            rows.size == 1 && rows.first().contains("empty", ignoreCase = true),
        )
    }
}
