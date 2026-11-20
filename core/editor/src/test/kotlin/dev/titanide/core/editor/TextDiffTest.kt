package dev.titanide.core.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextDiffTest {

    @Test
    fun `identical texts produce no edit`() {
        assertNull(TextDiff.singleEdit("abc", "abc"))
    }

    @Test
    fun `typing one character yields a one-character insert`() {
        val edit = TextDiff.singleEdit("ac", "abc")!!
        assertEquals(1, edit.startOffset)
        assertEquals(1, edit.endOffset)
        assertEquals(2, edit.newEndOffset)
        assertEquals("", edit.oldText)
        assertEquals("b", edit.newText)
    }

    @Test
    fun `deleting one character yields a one-character removal`() {
        val edit = TextDiff.singleEdit("abc", "ac")!!
        assertEquals(1, edit.startOffset)
        assertEquals(2, edit.endOffset)
        assertEquals(1, edit.newEndOffset)
        assertEquals("b", edit.oldText)
        assertEquals("", edit.newText)
    }

    @Test
    fun `replacing a selection captures both sides`() {
        val edit = TextDiff.singleEdit("val x = 1", "val x = 42")!!
        assertEquals("1", edit.oldText)
        assertEquals("42", edit.newText)
    }

    @Test
    fun `appending at the end is a pure insert`() {
        val edit = TextDiff.singleEdit("line", "line\nnext")!!
        assertEquals("", edit.oldText)
        assertEquals("\nnext", edit.newText)
        assertEquals(4, edit.startOffset)
    }

    @Test
    fun `edit applied to the original reproduces the new text`() {
        val old = "fun main() {\n    println(\"hi\")\n}\n"
        val new = "fun main() {\n    println(\"hello\")\n}\n"
        val edit = TextDiff.singleEdit(old, new)!!
        val rebuilt = old.replaceRange(edit.startOffset, edit.endOffset, edit.newText)
        assertEquals(new, rebuilt)
    }

    @Test
    fun `completely different text is one full replacement`() {
        val edit = TextDiff.singleEdit("aaa", "bbb")!!
        assertEquals(0, edit.startOffset)
        assertEquals(3, edit.endOffset)
        assertEquals("aaa", edit.oldText)
        assertEquals("bbb", edit.newText)
    }

    @Test
    fun `edit from empty document is a full insert`() {
        val edit = TextDiff.singleEdit("", "hello")!!
        assertEquals(0, edit.startOffset)
        assertEquals(0, edit.endOffset)
        assertEquals("hello", edit.newText)
    }

    @Test
    fun `undo stack reverts a recorded diff`() {
        val stack = UndoStack()
        val before = TextDocument.of("abc")
        val edit = TextDiff.singleEdit("abc", "abcd")!!
        stack.record(edit)
        val after = before.replace(edit.startOffset until edit.endOffset, edit.newText).first
        assertEquals("abcd", after.text)
        val reverted = stack.undo(after)!!
        assertEquals("abc", reverted.text)
    }

    @Test
    fun `repeated edits undo in reverse order`() {
        val stack = UndoStack()
        var doc = TextDocument.of("a")
        listOf("ab", "abc", "abcd").forEach { target ->
            val edit = TextDiff.singleEdit(doc.text, target)!!
            stack.record(edit)
            doc = doc.replace(edit.startOffset until edit.endOffset, edit.newText).first
        }
        assertEquals("abcd", doc.text)
        doc = stack.undo(doc)!!
        assertEquals("abc", doc.text)
        doc = stack.undo(doc)!!
        assertEquals("ab", doc.text)
        doc = stack.undo(doc)!!
        assertEquals("a", doc.text)
        assertNull(stack.undo(doc))
    }

    @Test
    fun `undo then redo returns to the edited text`() {
        val stack = UndoStack()
        val edit = TextDiff.singleEdit("one", "one two")!!
        stack.record(edit)
        val edited = TextDocument.of("one").replace(0 until 3, "one two").first
        val undone = stack.undo(edited)!!
        assertEquals("one", undone.text)
        val redone = stack.redo(undone)!!
        assertEquals("one two", redone.text)
    }
}
