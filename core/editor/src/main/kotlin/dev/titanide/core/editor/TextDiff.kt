package dev.titanide.core.editor

/**
 * Reduces the difference between two texts to a single replacement range.
 *
 * The editor receives whole strings from Compose rather than key events, so undo needs a
 * minimal edit derived after the fact. Trimming the common prefix and suffix yields exactly
 * the range the user touched in the common case (typing, deleting, pasting over a
 * selection), which keeps undo granularity predictable.
 */
object TextDiff {

    fun singleEdit(old: String, new: String): EditRecord? {
        if (old == new) return null

        val maxPrefix = minOf(old.length, new.length)
        var prefix = 0
        while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++

        val maxSuffix = minOf(old.length, new.length) - prefix
        var suffix = 0
        while (suffix < maxSuffix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++

        val removed = old.substring(prefix, old.length - suffix)
        val inserted = new.substring(prefix, new.length - suffix)
        return EditRecord(
            startOffset = prefix,
            endOffset = prefix + removed.length,
            newEndOffset = prefix + inserted.length,
            oldText = removed,
            newText = inserted,
        )
    }
}
