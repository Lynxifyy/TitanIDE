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
