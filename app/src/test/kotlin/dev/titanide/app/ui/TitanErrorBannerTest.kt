package dev.titanide.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import dev.titanide.app.ui.common.TitanErrorBanner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Errors were rendered as bare text on the projects and create-project screens, and `clearError`
 * existed on both view models with no caller: a failure message stayed on screen with no way to
 * dismiss it. The banner is now the single place errors are shown, and these cases pin that the
 * dismiss control exists and actually reports back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class TitanErrorBannerTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the message is shown`() {
        compose.setContent { TitanErrorBanner(message = "Could not load projects") }
        compose.onNodeWithText("Could not load projects").assertIsDisplayed()
    }

    @Test
    fun `dismissing invokes the callback`() {
        var dismissed = 0
        compose.setContent {
            TitanErrorBanner(message = "Save failed", onDismiss = { dismissed++ })
        }

        compose.onNodeWithContentDescription("Dismiss error").performClick()

        assertEquals("the dismiss control must call back so the error can be cleared", 1, dismissed)
    }

    @Test
    fun `no dismiss control is offered when there is nothing to clear it with`() {
        // Without a callback the button would be a dead control, so it must not be rendered.
        compose.setContent { TitanErrorBanner(message = "Heads up") }
        compose.onNodeWithContentDescription("Dismiss error").assertDoesNotExist()
    }
}
