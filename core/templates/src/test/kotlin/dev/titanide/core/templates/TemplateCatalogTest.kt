package dev.titanide.core.templates

import dev.titanide.core.model.ProjectCategory
import dev.titanide.core.model.ProjectTemplate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TemplateCatalogTest {

    private val registry = GeneratorRegistry.default()

    @Test
    fun `every catalogue template has a registered generator`() {
        val missing = TemplateCatalog.all.filter { !registry.isRegistered(it.generatorId) }
        assertTrue(
            "templates without a generator: ${missing.map { it.id }}",
            missing.isEmpty(),
        )
    }

    @Test
    fun `catalogue covers exactly four categories`() {
        assertEquals(4, ProjectCategory.entries.size)
        assertTrue(TemplateCatalog.forCategory(ProjectCategory.ANDROID_APP).isNotEmpty())
        assertTrue(TemplateCatalog.forCategory(ProjectCategory.EXTENSION).isNotEmpty())
        assertTrue(TemplateCatalog.forCategory(ProjectCategory.BLANK).isNotEmpty())
        assertTrue(TemplateCatalog.forCategory(ProjectCategory.GAME).isNotEmpty())
    }

    @Test
    fun `template ids are unique`() {
        val ids = TemplateCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `game templates declare their game`() {
        val gameTemplates = TemplateCatalog.forCategory(ProjectCategory.GAME)
        assertTrue(gameTemplates.all { it.gameId != null })
        gameTemplates.forEach { template ->
            assertNotNull("unknown game ${template.gameId}", TemplateCatalog.game(template.gameId!!))
        }
    }

    @Test
    fun `unsupported game templates are marked and excluded from device support`() {
        val scripthook = TemplateCatalog.byId("game.gta.script")!!
        assertTrue(!scripthook.isSupportedOnDevice)
        assertTrue(scripthook.requirements.any { it.startsWith(ProjectTemplate.UNSUPPORTED_PREFIX) })
    }

    @Test
    fun `minecraft framework list covers java plugin and bedrock targets`() {
        val minecraft = TemplateCatalog.game("minecraft")!!
        val ids = minecraft.frameworks.map { it.id }
        assertTrue(ids.containsAll(listOf("fabric", "forge", "paper", "bedrock", "native")))
        assertTrue(minecraft.frameworks.all { TemplateCatalog.byId(it.templateId) != null })
    }

    @Test
    fun `native minecraft framework states cross-platform limits`() {
        val native = TemplateCatalog.game("minecraft")!!.frameworks.first { it.id == "native" }
        assertTrue(native.compatibility.contains("will NOT load"))
    }
}

class GeneratorOutputTest {

    private val registry = GeneratorRegistry.default()

    private fun request(
        name: String = "Demo Project",
        pkg: String = "dev.demo.app",
        language: dev.titanide.core.model.Language? = null,
    ) = ScaffoldRequest(
        projectName = name,
        packageName = pkg,
        applicationId = pkg,
        rootDir = File("/tmp/unused"),
        language = language,
    )

    @Test
    fun `all generators emit at least one file and a valid entry point`() {
        TemplateCatalog.all.forEach { template ->
            val generator = registry.generatorFor(template.generatorId)!!
            val result = generator.generate(request(language = template.languages.firstOrNull()))
            assertTrue("${template.id} produced no files", result.files.isNotEmpty())
            assertTrue(
                "${template.id} entry point ${result.entryPoint} is not among generated files",
                result.files.any { it.relativePath == result.entryPoint },
            )
        }
    }

    @Test
    fun `no generator emits duplicate or escaping paths`() {
        TemplateCatalog.all.forEach { template ->
            val generator = registry.generatorFor(template.generatorId)!!
            val result = generator.generate(request(language = template.languages.firstOrNull()))
            val paths = result.files.map { it.relativePath }
            assertEquals("${template.id} has duplicate paths", paths.size, paths.toSet().size)
            paths.forEach { path ->
                assertTrue("${template.id} escapes root: $path", !path.contains("..") && !path.startsWith("/"))
            }
        }
    }

    @Test
    fun `compose template writes a manifest and gradle build`() {
        val generator = registry.generatorFor(TemplateCatalog.GEN_ANDROID_COMPOSE)!!
        val result = generator.generate(request())
        val paths = result.files.map { it.relativePath }
        assertTrue(paths.contains("app/src/main/AndroidManifest.xml"))
        assertTrue(paths.contains("app/build.gradle.kts"))
        assertTrue(paths.contains("settings.gradle.kts"))
        val build = result.files.first { it.relativePath == "app/build.gradle.kts" }.content
        assertTrue(build.contains("compose = true"))
        assertTrue(build.contains("applicationId = \"dev.demo.app\""))
    }

    @Test
    fun `java and kotlin jvm templates differ in source language`() {
        val generator = registry.generatorFor(TemplateCatalog.GEN_GRADLE_JVM)!!
        val kotlin = generator.generate(request(language = dev.titanide.core.model.Language.KOTLIN))
        val java = generator.generate(request(language = dev.titanide.core.model.Language.JAVA))
        assertTrue(kotlin.entryPoint.endsWith(".kt"))
        assertTrue(java.entryPoint.endsWith(".java"))
        assertTrue(java.files.any { it.relativePath == "src/test/java/dev/demo/app/DemoProjectTest.java" })
    }

    @Test
    fun `package names are sanitized into valid identifiers`() {
        val generator = registry.generatorFor(TemplateCatalog.GEN_PYTHON)!!
        val result = generator.generate(request(pkg = "9bad..pkg-name"))
        assertTrue(result.files.all { !it.relativePath.contains("..") })
        assertTrue(result.files.any { it.relativePath.startsWith("_9bad") })
    }

    @Test
    fun `bedrock addon produces both packs with distinct uuids`() {
        val generator = registry.generatorFor(TemplateCatalog.GEN_MINECRAFT_BEDROCK)!!
        val result = generator.generate(request())
        val behaviour = result.files.first { it.relativePath == "behaviour_pack/manifest.json" }.content
        val resource = result.files.first { it.relativePath == "resource_pack/manifest.json" }.content
        val behaviourUuid = Regex("\"uuid\": \"([^\"]+)\"").find(behaviour)!!.groupValues[1]
        val resourceUuid = Regex("\"uuid\": \"([^\"]+)\"").find(resource)!!.groupValues[1]
        assertTrue(behaviourUuid != resourceUuid)
    }

    @Test
    fun `fabric template declares java 21 and its loader dependency`() {
        val generator = registry.generatorFor(TemplateCatalog.GEN_MINECRAFT_FABRIC)!!
        val result = generator.generate(request())
        val build = result.files.first { it.relativePath == "build.gradle.kts" }.content
        assertTrue(build.contains("VERSION_21"))
        assertTrue(build.contains("fabric-loader"))
        val modJson = result.files.first { it.relativePath == "src/main/resources/fabric.mod.json" }.content
        assertTrue(modJson.contains("\"entrypoints\""))
    }
}

class ProjectScaffolderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val scaffolder = ProjectScaffolder()

    private fun spec(
        templateId: String = "android.compose",
        category: ProjectCategory = ProjectCategory.ANDROID_APP,
        name: String = "My App",
        pkg: String = "dev.titanide.myapp",
    ) = CreateProjectSpec(
        name = name,
        packageName = pkg,
        category = category,
        templateId = templateId,
    )

    @Test
    fun `valid spec passes validation`() {
        assertNull(scaffolder.validate(spec()))
    }

    @Test
    fun `unknown template fails validation`() {
        assertNotNull(scaffolder.validate(spec(templateId = "does.not.exist")))
    }

    @Test
    fun `category mismatch fails validation`() {
        assertNotNull(scaffolder.validate(spec(templateId = "android.compose", category = ProjectCategory.BLANK)))
    }

    @Test
    fun `invalid package name fails validation`() {
        assertNotNull(scaffolder.validate(spec(pkg = "single")))
        assertNotNull(scaffolder.validate(spec(pkg = "dev.9bad")))
        assertNotNull(scaffolder.validate(spec(pkg = "dev..app")))
    }

    @Test
    fun `blank project name fails validation`() {
        assertNotNull(scaffolder.validate(spec(name = "  ")))
    }

    @Test
    fun `create writes files to disk and returns a project`() = runBlocking {
        val target = File(temp.root, "MyApp")
        val outcome = scaffolder.create(spec(), "proj-1", target)
        assertTrue("expected success, got $outcome", outcome is CreateProjectOutcome.Success)
        val success = outcome as CreateProjectOutcome.Success
        assertTrue(success.filesWritten > 0)
        assertTrue(File(target, "app/src/main/AndroidManifest.xml").exists())
        assertTrue(File(target, "app/build.gradle.kts").exists())
        assertEquals("proj-1", success.project.id)
        assertEquals("My App", success.project.name)
        assertTrue(File(target, success.entryPoint).exists())
    }

    @Test
    fun `create refuses a non-empty target directory`() = runBlocking {
        val target = temp.newFolder("occupied")
        File(target, "existing.txt").writeText("keep me")
        val outcome = scaffolder.create(spec(), "proj-2", target)
        assertTrue(outcome is CreateProjectOutcome.Failure)
        assertEquals("keep me", File(target, "existing.txt").readText())
    }

    @Test
    fun `create failure for unknown template writes nothing`() = runBlocking {
        val target = File(temp.root, "nothing")
        val outcome = scaffolder.create(spec(templateId = "nope"), "proj-3", target)
        assertTrue(outcome is CreateProjectOutcome.Failure)
        assertTrue(!target.exists() || target.listFiles()?.isEmpty() == true)
    }

    @Test
    fun `created python project has an importable module and a test`() = runBlocking {
        val target = File(temp.root, "PyApp")
        val outcome = scaffolder.create(
            spec(templateId = "blank.python", category = ProjectCategory.BLANK, name = "Py App", pkg = "dev.pyapp"),
            "proj-4",
            target,
        )
        assertTrue(outcome is CreateProjectOutcome.Success)
        assertTrue(File(target, "dev_pyapp/main.py").exists())
        assertTrue(File(target, "tests/test_main.py").exists())
        assertTrue(File(target, "pyproject.toml").readText().contains("dev_pyapp"))
    }

    @Test
    fun `created game project records its template`() = runBlocking {
        val target = File(temp.root, "Fabric")
        val outcome = scaffolder.create(
            spec(
                templateId = "game.mc.fabric",
                category = ProjectCategory.GAME,
                name = "Fabric Mod",
                pkg = "dev.fabricmod",
            ),
            "proj-5",
            target,
        )
        assertTrue(outcome is CreateProjectOutcome.Success)
        val project = (outcome as CreateProjectOutcome.Success).project
        assertEquals("game.mc.fabric", project.templateId)
        assertEquals(ProjectCategory.GAME, project.category)
        assertTrue(File(target, "src/main/resources/fabric.mod.json").exists())
    }

    @Test
    fun `isValidPackage accepts conventional names`() {
        assertTrue(ProjectScaffolder.isValidPackage("com.example.app"))
        assertTrue(ProjectScaffolder.isValidPackage("dev.titanide.my_app"))
        assertTrue(!ProjectScaffolder.isValidPackage("com"))
        assertTrue(!ProjectScaffolder.isValidPackage("com.example.1bad"))
        assertTrue(!ProjectScaffolder.isValidPackage("com.example-app"))
    }
}
