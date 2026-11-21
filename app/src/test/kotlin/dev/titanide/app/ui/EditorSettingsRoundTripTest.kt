package dev.titanide.app.ui

import androidx.test.core.app.ApplicationProvider
import dev.titanide.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The editor settings were stored but never reached the editor: font size, tab width, wrap
 * and line numbers were fixed in the composable. These tests check the value a user picks
 * survives a write and read, which is the half of the path that can be verified without a
 * device. The other half (applying it to the text field) is a direct state read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EditorSettingsRoundTripTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `tab width round trips through storage`() = runTest {
        val settings = SettingsRepository(context)
        settings.updateEditor { it.copy(tabWidth = 8) }
        assertEquals(8, settings.settings.first().editor.tabWidth)
    }

    @Test
    fun `word wrap round trips through storage`() = runTest {
        val settings = SettingsRepository(context)
        assertFalse("wrapping is off by default", settings.settings.first().editor.wordWrap)
        settings.updateEditor { it.copy(wordWrap = true) }
        assertTrue(settings.settings.first().editor.wordWrap)
    }

    @Test
    fun `line numbers round trip through storage`() = runTest {
        val settings = SettingsRepository(context)
        settings.updateEditor { it.copy(showLineNumbers = false) }
        assertFalse(settings.settings.first().editor.showLineNumbers)
    }

    @Test
    fun `line height round trips so spacing chips reflect the real value`() = runTest {
        val settings = SettingsRepository(context)
        settings.updateEditor { it.copy(fontSize = 20, lineHeight = 30) }
        val editor = settings.settings.first().editor
        assertEquals(20, editor.fontSize)
        assertEquals(30, editor.lineHeight)
    }

    @Test
    fun `a tab width outside the supported range is clamped, not stored`() = runTest {
        val settings = SettingsRepository(context)
        settings.updateEditor { it.copy(tabWidth = 1) }
        assertEquals("tab width below the minimum must clamp", 2, settings.settings.first().editor.tabWidth)
        settings.updateEditor { it.copy(tabWidth = 99) }
        assertEquals("tab width above the maximum must clamp", 8, settings.settings.first().editor.tabWidth)
    }
}
