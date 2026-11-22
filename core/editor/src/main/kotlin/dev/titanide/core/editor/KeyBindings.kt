package dev.titanide.core.editor

/** A key chord as the editor sees it, independent of the UI toolkit. */
data class KeyChord(
    val key: KeyName,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
)

enum class KeyName { Z, Y, S, SLASH, F, F2, F12, UNKNOWN }

/** An editor command a key chord can trigger. */
enum class EditorCommand {
    UNDO,
    REDO,
    SAVE,
    TOGGLE_COMMENT,
    FORMAT,
    GO_TO_DEFINITION,
    FIND_REFERENCES,
    RENAME,
}

/**
 * Maps key chords to editor commands.
 *
 * Kept out of the Compose layer so the bindings can be tested without injecting key events.
 * Undo and redo are also on screen, because a phone has no Ctrl key; the chords exist for a
 * Bluetooth keyboard.
 */
object KeyBindings {

    fun commandFor(chord: KeyChord): EditorCommand? {
        if (!chord.ctrl) {
            return when (chord.key) {
                KeyName.F12 -> if (chord.shift) EditorCommand.FIND_REFERENCES else EditorCommand.GO_TO_DEFINITION
                KeyName.F2 -> EditorCommand.RENAME
                else -> null
            }
        }
        return when (chord.key) {
            KeyName.Z -> if (chord.shift) EditorCommand.REDO else EditorCommand.UNDO
            KeyName.Y -> EditorCommand.REDO
            KeyName.S -> EditorCommand.SAVE
            KeyName.SLASH -> EditorCommand.TOGGLE_COMMENT
            KeyName.F -> EditorCommand.FORMAT
            KeyName.F12 -> if (chord.shift) EditorCommand.FIND_REFERENCES else EditorCommand.GO_TO_DEFINITION
            KeyName.F2 -> EditorCommand.RENAME
            KeyName.UNKNOWN -> null
        }
    }
}
