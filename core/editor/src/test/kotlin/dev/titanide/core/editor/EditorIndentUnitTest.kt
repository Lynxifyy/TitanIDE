package dev.titanide.core.editor

import dev.titanide.core.model.Language
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tab width setting has to reach the engine. Indentation used to be fixed to the
 * language default, so the setting changed nothing on screen.
 */
class EditorIndentUnitTest {

    private fun indentAfterEnter(indentUnit: String?, language: Language = Language.KOTLIN): String {
        val engine = EditorEngine(language, indentUnit)
        val document = TextDocument.of("fun main() {")
        val transform = engine.onType(document, document.text.length, '\n')
            ?: error("Enter at the end of an opening brace must indent")
        return transform.document.text.substringAfterLast('\n')
    }

    @Test
    fun `the language default applies when no width is chosen`() {
        assertEquals("    ", indentAfterEnter(null))
    }

    @Test
    fun `a two space width overrides the kotlin default of four`() {
        assertEquals("  ", indentAfterEnter("  "))
    }

    @Test
    fun `an eight space width is honoured`() {
        assertEquals("        ", indentAfterEnter("        "))
    }

    @Test
    fun `the override also applies to languages with their own convention`() {
        // JSON defaults to two spaces; the user asked for four.
        assertEquals("    ", indentAfterEnter("    ", Language.JSON))
    }

    @Test
    fun `a closing brace removes exactly one overridden indent level`() {
        // The engine only re-indents here; the caller inserts the character itself.
        val engine = EditorEngine(Language.KOTLIN, "  ")
        val document = TextDocument.of("fun main() {\n    ")
        val transform = engine.onType(document, document.text.length, '}')
            ?: error("closing brace must outdent")
        assertEquals(
            "four spaces must become one two-space level",
            "fun main() {\n  ",
            transform.document.text,
        )
    }

    @Test
    fun `a closing brace with the default width leaves one four-space level`() {
        val engine = EditorEngine(Language.KOTLIN)
        val document = TextDocument.of("fun main() {\n        ")
        val transform = engine.onType(document, document.text.length, '}')
            ?: error("closing brace must outdent")
        assertEquals("fun main() {\n    ", transform.document.text)
    }
}
