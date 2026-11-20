package dev.titanide.core.editor

import dev.titanide.core.model.Language
import dev.titanide.core.model.SymbolKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorEngineTest {

    private val kotlin = EditorEngine(Language.KOTLIN)

    @Test
    fun `newline after opening brace indents one level`() {
        val doc = TextDocument.of("fun f() {}")
        val caret = doc.text.indexOf('{') + 1
        val result = kotlin.handleNewline(doc, caret)
        assertTrue(result.document.text.contains("{\n    \n}"))
        assertEquals(caret + 5, result.caret)
    }

    @Test
    fun `newline keeps existing indentation`() {
        val doc = TextDocument.of("    val a = 1")
        val result = kotlin.handleNewline(doc, doc.length)
        assertEquals("    val a = 1\n    ", result.document.text)
    }

    @Test
    fun `typing open paren inserts the closing paren`() {
        val doc = TextDocument.of("f")
        val result = kotlin.onType(doc, 1, '(')
        assertNotNull(result)
        assertEquals("f()", result!!.document.text)
        assertEquals(2, result.caret)
    }

    @Test
    fun `typing quote inserts a matching quote`() {
        val doc = TextDocument.of("val s = ")
        val result = kotlin.onType(doc, doc.length, '"')
        assertEquals("val s = \"\"", result!!.document.text)
    }

    @Test
    fun `closing quote is skipped rather than duplicated`() {
        val doc = TextDocument.of("\"\"")
        val result = kotlin.onType(doc, 1, '"')
        assertEquals("\"\"", result!!.document.text)
        assertEquals(2, result.caret)
    }

    @Test
    fun `backspace between a pair removes both characters`() {
        val doc = TextDocument.of("f()")
        val result = kotlin.onBackspace(doc, 2)
        assertEquals("f", result!!.document.text)
    }

    @Test
    fun `toggle comment adds and removes line markers`() {
        val doc = TextDocument.of("val a = 1\nval b = 2")
        val commented = kotlin.toggleLineComment(doc, 0, doc.length)!!
        assertEquals("// val a = 1\n// val b = 2", commented.document.text)

        val restored = kotlin.toggleLineComment(commented.document, 0, commented.document.length)!!
        assertEquals("val a = 1\nval b = 2", restored.document.text)
    }

    @Test
    fun `python comment toggle uses hash`() {
        val python = EditorEngine(Language.PYTHON)
        val doc = TextDocument.of("x = 1")
        val result = python.toggleLineComment(doc, 0, 1)!!
        assertEquals("# x = 1", result.document.text)
    }

    @Test
    fun `indent and outdent shift the whole selection`() {
        val doc = TextDocument.of("a\nb")
        val indented = kotlin.indentSelection(doc, 0, doc.length, outdent = false)
        assertEquals("    a\n    b", indented.document.text)
        val outdented = kotlin.indentSelection(indented.document, 0, indented.document.length, outdent = true)
        assertEquals("a\nb", outdented.document.text)
    }

    @Test
    fun `format document normalises indentation by brace depth`() {
        val messy = "fun f() {\nval a = 1\nif (a > 0) {\nprintln(a)\n}\n}"
        val formatted = kotlin.formatDocument(TextDocument.of(messy)).document.text
        val expected = """
            fun f() {
                val a = 1
                if (a > 0) {
                    println(a)
                }
            }
        """.trimIndent()
        assertEquals(expected, formatted)
    }

    @Test
    fun `format ignores braces inside string literals`() {
        val source = "fun f() {\nval s = \"}\"\n}"
        val formatted = kotlin.formatDocument(TextDocument.of(source)).document.text
        assertEquals("fun f() {\n    val s = \"}\"\n}", formatted)
    }

    @Test
    fun `fold regions cover nested blocks`() {
        val doc = TextDocument.of("fun outer() {\n    if (true) {\n        println(1)\n    }\n}")
        val regions = kotlin.foldRegions(doc)
        assertEquals(2, regions.size)
        assertEquals(0, regions[0].startLine)
        assertEquals(4, regions[0].endLine)
    }

    @Test
    fun `matching bracket finds the pair in both directions`() {
        val doc = TextDocument.of("f(a, b)")
        assertEquals(6, kotlin.matchingBracket(doc, 1))
        assertEquals(1, kotlin.matchingBracket(doc, 6))
    }

    @Test
    fun `symbols are extracted with correct kinds`() {
        val code = """
            class Widget {
                val label: String = ""
                fun render() {}
            }
            typealias Callback = () -> Unit
        """.trimIndent()
        val symbols = kotlin.extractSymbols(TextDocument.of(code), "Widget.kt")
        val names = symbols.map { it.name }
        assertTrue(names.contains("Widget"))
        assertTrue(names.contains("label"))
        assertTrue(names.contains("render"))
        assertTrue(names.contains("Callback"))
        assertEquals(SymbolKind.CLASS, symbols.first { it.name == "Widget" }.kind)
        assertEquals(SymbolKind.FUNCTION, symbols.first { it.name == "render" }.kind)
    }

    @Test
    fun `python symbols include classes and functions`() {
        val python = EditorEngine(Language.PYTHON)
        val code = "class Service:\n    def run(self):\n        pass"
        val symbols = python.extractSymbols(TextDocument.of(code), "service.py")
        assertEquals(listOf("Service", "run"), symbols.map { it.name })
    }

    @Test
    fun `completions include keywords and declared symbols filtered by prefix`() {
        val doc = TextDocument.of("val counter = 1\ncou")
        val items = kotlin.completions(doc, doc.length)
        assertTrue(items.any { it.label == "counter" })
        assertTrue(items.all { it.label.startsWith("cou", ignoreCase = true) })
    }

    @Test
    fun `completions are empty-prefix safe and bounded`() {
        val doc = TextDocument.of("")
        val items = kotlin.completions(doc, 0)
        assertTrue(items.size <= 60)
    }
}

class SearchEngineTest {

    private val engine = SearchEngine()

    @Test
    fun `search finds all occurrences with positions`() {
        val doc = TextDocument.of("val a = 1\nval b = a + a")
        val matches = engine.search(doc, "a", SearchOptions(wholeWord = true))
        assertEquals(3, matches.size)
        assertEquals(0, matches.first().line)
    }

    @Test
    fun `case sensitivity is honoured`() {
        val doc = TextDocument.of("Titan titan TITAN")
        assertEquals(3, engine.search(doc, "titan", SearchOptions()).size)
        assertEquals(1, engine.search(doc, "titan", SearchOptions(caseSensitive = true)).size)
    }

    @Test
    fun `regex search works`() {
        val doc = TextDocument.of("id1 id2 id3")
        val matches = engine.search(doc, "id\\d", SearchOptions(regex = true))
        assertEquals(3, matches.size)
    }

    @Test
    fun `invalid regex returns no matches instead of throwing`() {
        val doc = TextDocument.of("anything")
        assertTrue(engine.search(doc, "[unclosed", SearchOptions(regex = true)).isEmpty())
    }

    @Test
    fun `replaceAll replaces every occurrence and counts them`() {
        val doc = TextDocument.of("a b a b a")
        val (result, count) = engine.replaceAll(doc, "a", "x", SearchOptions())
        assertEquals(3, count)
        assertEquals("x b x b x", result.text)
    }

    @Test
    fun `replaceAll supports capture groups`() {
        val doc = TextDocument.of("name: titan")
        val (result, count) = engine.replaceAll(doc, "name: (\\w+)", "title: $1", SearchOptions(regex = true))
        assertEquals(1, count)
        assertEquals("title: titan", result.text)
    }

    @Test
    fun `searchFiles returns only files with matches`() {
        val files = mapOf(
            "A.kt" to "val target = 1",
            "B.kt" to "val other = 2",
        )
        val results = engine.searchFiles(files, "target", SearchOptions())
        assertEquals(1, results.size)
        assertEquals("A.kt", results.first().filePath)
    }
}
