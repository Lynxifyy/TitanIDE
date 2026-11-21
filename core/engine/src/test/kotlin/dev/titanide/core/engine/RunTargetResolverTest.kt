package dev.titanide.core.engine

import dev.titanide.core.data.files.ProjectDetector
import dev.titanide.core.model.BuildKind
import dev.titanide.core.model.BuildRequest
import dev.titanide.core.model.ExecutionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * RUN used to be answered with a literal "run" task for every build system. That is right
 * for `cargo run` and wrong for almost everything else: `python -m run` and `mvn run` do not
 * exist, and `npm start` only works because the generated templates happen to define it.
 * These cases pin the resolution to the files actually present in the project.
 */
class RunTargetResolverTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun project(name: String): File = temp.newFolder(name)

    private fun request(root: File, kind: BuildKind = BuildKind.RUN) = BuildRequest(
        projectId = "p",
        projectPath = root.absolutePath,
        kind = kind,
        target = ExecutionTarget.LOCAL,
    )

    private fun plan(root: File, kind: BuildKind = BuildKind.RUN) =
        BuildPlanner.plan(request(root, kind), root, ProjectDetector.detect(root))

    // ------------------------------------------------------------------ python

    @Test
    fun `python runs through the console script declared in pyproject`() {
        val root = project("py-script")
        File(root, "pyproject.toml").writeText(
            """
            [project]
            name = "demo"

            [project.scripts]
            demo = "demo.main:main"
            """.trimIndent(),
        )
        File(root, "demo").mkdirs()
        File(root, "demo/__init__.py").writeText("")

        val target = RunTargetResolver.resolve(root, "pip")
        assertNotNull("a console script is a runnable entry point", target)
        assertEquals(listOf("python3", "-m", "demo.main:main"), listOf(target!!.program) + target.args)
    }

    @Test
    fun `python falls back to a package main module then to main py`() {
        val pkg = project("py-pkg")
        File(pkg, "pyproject.toml").writeText("[project]\nname = \"demo\"\n")
        File(pkg, "demo").mkdirs()
        File(pkg, "demo/__main__.py").writeText("print('hi')\n")
        assertEquals(
            listOf("python3", "-m", "demo"),
            RunTargetResolver.resolve(pkg, "pip")!!.let { listOf(it.program) + it.args },
        )

        val flat = project("py-flat")
        File(flat, "requirements.txt").writeText("")
        File(flat, "main.py").writeText("print('hi')\n")
        assertEquals(
            listOf("python3", "main.py"),
            RunTargetResolver.resolve(flat, "pip")!!.let { listOf(it.program) + it.args },
        )
    }

    @Test
    fun `python with no entry point reports that nothing is runnable`() {
        val root = project("py-none")
        File(root, "requirements.txt").writeText("")

        val planned = plan(root)
        assertNull("there must be no command to run", planned.commands.firstOrNull())
        assertNotNull("the user must be told why", planned.error)
        assertTrue(
            "the reason must mention runnability, was '${planned.error}'",
            planned.error!!.contains("runnable"),
        )
    }

    @Test
    fun `python never plans the non-existent run module`() {
        val root = project("py-nomodule")
        File(root, "requirements.txt").writeText("")
        File(root, "main.py").writeText("print('hi')\n")

        val planned = plan(root)
        val display = planned.commands.firstOrNull()?.display.orEmpty()
        assertTrue("planned '$display'", display.isNotBlank())
        assertTrue("'-m run' is not a real module", !display.contains("-m run"))
    }

    // -------------------------------------------------------------------- node

    @Test
    fun `npm requires an explicit start script`() {
        val withStart = project("npm-start")
        File(withStart, "package.json").writeText("""{"scripts":{"start":"node src/index.js"}}""")
        assertEquals(
            listOf("npm", "start"),
            RunTargetResolver.resolve(withStart, "npm")!!.let { listOf(it.program) + it.args },
        )

        val withoutStart = project("npm-nostart")
        File(withoutStart, "package.json").writeText("""{"scripts":{"build":"tsc"}}""")
        assertNull(
            "npm start would silently fall back to node server.js, so this is not runnable",
            RunTargetResolver.resolve(withoutStart, "npm"),
        )
    }

    // ------------------------------------------------------------------- cmake

    @Test
    fun `cmake runs the declared executable from the build directory`() {
        val root = project("cmake")
        File(root, "CMakeLists.txt").writeText(
            """
            cmake_minimum_required(VERSION 3.20)
            project(demo CXX)
            add_library(core src/core.cpp)
            add_executable(demo_app src/main.cpp)
            """.trimIndent(),
        )
        File(root, "build").mkdirs()

        val target = RunTargetResolver.resolve(root, "cmake")!!
        assertEquals("./demo_app", target.program)
        assertEquals("build", target.workDir)

        val planned = plan(root)
        assertEquals("./demo_app", planned.commands.single().program)
        assertEquals("build", planned.commands.single().workDir)
    }

    @Test
    fun `cmake without add_executable is not runnable`() {
        val root = project("cmake-lib")
        File(root, "CMakeLists.txt").writeText("add_library(core src/core.cpp)\n")
        assertNull(RunTargetResolver.resolve(root, "cmake"))
        assertNotNull("the planner must explain the failure", plan(root).error)
    }

    // -------------------------------------------------------------------- make

    @Test
    fun `make needs a run target in the makefile`() {
        val withRun = project("make-run")
        File(withRun, "Makefile").writeText("all:\n\ttrue\n\nrun:\n\t./app\n")
        assertEquals("make run", RunTargetResolver.resolve(withRun, "make")!!.display)

        val withoutRun = project("make-norun")
        File(withoutRun, "Makefile").writeText("all:\n\ttrue\n")
        assertNull(
            "'make run' does not exist here, so planning it would be a fabricated command",
            RunTargetResolver.resolve(withoutRun, "make"),
        )
    }

    // ------------------------------------------------------------------ others

    @Test
    fun `cargo and dotnet run themselves`() {
        val cargo = project("cargo")
        File(cargo, "Cargo.toml").writeText("[package]\nname = \"demo\"\n")
        assertEquals("cargo run", RunTargetResolver.resolve(cargo, "cargo")!!.display)

        val dotnet = project("dotnet")
        File(dotnet, "demo.csproj").writeText("<Project />")
        assertEquals("dotnet run", RunTargetResolver.resolve(dotnet, "dotnet")!!.display)
    }

    @Test
    fun `go only runs when a main package exists`() {
        val withMain = project("go-main")
        File(withMain, "go.mod").writeText("module demo\n")
        File(withMain, "main.go").writeText("package main\n\nfunc main() {}\n")
        assertEquals("go run .", RunTargetResolver.resolve(withMain, "go")!!.display)

        val library = project("go-lib")
        File(library, "go.mod").writeText("module demo\n")
        File(library, "lib.go").writeText("package demo\n")
        assertNull("a library has no main package to run", RunTargetResolver.resolve(library, "go"))
    }

    @Test
    fun `maven needs the exec plugin before exec java can resolve`() {
        val plain = project("maven-plain")
        File(plain, "pom.xml").writeText("<project><build/></project>")
        assertNull(
            "'mvn run' is not a goal; without the exec plugin there is nothing to invoke",
            RunTargetResolver.resolve(plain, "maven"),
        )

        val withExec = project("maven-exec")
        File(withExec, "pom.xml").writeText(
            "<project><build><plugins><plugin>" +
                "<artifactId>exec-maven-plugin</artifactId></plugin></plugins></build></project>",
        )
        assertEquals("mvn exec:java", RunTargetResolver.resolve(withExec, "maven")!!.display)
    }

    // ------------------------------------------------------------- work dirs

    @Test
    fun `a work directory outside the project is rejected`() {
        val root = project("escape")
        val command = PlannedCommand("run", "./app", emptyList(), workDir = "../..")
        assertNull(
            "a project file must not be able to run commands outside the project",
            command.workingDirUnder(root),
        )
    }

    @Test
    fun `a work directory inside the project resolves`() {
        val root = project("inside")
        val build = File(root, "build").apply { mkdirs() }
        val command = PlannedCommand("run", "./app", emptyList(), workDir = "build")
        assertEquals(build.canonicalFile, command.workingDirUnder(root))
    }

    @Test
    fun `a blank work directory means the project root`() {
        val root = project("root")
        val command = PlannedCommand("run", "./app", emptyList())
        assertEquals(root, command.workingDirUnder(root))
    }
}
