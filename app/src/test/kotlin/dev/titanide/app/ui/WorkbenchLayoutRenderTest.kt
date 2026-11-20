package dev.titanide.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import dev.titanide.app.LocalContainer
import dev.titanide.app.di.TitanContainer
import dev.titanide.app.ui.workbench.WorkbenchScreen
import dev.titanide.app.ui.workbench.WorkbenchViewModel
import dev.titanide.core.designsystem.TitanTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders the workbench at the three size classes the layout policy distinguishes. Each
 * class takes a different branch (drawer vs persistent sidebar, agent sheet vs agent
 * column), so composing at each size is the only way to know all branches are valid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkbenchLayoutRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun renderAt(qualifiers: String) {
        val container = TitanContainer(ApplicationProvider.getApplicationContext())
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                TitanTheme {
                    WorkbenchScreen(
                        viewModel = WorkbenchViewModel(container),
                        onOpenSettings = {},
                        onOpenProjects = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().assertExists()
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun rendersOnASmallPhone() = renderAt("w360dp-h780dp-xhdpi")

    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun rendersInLandscape() = renderAt("w891dp-h411dp-xhdpi")

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun rendersOnATablet() = renderAt("w1280dp-h800dp-xhdpi")
}
