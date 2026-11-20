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
        diagnostics += checkUnterminatedStrings(text, tokens, filePath)
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
    private fun checkUnterminatedStrings(text: String, tokens: List<Token>, filePath: String): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        var lineStart = 0
        text.split('\n').forEachIndexed { lineIndex, line ->
            val withoutStrings = stripCompleteStrings(line)
            val singleQuotes = withoutStrings.count { it == '\'' }
            val doubleQuotes = withoutStrings.count { it == '"' }
            if (singleQuotes % 2 != 0 || doubleQuotes % 2 != 0) {
                val inComment = tokens.any {
                    it.type == TokenType.COMMENT && it.start in lineStart until (lineStart + line.length + 1)
                }
                if (!inComment) {
                    out += Diagnostic(
                        filePath = filePath,
                        line = lineIndex,
                        column = line.indexOfFirst { it == '"' || it == '\'' }.coerceAtLeast(0),
                        severity = Severity.ERROR,
                        message = "Unterminated string literal",
                        source = "codeguard.syntax",
                    )
                }
            }
            lineStart += line.length + 1
        }
        return out
    }

    private fun stripCompleteStrings(line: String): String {
        val builder = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"' || c == '\'') {
                var j = i + 1
                var closed = false
                while (j < line.length) {
                    if (line[j] == '\\') {
                        j += 2
                        continue
                    }
                    if (line[j] == c) {
                        closed = true
                        break
                    }
                    j++
                }
                if (closed) {
                    i = j + 1
                    continue
                }
            }
            builder.append(c)
            i++
        }
        return builder.toString()
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
     * Level 1: catches typos that are unambiguous regardless of the project — a keyword
     * misspelled by one or two edits (`fucntion`, `retrun`, `improt`).
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

        for (token in tokens) {
            if (token.type != TokenType.IDENTIFIER) continue
            val word = text.substring(token.start, token.end)
            if (word.length < 4) continue
            val close = nearestKeyword(word, keywords) ?: continue
            out += diagAt(
                text, token.start, Severity.WARNING,
                "Unknown identifier '$word'. Did you mean '$close'?", filePath,
                quickFix = QuickFix(
                    title = "Replace with '$close'",
                    kind = QuickFixKind.REPLACE_RANGE,
                    replacement = close,
                    startOffset = token.start,
                    endOffset = token.end,
                ),
            )
        }
        return out
    }

    private fun nearestKeyword(word: String, keywords: Set<String>): String? {
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (keyword in keywords) {
            if (kotlin.math.abs(keyword.length - word.length) > 2) continue
            val distance = levenshtein(word, keyword)
            if (distance in 1..2 && distance < bestDistance) {
                bestDistance = distance
                best = keyword
            }
        }
        return best
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
                text, token.start, Severity.WARNING,
                "'$word' is not declared in this file", filePath,
            )
        }
        return out
    }

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
            if (Regex("\\b${Regex.escape(name)}\\b").findAll(text).count() <= 1) {
                out += diagAt(
                    text, match.range.first, Severity.WARNING,
                    "'$name' is declared but never used", filePath,
                )
            }
        }
        return out
    }

    private fun declaredNames(text: String, language: Language): Set<String> {
        val names = HashSet<String>()
        val patterns = when (language) {
            Language.KOTLIN -> listOf(
                Regex("\\bfun\\s+(?:<[^>]*>\\s*)?(?:[A-Za-z0-9_.<>?]+\\.)?([a-zA-Z_][A-Za-z0-9_]*)"),
                Regex("\\b(?:val|var)\\s+([a-zA-Z_][A-Za-z0-9_]*)"),
                Regex("\\b(?:class|interface|object|enum class)\\s+([A-Za-z_][A-Za-z0-9_]*)"),
                Regex("\\btypealias\\s+([A-Za-z_][A-Za-z0-9_]*)"),
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
                val captured = match.groupValues.getOrNull(1).orEmpty()
                if (captured.isNotEmpty()) names += captured
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
