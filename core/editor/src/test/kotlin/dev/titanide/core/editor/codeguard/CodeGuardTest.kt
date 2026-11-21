package dev.titanide.core.editor.codeguard

import dev.titanide.core.model.Language
import dev.titanide.core.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeGuardTest {

    private val guard = CodeGuard()

    @Test
    fun `unclosed brace is reported as an error`() {
        val diagnostics = guard.analyze("fun main() {\n    println(\"hi\")\n", Language.KOTLIN, "Main.kt")
        val unclosed = diagnostics.filter { it.message.contains("Unclosed") }
        assertEquals(1, unclosed.size)
        assertEquals(Severity.ERROR, unclosed.first().severity)
    }

    @Test
    fun `mismatched bracket is reported`() {
        val diagnostics = guard.analyze("fun f() {\n    val x = listOf(1, 2]\n}", Language.KOTLIN)
        assertTrue(diagnostics.any { it.message.contains("Mismatched bracket") })
    }

    @Test
    fun `unexpected closing bracket is reported`() {
        val diagnostics = guard.analyze("val x = 1\n}", Language.KOTLIN)
        assertTrue(diagnostics.any { it.message.contains("Unexpected closing") })
    }

    @Test
    fun `balanced code produces no syntax errors`() {
        val code = """
            fun main() {
                val items = listOf(1, 2, 3)
                items.forEach { println(it) }
            }
        """.trimIndent()
        val errors = guard.analyze(code, Language.KOTLIN).filter { it.severity == Severity.ERROR }
        assertTrue("expected no errors but got: ${errors.map { it.message }}", errors.isEmpty())
    }

    @Test
    fun `misspelled keyword is detected with a quick fix`() {
        val diagnostics = guard.analyze("fucntion greet() { }", Language.JAVASCRIPT)
        val typo = diagnostics.firstOrNull { it.message.contains("Did you mean 'function'") }
        assertTrue("expected a keyword typo diagnostic, got ${diagnostics.map { it.message }}", typo != null)
        assertEquals("function", typo!!.quickFix?.replacement)
    }

    @Test
    fun `duplicate modifier is flagged`() {
        val diagnostics = guard.analyze("public public class A {}", Language.JAVA)
        assertTrue(diagnostics.any { it.message.contains("Duplicate modifier") })
    }

    @Test
    fun `unused local variable is reported`() {
        val code = "fun f() {\n    val unused = 5\n    println(1)\n}"
        val diagnostics = guard.analyze(code, Language.KOTLIN)
        assertTrue(
            "expected unused warning, got ${diagnostics.map { it.message }}",
            diagnostics.any { it.message.contains("'unused' is declared but never used") },
        )
    }

    @Test
    fun `used variable is not reported as unused`() {
        val code = "fun f() {\n    val used = 5\n    println(used)\n}"
        val diagnostics = guard.analyze(code, Language.KOTLIN)
        assertTrue(diagnostics.none { it.message.contains("'used' is declared but never used") })
    }

    @Test
    fun `undefined function call is reported`() {
        val code = "fun f() {\n    unknownHelper()\n}"
        val diagnostics = guard.analyze(code, Language.KOTLIN)
        assertTrue(
            "expected undefined reference, got ${diagnostics.map { it.message }}",
            diagnostics.any { it.message.contains("'unknownHelper' is not declared") },
        )
    }

    @Test
    fun `declared function call is not reported`() {
        val code = "fun helper() {}\n\nfun f() {\n    helper()\n}"
        val diagnostics = guard.analyze(code, Language.KOTLIN)
        assertTrue(diagnostics.none { it.message.contains("'helper' is not declared") })
    }

    @Test
    fun `json trailing comma is an error with a delete fix`() {
        val json = """{"a": 1,}"""
        val diagnostics = guard.analyze(json, Language.JSON, "data.json")
        val trailing = diagnostics.firstOrNull { it.message.contains("Trailing comma") }
        assertTrue(trailing != null)
        assertTrue(trailing!!.quickFix != null)
    }

    @Test
    fun `unterminated string is reported`() {
        val diagnostics = guard.analyze("val s = \"unterminated\n", Language.KOTLIN)
        assertTrue(diagnostics.any { it.message.contains("Unterminated string") })
    }

    @Test
    fun `plaintext is never analysed`() {
        assertTrue(guard.analyze("}}} [[[ \"", Language.PLAINTEXT).isEmpty())
    }

    @Test
    fun `diagnostics are ordered by position`() {
        val code = "}\n}\n}"
        val diagnostics = guard.analyze(code, Language.KOTLIN)
        assertEquals(diagnostics.sortedBy { it.line }, diagnostics)
    }
}

/**
 * Precision cases.
 *
 * The analyser runs on every keystroke and paints markers in the gutter, so a false positive
 * is not a cosmetic problem: enough of them and the panel is something the user learns to
 * ignore, which costs them the real error. These cases pin the ones that were wrong on this
 * repository's own sources — 7114 diagnostics over 143 files, most of them noise.
 */
class CodeGuardPrecisionTest {

    private val guard = CodeGuard()

    private fun warnings(code: String, language: Language = Language.KOTLIN) =
        guard.analyze(code, language).filter { it.severity != Severity.HINT }

    @Test
    fun `a correctly spelled identifier close to a keyword is not a typo`() {
        // `cost` is one edit from `const`; it is also a perfectly ordinary local.
        val diagnostics = warnings("fun f() {\n    val cost = 1\n    println(cost)\n}")
        assertTrue(
            "declared names are symbols, not typos: ${diagnostics.map { it.message }}",
            diagnostics.none { it.message.contains("Did you mean") },
        )
    }

    @Test
    fun `a transposed keyword is still caught`() {
        val diagnostics = guard.analyze("fucntion greet() { }", Language.JAVASCRIPT)
        val typo = diagnostics.firstOrNull { it.message.contains("Did you mean 'function'") }
        assertTrue("a transposition is the commonest typo of all", typo != null)
        assertEquals("function", typo!!.quickFix?.replacement)
    }

    @Test
    fun `a typo inside a backticked name is not reported`() {
        val code = "fun `invalid regex returns no matches`() { }"
        val diagnostics = guard.analyze(code, Language.KOTLIN)
        assertTrue(
            "backticked text is prose: ${diagnostics.map { it.message }}",
            diagnostics.none { it.message.contains("Did you mean") },
        )
    }

    @Test
    fun `a char literal holding a quote does not look like an open string`() {
        val cases = listOf(
            "    val c = '\"'",
            "        append('\"')",
            "            val name = obj[\"name\"]?.toString()?.trim('\"') ?: continue",
            "            char == '\\'' || char == '\"' -> {",
        )
        cases.forEach { line ->
            val diagnostics = guard.analyze(line, Language.KOTLIN)
            assertTrue(
                "no unterminated string on <$line>, got ${diagnostics.map { it.message }}",
                diagnostics.none { it.message.contains("Unterminated") },
            )
        }
    }

    @Test
    fun `a genuinely unterminated string is still an error`() {
        val diagnostics = guard.analyze("val s = \"unclosed\nval t = 1", Language.KOTLIN)
        val error = diagnostics.firstOrNull { it.message.contains("Unterminated") }
        assertTrue("a real open string must be reported", error != null)
        assertEquals(Severity.ERROR, error!!.severity)
    }

    @Test
    fun `a raw string block is not an unterminated string`() {
        val code = """
            val text = ${"\"\"\""}
            fun main() {
                println("hello")
            }
            ${"\"\"\""}
        """.trimIndent()
        val diagnostics = guard.analyze(code, Language.KOTLIN)
        assertTrue(
            "a \"\"\" block spans lines legitimately: ${diagnostics.map { it.message }}",
            diagnostics.none { it.message.contains("Unterminated") },
        )
    }

    @Test
    fun `a comment mentioning a quote is not an unterminated string`() {
        val diagnostics = guard.analyze("// don't do this\nval x = 1", Language.KOTLIN)
        assertTrue(
            "prose in a comment is not code: ${diagnostics.map { it.message }}",
            diagnostics.none { it.message.contains("Unterminated") },
        )
    }

    @Test
    fun `data class properties are not reported as unused`() {
        val code = """
            data class Request(
                val projectId: String,
                val projectPath: String,
                val kind: Int,
            )
        """.trimIndent()
        val diagnostics = warnings(code)
        assertTrue(
            "properties are read from other files: ${diagnostics.map { it.message }}",
            diagnostics.none { it.message.contains("never used") },
        )
    }

    @Test
    fun `a local that is never read is reported`() {
        val diagnostics = warnings("fun f() {\n    val unusedLocal = 1\n}")
        assertTrue(
            "a genuinely unused local should be reported",
            diagnostics.any { it.message.contains("'unusedLocal' is declared but never used") },
        )
    }

    @Test
    fun `a local read after its declaration is not reported`() {
        val diagnostics = warnings("fun f() {\n    val used = 1\n    println(used)\n}")
        assertTrue(
            "reading a local counts as using it: ${diagnostics.map { it.message }}",
            diagnostics.none { it.message.contains("never used") },
        )
    }

    @Test
    fun `lambda parameters are declarations, not undeclared references`() {
        val code = "fun f(xs: List<Int>) {\n    xs.forEach { item -> println(item) }\n}"
        val hints = guard.analyze(code, Language.KOTLIN)
            .filter { it.message.contains("not declared in this file") }
        assertTrue(
            "a lambda parameter is declared by the lambda: ${hints.map { it.message }}",
            hints.none { it.message.contains("'item'") },
        )
    }

    @Test
    fun `cross-file references are hints, not warnings`() {
        // `println` lives elsewhere; the analyser only sees this file, so it must not look
        // like a compiler error.
        val diagnostics = guard.analyze("fun f() {\n    helperFromAnotherFile()\n}", Language.KOTLIN)
        val reference = diagnostics.firstOrNull { it.message.contains("not declared in this file") }
        assertTrue("the reference should be reported at all", reference != null)
        assertEquals(
            "a single-file heuristic must not outrank a real warning",
            Severity.HINT,
            reference!!.severity,
        )
    }
}
