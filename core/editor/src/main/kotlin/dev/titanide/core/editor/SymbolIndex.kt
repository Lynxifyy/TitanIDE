package dev.titanide.core.editor

import dev.titanide.core.model.CodeSymbol
import dev.titanide.core.model.Language
import dev.titanide.core.model.SymbolKind

/** A symbol together with the place it is declared. */
data class SymbolDeclaration(
    val symbol: CodeSymbol,
    val line: Int,
    val column: Int,
    val container: String? = null,
)

/** One place a symbol name is used. */
data class SymbolReference(
    val filePath: String,
    val line: Int,
    val column: Int,
    val lineText: String,
    val isDeclaration: Boolean,
)

/**
 * A project-wide symbol index built from the real tokenizer and the per-language declaration
 * extractor.
 *
 * The IDE has no language server, so navigation is answered from this index instead of being
 * faked: a definition is a declaration the extractor actually found, and a reference is a
 * token whose text matches that name. Identifiers inside comments and strings are skipped, so
 * the index does not report prose as a reference.
 */
class SymbolIndex {

    private data class Entry(
        val path: String,
        val text: String,
        val language: Language,
        val document: TextDocument,
        val declarations: List<SymbolDeclaration>,
        val identifierTokens: List<Token>,
    )

    private val entries = LinkedHashMap<String, Entry>()

    val size: Int get() = entries.size

    fun clear() = entries.clear()

    /** Index (or re-index) one file. Passing the same path again replaces the old entry. */
    fun put(path: String, text: String, language: Language) {
        val document = TextDocument.of(text)
        val declarations = EditorEngine(language)
            .extractSymbols(document, path)
            .map { symbol ->
                SymbolDeclaration(
                    symbol = symbol,
                    line = symbol.line,
                    column = symbol.column,
                )
            }
        val tokens = Tokenizer.tokenize(text, language)
            .filter { it.type == TokenType.IDENTIFIER || it.type == TokenType.FUNCTION || it.type == TokenType.TYPE }
        entries[path] = Entry(path, text, language, document, declarations, tokens)
    }

    fun remove(path: String) {
        entries.remove(path)
    }

    /** Every declaration in the project, sorted by file then line. */
    fun allSymbols(): List<SymbolDeclaration> =
        entries.values
            .flatMap { it.declarations }
            .sortedWith(compareBy({ it.symbol.filePath }, { it.line }, { it.column }))

    /** Declaration sites for [name]. Empty when nothing in the project declares it. */
    fun declarationsOf(name: String): List<SymbolDeclaration> =
        allSymbols().filter { it.symbol.name == name }

    /**
     * The declaration a use at [offset] in [path] resolves to.
     *
     * A symbol declared in the same file wins over one declared elsewhere, which is what a
     * developer expects when a local shadows a same-named declaration in another file.
     */
    fun definitionAt(path: String, offset: Int): SymbolDeclaration? {
        val name = identifierAt(path, offset) ?: return null
        val declarations = declarationsOf(name)
        if (declarations.isEmpty()) return null
        return declarations.firstOrNull { it.symbol.filePath == path } ?: declarations.first()
    }

    /** The identifier text covering [offset], if the offset sits inside one. */
    fun identifierAt(path: String, offset: Int): String? {
        val entry = entries[path] ?: return null
        if (offset < 0 || offset > entry.text.length) return null
        // An offset at the very end of a token belongs to that token, which is where a caret
        // sits after clicking the last character.
        val token = entry.identifierTokens.firstOrNull { offset >= it.start && offset <= it.end }
            ?: return null
        return entry.text.substring(token.start, token.end)
    }

    /**
     * Every use of [name] in the project, declaration sites included.
     *
     * Matches are token-based, so `count` is not reported as a reference to `counter`.
     */
    fun referencesTo(name: String): List<SymbolReference> {
        val references = ArrayList<SymbolReference>()
        for (entry in entries.values) {
            val declarationOffsets = entry.declarations
                .filter { it.symbol.name == name }
                .map { it.line to it.column }
                .toSet()
            for (token in entry.identifierTokens) {
                if (entry.text.substring(token.start, token.end) != name) continue
                val line = entry.document.lineOf(token.start)
                val column = token.start - entry.document.lineStart(line)
                references += SymbolReference(
                    filePath = entry.path,
                    line = line,
                    column = column,
                    lineText = entry.document.lineText(line).trim(),
                    isDeclaration = (line to column) in declarationOffsets,
                )
            }
        }
        return references.sortedWith(compareBy({ it.filePath }, { it.line }, { it.column }))
    }

    /** Symbols whose name contains [query], case-insensitively, best matches first. */
    fun search(query: String, limit: Int = 50): List<SymbolDeclaration> {
        if (query.isBlank()) return emptyList()
        val needle = query.lowercase()
        return allSymbols()
            .mapNotNull { declaration ->
                val name = declaration.symbol.name.lowercase()
                val rank = when {
                    name == needle -> 0
                    name.startsWith(needle) -> 1
                    name.contains(needle) -> 2
                    else -> null
                } ?: return@mapNotNull null
                rank to declaration
            }
            .sortedWith(compareBy({ it.first }, { it.second.symbol.name.length }, { it.second.symbol.name }))
            .take(limit)
            .map { it.second }
    }
}
