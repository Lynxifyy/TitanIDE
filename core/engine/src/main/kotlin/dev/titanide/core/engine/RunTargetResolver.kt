package dev.titanide.core.engine

import java.io.File

/**
 * Works out how to *run* a project, as opposed to how to build it.
 *
 * The planner used to answer every RUN with a literal "run" task, which is only correct for
 * `cargo run`. `npm start` works only because the generated templates define it, `go run .`
 * only when a main package sits at the root, and `python -m run`, `mvn run` and `make run`
 * never exist at all — the user saw a task-not-found error for a button that looked like it
 * should work.
 *
 * Every answer here is derived from files in the project. When nothing runnable can be
 * found this returns null and the planner reports why, rather than inventing a command.
 */
object RunTargetResolver {

    /** A command plus the directory it must run in, relative to the project root. */
    data class RunTarget(val program: String, val args: List<String>, val workDir: String = "") {
        val display: String get() = (listOf(program) + args).joinToString(" ")
    }

    fun resolve(root: File, system: String): RunTarget? = when (system) {
        "gradle" -> null // Gradle runs are resolved from the build scripts by GradleTaskPlanner.
        "npm" -> npm(root)
        "cargo" -> RunTarget("cargo", listOf("run"))
        "go" -> go(root)
        "cmake" -> cmake(root)
        "make" -> make(root)
        "pip" -> python(root)
        "composer" -> composer(root)
        "bundler" -> bundler(root)
        "maven" -> maven(root)
        "dotnet" -> RunTarget("dotnet", listOf("run"))
        "pub" -> RunTarget("dart", listOf("run"))
        else -> null
    }

    /**
     * Prefers an explicit `start` script. `npm start` silently falls back to
     * `node server.js`, so a project without one would launch the wrong file.
     */
    private fun npm(root: File): RunTarget? {
        val pkg = File(root, "package.json")
        if (!pkg.isFile) return null
        val text = runCatching { pkg.readText() }.getOrNull() ?: return null
        return if (Regex(""""start"\s*:""").containsMatchIn(text)) {
            RunTarget("npm", listOf("start"))
        } else {
            null
        }
    }

    private fun go(root: File): RunTarget? {
        val hasMain = root.walkTopDown()
            .onEnter { it.name != "vendor" && !it.name.startsWith(".") }
            .maxDepth(6)
            .any { it.isFile && it.extension == "go" && runCatching {
                it.readText().lineSequence().any { line ->
                    line.trimStart().startsWith("package main")
                }
            }.getOrDefault(false) }
        return if (hasMain) RunTarget("go", listOf("run", ".")) else null
    }

    /** The executable target's name comes from add_executable, not from the folder name. */
    private fun cmake(root: File): RunTarget? {
        val cmakeLists = File(root, "CMakeLists.txt")
        if (!cmakeLists.isFile) return null
        val text = runCatching { cmakeLists.readText() }.getOrNull() ?: return null
        val match = Regex("""add_executable\(\s*([A-Za-z0-9_.\-]+)""").find(text) ?: return null
        return RunTarget("./${match.groupValues[1]}", emptyList(), workDir = "build")
    }

    private fun make(root: File): RunTarget? {
        val makefile = listOf("Makefile", "makefile").map { File(root, it) }.firstOrNull { it.isFile }
            ?: return null
        val text = runCatching { makefile.readText() }.getOrNull() ?: return null
        return if (Regex("""^run\s*:""", RegexOption.MULTILINE).containsMatchIn(text)) {
            RunTarget("make", listOf("run"))
        } else {
            null
        }
    }

    /**
     * A Python project is run through its own entry point: the `[project.scripts]` console
     * script if pyproject declares one, otherwise `__main__.py` in the top-level package,
     * otherwise a `main.py` at the root.
     */
    private fun python(root: File): RunTarget? {
        consoleScript(root)?.let { (module, func) ->
            return RunTarget("python3", listOf("-m", "$module:$func"))
        }
        val packageDir = root.listFiles()
            ?.filter { it.isDirectory && File(it, "__main__.py").isFile }
            ?.minByOrNull { it.name }
        if (packageDir != null) return RunTarget("python3", listOf("-m", packageDir.name))
        if (File(root, "main.py").isFile) return RunTarget("python3", listOf("main.py"))
        return null
    }

    private fun consoleScript(root: File): Pair<String, String>? {
        val pyproject = File(root, "pyproject.toml")
        if (!pyproject.isFile) return null
        val text = runCatching { pyproject.readText() }.getOrNull() ?: return null
        val entry = Regex("""^[A-Za-z0-9_.\-]+\s*=\s*"([A-Za-z0-9_.]+):([A-Za-z0-9_]+)"""", RegexOption.MULTILINE)
            .find(text) ?: return null
        return entry.groupValues[1] to entry.groupValues[2]
    }

    private fun composer(root: File): RunTarget? {
        val manifest = File(root, "composer.json")
        if (!manifest.isFile) return null
        val text = runCatching { manifest.readText() }.getOrNull() ?: return null
        return if (Regex(""""scripts"\s*:""").containsMatchIn(text)) {
            RunTarget("composer", listOf("run-script", "start"))
        } else {
            null
        }
    }

    private fun bundler(root: File): RunTarget? {
        val gemfile = File(root, "Gemfile")
        if (!gemfile.isFile) return null
        return if (root.listFiles()?.any { it.name.endsWith(".rb") } == true) {
            RunTarget("bundle", listOf("exec", "ruby", "main.rb"))
        } else {
            null
        }
    }

    /**
     * Maven has no run goal of its own, so the exec plugin has to be present before
     * `exec:java` will resolve anything.
     */
    private fun maven(root: File): RunTarget? {
        val pom = File(root, "pom.xml")
        if (!pom.isFile) return null
        val text = runCatching { pom.readText() }.getOrNull() ?: return null
        return if (text.contains("exec-maven-plugin")) {
            RunTarget("mvn", listOf("exec:java"))
        } else {
            null
        }
    }
}
