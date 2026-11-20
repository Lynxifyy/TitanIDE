package dev.titanide.core.editor

/**
 * Immutable text buffer with offset/line conversion. Every mutation returns a new
 * document plus an [EditRecord] so undo, auto-save and diffing all work off the same
 * primitive.
 */
class TextDocument private constructor(
    val text: String,
    private val lineStarts: IntArray,
) {
    val length: Int get() = text.length
    val lineCount: Int get() = lineStarts.size

    companion object {
        fun of(text: String): TextDocument = TextDocument(text, computeLineStarts(text))

        private fun computeLineStarts(text: String): IntArray {
            val starts = ArrayList<Int>(text.length / 40 + 2)
            starts += 0
            text.forEachIndexed { i, c -> if (c == '\n') starts += i + 1 }
            return starts.toIntArray()
        }
    }

    fun lineStart(line: Int): Int = lineStarts[line.coerceIn(0, lineStarts.size - 1)]

    fun lineEnd(line: Int): Int {
        val clamped = line.coerceIn(0, lineStarts.size - 1)
        return if (clamped == lineStarts.size - 1) text.length else lineStarts[clamped + 1] - 1
    }

    /** Line content without the trailing newline. */
    fun lineText(line: Int): String {
        val clamped = line.coerceIn(0, lineStarts.size - 1)
        return text.substring(lineStarts[clamped], lineEnd(clamped))
    }

    fun lineRange(line: Int): IntRange {
        val clamped = line.coerceIn(0, lineStarts.size - 1)
        return lineStarts[clamped]..lineEnd(clamped)
    }

    fun lines(): List<String> = (0 until lineCount).map { lineText(it) }

    /** 0-based line index containing [offset]. */
    fun lineOf(offset: Int): Int {
        val clamped = offset.coerceIn(0, text.length)
        var low = 0
        var high = lineStarts.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (lineStarts[mid] <= clamped) low = mid else high = mid - 1
        }
        return low
    }

    /** 0-based column for [offset]. */
    fun columnOf(offset: Int): Int {
        val clamped = offset.coerceIn(0, text.length)
        return clamped - lineStart(lineOf(clamped))
    }

    fun offsetOf(line: Int, column: Int): Int {
        val start = lineStart(line)
        return (start + column).coerceIn(start, lineEnd(line))
    }

    fun replace(range: IntRange, replacement: String): Pair<TextDocument, EditRecord> {
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(start, text.length)
        return applyEdit(start, end, replacement)
    }

    fun insert(offset: Int, insertText: String): Pair<TextDocument, EditRecord> =
        applyEdit(offset.coerceIn(0, text.length), offset.coerceIn(0, text.length), insertText)

    fun delete(range: IntRange): Pair<TextDocument, EditRecord> {
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(start, text.length)
        return applyEdit(start, end, "")
    }

    private fun applyEdit(start: Int, end: Int, replacement: String): Pair<TextDocument, EditRecord> {
        val removed = text.substring(start, end)
        val newText = buildString(text.length - removed.length + replacement.length) {
            append(text, 0, start)
            append(replacement)
            append(text, end, text.length)
        }
        val record = EditRecord(start, start + removed.length, start + replacement.length, removed, replacement)
        return TextDocument.of(newText) to record
    }

    /** Applies [edits] from last to first so earlier offsets stay valid. */
    fun applyAll(edits: List<EditRecord>): TextDocument {
        var doc = this
        for (edit in edits.sortedByDescending { it.startOffset }) {
            doc = doc.applyEdit(edit.startOffset, edit.endOffset, edit.newText).first
        }
        return doc
    }

    fun wordRangeAt(offset: Int): IntRange? {
        if (text.isEmpty()) return null
        var pos = offset.coerceIn(0, text.length - 1)
        if (!text[pos].isLetterOrDigit() && text[pos] != '_') {
            if (pos == 0) return null
            pos--
            if (!text[pos].isLetterOrDigit() && text[pos] != '_') return null
        }
        var start = pos
        var end = pos
        while (start > 0 && isWord(text[start - 1])) start--
        while (end < text.length - 1 && isWord(text[end + 1])) end++
        return start..end
    }

    private fun isWord(c: Char) = c.isLetterOrDigit() || c == '_'

    fun indentOf(line: Int): String {
        val content = lineText(line)
        return content.takeWhile { it == ' ' || it == '\t' }
    }

    fun isBlankLine(line: Int): Boolean = lineText(line).isBlank()

    override fun toString(): String = text
}

/** A single reversible text mutation. */
data class EditRecord(
    val startOffset: Int,
    val endOffset: Int,
    val newEndOffset: Int,
    val oldText: String,
    val newText: String,
) {
    fun isNoOp(): Boolean = oldText == newText
}

/** Bounded undo/redo history over [TextDocument]. */
class UndoStack(private val limit: Int = 200) {
    private val undoable = ArrayDeque<EditRecord>()
    private val redoable = ArrayDeque<EditRecord>()

    fun record(edit: EditRecord) {
        if (edit.isNoOp()) return
        undoable.addLast(edit)
        if (undoable.size > limit) undoable.removeFirst()
        redoable.clear()
    }

    val canUndo: Boolean get() = undoable.isNotEmpty()
    val canRedo: Boolean get() = redoable.isNotEmpty()

    /** Returns the document with the last edit reverted, or null when nothing to undo. */
    fun undo(doc: TextDocument): TextDocument? {
        val edit = undoable.removeLastOrNull() ?: return null
        redoable.addLast(edit)
        return doc.replace(edit.startOffset until edit.newEndOffset, edit.oldText).first
    }

    fun redo(doc: TextDocument): TextDocument? {
        val edit = redoable.removeLastOrNull() ?: return null
        undoable.addLast(edit)
        return doc.replace(edit.startOffset until (edit.startOffset + edit.oldText.length), edit.newText).first
    }

    fun clear() {
        undoable.clear()
        redoable.clear()
    }
}
