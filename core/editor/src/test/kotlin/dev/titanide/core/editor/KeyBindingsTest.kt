package dev.titanide.core.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The editor showed a "Ctrl+S saves - Ctrl+/ comments - Ctrl+Z undoes" hint while no key event
 * was handled anywhere, so a Bluetooth keyboard could not drive any of it. These cases pin the
 * bindings that now back the hint.
 */
class KeyBindingsTest {

    @Test
    fun `ctrl z undoes`() {
        assertEquals(EditorCommand.UNDO, KeyBindings.commandFor(KeyChord(KeyName.Z, ctrl = true)))
    }

    @Test
    fun `ctrl shift z redoes`() {
        assertEquals(
            EditorCommand.REDO,
            KeyBindings.commandFor(KeyChord(KeyName.Z, ctrl = true, shift = true)),
        )
    }

    @Test
    fun `ctrl y also redoes`() {
        assertEquals(EditorCommand.REDO, KeyBindings.commandFor(KeyChord(KeyName.Y, ctrl = true)))
    }

    @Test
    fun `ctrl s saves`() {
        assertEquals(EditorCommand.SAVE, KeyBindings.commandFor(KeyChord(KeyName.S, ctrl = true)))
    }

    @Test
    fun `ctrl slash toggles a comment`() {
        assertEquals(
            EditorCommand.TOGGLE_COMMENT,
            KeyBindings.commandFor(KeyChord(KeyName.SLASH, ctrl = true)),
        )
    }

    @Test
    fun `ctrl f formats`() {
        assertEquals(EditorCommand.FORMAT, KeyBindings.commandFor(KeyChord(KeyName.F, ctrl = true)))
    }

    @Test
    fun `a bare letter is not a shortcut`() {
        assertNull(KeyBindings.commandFor(KeyChord(KeyName.Z)))
        assertNull(KeyBindings.commandFor(KeyChord(KeyName.S)))
    }

    @Test
    fun `an unbound key does nothing`() {
        assertNull(KeyBindings.commandFor(KeyChord(KeyName.UNKNOWN, ctrl = true)))
    }

    @Test
    fun `f12 navigates to the definition`() {
        assertEquals(EditorCommand.GO_TO_DEFINITION, KeyBindings.commandFor(KeyChord(KeyName.F12)))
    }

    @Test
    fun `shift f12 finds references`() {
        assertEquals(
            EditorCommand.FIND_REFERENCES,
            KeyBindings.commandFor(KeyChord(KeyName.F12, shift = true)),
        )
    }

    @Test
    fun `a bare unknown key is not a shortcut`() {
        assertNull(KeyBindings.commandFor(KeyChord(KeyName.UNKNOWN)))
    }
}
