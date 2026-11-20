package dev.titanide.core.engine

import dev.titanide.core.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogParserTest {

    @Test
    fun `kotlin error with file line and column is parsed`() {
        val lines = listOf("e: file:///w/Main.kt:12:5 Unresolved reference: foo")
        val result = LogParser.parse(lines, 1)
        assertEquals(1, result.diagnostics.size)
        val d = result.diagnostics.first()
        assertEquals(Severity.ERROR, d.severity)
        assertEquals(12, d.line)
        assertEquals(5, d.column)
        assertTrue(d.message.contains("Unresolved reference"))
    }

    @Test
    fun `kotlin warning is not an error`() {
        val result = LogParser.parse(listOf("w: file:///w/A.kt:3:1 Variable is never used"), 0)
        assertEquals(Severity.WARNING, result.diagnostics.first().severity)
    }

    @Test
    fun `javac error is parsed`() {
        val result = LogParser.parse(listOf("/w/src/Main.java:7: error: cannot find symbol"), 1)
        val d = result.diagnostics.first()
        assertEquals(Severity.ERROR, d.severity)
        assertEquals(7, d.line)
        assertEquals("javac", d.source)
    }

    @Test
    fun `clang error carries column`() {
        val result = LogParser.parse(listOf("src/main.cpp:9:5: error: use of undeclared identifier 'x'"), 1)
        val d = result.diagnostics.first()
        assertEquals(9, d.line)
        assertEquals(5, d.column)
        assertEquals("clang", d.source)
    }

    @Test
    fun `go error is parsed`() {
        val result = LogParser.parse(listOf("./main.go:7:2: undefined: foo"), 1)
        val d = result.diagnostics.first()
        assertEquals("main.go", d.filePath)
        assertEquals(7, d.line)
        assertEquals("go", d.source)
    }

    @Test
    fun `rust error attaches the location from the following line`() {
        val lines = listOf(
            "error[E0425]: cannot find value `x` in this scope",
            " --> src/main.rs:3:9",
            "  |",
        )
        val result = LogParser.parse(lines, 1)
        val d = result.diagnostics.first()
        assertEquals(Severity.ERROR, d.severity)
        assertEquals("src/main.rs", d.filePath)
        assertEquals(3, d.line)
        assertEquals(9, d.column)
    }

    @Test
    fun `gradle failure reason is captured`() {
        val result = LogParser.parse(listOf("FAILURE: Build failed with an exception."), 1)
        assertEquals("Build failed with an exception.", result.failureReason)
    }

    @Test
    fun `gradle task outcomes become stages`() {
        val lines = listOf(
            "> Task :app:compileDebugKotlin",
            "> Task :app:assembleDebug",
            "> Task :app:testDebugUnitTest FAILED",
        )
        val result = LogParser.parse(lines, 1)
        assertEquals(3, result.stages.size)
        assertEquals(false, result.stages.last().second)
    }

    @Test
    fun `non zero exit without a failure line still yields a reason`() {
        val result = LogParser.parse(listOf("something went wrong"), 2)
        assertNotNull(result.failureReason)
        assertTrue(result.failureReason!!.contains("code 2"))
    }

    @Test
    fun `clean exit has no failure reason`() {
        assertNull(LogParser.parse(listOf("BUILD SUCCESSFUL"), 0).failureReason)
    }

    @Test
    fun `diagnostics are sorted by file and position`() {
        val lines = listOf(
            "e: file:///w/B.kt:5:1 second",
            "e: file:///w/A.kt:9:1 third",
            "e: file:///w/A.kt:2:1 first",
        )
        val result = LogParser.parse(lines, 1)
        assertEquals(listOf(2, 9), result.diagnostics.take(2).map { it.line })
    }
}

class TestResultParserTest {

    @Test
    fun `junit summary is parsed`() {
        val summary = LogParser.parseTests(listOf("Tests run: 12, Failures: 1, Errors: 1, Skipped: 2"))
        assertNotNull(summary)
        assertEquals(12, summary!!.total)
        assertEquals(8, summary.passed)
        assertEquals(2, summary.failed)
        assertEquals(2, summary.skipped)
    }

    @Test
    fun `gradle per test lines are parsed`() {
        val lines = listOf(
            "com.example.CounterTest > increment adds one PASSED",
            "com.example.CounterTest > decrement adds one FAILED",
        )
        val summary = LogParser.parseTests(lines)
        assertNotNull(summary)
        assertEquals(2, summary!!.total)
        assertEquals(1, summary.passed)
        assertEquals(1, summary.failed)
        assertEquals(2, summary.cases.size)
    }

    @Test
    fun `node test summary is parsed`() {
        val summary = LogParser.parseTests(listOf("# pass 5", "# fail 2"))
        assertNotNull(summary)
        assertEquals(7, summary!!.total)
        assertEquals(5, summary.passed)
        assertEquals(2, summary.failed)
    }

    @Test
    fun `cargo test summary is parsed`() {
        val summary = LogParser.parseTests(listOf("test result: ok. 3 passed; 0 failed; 1 ignored"))
        assertNotNull(summary)
        assertEquals(4, summary!!.total)
        assertEquals(3, summary.passed)
        assertEquals(1, summary.skipped)
    }

    @Test
    fun `pytest summary is parsed`() {
        val summary = LogParser.parseTests(listOf("4 passed, 1 failed, 1 skipped in 0.20s"))
        assertNotNull(summary)
        assertEquals(4, summary!!.passed)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.skipped)
    }

    @Test
    fun `no test output returns null rather than inventing results`() {
        assertNull(LogParser.parseTests(listOf("BUILD SUCCESSFUL in 4s")))
    }
}
