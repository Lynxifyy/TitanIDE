package dev.titanide.core.engine

import dev.titanide.core.model.TestCaseResult
import dev.titanide.core.model.TestSummary
import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads the JUnit XML that Gradle, Maven and `dotnet test` write after a test run.
 *
 * The log is not enough on its own: `gradle test --console=plain` prints the task name and
 * nothing else, so a genuine run would otherwise report no results at all. The reports on disk
 * hold the real counts, the failing messages and the stack traces.
 *
 * Only the newest run in each results directory is read. Gradle leaves the previous run's XML
 * beside the new one, and summing both would report results for tests that did not run.
 */
object JUnitReportReader {

    /** Directories build tools write JUnit XML into, relative to the project root. */
    private val RESULTS_DIRS = listOf(
        "build/test-results",
        "target/surefire-reports",
        "target/failsafe-reports",
        "TestResults",
    )

    private const val MAX_DEPTH = 4

    /** One test task writes all of its reports within a few seconds of each other. */
    private const val SAME_RUN_WINDOW_MS = 60_000L

    /**
     * A summary plus anything the user needs to know about how it was produced.
     *
     * A report that could not be read is not the same as a report that was not there, and the
     * difference belongs in the build log rather than in a logcat line nobody sees.
     */
    data class Reading(val summary: TestSummary?, val notes: List<String> = emptyList())

    fun read(root: File): TestSummary? = readAll(root).summary

    fun readAll(root: File): Reading {
        if (!root.isDirectory) return Reading(null)

        val notes = ArrayList<String>()
        val files = resultsDirectories(root).flatMap { dir -> newestRunFiles(dir) }
        if (files.isEmpty()) return Reading(null)

        val cases = ArrayList<TestCaseResult>()
        var total = 0
        var failed = 0
        var skipped = 0
        var unreadable = 0

        for (file in files) {
            val suite = parseSuite(file)
            if (suite == null) {
                unreadable++
                notes += "Could not read test report ${file.relativeTo(root).path}"
                continue
            }
            total += suite.total
            failed += suite.failed
            skipped += suite.skipped
            cases += suite.cases
        }

        if (total == 0) {
            if (unreadable > 0) notes += "No usable test report was found in this project."
            return Reading(null, notes)
        }
        return Reading(
            TestSummary(
                total = total,
                passed = (total - failed - skipped).coerceAtLeast(0),
                failed = failed,
                skipped = skipped,
                cases = cases,
            ),
            notes,
        )
    }

    /** Every `test-results`-style directory up to [MAX_DEPTH] levels below [root]. */
    private fun resultsDirectories(root: File): List<File> {
        val found = ArrayList<File>()
        fun walk(dir: File, depth: Int) {
            if (depth > MAX_DEPTH) return
            for (child in dir.listFiles().orEmpty()) {
                if (!child.isDirectory) continue
                val relative = child.path.removePrefix(root.path).trimStart(File.separatorChar)
                if (RESULTS_DIRS.any { relative == it || relative.endsWith(File.separator + it) }) {
                    found += child
                } else if (child.name != "node_modules" && child.name != ".git") {
                    walk(child, depth + 1)
                }
            }
        }
        walk(root, 0)
        return found
    }

    /**
     * The XML files from the most recent run under [dir].
     *
     * Gradle puts its reports one level down (`test-results/testDebugUnitTest/`), so the files
     * are gathered per directory first. A run is then identified by its newest file: one test
     * task writes all of its reports together, while the previous run's files are older.
     */
    private fun newestRunFiles(dir: File): List<File> =
        xmlFiles(dir, 0).groupBy { it.parentFile }.values.flatMap { files ->
            val cutoff = files.maxOf { it.lastModified() } - SAME_RUN_WINDOW_MS
            files.filter { it.lastModified() >= cutoff }
        }

    private fun xmlFiles(dir: File, depth: Int): List<File> {
        if (depth > 2) return emptyList()
        val found = ArrayList<File>()
        for (child in dir.listFiles().orEmpty()) {
            if (child.isDirectory) found += xmlFiles(child, depth + 1)
            else if (child.name.endsWith(".xml")) found += child
        }
        return found
    }

    private fun parseSuite(file: File): Suite? {
        val roots = try {
            val factory = DocumentBuilderFactory.newInstance()
            // Reports come from the project's own build, but they are still untrusted input:
            // a crafted report must not be able to read local files through an external entity.
            runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            // The constants for these are absent from Android's XMLConstants, so the property
            // names are spelled out; unknown properties are rejected and ignored on purpose.
            runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
            factory.isExpandEntityReferences = false
            factory.isXIncludeAware = false
            factory.newDocumentBuilder().parse(file).getElementsByTagName("testsuite")
        } catch (e: Exception) {
            return null
        }
        if (roots.length == 0) return null

        var total = 0
        var failed = 0
        var skipped = 0
        val cases = ArrayList<TestCaseResult>()

        for (index in 0 until roots.length) {
            val suite = roots.item(index) as? Element ?: continue
            total += suite.getAttribute("tests").toIntOrNull() ?: 0
            failed += (suite.getAttribute("failures").toIntOrNull() ?: 0) +
                (suite.getAttribute("errors").toIntOrNull() ?: 0)
            skipped += suite.getAttribute("skipped").toIntOrNull() ?: 0

            val caseNodes = suite.getElementsByTagName("testcase")
            for (caseIndex in 0 until caseNodes.length) {
                val node = caseNodes.item(caseIndex) as? Element ?: continue
                val failure = firstChild(node, "failure")
                val error = firstChild(node, "error")
                val skip = firstChild(node, "skipped")
                cases += TestCaseResult(
                    suite = suite.getAttribute("name").ifEmpty { node.getAttribute("classname") },
                    name = node.getAttribute("name"),
                    passed = failure == null && error == null && skip == null,
                    durationMs = ((node.getAttribute("time").toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                    failureMessage = failure?.getAttribute("message")?.takeIf { it.isNotEmpty() }
                        ?: error?.getAttribute("message")?.takeIf { it.isNotEmpty() },
                    stackTrace = (failure ?: error)?.textContent?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
        }
        return Suite(total, failed, skipped, cases)
    }

    private fun firstChild(parent: Element, tag: String): Element? =
        parent.getElementsByTagName(tag).item(0) as? Element

    private data class Suite(
        val total: Int,
        val failed: Int,
        val skipped: Int,
        val cases: List<TestCaseResult>,
    )
}
