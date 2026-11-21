package dev.titanide.app.ui.workbench

import androidx.test.core.app.ApplicationProvider
import dev.titanide.app.di.TitanContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The editor settings screen offers font size, line height, line numbers and word wrap, but the
 * workbench editor used to hardcode them, so changing a setting did nothing. These tests change
 * the real persisted settings and assert the editor state follows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EditorAppearanceWiringTest {

    private lateinit var container: TitanContainer

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        container = TitanContainer(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        runBlocking { container.settings.updateEditor { dev.titanide.core.data.settings.EditorSettings() } }
        Dispatchers.resetMain()
    }

    private fun viewModel(): WorkbenchViewModel = WorkbenchViewModel(container)

    @Test
    fun `font size and line height come from settings`() = runBlocking {
        runBlocking { container.settings.updateEditor { it.copy(fontSize = 18, lineHeight = 26) } }

        val viewModel = viewModel()
        val appearance = withTimeout(10_000) {
            viewModel.appearance.first { it.fontSizeSp == 18 }
        }
        assertEquals(18, appearance.fontSizeSp)
        assertEquals(26, appearance.lineHeightSp)
    }

    @Test
    fun `line numbers and word wrap come from settings`() = runBlocking {
        runBlocking {
            container.settings.updateEditor { it.copy(showLineNumbers = false, wordWrap = true) }
        }

        val viewModel = viewModel()
        val appearance = withTimeout(10_000) {
            viewModel.appearance.first { !it.showLineNumbers }
        }
        assertFalse("line numbers must follow the setting", appearance.showLineNumbers)
        assertTrue("word wrap must follow the setting", appearance.wordWrap)
    }

    @Test
    fun `an out of range font size is clamped rather than applied`() = runBlocking {
        runBlocking { container.settings.updateEditor { it.copy(fontSize = 400, lineHeight = 1) } }

        val viewModel = viewModel()
        val appearance = withTimeout(10_000) {
            viewModel.appearance.first { it.fontSizeSp == 32 }
        }
        assertEquals("a corrupt value must not make the editor unreadable", 32, appearance.fontSizeSp)
        assertEquals(12, appearance.lineHeightSp)
    }
}
