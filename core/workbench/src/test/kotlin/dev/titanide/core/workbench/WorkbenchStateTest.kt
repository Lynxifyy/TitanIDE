package dev.titanide.core.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutSelectionTest {

    @Test
    fun `phone portrait is the default for a tall narrow window`() {
        assertEquals(WorkbenchLayout.PHONE_PORTRAIT, WorkbenchLayout.forSize(411, 891))
    }

    @Test
    fun `phone landscape is chosen when the window is wide and short`() {
        assertEquals(WorkbenchLayout.PHONE_LANDSCAPE, WorkbenchLayout.forSize(891, 411))
    }

    @Test
    fun `tablet is chosen from 840dp wide in either orientation`() {
        assertEquals(WorkbenchLayout.TABLET, WorkbenchLayout.forSize(840, 1200))
        assertEquals(WorkbenchLayout.TABLET, WorkbenchLayout.forSize(1200, 840))
    }

    @Test
    fun `600dp portrait is a tablet because there is room for two panels`() {
        assertEquals(WorkbenchLayout.TABLET, WorkbenchLayout.forSize(600, 960))
    }

    @Test
    fun `tablet policy shows the agent panel and allows two editor groups`() {
        val policy = LayoutPolicy.of(WorkbenchLayout.TABLET)
        assertTrue(policy.showAgentPanel)
        assertTrue(policy.activityBarExpanded)
        assertEquals(2, policy.maxEditorGroups)
    }

    @Test
    fun `phone portrait hides the sidebar and agent panel by default`() {
        val policy = LayoutPolicy.of(WorkbenchLayout.PHONE_PORTRAIT)
        assertFalse(policy.showSidebar)
        assertFalse(policy.showAgentPanel)
        assertEquals(1, policy.maxEditorGroups)
    }
}

class EditorGroupTest {

    private fun tab(path: String) = EditorTab(id = path, path = path, title = path.substringAfterLast('/'))

    @Test
    fun `opening the same path twice does not duplicate the tab`() {
        val group = EditorGroup().open(tab("a/Main.kt")).open(tab("a/Main.kt"))
        assertEquals(1, group.tabs.size)
    }

    @Test
    fun `opening a second file activates it`() {
        val group = EditorGroup().open(tab("A.kt")).open(tab("B.kt"))
        assertEquals("B.kt", group.activeTab?.path)
    }

    @Test
    fun `reopening an existing file activates it instead of appending`() {
        val group = EditorGroup().open(tab("A.kt")).open(tab("B.kt")).open(tab("A.kt"))
        assertEquals(2, group.tabs.size)
        assertEquals("A.kt", group.activeTab?.path)
    }

    @Test
    fun `closing the active tab activates the next neighbour`() {
        val group = EditorGroup().open(tab("A.kt")).open(tab("B.kt")).open(tab("C.kt")).activate("B.kt")
        val afterClose = group.close("B.kt")
        assertEquals(listOf("A.kt", "C.kt"), afterClose.tabs.map { it.path })
        assertEquals("C.kt", afterClose.activeTab?.path)
    }

    @Test
    fun `closing the last tab leaves no active tab`() {
        val group = EditorGroup().open(tab("Only.kt")).close("Only.kt")
        assertTrue(group.tabs.isEmpty())
        assertNull(group.activeTab)
    }

    @Test
    fun `closing a non active tab keeps the active selection`() {
        val group = EditorGroup().open(tab("A.kt")).open(tab("B.kt")).activate("A.kt")
        assertEquals("A.kt", group.close("B.kt").activeTab?.path)
    }

    @Test
    fun `marking dirty is per path and reversible`() {
        val group = EditorGroup().open(tab("A.kt")).open(tab("B.kt")).markDirty("A.kt", true)
        assertTrue(group.tabs.first { it.path == "A.kt" }.dirty)
        assertFalse(group.tabs.first { it.path == "B.kt" }.dirty)
        assertFalse(group.markDirty("A.kt", false).tabs.first { it.path == "A.kt" }.dirty)
    }

    @Test
    fun `close others keeps pinned tabs`() {
        val group = EditorGroup().open(tab("A.kt"))
            .open(tab("B.kt"))
            .open(tab("C.kt").copy(pinned = true))
            .closeOthers("A.kt")
        assertEquals(setOf("A.kt", "C.kt"), group.tabs.map { it.path }.toSet())
    }
}

class WorkbenchStateTest {

    private fun state() = WorkbenchState().withLayout(411, 891)

    @Test
    fun `opening a file closes the phone sidebar`() {
        val opened = state().selectActivity(Activity.EXPLORER).openFile("src/Main.kt", "kotlin")
        assertFalse(opened.sidebarVisible)
        assertEquals("Main.kt", opened.activeTab?.title)
        assertEquals("kotlin", opened.activeTab?.languageId)
    }

    @Test
    fun `tapping the active activity toggles the sidebar`() {
        val opened = state().selectActivity(Activity.SEARCH)
        assertTrue(opened.sidebarVisible)
        val toggled = opened.selectActivity(Activity.SEARCH)
        assertFalse(toggled.sidebarVisible)
    }

    @Test
    fun `phone portrait cannot split the editor`() {
        val split = state().splitEditor()
        assertEquals(1, split.groups.size)
    }

    @Test
    fun `tablet can split into two groups and close back to one`() {
        val tablet = WorkbenchState().withLayout(1000, 800)
        val split = tablet.splitEditor()
        assertEquals(2, split.groups.size)
        assertEquals(1, split.closeSplit().groups.size)
    }

    @Test
    fun `shrinking to portrait collapses extra editor groups`() {
        val tablet = WorkbenchState().withLayout(1000, 800).splitEditor()
        assertEquals(2, tablet.groups.size)
        val shrunk = tablet.withLayout(411, 891)
        assertEquals(1, shrunk.groups.size)
    }

    @Test
    fun `open tab count spans all groups`() {
        val tablet = WorkbenchState().withLayout(1000, 800)
            .openFile("A.kt", "kotlin")
            .splitEditor()
        assertEquals(1, tablet.openTabCount)
    }

    @Test
    fun `showing a bottom tool expands the panel`() {
        val shown = state().showBottom(BottomTool.PROBLEMS)
        assertTrue(shown.bottomPanelExpanded)
        assertEquals(BottomTool.PROBLEMS, shown.bottomTool)
    }

    @Test
    fun `toggling the bottom panel flips expansion`() {
        val state = state().toggleBottom()
        assertTrue(state.bottomPanelExpanded)
        assertFalse(state.toggleBottom().bottomPanelExpanded)
    }

    @Test
    fun `dirty state is tracked by path across groups`() {
        val dirty = state().openFile("src/Main.kt", "kotlin").setDirty("src/Main.kt", true)
        assertTrue(dirty.activeTab?.dirty == true)
    }
}

class BreadcrumbTest {

    @Test
    fun `path splits into segments`() {
        assertEquals(
            listOf("app", "src", "main", "Main.kt"),
            breadcrumbFor("app/src/main/Main.kt"),
        )
    }

    @Test
    fun `root name is prepended when absent`() {
        assertEquals(listOf("TitanIDE", "app", "Main.kt"), breadcrumbFor("app/Main.kt", "TitanIDE"))
    }

    @Test
    fun `root name is not duplicated`() {
        assertEquals(listOf("TitanIDE", "Main.kt"), breadcrumbFor("TitanIDE/Main.kt", "TitanIDE"))
    }

    @Test
    fun `blank path yields just the root`() {
        assertEquals(listOf("TitanIDE"), breadcrumbFor("", "TitanIDE"))
    }
}

class LanguageDetectorTest {

    @Test
    fun `gradle kts resolves to kotlin not groovy`() {
        assertEquals("kotlin", LanguageDetector.detect("build.gradle.kts"))
    }

    @Test
    fun `groovy gradle resolves to groovy`() {
        assertEquals("groovy", LanguageDetector.detect("build.gradle"))
    }

    @Test
    fun `common languages map correctly`() {
        assertEquals("kotlin", LanguageDetector.detect("Main.kt"))
        assertEquals("java", LanguageDetector.detect("Main.java"))
        assertEquals("cpp", LanguageDetector.detect("native.cpp"))
        assertEquals("json", LanguageDetector.detect("data.json"))
        assertEquals("xml", LanguageDetector.detect("AndroidManifest.xml"))
    }

    @Test
    fun `case does not matter`() {
        assertEquals("kotlin", LanguageDetector.detect("MAIN.KT"))
    }

    @Test
    fun `unknown extension falls back to plaintext`() {
        assertEquals("plaintext", LanguageDetector.detect("mystery.qqq"))
        assertEquals("plaintext", LanguageDetector.detect("noextension"))
    }

    @Test
    fun `display names are human readable`() {
        assertEquals("Kotlin", LanguageDetector.displayName("kotlin"))
        assertEquals("C++", LanguageDetector.displayName("cpp"))
        assertEquals("Plain Text", LanguageDetector.displayName("plaintext"))
    }
}

class AndroidProjectViewTest {

    private val files = listOf(
        "app/src/main/AndroidManifest.xml",
        "app/src/main/kotlin/dev/titanide/app/MainActivity.kt",
        "app/src/main/java/dev/titanide/Legacy.java",
        "app/src/main/res/layout/activity_main.xml",
        "app/src/main/res/values/strings.xml",
        "app/src/main/assets/config.json",
        "app/build.gradle.kts",
        "settings.gradle.kts",
        "gradle/libs.versions.toml",
        "app/build/generated/source/BuildConfig.kt",
        "README.md",
    )

    @Test
    fun `manifest is classified as a manifest`() {
        assertEquals(AndroidGroup.MANIFESTS, AndroidProjectView.classify("app/src/main/AndroidManifest.xml"))
    }

    @Test
    fun `kotlin under src is source`() {
        assertEquals(AndroidGroup.SOURCES, AndroidProjectView.classify("app/src/main/kotlin/dev/A.kt"))
    }

    @Test
    fun `generated code is separated from hand written source`() {
        assertEquals(
            AndroidGroup.GENERATED,
            AndroidProjectView.classify("app/build/generated/source/BuildConfig.kt"),
        )
    }

    @Test
    fun `res xml is a resource not a source file`() {
        assertEquals(AndroidGroup.RESOURCES, AndroidProjectView.classify("app/src/main/res/layout/activity_main.xml"))
    }

    @Test
    fun `assets are their own group`() {
        assertEquals(AndroidGroup.ASSETS, AndroidProjectView.classify("app/src/main/assets/config.json"))
    }

    @Test
    fun `gradle scripts are grouped`() {
        assertEquals(AndroidGroup.GRADLE, AndroidProjectView.classify("app/build.gradle.kts"))
        assertEquals(AndroidGroup.GRADLE, AndroidProjectView.classify("gradle/libs.versions.toml"))
        assertEquals(AndroidGroup.GRADLE, AndroidProjectView.classify("gradle/wrapper/gradle-wrapper.properties"))
    }

    @Test
    fun `build produces groups in Android Studio order`() {
        val tree = AndroidProjectView.build(files)
        val labels = tree.map { it.name }
        assertEquals(
            listOf("Manifests", "Kotlin+Java", "Generated", "res", "Assets", "Gradle Scripts", "Other"),
            labels,
        )
    }

    @Test
    fun `every file appears exactly once across the tree`() {
        val tree = AndroidProjectView.build(files)
        val leaves = tree.flatMap { collectLeaves(it) }
        assertEquals(files.size, leaves.size)
        assertEquals(files.toSet(), leaves.toSet())
    }

    @Test
    fun `nested directories become nested nodes`() {
        val tree = AndroidProjectView.build(files)
        val sources = tree.first { it.name == "Kotlin+Java" }
        val app = sources.children.first { it.name == "app" }
        val src = app.children.first { it.name == "src" }
        assertTrue(src.children.any { it.name == "main" })
    }

    @Test
    fun `project view keeps the real filesystem hierarchy`() {
        val tree = AndroidProjectView.buildProjectView(files)
        // Directories first, then files, each case-insensitively sorted.
        assertEquals(listOf("app", "gradle", "README.md", "settings.gradle.kts"), tree.map { it.name })
    }

    @Test
    fun `empty input yields an empty tree`() {
        assertTrue(AndroidProjectView.build(emptyList()).isEmpty())
        assertTrue(AndroidProjectView.buildProjectView(emptyList()).isEmpty())
    }

    private fun collectLeaves(node: ProjectNode): List<String> =
        if (node.isDirectory) node.children.flatMap { collectLeaves(it) } else listOf(node.path)
}
