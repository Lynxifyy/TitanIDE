package dev.titanide.core.editor

/**
 * One file's worth of a rename: the original text and the text with every reference replaced.
 */
data class RenameFileChange(
    val filePath: String,
    val before: String,
    val after: String,
    val occurrences: Int,
) {
    val changed: Boolean get() = before != after
}

/** The complete effect of a rename, computed before anything is written. */
data class RenamePlan(
    val oldName: String,
    val newName: String,
    val files: List<RenameFileChange>,
) {
    val totalOccurrences: Int get() = files.sumOf { it.occurrences }
    val changedFiles: List<RenameFileChange> get() = files.filter { it.changed }
    val isEmpty: Boolean get() = changedFiles.isEmpty()

    /** Why this plan must not be applied, or null when it is safe. */
    val rejection: String?
        get() = when {
            !isValidIdentifier(newName) ->
                "'$newName' is not a valid identifier"
            newName == oldName -> "The new name is the same as the old one"
            isEmpty -> "Nothing in the project uses $oldName"
            else -> null
        }

    companion object {
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /**
         * Rejects names that would produce code the editor cannot re-tokenise as one
         * identifier, which is what a rename to `my name` or `2fast` would do.
         */
        fun isValidIdentifier(name: String): Boolean = IDENTIFIER.matches(name)
    }
}

/**
 * Computes renames over a project.
 *
 * A rename is planned in full before any file is written: the plan carries the old and new
 * text of every file it touches, so the UI can show exactly what will change and the caller
 * can refuse it. Applying a plan is a separate step, which keeps "show me what you would do"
 * and "do it" from being the same action.
 */
object RenamePlanner {

    /**
     * Plans the rename of [oldName] to [newName] across [sources] (path to text).
     *
     * Only real identifier tokens are rewritten, so a same-named word in a comment, string or
     * a longer identifier (`counter` when renaming `count`) is left alone.
     */
    fun plan(
        sources: Map<String, String>,
        languages: Map<String, dev.titanide.core.model.Language>,
        oldName: String,
        newName: String,
    ): RenamePlan {
        if (!RenamePlan.isValidIdentifier(oldName) || oldName.isEmpty()) {
            return RenamePlan(oldName, newName, emptyList())
        }
        val files = sources.map { (path, text) ->
            val language = languages[path] ?: dev.titanide.core.model.Language.fromFileName(path)
            renameInFile(path, text, language, oldName, newName)
        }
        return RenamePlan(oldName, newName, files)
    }

    private fun renameInFile(
        path: String,
        text: String,
        language: dev.titanide.core.model.Language,
        oldName: String,
        newName: String,
    ): RenameFileChange {
        val tokens = Tokenizer.tokenize(text, language)
            .filter {
                it.type == TokenType.IDENTIFIER ||
                    it.type == TokenType.FUNCTION ||
                    it.type == TokenType.TYPE
            }
            .filter { text.substring(it.start, it.end) == oldName }
        if (tokens.isEmpty()) return RenameFileChange(path, text, text, 0)

        val builder = StringBuilder(text.length)
        var cursor = 0
        for (token in tokens) {
            builder.append(text, cursor, token.start)
            builder.append(newName)
            cursor = token.end
        }
        builder.append(text, cursor, text.length)
        return RenameFileChange(path, text, builder.toString(), tokens.size)
    }
}
