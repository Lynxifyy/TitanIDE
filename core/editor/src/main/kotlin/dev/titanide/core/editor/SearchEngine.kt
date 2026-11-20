package dev.titanide.core.editor

data class SearchOptions(
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
)

data class SearchMatch(
    val start: Int,
    val end: Int,
    val line: Int,
    val column: Int,
    val lineText: String,
)

data class FileSearchResult(
    val filePath: String,
    val matches: List<SearchMatch>,
)

/** Find/replace over a single document and across a project. */
class SearchEngine {

    fun search(document: TextDocument, query: String, options: SearchOptions): List<SearchMatch> {
        if (query.isEmpty()) return emptyList()
        val regex = buildRegex(query, options) ?: return emptyList()
        val text = document.text
        val matches = ArrayList<SearchMatch>()

        for (match in regex.findAll(text)) {
            if (match.range.first > text.length) break
            val start = match.range.first
            val end = match.range.last + 1
            val line = document.lineOf(start)
            matches += SearchMatch(
                start = start,
                end = end,
                line = line,
                column = start - document.lineStart(line),
                lineText = document.lineText(line).trimEnd(),
            )
        }
        return matches
    }

    fun searchFiles(
        files: Map<String, String>,
        query: String,
        options: SearchOptions,
    ): List<FileSearchResult> {
        if (query.isEmpty()) return emptyList()
        return files.mapNotNull { (path, content) ->
            val document = TextDocument.of(content)
            val matches = search(document, query, options)
            if (matches.isEmpty()) null else FileSearchResult(path, matches)
        }.sortedByDescending { it.matches.size }
    }

    /** Replaces every match in [document]; returns the new document and count. */
    fun replaceAll(
        document: TextDocument,
        query: String,
        replacement: String,
        options: SearchOptions,
    ): Pair<TextDocument, Int> {
        val matches = search(document, query, options)
        if (matches.isEmpty()) return document to 0
        val effectiveReplacement = if (options.regex) {
            val regex = buildRegex(query, options) ?: return document to 0
            { matched: String -> regex.replace(matched, replacement) }
        } else {
            { _: String -> replacement }
        }
        var doc = document
        for (match in matches.asReversed()) {
            val matched = doc.text.substring(match.start, match.end)
            doc = doc.replace(match.start until match.end, effectiveReplacement(matched)).first
        }
        return doc to matches.size
    }

    private fun buildRegex(query: String, options: SearchOptions): Regex? {
        val pattern = if (options.regex) query else Regex.escape(query)
        val withWordBoundary = if (options.wholeWord) "\\b(?:$pattern)\\b" else pattern
        val flags = if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return runCatching { Regex(withWordBoundary, flags) }.getOrNull()
    }
}
