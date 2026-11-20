package dev.titanide.core.editor

import dev.titanide.core.model.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDocumentTest {

    @Test
    fun `line offsets are correct for multiline text`() {
        val doc = TextDocument.of("first\nsecond\nthird")
        assertEquals(3, doc.lineCount)
        assertEquals("first", doc.lineText(0))
        assertEquals("second", doc.lineText(1))
        assertEquals("third", doc.lineText(2))
        assertEquals(6, doc.lineStart(1))
        assertEquals(5, doc.lineEnd(0))
    }

    @Test
    fun `line and column round trip through offsets`() {
        val doc = TextDocument.of("abc\ndefgh\nij")
        assertEquals(1, doc.lineOf(5))
        assertEquals(1, doc.columnOf(5))
        assertEquals(5, doc.offsetOf(1, 1))
    }

    @Test
    fun `trailing newline creates a final empty line`() {
        val doc = TextDocument.of("a\n")
        assertEquals(2, doc.lineCount)
        assertEquals("", doc.lineText(1))
    }

    @Test
    fun `insert records an invertible edit`() {
        val (doc, edit) = TextDocument.of("hello world").insert(5, ",")
        assertEquals("hello, world", doc.text)
        assertEquals("", edit.oldText)
        assertEquals(",", edit.newText)
    }

    @Test
    fun `applyAll replays edits from last to first`() {
        val original = TextDocument.of("alpha beta gamma")
        val edits = listOf(
            EditRecord(0, 5, 5, "alpha", "ALPHA"),
            EditRecord(11, 16, 16, "gamma", "GAMMA"),
        )
        val result = original.applyAll(edits)
        assertEquals("ALPHA beta GAMMA", result.text)
    }

    @Test
    fun `wordRangeAt selects the identifier under the caret`() {
        val doc = TextDocument.of("val counter = 0")
        assertEquals(4..10, doc.wordRangeAt(6))
    }

    @Test
    fun `indentOf returns leading whitespace only`() {
        val doc = TextDocument.of("    indented\n\t tabbed")
        assertEquals("    ", doc.indentOf(0))
        assertEquals("\t ", doc.indentOf(1))
    }
}

class UndoStackTest {

    @Test
    fun `undo restores previous text and redo reapplies`() {
        val stack = UndoStack()
        val (after, edit) = TextDocument.of("abc").insert(3, "def")
        stack.record(edit)
        assertEquals("abcdef", after.text)

        val undone = stack.undo(after)
        assertNotNull(undone)
        assertEquals("abc", undone!!.text)

        val redone = stack.redo(undone)
        assertNotNull(redone)
        assertEquals("abcdef", redone!!.text)
    }

    @Test
    fun `new edit clears redo history`() {
        val stack = UndoStack()
        val (doc1, e1) = TextDocument.of("a").insert(1, "b")
        stack.record(e1)
        val undone = stack.undo(doc1)!!
        assertTrue(stack.canRedo)

        val (_, e2) = undone.insert(1, "c")
        stack.record(e2)
        assertFalse(stack.canRedo)
    }

    @Test
    fun `undo on empty stack returns null`() {
        assertNull(UndoStack().undo(TextDocument.of("x")))
    }

    @Test
    fun `history is bounded by the limit`() {
        val stack = UndoStack(limit = 3)
        var doc = TextDocument.of("")
        repeat(10) { i ->
            val (next, edit) = doc.insert(doc.length, "$i")
            doc = next
            stack.record(edit)
        }
        var undone = doc
        var count = 0
        while (stack.canUndo) {
            undone = stack.undo(undone)!!
            count++
        }
        assertEquals(3, count)
    }
}

class TokenizerTest {

    @Test
    fun `kotlin keywords strings and comments are classified`() {
        val code = """
            // a comment
            fun main() {
                val name = "titan"
            }
        """.trimIndent()
        val tokens = Tokenizer.tokenize(code, Language.KOTLIN)
        val types = tokens.map { it.type }.toSet()
        assertTrue(types.contains(TokenType.COMMENT))
        assertTrue(types.contains(TokenType.KEYWORD))
        assertTrue(types.contains(TokenType.STRING))
    }

    @Test
    fun `tokens never overlap and stay in bounds`() {
        val code = "class A(val x: Int = 1) { fun f() = listOf(1, 2) }"
        val tokens = Tokenizer.tokenize(code, Language.KOTLIN)
        var previousEnd = 0
        for (token in tokens) {
            assertTrue("token start before previous end", token.start >= previousEnd)
            assertTrue("token end within text", token.end <= code.length)
            assertTrue("token non-empty", token.length > 0)
            previousEnd = token.end
        }
    }

    @Test
    fun `plaintext produces no tokens`() {
        assertTrue(Tokenizer.tokenize("anything at all", Language.PLAINTEXT).isEmpty())
    }

    @Test
    fun `python comments use hash`() {
        val tokens = Tokenizer.tokenize("# note\nx = 1", Language.PYTHON)
        assertEquals(TokenType.COMMENT, tokens.first().type)
    }

    @Test
    fun `unterminated string does not hang the tokenizer`() {
        val tokens = Tokenizer.tokenize("val s = \"oops", Language.KOTLIN)
        assertNotNull(tokens)
    }

    @Test
    fun `wordAt reads the identifier around the offset`() {
        val code = "val total = count + 1"
        assertEquals("total", Tokenizer.wordAt(code, 5))
        assertEquals("count", Tokenizer.wordAt(code, 13))
    }
}
