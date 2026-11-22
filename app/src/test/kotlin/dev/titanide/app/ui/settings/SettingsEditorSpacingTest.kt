package dev.titanide.app.ui.settings

import dev.titanide.app.di.TitanContainer
import dev.titanide.app.testing.CloseContainersRule
import dev.titanide.app.testing.TestContainers
import dev.titanide.app.testing.ViewModelTracker
import dev.titanide.app.testing.resetMainDispatcher
import dev.titanide.core.data.settings.EditorSettings
import dev.titanide.core.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Line spacing is presented as a percentage of the font size, so the percentage is what has to
 * survive a write.
 *
 * It did not, for two reasons. It was stored as an absolute sp height, which cannot represent
 * every percentage (175% of 9sp is 15.75sp, which came back as 166%), and changing the font size
 * left the old height in place, so the chips silently rescaled from 200% to 130%.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsEditorSpacingTest {

    @get:Rule
    val containers = CloseContainersRule()

    private lateinit var container: TitanContainer

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        container = TestContainers.create()
    }

    @After
    fun tearDown() {
        ViewModelTracker.clearAll()
        resetMainDispatcher()
    }

    /**
     * Settings writes are suspending, so the value is polled until it lands. Reading once right
     * after a setter raced the DataStore write and asserted against the previous value.
     */
    private fun awaitEditor(predicate: (EditorSettings) -> Boolean): EditorSettings = runBlocking {
        withTimeout(10_000) {
            var current = container.settings.settings.first().editor
            while (!predicate(current)) {
                delay(20)
                current = container.settings.settings.first().editor
            }
            current
        }
    }

    private fun awaitSpacing(fontSize: Int, percent: Int): EditorSettings =
        awaitEditor { it.fontSize == fontSize && it.lineSpacingPercent == percent }

    private fun newViewModel(): SettingsViewModel =
        ViewModelTracker.track(SettingsViewModel(container))

    @Test
    fun `changing the font size keeps the chosen line spacing`() {
        val viewModel = newViewModel()
        viewModel.setEditorLineSpacing(200)
        awaitSpacing(fontSize = 13, percent = 200)

        viewModel.setEditorFontSize(20)
        val after = awaitEditor { it.fontSize == 20 }

        assertEquals(
            "changing the font size must not rescale the line spacing the user chose",
            200,
            after.lineSpacingPercent,
        )
    }

    @Test
    fun `200 percent survives the largest font size`() {
        val viewModel = newViewModel()
        viewModel.setEditorFontSize(28)
        awaitEditor { it.fontSize == 28 }

        viewModel.setEditorLineSpacing(200)
        val after = awaitEditor { it.lineSpacingPercent == 200 }

        assertEquals("the font size must have been accepted", 28, after.fontSize)
    }

    @Test
    fun `spacing round-trips at every offered step`() {
        val viewModel = newViewModel()
        for (fontSize in listOf(9, 13, 20, 28)) {
            viewModel.setEditorFontSize(fontSize)
            awaitEditor { it.fontSize == fontSize }
            for (percent in listOf(100, 130, 150, 175, 200)) {
                viewModel.setEditorLineSpacing(percent)
                val stored = awaitEditor {
                    it.fontSize == fontSize && it.lineSpacingPercent == percent
                }
                assertEquals("font ${fontSize}sp at $percent%", percent, stored.lineSpacingPercent)
            }
        }
    }

    @Test
    fun `the offered percentages are clamped, not stored raw`() {
        val viewModel = newViewModel()
        viewModel.setEditorLineSpacing(900)
        awaitEditor { it.lineSpacingPercent == SettingsRepository.MAX_LINE_SPACING_PERCENT }

        viewModel.setEditorLineSpacing(10)
        awaitEditor { it.lineSpacingPercent == SettingsRepository.MIN_LINE_SPACING_PERCENT }
    }

    @Test
    fun `the drawn height follows the percentage`() {
        val viewModel = newViewModel()
        viewModel.setEditorFontSize(13)
        awaitEditor { it.fontSize == 13 }
        viewModel.setEditorLineSpacing(200)

        assertEquals("200% of 13sp", 26, awaitSpacing(fontSize = 13, percent = 200).lineHeight)
    }
}
