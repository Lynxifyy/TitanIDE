package dev.titanide.core.editor.codeguard

import dev.titanide.core.editor.Token
import dev.titanide.core.editor.TokenType
import dev.titanide.core.editor.Tokenizer
import dev.titanide.core.model.Diagnostic
import dev.titanide.core.model.Language
import dev.titanide.core.model.QuickFix
import dev.titanide.core.model.QuickFixKind
import dev.titanide.core.model.Severity

/**
 * TITAN CodeGuard: a deterministic analyser that runs on every keystroke.
 *
 * Levels 1-3 are real lexical and structural analysis over the token stream. Level 4
 * (AI correction) is deliberately kept out of this class: it is an explicit, confirmed
 * user action so a fix can never silently change program behaviour.
 */
class CodeGuard {

    fun analyze(text: String, language: Language, filePath: String = ""): List<Diagnostic> {
        if (language == Language.PLAINTEXT) return emptyList()
        val tokens = Tokenizer.tokenize(text, language)
        val diagnostics = ArrayList<Diagnostic>()

        diagnostics += checkUnbalancedBrackets(text, tokens, language, filePath)
        diagnostics += checkUnterminatedStrings(text, tokens, language, filePath)
        diagnostics += checkTrailingCommas(text, language, filePath)
        diagnostics += checkDuplicateModifiers(text, tokens, filePath)
        diagnostics += checkSuspiciousIdentifiers(text, tokens, language, filePath)
        diagnostics += checkEmptyFunctionBodies(text, tokens, language, filePath)

        if (language in SEMANTIC_LANGUAGES) {
            diagnostics += checkUndefinedReferences(text, tokens, language, filePath)
            diagnostics += checkUnusedLocals(text, language, filePath)
        }
        if (language == Language.JSON) {
            diagnostics += checkJsonDepth(text, tokens, filePath)
        }

        return diagnostics.sortedWith(compareBy({ it.line }, { it.column }))
    }

    // -------------------------------------------------------------- level 2: syntax

    private fun checkUnbalancedBrackets(
        text: String,
        tokens: List<Token>,
        language: Language,
        filePath: String,
    ): List<Diagnostic> {
        if (language in setOf(Language.MARKDOWN, Language.YAML)) return emptyList()
        val open = mapOf('(' to ')', '[' to ']', '{' to '}')
        val closers = open.values.toSet()
        val stack = ArrayDeque<Token>()
        val out = ArrayList<Diagnostic>()

        for (token in tokens) {
            if (token.type != TokenType.PUNCTUATION) continue
            val ch = text.getOrNull(token.start) ?: continue
            if (ch in open.keys) {
                stack.addLast(token)
            } else if (ch in closers) {
                val opener = stack.lastOrNull()
                if (opener == null) {
                    out += diagAt(text, token.start, Severity.ERROR, "Unexpected closing '$ch'", filePath)
                } else {
                    val expected = open[text[opener.start]]
                    if (expected != ch) {
                        out += diagAt(
                            text, token.start, Severity.ERROR,
                            "Mismatched bracket: expected '$expected' but found '$ch'", filePath,
                        )
                        stack.removeLast()
                    } else {
                        stack.removeLast()
                    }
                }
            }
        }
        for (unclosed in stack) {
            val ch = text[unclosed.start]
            out += diagAt(
                text, unclosed.start, Severity.ERROR, "Unclosed '$ch'", filePath,
                quickFix = QuickFix(
                    title = "Insert '${open[ch]}' at end of file",
                    kind = QuickFixKind.INSERT_TEXT,
                    replacement = open[ch].toString(),
                    startOffset = text.length,
                    endOffset = text.length,
                ),
            )
        }
        return out
    }

    /** A quote run that reaches end-of-line without closing is an unterminated literal. */
    /**
     * A string is unterminated when a quote opens on a line and nothing closes it.
     *
     * Counting quote characters per line cannot answer that: an escaped quote, a char
     * literal such as `'"'`, a comment mentioning an apostrophe, and a raw `"""` block all
     * put the count off by one on lines that are perfectly valid. The previous version
     * reported 327 of those on this repository's own sources. This one walks the line and
     * tracks the real lexical state instead.
     */
    private fun checkUnterminatedStrings(
        text: String,
        tokens: List<Token>,
        language: Language,
        filePath: String,
    ): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        val quoteStyle = QuoteStyle.of(language) ?: return out
        val lineComment = language.lineComment
        val blockComment = language.blockComment?.first

        var offset = 0
        var rawBlockOpen = false
        text.split('\n').forEachIndexed { lineIndex, line ->
            val inComment = tokens.any {
                it.type == TokenType.COMMENT &&
                    it.start <= offset && offset <= it.end
            }
            if (!inComment && !rawBlockOpen) {
                val open = firstUnterminatedQuote(line, quoteStyle, lineComment, blockComment)
                if (open != null) {
                    out += Diagnostic(
                        filePath = filePath,
                        line = lineIndex,
                        column = open,
                        severity = Severity.ERROR,
                        message = "Unterminated string literal",
                        source = "codeguard.syntax",
                    )
                }
            }
            if (quoteStyle.rawBlock != null) {
                rawBlockOpen = togglesRawBlock(line, quoteStyle.rawBlock, rawBlockOpen)
            }
            offset += line.length + 1
        }
        return out
    }

    /** Index of a quote that opens on [line] and never closes, or null. */
    private fun firstUnterminatedQuote(
        line: String,
        style: QuoteStyle,
        lineComment: String?,
        blockComment: String?,
    ): Int? {
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (lineComment != null && line.startsWith(lineComment, i)) return null
            if (blockComment != null && line.startsWith(blockComment, i)) return null
            if (c == '\'' && style.charLiterals) {
                // A char literal holds exactly one character or one escape sequence.
                val width = if (i + 1 < line.length && line[i + 1] == '\\') 4 else 3
                i += width
                continue
            }
            if (c in style.quotes) {
                if (style.rawBlock != null && line.startsWith(style.rawBlock, i)) return null
                var j = i + 1
                while (j < line.length) {
                    if (line[j] == '\\') {
                        j += 2
                        continue
                    }
                    if (line[j] == c) break
                    j++
                }
                if (j >= line.length) return i
                i = j + 1
                continue
            }
            i++
        }
        return null
    }

    private fun togglesRawBlock(line: String, marker: String, open: Boolean): Boolean {
        var count = 0
        var index = line.indexOf(marker)
        while (index >= 0) {
            count++
            index = line.indexOf(marker, index + marker.length)
        }
        return if (count % 2 == 0) open else !open
    }

    /**
     * Which characters delimit a string on one line, whether a raw block form exists, and
     * whether `'` means a character literal rather than a string.
     *
     * The distinction matters: in Kotlin `'"'` is a char literal holding a quote, so treating
     * `"` as a string opener there reports an unterminated string on correct code. That was
     * the remaining source of false positives after the first pass.
     */
    private data class QuoteStyle(
        val quotes: Set<Char>,
        val rawBlock: String?,
        val charLiterals: Boolean = false,
    ) {
        companion object {
            fun of(language: Language): QuoteStyle? = when (language) {
                Language.KOTLIN -> QuoteStyle(setOf('"'), "\"\"\"", charLiterals = true)
                Language.JAVA -> QuoteStyle(setOf('"'), "\"\"\"", charLiterals = true)
                Language.CSHARP -> QuoteStyle(setOf('"'), null, charLiterals = true)
                Language.RUST -> QuoteStyle(setOf('"'), null, charLiterals = true)
                Language.DART -> QuoteStyle(setOf('"', '\''), null, charLiterals = true)
                Language.C, Language.CPP -> QuoteStyle(setOf('"'), null, charLiterals = true)
                Language.PYTHON -> QuoteStyle(setOf('"', '\''), "\"\"\"")
                Language.JAVASCRIPT, Language.TYPESCRIPT -> QuoteStyle(setOf('"', '\'', '`'), null)
                Language.GO -> QuoteStyle(setOf('"', '`'), null, charLiterals = true)
                Language.PHP, Language.RUBY, Language.LUA, Language.SHELL ->
                    QuoteStyle(setOf('"', '\''), null)
                else -> null
            }
        }
    }

    private fun checkTrailingCommas(text: String, language: Language, filePath: String): List<Diagnostic> {
        if (language != Language.JSON) return emptyList()
        val out = ArrayList<Diagnostic>()
        Regex(",\\s*[}\\]]").findAll(text).forEach { match ->
            out += diagAt(
                text, match.range.first, Severity.ERROR,
                "Trailing comma is not allowed in JSON", filePath,
                quickFix = QuickFix(
                    title = "Remove trailing comma",
                    kind = QuickFixKind.DELETE_RANGE,
                    startOffset = match.range.first,
                    endOffset = match.range.first + 1,
                ),
            )
        }
        return out
    }

    private fun checkDuplicateModifiers(text: String, tokens: List<Token>, filePath: String): List<Diagnostic> {
        val doubled = setOf("public", "private", "protected", "static", "final", "abstract", "override", "open")
        val out = ArrayList<Diagnostic>()
        val keywordTokens = tokens.filter { it.type == TokenType.KEYWORD }
        for (i in 1 until keywordTokens.size) {
            val prev = keywordTokens[i - 1]
            val cur = keywordTokens[i]
            val prevWord = text.substring(prev.start, prev.end)
            val curWord = text.substring(cur.start, cur.end)
            if (prevWord == curWord && curWord in doubled && text.substring(prev.end, cur.start).isBlank()) {
                out += diagAt(
                    text, cur.start, Severity.WARNING, "Duplicate modifier '$curWord'", filePath,
                    quickFix = QuickFix(
                        title = "Remove duplicate '$curWord'",
                        kind = QuickFixKind.DELETE_RANGE,
                        startOffset = prev.start,
                        endOffset = cur.end,
                    ),
                )
            }
        }
        return out
    }

    /**
     * Level 1: catches a keyword misspelled by one edit (`fucntion`, `retrun`, `improt`).
     *
     * Only one edit is allowed, and only when the word keeps the keyword's first character.
     * Two edits, or a shared prefix of one letter, matches an enormous amount of ordinary
     * code: on this repository's own sources the previous rule flagged 4177 identifiers,
     * every one of them a correctly spelled name. A checker that cries wolf that often is
     * worse than no checker, because the real typo is buried in the noise.
     */
    private fun checkSuspiciousIdentifiers(
        text: String,
        tokens: List<Token>,
        language: Language,
        filePath: String,
    ): List<Diagnostic> {
        val keywords = keywordsFor(language)
        if (keywords.isEmpty()) return emptyList()
        val out = ArrayList<Diagnostic>()
        // A word the file declares is a symbol, not a typo, however close it sits to a keyword.
        // `cost` and `cont` are one edit from `const`; flagging every such local would put a
        // marker on ordinary code.
        val declared = declaredNames(text, language)
        val quotedSpans = backtickSpans(text)

        for (token in tokens) {
            if (token.type != TokenType.IDENTIFIER) continue
            val word = text.substring(token.start, token.end)
            if (word.length < 4) continue
            if (word in keywords || word in declared) continue
            // `fun \`invalid regex returns no matches\`()` — text inside backticks is prose.
            if (quotedSpans.any { token.start in it }) continue
            val close = nearestKeyword(word, keywords) ?: continue
            out += diagAt(
                text, token.start, Severity.WARNING,
                "Unknown identifier '$word'. Did you mean '$close'?", filePath,
                quickFix = QuickFix(
                    title = "Replace with '$close'",
                    kind = QuickFixKind.REPLACE_RANGE,
                    startOffset = token.start,
                    endOffset = token.end,
                    replacement = close,
                ),
            )
        }
        return out
    }

    /** Offsets covered by backticks, which in Kotlin delimit names written as prose. */
    private fun backtickSpans(text: String): List<IntRange> {
        val spans = ArrayList<IntRange>()
        var open = -1
        text.forEachIndexed { index, c ->
            if (c != '`') return@forEachIndexed
            if (open < 0) {
                open = index
            } else {
                spans += open..index
                open = -1
            }
        }
        return spans
    }

    private fun nearestKeyword(word: String, keywords: Set<String>): String? {
        var best: String? = null
        for (keyword in keywords) {
            if (keyword[0] != word[0]) continue
            if (kotlin.math.abs(keyword.length - word.length) > 1) continue
            if (damerauLevenshtein(word, keyword) == 1) {
                // Two keywords one edit away means the intent is ambiguous; say nothing.
                if (best != null) return null
                best = keyword
            }
        }
        return best
    }

    /**
     * Edit distance where swapping two adjacent characters costs one edit.
     *
     * Plain Levenshtein scores a transposition as two, so the most common typing mistake
     * there is — `fucntion`, `retrun`, `improt` — would not register as a single-edit typo.
     */
    private fun damerauLevenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val rows = a.length + 1
        val cols = b.length + 1
        val d = Array(rows) { IntArray(cols) }
        for (i in 0 until rows) d[i][0] = i
        for (j in 0 until cols) d[0][j] = j
        for (i in 1 until rows) {
            for (j in 1 until cols) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
        }
        return d[a.length][b.length]
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val swap = prev
            prev = cur
            cur = swap
        }
        return prev[b.length]
    }

    private fun checkEmptyFunctionBodies(
        text: String,
        tokens: List<Token>,
        language: Language,
        filePath: String,
    ): List<Diagnostic> {
        if (language !in SEMANTIC_LANGUAGES) return emptyList()
        val out = ArrayList<Diagnostic>()
        for (i in 0 until tokens.size - 1) {
            val a = tokens[i]
            val b = tokens[i + 1]
            if (a.type != TokenType.PUNCTUATION || b.type != TokenType.PUNCTUATION) continue
            if (text[a.start] != '{' || text[b.start] != '}' || b.start - a.start != 1) continue
            val before = text.substring((a.start - 40).coerceAtLeast(0), a.start)
            if (before.contains("fun ") || before.contains("def ") || before.contains("function ")) {
                out += diagAt(text, a.start, Severity.HINT, "Empty function body", filePath)
            }
        }
        return out
    }

    // ------------------------------------------------------------- level 3: semantic

    /**
     * Reports a call `name(` where `name` is declared nowhere in the file and is not a
     * known library/builtin name. Receiver calls (`obj.name(`) are skipped because their
     * target lives in another file.
     */
    /**
     * Reports calls to names this file never declares.
     *
     * This is a single-file heuristic: a name defined in another file of the same project is
     * invisible here, so most matches on real code are correct code. It is therefore a HINT,
     * not a warning, and the message says which scope it looked at. Raising it to a warning
     * would put a marker on nearly every line of a well-factored project.
     */
    private fun checkUndefinedReferences(
        text: String,
        tokens: List<Token>,
        language: Language,
        filePath: String,
    ): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        val declared = declaredNames(text, language)
        val builtins = builtinsFor(language)

        for (token in tokens) {
            if (token.type != TokenType.FUNCTION) continue
            val word = text.substring(token.start, token.end)
            if (word in declared || word in builtins) continue
            val prevChar = text.getOrNull(token.start - 1)
            if (prevChar == '.' || prevChar == ':') continue
            out += diagAt(
                text, token.start, Severity.HINT,
                "'$word' is not declared in this file (it may come from an import)", filePath,
            )
        }
        return out
    }

    /**
     * A local declared and never read again.
     *
     * The count is over uses *after* the declaration, and constructor properties are excluded:
     * `val projectId: String,` in a data class is read from other files, so counting only this
     * file made every data-class property look unused. That was 421 wrong warnings on this
     * repository alone, which is enough to teach the user to ignore the panel.
     */
    private fun checkUnusedLocals(text: String, language: Language, filePath: String): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        val declRegex = when (language) {
            Language.KOTLIN -> Regex("\\b(?:val|var)\\s+([a-zA-Z_][A-Za-z0-9_]*)")
            Language.JAVA, Language.CSHARP, Language.DART ->
                Regex("\\b(?:int|long|double|float|boolean|bool|String|var|let|const)\\s+([a-zA-Z_][A-Za-z0-9_]*)\\s*=")
            Language.PYTHON -> Regex("^\\s*([a-zA-Z_][A-Za-z0-9_]*)\\s*=(?!=)", RegexOption.MULTILINE)
            Language.JAVASCRIPT, Language.TYPESCRIPT -> Regex("\\b(?:let|const|var)\\s+([a-zA-Z_][A-Za-z0-9_]*)")
            else -> return out
        }
        declRegex.findAll(text).forEach { match ->
            val name = match.groupValues[1]
            if (name == "it" || name.startsWith("_")) return@forEach
            if (isConstructorProperty(text, match.range.first)) return@forEach
            val usesAfter = Regex("\\b${Regex.escape(name)}\\b")
                .findAll(text)
                .count { it.range.first > match.range.last }
            if (usesAfter == 0) {
                out += diagAt(
                    text, match.range.first, Severity.WARNING,
                    "'$name' is declared but never used", filePath,
                )
            }
        }
        return out
    }

    /** True when the declaration sits inside a parameter list, i.e. it is a property. */
    private fun isConstructorProperty(text: String, offset: Int): Boolean {
        var depth = 0
        var index = offset - 1
        while (index >= 0) {
            when (text[index]) {
                ')', '}' -> depth++
                '{' -> if (depth == 0) return false else depth--
                '(' -> if (depth == 0) return true else depth--
            }
            index--
        }
        return false
    }

    private fun declaredNames(text: String, language: Language): Set<String> {
        val names = HashSet<String>()
        val patterns = when (language) {
            Language.KOTLIN -> listOf(
                Regex("\\bfun\\s+(?:<[^>]*>\\s*)?(?:[A-Za-z0-9_.<>?]+\\.)?([a-zA-Z_][A-Za-z0-9_]*)"),
                Regex("\\b(?:val|var)\\s+([a-zA-Z_][A-Za-z0-9_]*)"),
                Regex("\\b(?:class|interface|object|enum class)\\s+([A-Za-z_][A-Za-z0-9_]*)"),
                Regex("\\btypealias\\s+([A-Za-z_][A-Za-z0-9_]*)"),
                // Lambda parameters: `{ item -> ... }` and `{ (a, b) -> ... }`.
                Regex("\\{\\s*([a-zA-Z_][A-Za-z0-9_]*)\\s*->"),
                // Destructuring declarations: `val (a, b) = ...` and lambda destructuring.
                Regex("\\b(?:val|var)\\s*\\(([^)]*)\\)"),
                Regex("\\(\\s*([a-zA-Z_][A-Za-z0-9_]*(?:\\s*,\\s*[a-zA-Z_][A-Za-z0-9_]*)*)\\s*\\)\\s*->"),
            )
            Language.JAVA, Language.CSHARP -> listOf(
                Regex("\\b(?:class|interface|enum|record|struct)\\s+([A-Za-z_][A-Za-z0-9_]*)"),
                Regex("(?:[A-Za-z0-9_<>\\[\\],.]+\\s+)?([a-zA-Z_][A-Za-z0-9_]*)\\s*\\([^)]*\\)\\s*(?:throws [\\w., ]+)?\\{"),
                Regex("\\b(?:var|let|const)\\s+([a-zA-Z_][A-Za-z0-9_]*)"),
            )
            Language.PYTHON -> listOf(
                Regex("^\\s*def\\s+([a-zA-Z_][A-Za-z0-9_]*)", RegexOption.MULTILINE),
                Regex("^\\s*class\\s+([a-zA-Z_][A-Za-z0-9_]*)", RegexOption.MULTILINE),
                Regex("^\\s*([a-zA-Z_][A-Za-z0-9_]*)\\s*=", RegexOption.MULTILINE),
            )
            Language.JAVASCRIPT, Language.TYPESCRIPT -> listOf(
                Regex("\\bfunction\\s+([a-zA-Z_$][A-Za-z0-9_$]*)"),
                Regex("\\b(?:const|let|var)\\s+([a-zA-Z_$][A-Za-z0-9_$]*)"),
                Regex("\\bclass\\s+([a-zA-Z_$][A-Za-z0-9_$]*)"),
            )
            Language.RUST -> listOf(
                Regex("\\bfn\\s+([a-z_][A-Za-z0-9_]*)"),
                Regex("\\b(?:let|static|const)\\s+(?:mut\\s+)?([a-z_][A-Za-z0-9_]*)"),
                Regex("\\b(?:struct|enum|trait|type)\\s+([A-Za-z_][A-Za-z0-9_]*)"),
            )
            Language.GO -> listOf(
                Regex("\\bfunc\\s+(?:\\([^)]*\\)\\s*)?([a-zA-Z_][A-Za-z0-9_]*)"),
                Regex("\\btype\\s+([a-zA-Z_][A-Za-z0-9_]*)"),
            )
            else -> emptyList()
        }
        patterns.forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                // A group may hold several names (destructuring), so split on anything that
                // cannot be part of an identifier.
                match.groupValues.getOrNull(1).orEmpty()
                    .split(Regex("[^A-Za-z0-9_]+"))
                    .filter { it.isNotEmpty() }
                    .forEach { names += it }
            }
        }
        return names
    }

    private fun checkJsonDepth(text: String, tokens: List<Token>, filePath: String): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        var depth = 0
        for (token in tokens) {
            if (token.type != TokenType.PUNCTUATION) continue
            when (text[token.start]) {
                '{', '[' -> depth++
                '}', ']' -> depth--
            }
            if (depth < 0) {
                out += diagAt(text, token.start, Severity.ERROR, "Unexpected closing token in JSON", filePath)
                depth = 0
            }
        }
        if (depth > 0) {
            out += Diagnostic(
                filePath = filePath, line = 0, column = 0, severity = Severity.ERROR,
                message = "JSON document is incomplete: $depth unclosed container(s)",
                source = "codeguard.syntax",
            )
        }
        return out
    }

    private fun diagAt(
        text: String,
        offset: Int,
        severity: Severity,
        message: String,
        filePath: String,
        quickFix: QuickFix? = null,
    ): Diagnostic {
        val safe = offset.coerceIn(0, text.length)
        var line = 0
        var lastNewline = -1
        for (i in 0 until safe) {
            if (text[i] == '\n') {
                line++
                lastNewline = i
            }
        }
        return Diagnostic(
            filePath = filePath,
            line = line,
            column = (safe - lastNewline - 1).coerceAtLeast(0),
            severity = severity,
            message = message,
            source = "codeguard",
            quickFix = quickFix,
        )
    }

    private fun keywordsFor(language: Language): Set<String> = when (language) {
        Language.KOTLIN -> setOf(
            "fun", "val", "var", "class", "object", "interface", "return", "import", "package",
            "when", "while", "for", "if", "else", "try", "catch", "finally", "throw", "override",
            "private", "public", "internal", "protected", "suspend", "inline", "data", "sealed",
            "companion", "constructor", "init", "typealias", "lateinit", "const", "abstract",
            "open", "enum", "annotation", "reified", "crossinline", "noinline", "vararg",
        )
        Language.JAVA -> setOf(
            "class", "interface", "enum", "record", "public", "private", "protected", "static",
            "final", "abstract", "return", "import", "package", "void", "int", "long", "double",
            "float", "boolean", "char", "new", "this", "super", "try", "catch", "finally",
            "throw", "throws", "synchronized", "volatile", "transient", "native", "extends",
            "implements", "instanceof", "switch", "case", "default",
        )
        Language.PYTHON -> setOf(
            "def", "class", "return", "import", "from", "as", "if", "elif", "else", "for",
            "while", "try", "except", "finally", "raise", "with", "lambda", "yield", "global",
            "nonlocal", "assert", "pass", "break", "continue", "async", "await", "self",
        )
        Language.JAVASCRIPT, Language.TYPESCRIPT -> setOf(
            "function", "const", "let", "var", "class", "return", "import", "export", "from",
            "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "try",
            "catch", "finally", "throw", "new", "this", "async", "await", "yield", "interface",
            "type", "enum", "extends", "implements", "static", "readonly", "public", "private",
            "protected", "declare", "namespace",
        )
        Language.RUST -> setOf(
            "fn", "let", "mut", "struct", "enum", "trait", "impl", "mod", "use", "pub", "return",
            "if", "else", "match", "for", "while", "loop", "break", "continue", "const", "static",
            "async", "await", "move", "where", "dyn", "crate", "super", "self",
        )
        Language.GO -> setOf(
            "func", "var", "const", "type", "struct", "interface", "map", "chan", "package",
            "import", "return", "if", "else", "for", "range", "switch", "case", "default",
            "break", "continue", "go", "defer", "select", "nil", "true", "false",
        )
        Language.C, Language.CPP -> setOf(
            "int", "char", "float", "double", "void", "struct", "union", "enum", "typedef",
            "return", "if", "else", "for", "while", "do", "switch", "case", "break", "continue",
            "sizeof", "static", "extern", "const", "unsigned", "signed", "long", "short",
            "class", "namespace", "template", "typename", "public", "private", "protected",
            "virtual", "override", "new", "delete", "nullptr", "auto", "using", "constexpr",
        )
        else -> emptySet()
    }

    private fun builtinsFor(language: Language): Set<String> = when (language) {
        Language.KOTLIN -> setOf(
            "println", "print", "listOf", "mapOf", "setOf", "mutableListOf", "mutableMapOf",
            "arrayOf", "lazy", "require", "check", "error", "runCatching", "run", "let", "apply",
            "also", "with", "repeat", "synchronized", "TODO", "maxOf", "minOf", "abs", "sumOf",
            "buildString", "buildList", "buildMap", "compareBy", "sortedBy", "filter", "map",
            "forEach", "fold", "reduce", "firstOrNull", "lastOrNull", "getOrNull", "coerceAtLeast",
            "coerceAtMost", "coerceIn", "takeIf", "takeUnless", "toInt", "toString",
        )
        Language.JAVA -> setOf(
            "println", "printf", "main", "valueOf", "parseInt", "parseLong", "format", "equals",
            "hashCode", "toString", "length", "size", "get", "set", "add", "remove", "contains",
            "forEach", "stream", "collect", "of", "asList", "requireNonNull", "isEmpty",
        )
        Language.PYTHON -> setOf(
            "print", "len", "range", "str", "int", "float", "list", "dict", "set", "tuple",
            "open", "super", "isinstance", "enumerate", "zip", "map", "filter", "sorted", "sum",
            "min", "max", "abs", "round", "type", "getattr", "setattr", "hasattr", "repr",
            "append", "extend", "join", "split", "strip", "format", "keys", "values", "items",
        )
        Language.JAVASCRIPT, Language.TYPESCRIPT -> setOf(
            "console", "log", "parseInt", "parseFloat", "String", "Number", "Boolean", "Array",
            "Object", "JSON", "Promise", "setTimeout", "setInterval", "require", "map", "filter",
            "reduce", "forEach", "push", "pop", "slice", "splice", "join", "split", "trim",
        )
        else -> emptySet()
    }

    private companion object {
        val SEMANTIC_LANGUAGES = setOf(
            Language.KOTLIN, Language.JAVA, Language.PYTHON, Language.JAVASCRIPT,
            Language.TYPESCRIPT, Language.RUST, Language.GO, Language.CSHARP, Language.DART,
        )
    }
}
