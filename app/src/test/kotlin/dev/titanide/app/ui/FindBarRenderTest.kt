package dev.titanide.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.titanide.app.di.TitanContainer
import dev.titanide.app.ui.workbench.BufferState
import dev.titanide.app.ui.workbench.EditorArea
import dev.titanide.app.ui.workbench.FindState
import dev.titanide.core.designsystem.TitanTheme
import dev.titanide.core.model.Language
import dev.titanide.core.workbench.EditorGroup
import dev.titanide.core.workbench.EditorTab
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The find bar is only useful if it is actually reachable on screen: the "Find" chip in the
 * status strip must open it, and it must show the match counter and replace controls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FindBarRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val tab = EditorTab(id = "a.kt", path = "a.kt", title = "a.kt")
    private val buffer = BufferState(
        path = "a.kt",
        content = "val count = 1\nval count = 2\n",
        language = Language.KOTLIN,
        savedContent = "val count = 1\nval count = 2\n",
    )

    private fun render(find: FindState, onQueryChange: (String) -> Unit = {}) {
        compose.setContent {
            TitanTheme {
                EditorArea(
                    group = EditorGroup(0).open(tab),
                    buffer = buffer,
                    diagnostics = emptyList(),
                    rootName = "demo",
                    onSelectTab = {},
                    onCloseTab = {},
                    onChange = { _, _ -> },
                    onSave = {},
                    onToggleComment = {},
                    onFormat = {},
                    find = find,
                    onFindQueryChange = onQueryChange,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun theFindChipIsOfferedWhenFindIsClosed() {
        render(FindState(open = false))
        compose.onNodeWithText("Find").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun theOpenFindBarShowsTheMatchCounterAndStepping() {
        render(FindState(open = true, query = "count", matches = listOf(4..8, 20..24), activeIndex = 0))

        compose.onNodeWithText("1/2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next match").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous match").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close find").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun replaceControlsAreHiddenInPlainFindMode() {
        render(FindState(open = true, query = "count", matches = listOf(4..8)))
        compose.onNodeWithText("All").assertDoesNotExist()
        compose.onNodeWithText("Replace").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun replaceControlsAppearInReplaceMode() {
        render(FindState(open = true, query = "count", replaceMode = true, matches = listOf(4..8)))
        compose.onNodeWithText("All").assertIsDisplayed()
        compose.onNodeWithText("Replace").assertIsDisplayed()
    }
}
