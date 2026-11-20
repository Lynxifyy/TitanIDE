package dev.titanide.core.editor

import dev.titanide.core.model.CodeSymbol
import dev.titanide.core.model.Language
import dev.titanide.core.model.SymbolKind

/** A foldable region: [startLine] stays visible, lines startLine+1..endLine collapse. */
data class FoldRegion(val startLine: Int, val endLine: Int, val label: String)

data class CompletionItem(
    val label: String,
    val insertText: String,
    val detail: String,
    val kind: CompletionKind,
)

enum class CompletionKind { KEYWORD, FUNCTION, TYPE, PROPERTY, SNIPPET, FILE }

/**
 * Text transformation engine shared by the Compose editor and any headless caller
 * (tests, AI edits, project-wide formatting). All operations are pure: they take a
 * [TextDocument] and return the new document plus the caret offset to restore.
 */
class EditorEngine(private val language: Language) {

    data class Transform(val document: TextDocument, val caret: Int, val edit: EditRecord? = null)

    // ------------------------------------------------------------ typing assists

    /** Applies indentation and bracket/quote pairing to a single typed character. */
    fun onType(document: TextDocument, caret: Int, char: Char): Transform? {
        val text = document.text
        return when {
            char == '\n' -> handleNewline(document, caret)
            char == '}' -> handleClosingBrace(document, caret)
            char in PAIRS -> {
                val close = PAIRS.getValue(char)
                val selected = selectionText
                if (selected.isNotEmpty()) {
                    // Wrap the selection instead of replacing it.
                    val wrapped = "$char$selected$close"
                    val (doc, edit) = document.replace(caret until (caret + selected.length), wrapped)
                    Transform(doc, caret + wrapped.length, edit)
                } else if (shouldAutoClose(document, caret, char)) {
                    val (doc, edit) = document.insert(caret, "$char$close")
                    Transform(doc, caret + 1, edit)
                } else {
                    null
                }
            }
            char == '\'' || char == '"' -> {
                if (caret > 0 && text.getOrNull(caret - 1) == '\\') null
                else if (caret < text.length && text[caret] == char) Transform(document, caret + 1)
                else if (shouldAutoClose(document, caret, char)) {
                    val (doc, edit) = document.insert(caret, "$char$char")
                    Transform(doc, caret + 1, edit)
                } else null
            }
            else -> null
        }
    }

    /** Remembers the last selection so [onType] can wrap it. Set by the UI layer. */
    var selectionText: String = ""

    private fun shouldAutoClose(document: TextDocument, caret: Int, char: Char): Boolean {
        if (caret > 0 && document.text.getOrNull(caret - 1) == '\\') return false
        val next = document.text.getOrNull(caret)
        return next == null || next.isWhitespace() || next in CLOSERS || next in listOf(',', ';', ')')
    }

    /** Typing `}` on an otherwise-blank line removes one indent level first. */
    private fun handleClosingBrace(document: TextDocument, caret: Int): Transform? {
        val line = document.lineOf(caret)
        val lineStart = document.lineStart(line)
        val before = document.text.substring(lineStart, caret)
        if (before.isBlank() && before.isNotEmpty()) {
            val indent = document.indentOf(line)
            if (indent.isEmpty()) return null
            val newIndent = indent.dropLast(language.indentUnit.length.coerceAtMost(indent.length))
            val (doc, edit) = document.replace(lineStart until caret, newIndent)
            return Transform(doc, lineStart + newIndent.length, edit)
        }
        return null
    }

    /** Enter: keep indentation, add a level after an opening brace, and expand `{}`. */
    fun handleNewline(document: TextDocument, caret: Int): Transform {
        val line = document.lineOf(caret)
        val lineStart = document.lineStart(line)
        val indent = document.indentOf(line)
        val beforeCaret = document.text.substring(lineStart, caret).trimEnd()
        val afterCaret = document.text.substring(caret, document.lineEnd(line)).trimStart()

        val opensBlock = beforeCaret.endsWith("{") || beforeCaret.endsWith("(") ||
            beforeCaret.endsWith("[") || beforeCaret.endsWith(":") ||
            beforeCaret.endsWith("->") || beforeCaret.endsWith("=>")
        val closesImmediately = afterCaret.startsWith("}") || afterCaret.startsWith(")") ||
            afterCaret.startsWith("]")

        val deeper = indent + language.indentUnit
        return when {
            opensBlock && closesImmediately -> {
                val insertion = "\n$deeper\n$indent"
                val (doc, edit) = document.insert(caret, insertion)
                Transform(doc, caret + 1 + deeper.length, edit)
            }
            opensBlock -> {
                val (doc, edit) = document.insert(caret, "\n$deeper")
                Transform(doc, caret + 1 + deeper.length, edit)
            }
            else -> {
                val (doc, edit) = document.insert(caret, "\n$indent")
                Transform(doc, caret + 1 + indent.length, edit)
            }
        }
    }

    /** Backspace between a matching pair deletes both characters. */
    fun onBackspace(document: TextDocument, caret: Int): Transform? {
        if (caret <= 0 || caret >= document.text.length) return null
        val prev = document.text[caret - 1]
        val next = document.text[caret]
        if (PAIRS[prev] == next) {
            val (doc, edit) = document.delete((caret - 1)..caret)
            return Transform(doc, caret - 1, edit)
        }
        return null
    }

    // ------------------------------------------------------------------ commands

    fun toggleLineComment(document: TextDocument, selectionStart: Int, selectionEnd: Int): Transform? {
        val marker = language.lineComment ?: language.blockComment?.let { (open, close) -> "$open$close" } ?: return null
        val isBlock = language.lineComment == null
        val firstLine = document.lineOf(selectionStart)
        val lastLine = document.lineOf(selectionEnd)

        if (isBlock) {
            val (open, close) = language.blockComment ?: return null
            val start = document.lineStart(firstLine)
            val end = document.lineEnd(lastLine)
            val body = document.text.substring(start, end)
            val trimmed = body.trim()
            val (doc, edit) = if (trimmed.startsWith(open) && trimmed.endsWith(close)) {
                document.replace(start until end, trimmed.removePrefix(open).removeSuffix(close).trim())
            } else {
                document.replace(start until end, "$open$body$close")
            }
            return Transform(doc, start, edit)
        }

        val start = document.lineStart(firstLine)
        val end = document.lineEnd(lastLine)
        val block = document.text.substring(start, end)
        val lines = block.split('\n')
        val allCommented = lines.filter { it.isNotBlank() }.all { it.trimStart().startsWith(marker) }
        val transformed = lines.joinToString("\n") { line ->
            if (line.isBlank()) line
            else if (allCommented) line.replaceFirst(marker, "").let {
                if (it.startsWith(" ")) it.removePrefix(" ") else it
            }
            else {
                val indent = line.takeWhile { c -> c == ' ' || c == '\t' }
                indent + marker + " " + line.substring(indent.length)
            }
        }
        val (doc, edit) = document.replace(start..(end - 1).coerceAtLeast(start), transformed)
        return Transform(doc, start + transformed.length, edit)
    }

    fun indentSelection(document: TextDocument, selectionStart: Int, selectionEnd: Int, outdent: Boolean): Transform {
        val firstLine = document.lineOf(selectionStart)
        val lastLine = document.lineOf(selectionEnd)
        val start = document.lineStart(firstLine)
        val end = document.lineEnd(lastLine)
        val block = document.text.substring(start, end)

        val transformed = block.split('\n').joinToString("\n") { line ->
            if (line.isBlank()) line
            else if (outdent) {
                when {
                    line.startsWith(language.indentUnit) -> line.removePrefix(language.indentUnit)
                    line.startsWith(" ") -> line.drop(1)
                    line.startsWith("\t") -> line.drop(1)
                    else -> line
                }
            } else language.indentUnit + line
        }
        val (doc, edit) = document.replace(start..(end - 1).coerceAtLeast(start), transformed)
        return Transform(doc, start + transformed.length, edit)
    }

    /** Re-indents a whole document using brace depth, then normalises trailing spaces. */
    fun formatDocument(document: TextDocument): Transform {
        val lines = document.lines()
        val builder = StringBuilder(document.length)
        var depth = 0
        val openers = "{[("
        val closers = "}])"

        lines.forEachIndexed { index, rawLine ->
            val trimmed = rawLine.trim()
            val leadingClosers = trimmed.takeWhile { it in closers }.length
            val lineDepth = (depth - leadingClosers).coerceAtLeast(0)
            builder.append(language.indentUnit.repeat(lineDepth))
            builder.append(trimmed)
            if (index != lines.lastIndex) builder.append('\n')

            depth += countCode(trimmed, openers) - countCode(trimmed, closers)
            if (depth < 0) depth = 0
        }
        val formatted = builder.toString()
        val (doc, edit) = document.replace(0..(document.length - 1).coerceAtLeast(0), formatted)
        return Transform(doc, 0, edit)
    }

    /** Counts structural characters outside string literals and comments. */
    private fun countCode(line: String, targets: String): Int {
        var count = 0
        var inString: Char? = null
        var escaped = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (escaped) {
                escaped = false
                i++
                continue
            }
            when {
                c == '\\' -> escaped = true
                inString != null -> if (c == inString) inString = null
                c == '"' || c == '\'' -> inString = c
                c in targets -> count++
            }
            i++
        }
        return count
    }

    // ------------------------------------------------------------- navigation

    fun foldRegions(document: TextDocument): List<FoldRegion> {
        val regions = ArrayList<FoldRegion>()
        val stack = ArrayDeque<Pair<Int, Int>>() // line, brace column
        for (line in 0 until document.lineCount) {
            val content = document.lineText(line)
            var inString: Char? = null
            var i = 0
            while (i < content.length) {
                val c = content[i]
                if (inString != null) {
                    if (c == '\\') i++
                    else if (c == inString) inString = null
                } else if (c == '"' || c == '\'') {
                    inString = c
                } else if (c == '{') {
                    stack.addLast(line to i)
                } else if (c == '}' && stack.isNotEmpty()) {
                    val (startLine, _) = stack.removeLast()
                    if (line > startLine) {
                        regions += FoldRegion(startLine, line, foldLabel(document.lineText(startLine)))
                    }
                }
                i++
            }
        }
        return regions.sortedBy { it.startLine }
    }

    private fun foldLabel(line: String): String {
        val trimmed = line.trim()
        return if (trimmed.length > 48) trimmed.take(45) + "..." else trimmed
    }

    fun matchingBracket(document: TextDocument, offset: Int): Int? {
        val text = document.text
        if (text.isEmpty()) return null
        val pos = offset.coerceIn(0, text.length - 1)
        val char = text[pos]
        val open = mapOf('(' to ')', '[' to ']', '{' to '}')
        if (char in open.keys) {
            var depth = 0
            for (i in pos until text.length) {
                when (text[i]) {
                    char -> depth++
                    open[char] -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
        } else if (open.values.contains(char)) {
            val opener = open.entries.first { it.value == char }.key
            var depth = 0
            for (i in pos downTo 0) {
                when (text[i]) {
                    char -> depth++
                    opener -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
        }
        return null
    }

    // -------------------------------------------------------------- completion

    /**
     * Completion candidates for the identifier being typed: language keywords, every
     * symbol declared in the document, and files/keys visible in [extraSymbols].
     */
    fun completions(
        document: TextDocument,
        caret: Int,
        extraSymbols: List<CodeSymbol> = emptyList(),
        snippetProvider: (String) -> List<CompletionItem> = { emptyList() },
    ): List<CompletionItem> {
        val wordRange = document.wordRangeAt(caret - 1)
        val prefix = if (wordRange != null) document.text.substring(wordRange.first, caret.coerceAtLeast(wordRange.first)) else ""

        val candidates = LinkedHashMap<String, CompletionItem>()

        keywords().forEach { keyword ->
            candidates[keyword] = CompletionItem(keyword, keyword, "Keyword", CompletionKind.KEYWORD)
        }
        extractSymbols(document, "").forEach { symbol ->
            candidates.putIfAbsent(
                symbol.name,
                CompletionItem(symbol.name, symbol.name, symbol.kind.name.lowercase(), kindOf(symbol.kind)),
            )
        }
        extraSymbols.forEach { symbol ->
            candidates.putIfAbsent(
                symbol.name,
                CompletionItem(symbol.name, symbol.name, "in ${symbol.filePath.substringAfterLast('/')}", kindOf(symbol.kind)),
            )
        }
        snippetProvider(prefix).forEach { candidates.putIfAbsent(it.label, it) }

        val filtered = candidates.values.filter {
            prefix.isEmpty() || it.label.startsWith(prefix, ignoreCase = true)
        }
        // Exact prefix matches first, then shorter labels.
        return filtered.sortedWith(compareBy({ !it.label.startsWith(prefix) }, { it.label.length })).take(60)
    }

    private fun kindOf(kind: SymbolKind): CompletionKind = when (kind) {
        SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.OBJECT, SymbolKind.ENUM, SymbolKind.TYPE_ALIAS -> CompletionKind.TYPE
        SymbolKind.FUNCTION, SymbolKind.METHOD -> CompletionKind.FUNCTION
        else -> CompletionKind.PROPERTY
    }

    fun keywords(): List<String> = KEYWORDS[language] ?: emptyList()

    // ----------------------------------------------------------------- symbols

    /** Real declaration extraction via per-language patterns; drives outline and go-to-symbol. */
    fun extractSymbols(document: TextDocument, filePath: String): List<CodeSymbol> {
        val patterns: List<Pair<Regex, SymbolKind>> = when (language) {
            Language.KOTLIN -> listOf(
                Regex("^\\s*(?:@\\w+\\s*)*(?:public |private |internal |protected |open |abstract |sealed |data |inline |suspend )*(class|interface|object|enum class)\\s+([A-Za-z_][A-Za-z0-9_]*)") to SymbolKind.CLASS,
                Regex("^\\s*(?:public |private |internal |protected |open |abstract |inline |suspend |operator |override )*fun\\s+(?:<[^>]*>\\s*)?(?:[A-Za-z0-9_.<>?]+\\.)?([a-zA-Z_][A-Za-z0-9_]*)") to SymbolKind.FUNCTION,
                Regex("^\\s*(?:public |private |internal |protected |override )*(?:val|var)\\s+([a-zA-Z_][A-Za-z0-9_]*)") to SymbolKind.PROPERTY,
                Regex("^\\s*typealias\\s+([A-Za-z_][A-Za-z0-9_]*)") to SymbolKind.TYPE_ALIAS,
            )
            Language.JAVA -> listOf(
                Regex("^\\s*(?:public |private |protected |static |final |abstract |sealed )*(class|interface|enum|record)\\s+([A-Za-z_][A-Za-z0-9_]*)") to SymbolKind.CLASS,
                Regex("^\\s*(?:public |private |protected |static |final |synchronized |abstract )*(?:[A-Za-z0-9_<>\\[\\],.?]+\\s+)?([a-zA-Z_][A-Za-z0-9_]*)\\s*\\([^;]*\\)\\s*(?:throws [\\w., ]+)?\\{") to SymbolKind.METHOD,
            )
            Language.PYTHON -> listOf(
                Regex("^\\s*class\\s+([A-Za-z_][A-Za-z0-9_]*)") to SymbolKind.CLASS,
                Regex("^\\s*(?:async\\s+)?def\\s+([a-zA-Z_][A-Za-z0-9_]*)") to SymbolKind.FUNCTION,
            )
            Language.JAVASCRIPT, Language.TYPESCRIPT -> listOf(
                Regex("^\\s*(?:export\\s+)?(?:default\\s+)?class\\s+([A-Za-z_$][A-Za-z0-9_$]*)") to SymbolKind.CLASS,
                Regex("^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+([A-Za-z_$][A-Za-z0-9_$]*)") to SymbolKind.FUNCTION,
                Regex("^\\s*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)") to SymbolKind.PROPERTY,
                Regex("^\\s*(?:export\\s+)?interface\\s+([A-Za-z_$][A-Za-z0-9_$]*)") to SymbolKind.INTERFACE,
                Regex("^\\s*(?:export\\s+)?type\\s+([A-Za-z_$][A-Za-z0-9_$]*)") to SymbolKind.TYPE_ALIAS,
            )
            Language.RUST -> listOf(
                Regex("^\\s*(?:pub\\s+)?(?:struct|enum|union)\\s+([A-Za-z_][A-Za-z0-9_]*)") to SymbolKind.CLASS,
                Regex("^\\s*(?:pub\\s+)?trait\\s+([A-Za-z_][A-Za-z0-9_]*)") to SymbolKind.INTERFACE,
                Regex("^\\s*(?:pub\\s+)?(?:async\\s+)?fn\\s+([a-z_][A-Za-z0-9_]*)") to SymbolKind.FUNCTION,
                Regex("^\\s*(?:pub\\s+)?(?:const|static)\\s+([A-Za-z_][A-Za-z0-9_]*)") to SymbolKind.PROPERTY,
            )
            Language.GO -> listOf(
                Regex("^\\s*type\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+struct") to SymbolKind.CLASS,
                Regex("^\\s*type\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+interface") to SymbolKind.INTERFACE,
                Regex("^\\s*func\\s+(?:\\([^)]*\\)\\s*)?([a-zA-Z_][A-Za-z0-9_]*)") to SymbolKind.FUNCTION,
            )
            Language.C, Language.CPP -> listOf(
                Regex("^\\s*(?:class|struct|union|enum)\\s+([A-Za-z_][A-Za-z0-9_]*)") to SymbolKind.CLASS,
                Regex("^\\s*(?:static\\s+)?(?:inline\\s+)?[A-Za-z_][A-Za-z0-9_<>*&:\\s]*\\s+([a-zA-Z_][A-Za-z0-9_]*)\\s*\\([^;]*\\)\\s*\\{?") to SymbolKind.FUNCTION,
                Regex("^\\s*#define\\s+([A-Za-z_][A-Za-z0-9_]*)") to SymbolKind.PROPERTY,
            )
            Language.CSHARP -> listOf(
                Regex("^\\s*(?:public |private |internal |protected |static |sealed |abstract |partial )*(class|interface|enum|record|struct)\\s+([A-Za-z_][A-Za-z0-9_]*)") to SymbolKind.CLASS,
                Regex("^\\s*(?:public |private |internal |protected |static |virtual |override |async )*(?:[A-Za-z0-9_<>\\[\\],.?]+\\s+)?([a-zA-Z_][A-Za-z0-9_]*)\\s*\\(") to SymbolKind.METHOD,
            )
            else -> emptyList()
        }

        val symbols = ArrayList<CodeSymbol>()
        document.lines().forEachIndexed { lineIndex, line ->
            for ((pattern, kind) in patterns) {
                val match = pattern.find(line) ?: continue
                val name = match.groupValues.lastOrNull()?.takeIf { it.isNotEmpty() } ?: continue
                if (name in NON_SYMBOLS) continue
                val column = match.range.first + (match.value.lastIndexOf(name).coerceAtLeast(0))
                symbols += CodeSymbol(name = name, kind = kind, filePath = filePath, line = lineIndex, column = column)
                break
            }
        }
        return symbols
    }

    companion object {
        private val PAIRS = mapOf(
            '(' to ')', '[' to ']', '{' to '}', '<' to '>',
        )
        private val CLOSERS = setOf(')', ']', '}')

        private val NON_SYMBOLS = setOf("if", "for", "while", "when", "switch", "catch", "return", "new", "synchronized")

        private val COMMON_KEYWORDS = listOf(
            "if", "else", "for", "while", "return", "class", "interface", "fun", "val", "var",
            "true", "false", "null", "import", "package", "try", "catch", "finally", "throw",
            "this", "super", "object", "when", "is", "in", "as", "break", "continue",
        )

        private val KEYWORDS: Map<Language, List<String>> = mapOf(
            Language.KOTLIN to COMMON_KEYWORDS + listOf(
                "data class", "sealed class", "enum class", "suspend fun", "companion object",
                "inline fun", "private", "public", "internal", "protected", "override", "lateinit",
                "by lazy", "let", "apply", "also", "run", "with", "runCatching", "buildString",
                "mutableStateOf", "remember", "LaunchedEffect", "collectAsState",
            ),
            Language.JAVA to COMMON_KEYWORDS + listOf(
                "public", "private", "protected", "static", "final", "void", "int", "long",
                "double", "boolean", "String", "new", "extends", "implements", "abstract",
                "synchronized", "volatile", "record", "instanceof", "enum",
            ),
            Language.PYTHON to COMMON_KEYWORDS + listOf(
                "def", "self", "None", "True", "False", "elif", "lambda", "yield", "async",
                "await", "with", "as", "global", "nonlocal", "pass", "raise", "assert",
                "print", "len", "range", "enumerate", "zip", "open", "isinstance", "__init__",
            ),
            Language.JAVASCRIPT to COMMON_KEYWORDS + listOf(
                "const", "let", "var", "function", "=>", "async", "await", "export", "default",
                "import", "from", "console", "require", "Promise", "map", "filter", "reduce",
                "forEach", "then", "catch", "JSON", "undefined",
            ),
            Language.TYPESCRIPT to COMMON_KEYWORDS + listOf(
                "const", "let", "var", "function", "=>", "async", "await", "export", "default",
                "import", "from", "interface", "type", "enum", "implements", "extends",
                "readonly", "public", "private", "protected", "namespace", "declare", "satisfies",
                "console", "Promise", "undefined",
            ),
            Language.RUST to COMMON_KEYWORDS + listOf(
                "fn", "let", "mut", "pub", "use", "impl", "trait", "struct", "enum", "match",
                "Some", "None", "Ok", "Err", "Vec", "String", "println!", "format!", "unwrap",
                "expect", "iter", "collect", "crate", "mod", "where", "dyn",
            ),
            Language.GO to COMMON_KEYWORDS + listOf(
                "func", "var", "const", "type", "struct", "interface", "map", "chan", "go",
                "defer", "select", "range", "nil", "fmt", "error", "string", "int", "byte",
                "make", "append", "len", "cap",
            ),
            Language.C to COMMON_KEYWORDS + listOf(
                "int", "char", "float", "double", "void", "struct", "typedef", "sizeof",
                "unsigned", "signed", "const", "static", "extern", "include", "define",
                "printf", "malloc", "free", "NULL",
            ),
            Language.CPP to COMMON_KEYWORDS + listOf(
                "int", "char", "float", "double", "void", "class", "struct", "namespace",
                "template", "typename", "public", "private", "protected", "virtual", "override",
                "new", "delete", "nullptr", "auto", "constexpr", "std", "vector", "string",
                "unique_ptr", "shared_ptr", "include", "define", "sizeof",
            ),
            Language.CSHARP to COMMON_KEYWORDS + listOf(
                "public", "private", "protected", "internal", "static", "readonly", "async",
                "await", "var", "void", "int", "string", "bool", "new", "override", "virtual",
                "namespace", "using", "Console", "record", "sealed",
            ),
            Language.LUA to COMMON_KEYWORDS + listOf(
                "local", "function", "end", "then", "elseif", "nil", "not", "and", "or",
                "require", "print", "pairs", "ipairs", "table", "string", "math", "self",
            ),
            Language.PHP to COMMON_KEYWORDS + listOf(
                "function", "public", "private", "protected", "static", "echo", "namespace",
                "use", "new", "array", "isset", "foreach", "require", "include", "class",
            ),
            Language.RUBY to COMMON_KEYWORDS + listOf(
                "def", "end", "do", "module", "require", "require_relative", "attr_accessor",
                "nil", "self", "puts", "unless", "elsif", "yield", "lambda", "class",
            ),
            Language.DART to COMMON_KEYWORDS + listOf(
                "final", "const", "var", "void", "async", "await", "Widget", "build", "late",
                "required", "factory", "extension", "mixin", "override", "import",
            ),
        )
    }
}
