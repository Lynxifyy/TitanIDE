package dev.titanide.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Test results read from the reports a build writes.
 *
 * Gradle's plain console prints `> Task :app:test` and nothing else, so a real test run has no
 * summary in the log to parse. Reporting nothing after a run the user asked for looks like the
 * feature is broken, so the reports on disk are the source of truth instead.
 */
class JUnitReportReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeReport(relative: String, xml: String): File {
        val file = File(tmp.root, relative)
        file.parentFile.mkdirs()
        file.writeText(xml)
        return file
    }

    private fun suiteXml(
        name: String,
        tests: Int,
        failures: Int,
        errors: Int,
        skipped: Int,
        cases: String = "",
    ) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<testsuite name=\"$name\" tests=\"$tests\" skipped=\"$skipped\"")
        append(" failures=\"$failures\" errors=\"$errors\" time=\"0.12\">\n")
        if (cases.isNotEmpty()) append(cases).append('\n')
        append("</testsuite>\n")
    }

    @Test
    fun `a passing suite is counted`() {
        writeReport(
            "app/build/test-results/testDebugUnitTest/TEST-com.example.CounterTest.xml",
            suiteXml(
                "com.example.CounterTest", tests = 3, failures = 0, errors = 0, skipped = 0,
                cases = """
                    <testcase name="increment" classname="com.example.CounterTest" time="0.01"/>
                    <testcase name="decrement" classname="com.example.CounterTest" time="0.01"/>
                    <testcase name="reset" classname="com.example.CounterTest" time="0.01"/>
                """.trimIndent(),
            ),
        )

        val summary = JUnitReportReader.read(tmp.root)

        assertNotNull("a real report must produce a summary", summary)
        assertEquals(3, summary!!.total)
        assertEquals(3, summary.passed)
        assertEquals(0, summary.failed)
    }

    @Test
    fun `failures and skips are separated`() {
        writeReport(
            "build/test-results/test/TEST-a.BTest.xml",
            suiteXml(
                "a.BTest", tests = 4, failures = 1, errors = 1, skipped = 1,
                cases = """
                    <testcase name="ok" classname="a.BTest"/>
                    <testcase name="bad" classname="a.BTest"><failure message="expected 1">trace</failure></testcase>
                    <testcase name="boom" classname="a.BTest"><error message="npe">trace</error></testcase>
                    <testcase name="later" classname="a.BTest"><skipped/></testcase>
                """.trimIndent(),
            ),
        )

        val summary = JUnitReportReader.read(tmp.root)!!

        assertEquals(4, summary.total)
        assertEquals(1, summary.passed)
        assertEquals(2, summary.failed)
        assertEquals(1, summary.skipped)
        assertEquals(4, summary.cases.size)
    }

    @Test
    fun `a failing case keeps its message so the user can act on it`() {
        writeReport(
            "build/test-results/test/TEST-a.BTest.xml",
            suiteXml(
                "a.BTest", tests = 1, failures = 1, errors = 0, skipped = 0,
                cases = """<testcase name="bad" classname="a.BTest"><failure message="expected 1 but was 2">trace</failure></testcase>""",
            ),
        )

        val case = JUnitReportReader.read(tmp.root)!!.cases.single()

        assertEquals("bad", case.name)
        assertEquals(false, case.passed)
        assertEquals("expected 1 but was 2", case.failureMessage)
    }

    @Test
    fun `several modules are summed and newest run wins`() {
        // A stale report from an earlier run must not be added to the current one.
        val stale = writeReport(
            "app/build/test-results/test/TEST-old.StaleTest.xml",
            suiteXml("old.StaleTest", tests = 9, failures = 9, errors = 0, skipped = 0),
        )
        stale.setLastModified(1_000_000_000_000L)
        writeReport(
            "app/build/test-results/test/TEST-a.ATest.xml",
            suiteXml("a.ATest", tests = 2, failures = 0, errors = 0, skipped = 0),
        )
        writeReport(
            "lib/build/test-results/test/TEST-a.CTest.xml",
            suiteXml("a.CTest", tests = 3, failures = 0, errors = 0, skipped = 0),
        )

        val summary = JUnitReportReader.read(tmp.root)!!

        assertEquals("only the newest run counts", 5, summary.total)
        assertEquals(5, summary.passed)
        assertEquals(0, summary.failed)
    }

    @Test
    fun `no reports means no summary rather than invented results`() {
        assertNull(JUnitReportReader.read(tmp.root))
    }

    @Test
    fun `a malformed report is skipped instead of crashing the build`() {
        writeReport("build/test-results/test/TEST-broken.xml", "<testsuite name=\"x\" tests=\"2\"")
        writeReport(
            "build/test-results/test/TEST-good.xml",
            suiteXml("good", tests = 1, failures = 0, errors = 0, skipped = 0),
        )

        val summary = JUnitReportReader.read(tmp.root)!!

        assertEquals(1, summary.total)
    }

    @Test
    fun `the newest run is chosen per results directory`() {
        // Gradle keeps the old XML beside the new one until the task re-runs, so the filter is
        // per directory and keyed on the newest file in it.
        val dir = "build/test-results/test"
        writeReport("$dir/TEST-a.OldTest.xml", suiteXml("a.OldTest", 4, 4, 0, 0))
        writeReport("$dir/TEST-a.NewTest.xml", suiteXml("a.NewTest", 1, 0, 0, 0))
        File(tmp.root, "$dir/TEST-a.OldTest.xml").setLastModified(1_000_000_000_000L)
        File(tmp.root, "$dir/TEST-a.NewTest.xml").setLastModified(1_700_000_000_000L)

        val summary = JUnitReportReader.read(tmp.root)!!

        assertTrue("the newer suite must be the one reported", summary.total == 1)
    }
}

/**
 * The wiring, not just the parser: a test build whose log carries no summary must still report
 * the results the build wrote. This is the case that made a real Gradle run look broken.
 */
class ReportedTestSummaryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun report(tests: Int, failures: Int) {
        val file = File(tmp.root, "app/build/test-results/testDebugUnitTest/TEST-a.BTest.xml")
        file.parentFile.mkdirs()
        file.writeText(
            "<?xml version=\"1.0\"?><testsuite name=\"a.BTest\" tests=\"$tests\" skipped=\"0\" " +
                "failures=\"$failures\" errors=\"0\">" +
                (0 until tests).joinToString("") { "<testcase name=\"t$it\" classname=\"a.BTest\"/>" } +
                "</testsuite>",
        )
    }

    @Test
    fun `a test build with no summary in the log reports the written reports`() {
        report(tests = 3, failures = 0)

        val reading = reportedTestSummary(
            dev.titanide.core.model.BuildKind.TEST,
            null,
            tmp.root.path,
        )

        assertEquals(3, reading.summary?.total)
        assertEquals(3, reading.summary?.passed)
    }

    @Test
    fun `a summary already in the log is kept`() {
        report(tests = 9, failures = 0)
        val fromLog = dev.titanide.core.model.TestSummary(total = 2, passed = 1, failed = 1, skipped = 0)

        val reading = reportedTestSummary(
            dev.titanide.core.model.BuildKind.TEST,
            fromLog,
            tmp.root.path,
        )

        assertEquals("the log the user watched wins", 2, reading.summary?.total)
    }

    @Test
    fun `a non-test build never picks up stale reports`() {
        report(tests = 4, failures = 0)

        val reading = reportedTestSummary(
            dev.titanide.core.model.BuildKind.BUILD,
            null,
            tmp.root.path,
        )

        assertNull("a build is not a test run", reading.summary)
    }
}
