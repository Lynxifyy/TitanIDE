package dev.titanide.app.ui.tools

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.titanide.app.LocalContainer
import dev.titanide.app.extensions.CapabilityGate
import dev.titanide.app.extensions.TitanCapability
import dev.titanide.app.testing.CloseContainersRule
import dev.titanide.app.testing.TestContainers
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Developer Tools" is listed in Extensions as a switch, and the catalogue names the tools as
 * its effect. The tools screen ignored the switch, so turning it off changed a row in a list
 * and nothing else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class ToolsCapabilityTest {

    @get:Rule
    val containers = CloseContainersRule()

    @get:Rule
    val compose = createComposeRule()

    private val container = TestContainers.create()

    private fun renderTools(gate: CapabilityGate) = compose.setContent {
        CompositionLocalProvider(LocalContainer provides container) {
            ToolsScreen(
                onBack = {},
                onOpenApiTester = {},
                onOpenAssetStudio = {},
                onOpenDatabase = {},
                onOpenPreview = {},
                onOpenDocs = {},
                gate = gate,
            )
        }
    }

    @Test
    fun `the tools are reachable while the extension is on`() {
        renderTools(CapabilityGate.ALL_ENABLED)

        compose.onNodeWithText("API Tester").assertIsDisplayed()
    }

    @Test
    fun `switching the extension off replaces the tools with an explanation`() {
        renderTools(CapabilityGate { capability -> capability != TitanCapability.DEV_TOOLS })

        compose.onNodeWithText("API Tester").assertDoesNotExist()
        assertTrue(
            "the screen must say why it is empty, not just be empty",
            compose.onAllNodesWithText("Developer Tools", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
