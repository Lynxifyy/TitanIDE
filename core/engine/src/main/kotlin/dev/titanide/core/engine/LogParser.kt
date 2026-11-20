package dev.titanide.core.engine

import dev.titanide.core.model.Diagnostic
import dev.titanide.core.model.Severity
import dev.titanide.core.model.TestCaseResult
import dev.titanide.core.model.TestSummary

/**
 * Turns real tool output into structured diagnostics. Each pattern mirrors the format
 * a specific compiler actually prints, so a match means the compiler really said it.
 */
object LogParser {

    // Kotlin/Javac: "e: file:///path/Main.kt:12:5 Unresolved reference: foo"
    //               "/path/Main.java:12: error: cannot find symbol"
    private val KOTLIN = Regex("""^(e|w):\s*(?:file://)?([^:]+):(\d+):(\d+)\s+(.+)$""")
    private val JAVAC = Regex("""^(?:file://)?([^:]+\.java):(\d+):\s*(error|warning):\s*(.+)$""")
    // Rust: "error[E0425]: cannot find value `x`" followed by " --> src/main.rs:3:9"
    private val RUST_HEADER = Regex("""^(error|warning)(\[[A-Z]\d+])?:\s*(.+)$""")
    private val RUST_LOCATION = Regex("""^\s*-->\s*([^:]+):(\d+):(\d+)$""")
    // Go: "./main.go:7:2: undefined: foo"
    private val GO = Regex("""^(?:\./)?([^\s:]+\.go):(\d+):(\d+):\s*(.+)$""")
    // C/C++/clang: "src/main.cpp:9:5: error: use of undeclared identifier 'x'"
    private val CLANG = Regex("""^([^\s:]+\.(?:c|cc|cpp|cxx|h|hpp)):(\d+):(\d+):\s*(error|warning|note):\s*(.+)$""")
    // Python: '  File "main.py", line 7' then the message
    private val PYTHON_FILE = Regex("""^\s*File "([^"]+)", line (\d+)""")
    // Gradle task markers
    private val GRADLE_FAILURE = Regex("""^FAILURE:\s*(.+)$""")
    private val GRADLE_TASK = Regex("""^>\s*Task\s+(:[\w:-]+)\s*(.*)$""")

    private val WARNING_WORDS = setOf("warning", "w:")

    data class ParseResult(
        val diagnostics: List<Diagnostic>,
        val testSummary: TestSummary?,
        val failureReason: String?,
        val stages: List<Pair<String, Boolean?>>,
    )

    fun parse(lines: List<String>, exitCode: Int?): ParseResult {
        val diagnostics = ArrayList<Diagnostic>()
        val stages = ArrayList<Pair<String, Boolean?>>()
        var failureReason: String? = null
        var pendingRust: Diagnostic? = null
        var pendingPython: Pair<String, Int>? = null

        for (raw in lines) {
            val line = raw.trimEnd()

            KOTLIN.find(line)?.let { m ->
                val severity = if (m.groupValues[1] == "e") Severity.ERROR else Severity.WARNING
                diagnostics += Diagnostic(
                    filePath = m.groupValues[2].removePrefix("/").substringAfterLast("workspace/", m.groupValues[2]),
                    line = m.groupValues[3].toIntOrNull() ?: 1,
                    column = m.groupValues[4].toIntOrNull() ?: 1,
                    severity = severity,
                    message = m.groupValues[5].trim(),
                    source = "kotlin",
                )
                return@let
            }

            JAVAC.find(line)?.let { m ->
                diagnostics += Diagnostic(
                    filePath = m.groupValues[1],
                    line = m.groupValues[2].toIntOrNull() ?: 1,
                    column = 1,
                    severity = if (m.groupValues[3] == "error") Severity.ERROR else Severity.WARNING,
                    message = m.groupValues[4].trim(),
                    source = "javac",
                )
            }

            CLANG.find(line)?.let { m ->
                val severity = when (m.groupValues[4]) {
                    "error" -> Severity.ERROR
                    "warning" -> Severity.WARNING
                    else -> Severity.INFO
                }
                diagnostics += Diagnostic(
                    filePath = m.groupValues[1],
                    line = m.groupValues[2].toIntOrNull() ?: 1,
                    column = m.groupValues[3].toIntOrNull() ?: 1,
                    severity = severity,
                    message = m.groupValues[5].trim(),
                    source = "clang",
                )
            }

            GO.find(line)?.let { m ->
                diagnostics += Diagnostic(
                    filePath = m.groupValues[1],
                    line = m.groupValues[2].toIntOrNull() ?: 1,
                    column = m.groupValues[3].toIntOrNull() ?: 1,
                    severity = Severity.ERROR,
                    message = m.groupValues[4].trim(),
                    source = "go",
                )
            }

            RUST_LOCATION.find(line)?.let { m ->
                pendingRust?.let { header ->
                    diagnostics += header.copy(
                        filePath = m.groupValues[1],
                        line = m.groupValues[2].toIntOrNull() ?: 1,
                        column = m.groupValues[3].toIntOrNull() ?: 1,
                    )
                }
                pendingRust = null
            }

            RUST_HEADER.find(line)?.let { m ->
                pendingRust = Diagnostic(
                    filePath = "",
                    line = 0,
                    column = 0,
                    severity = if (m.groupValues[1] == "error") Severity.ERROR else Severity.WARNING,
                    message = m.groupValues[3].trim(),
                    source = "rustc",
                )
            }

            PYTHON_FILE.find(line)?.let { m ->
                pendingPython = m.groupValues[1] to (m.groupValues[2].toIntOrNull() ?: 1)
            }

            if (line.matches(Regex(""".*(Error|Exception):.*"""))) {
                pendingPython?.let { (file, lineNo) ->
                    if (diagnostics.none { it.filePath == file && it.line == lineNo }) {
                        diagnostics += Diagnostic(
                            filePath = file,
                            line = lineNo,
                            column = 1,
                            severity = Severity.ERROR,
                            message = line.trim(),
                            source = "python",
                        )
                    }
                    pendingPython = null
                }
            }

            GRADLE_TASK.find(line)?.let { m ->
                val task = m.groupValues[1]
                val outcome = m.groupValues[2].trim()
                val ok = when {
                    outcome.contains("FAILED") -> false
                    outcome.contains("UP-TO-DATE") || outcome.contains("SUCCESSFUL") -> true
                    else -> null
                }
                stages += task to ok
            }

            GRADLE_FAILURE.find(line)?.let { m ->
                if (failureReason == null) failureReason = m.groupValues[1].trim()
            }
        }

        // A rust header with no location still matters.
        pendingRust?.let { if (it.severity == Severity.ERROR) diagnostics += it.copy(filePath = "unknown", line = 1) }

        val tests = parseTests(lines)
        if (failureReason == null && exitCode != null && exitCode != 0) {
            failureReason = "Process exited with code $exitCode"
        }

        return ParseResult(
            diagnostics = diagnostics.sortedWith(compareBy({ it.filePath }, { it.line }, { it.column })),
            testSummary = tests,
            failureReason = failureReason,
            stages = stages,
        )
    }

    // JUnit XML-ish summaries: "Tests run: 12, Failures: 1, Errors: 0, Skipped: 0"
    private val JUNIT_SUMMARY = Regex(
        """Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+),\s*Skipped:\s*(\d+)""",
    )
    // Gradle per-test lines: "SomeTest > testName FAILED"
    private val GRADLE_TEST = Regex("""^([\w.$]+)\s*>\s*(.+?)\s+(FAILED|PASSED|SKIPPED)$""")
    // node --test: "# pass 3" / "# fail 1"
    private val NODE_PASS = Regex("""^#\s*pass\s+(\d+)""")
    private val NODE_FAIL = Regex("""^#\s*fail\s+(\d+)""")
    // pytest: "3 passed, 1 failed in 0.12s"
    private val PYTEST = Regex("""(\d+)\s+passed(?:,\s*(\d+)\s+failed)?(?:,\s*(\d+)\s+skipped)?""")
    // cargo: "test result: ok. 3 passed; 0 failed; 0 ignored"
    private val CARGO = Regex("""test result:\s*\w+\.\s*(\d+)\s+passed;\s*(\d+)\s+failed;\s*(\d+)\s+ignored""")

    fun parseTests(lines: List<String>): TestSummary? {
        val cases = ArrayList<TestCaseResult>()

        for (line in lines) {
            GRADLE_TEST.find(line.trim())?.let { m ->
                cases += TestCaseResult(
                    suite = m.groupValues[1],
                    name = m.groupValues[2],
                    passed = m.groupValues[3] == "PASSED",
                )
            }
        }

        for (line in lines) {
            JUNIT_SUMMARY.find(line)?.let { m ->
                val total = m.groupValues[1].toInt()
                val failures = m.groupValues[2].toInt()
                val errors = m.groupValues[3].toInt()
                val skipped = m.groupValues[4].toInt()
                return TestSummary(
                    total = total,
                    passed = (total - failures - errors - skipped).coerceAtLeast(0),
                    failed = failures + errors,
                    skipped = skipped,
                    cases = cases,
                )
            }
        }

        var nodePass = -1
        var nodeFail = 0
        for (line in lines) {
            NODE_PASS.find(line)?.let { nodePass = it.groupValues[1].toInt() }
            NODE_FAIL.find(line)?.let { nodeFail = it.groupValues[1].toInt() }
        }
        if (nodePass >= 0) {
            return TestSummary(
                total = nodePass + nodeFail,
                passed = nodePass,
                failed = nodeFail,
                skipped = 0,
                cases = cases,
            )
        }

        for (line in lines) {
            CARGO.find(line)?.let { m ->
                val passed = m.groupValues[1].toInt()
                val failed = m.groupValues[2].toInt()
                val ignored = m.groupValues[3].toInt()
                return TestSummary(
                    total = passed + failed + ignored,
                    passed = passed,
                    failed = failed,
                    skipped = ignored,
                    cases = cases,
                )
            }
        }

        for (line in lines) {
            val m = PYTEST.find(line)
            if (m != null && line.contains("passed")) {
                val passed = m.groupValues[1].toInt()
                val failed = m.groupValues[2].toIntOrNull() ?: 0
                val skipped = m.groupValues[3].toIntOrNull() ?: 0
                return TestSummary(
                    total = passed + failed + skipped,
                    passed = passed,
                    failed = failed,
                    skipped = skipped,
                    cases = cases,
                )
            }
        }

        return if (cases.isEmpty()) null else {
            TestSummary(
                total = cases.size,
                passed = cases.count { it.passed },
                failed = cases.count { !it.passed },
                skipped = 0,
                cases = cases,
            )
        }
    }
}
