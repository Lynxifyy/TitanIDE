package dev.titanide.core.editor

import dev.titanide.core.model.Language

enum class TokenType {
    KEYWORD, STRING, NUMBER, COMMENT, FUNCTION, TYPE, ANNOTATION, OPERATOR, PUNCTUATION, IDENTIFIER, PLAIN,
}

data class Token(
    val type: TokenType,
    val start: Int,
    val end: Int,
) {
    val length: Int get() = end - start
}

/**
 * A regex-driven tokenizer. It is a real lexer over the document text (not a render-time
 * highlight), so the same tokens feed syntax colouring, bracket matching, symbol
 * extraction, and CodeGuard analysis.
 */
object Tokenizer {

    private data class Rule(val type: TokenType, val regex: Regex)

    private fun rulesFor(language: Language): List<Rule> = when (language) {
        Language.KOTLIN, Language.GRADLE -> listOf(
            Rule(TokenType.COMMENT, Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/")),
            Rule(TokenType.STRING, Regex("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'")),
            Rule(TokenType.ANNOTATION, Regex("@[A-Za-z_][A-Za-z0-9_]*")),
            Rule(TokenType.NUMBER, Regex("\\b(?:0[xX][0-9a-fA-F_]+|\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?[fFlLdD]?)\\b")),
            Rule(TokenType.KEYWORD, keywords(kotlinKeywords)),
            Rule(TokenType.TYPE, Regex("\\b[A-Z][A-Za-z0-9_]*\\b")),
            Rule(TokenType.FUNCTION, Regex("\\b[a-z_][A-Za-z0-9_]*(?=\\s*\\()")),
            Rule(TokenType.OPERATOR, Regex("->|::|\\.\\.|[-+*/%=!<>&|^~?]+")),
            Rule(TokenType.PUNCTUATION, Regex("[(){}\\[\\],;:.]")),
            Rule(TokenType.IDENTIFIER, Regex("[A-Za-z_][A-Za-z0-9_]*")),
        )

        Language.JAVA -> listOf(
            Rule(TokenType.COMMENT, Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/")),
            Rule(TokenType.STRING, Regex("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'")),
            Rule(TokenType.ANNOTATION, Regex("@[A-Za-z_][A-Za-z0-9_]*")),
            Rule(TokenType.NUMBER, Regex("\\b(?:0[xX][0-9a-fA-F_]+|\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?[fFlLdD]?)\\b")),
            Rule(TokenType.KEYWORD, keywords(javaKeywords)),
            Rule(TokenType.TYPE, Regex("\\b[A-Z][A-Za-z0-9_]*\\b")),
            Rule(TokenType.FUNCTION, Regex("\\b[a-z_][A-Za-z0-9_]*(?=\\s*\\()")),
            Rule(TokenType.OPERATOR, Regex("->|::|[-+*/%=!<>&|^~?]+")),
            Rule(TokenType.PUNCTUATION, Regex("[(){}\\[\\],;:.]")),
            Rule(TokenType.IDENTIFIER, Regex("[A-Za-z_][A-Za-z0-9_]*")),
        )

        Language.PYTHON -> listOf(
            Rule(TokenType.COMMENT, Regex("#[^\\n]*")),
            Rule(TokenType.STRING, Regex("\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|f?\"(?:\\\\.|[^\"\\\\\\n])*\"|f?'(?:\\\\.|[^'\\\\\\n])*'")),
            Rule(TokenType.ANNOTATION, Regex("@[A-Za-z_][A-Za-z0-9_.]*")),
            Rule(TokenType.NUMBER, Regex("\\b(?:0[xXbBoO][0-9a-fA-F_]+|\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?j?)\\b")),
            Rule(TokenType.KEYWORD, keywords(pythonKeywords)),
            Rule(TokenType.TYPE, Regex("\\b[A-Z][A-Za-z0-9_]*\\b")),
            Rule(TokenType.FUNCTION, Regex("\\b[a-zA-Z_][A-Za-z0-9_]*(?=\\s*\\()")),
            Rule(TokenType.OPERATOR, Regex("[-+*/%=!<>&|^~]+")),
            Rule(TokenType.PUNCTUATION, Regex("[(){}\\[\\],;:.]")),
            Rule(TokenType.IDENTIFIER, Regex("[A-Za-z_][A-Za-z0-9_]*")),
        )

        Language.JAVASCRIPT, Language.TYPESCRIPT -> listOf(
            Rule(TokenType.COMMENT, Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/")),
            Rule(TokenType.STRING, Regex("`(?:\\\\.|[^`\\\\])*`|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'")),
            Rule(TokenType.NUMBER, Regex("\\b(?:0[xXbBoO][0-9a-fA-F_]+|\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?n?)\\b")),
            Rule(TokenType.KEYWORD, keywords(jsKeywords)),
            Rule(TokenType.TYPE, Regex("\\b[A-Z][A-Za-z0-9_]*\\b")),
            Rule(TokenType.FUNCTION, Regex("\\b[a-zA-Z_$][A-Za-z0-9_$]*(?=\\s*\\()")),
            Rule(TokenType.OPERATOR, Regex("=>|\\.\\.\\.|===|!==|[-+*/%=!<>&|^~?]+")),
            Rule(TokenType.PUNCTUATION, Regex("[(){}\\[\\],;:.]")),
            Rule(TokenType.IDENTIFIER, Regex("[A-Za-z_$][A-Za-z0-9_$]*")),
        )

        Language.C, Language.CPP -> listOf(
            Rule(TokenType.COMMENT, Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/")),
            Rule(TokenType.STRING, Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'")),
            Rule(TokenType.ANNOTATION, Regex("#\\s*[a-zA-Z]+")),
            Rule(TokenType.NUMBER, Regex("\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?[fFuUlL]*)\\b")),
            Rule(TokenType.KEYWORD, keywords(if (language == Language.C) cKeywords else cppKeywords)),
            Rule(TokenType.TYPE, Regex("\\b[A-Z][A-Za-z0-9_]*\\b")),
            Rule(TokenType.FUNCTION, Regex("\\b[a-zA-Z_][A-Za-z0-9_]*(?=\\s*\\()")),
            Rule(TokenType.OPERATOR, Regex("->|::|[-+*/%=!<>&|^~?]+")),
            Rule(TokenType.PUNCTUATION, Regex("[(){}\\[\\],;:.]")),
            Rule(TokenType.IDENTIFIER, Regex("[A-Za-z_][A-Za-z0-9_]*")),
        )

        Language.RUST -> listOf(
            Rule(TokenType.COMMENT, Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/")),
            Rule(TokenType.STRING, Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])'")),
            Rule(TokenType.ANNOTATION, Regex("#!?\\[[^\\]]*\\]")),
            Rule(TokenType.NUMBER, Regex("\\b(?:0[xXbBoO][0-9a-fA-F_]+|\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?(?:f32|f64|u8|u16|u32|u64|usize|i8|i16|i32|i64|isize)?)\\b")),
            Rule(TokenType.KEYWORD, keywords(rustKeywords)),
            Rule(TokenType.TYPE, Regex("\\b[A-Z][A-Za-z0-9_]*\\b")),
            Rule(TokenType.FUNCTION, Regex("\\b[a-z_][a-zA-Z0-9_]*(?=\\s*[(<])")),
            Rule(TokenType.OPERATOR, Regex("->|=>|::|[-+*/%=!<>&|^~?]+")),
            Rule(TokenType.PUNCTUATION, Regex("[(){}\\[\\],;:.]")),
            Rule(TokenType.IDENTIFIER, Regex("[A-Za-z_][A-Za-z0-9_]*")),
        )

        Language.GO -> listOf(
            Rule(TokenType.COMMENT, Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/")),
            Rule(TokenType.STRING, Regex("`[^`]*`|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'")),
            Rule(TokenType.NUMBER, Regex("\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b")),
            Rule(TokenType.KEYWORD, keywords(goKeywords)),
            Rule(TokenType.TYPE, Regex("\\b[A-Z][A-Za-z0-9_]*\\b")),
            Rule(TokenType.FUNCTION, Regex("\\b[a-zA-Z_][A-Za-z0-9_]*(?=\\s*\\()")),
            Rule(TokenType.OPERATOR, Regex("<-|:=|[-+*/%=!<>&|^~]+")),
            Rule(TokenType.PUNCTUATION, Regex("[(){}\\[\\],;:.]")),
            Rule(TokenType.IDENTIFIER, Regex("[A-Za-z_][A-Za-z0-9_]*")),
        )

        Language.CSHARP -> listOf(
            Rule(TokenType.COMMENT, Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/")),
            Rule(TokenType.STRING, Regex("@\"[^\"]*(?:\"\"[^\"]*)*\"|\\$?\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'")),
            Rule(TokenType.ANNOTATION, Regex("@?\\[[A-Za-z_][A-Za-z0-9_.]*")),
            Rule(TokenType.NUMBER, Regex("\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?[fFdDmMlLuU]*)\\b")),
            Rule(TokenType.KEYWORD, keywords(csharpKeywords)),
            Rule(TokenType.TYPE, Regex("\\b[A-Z][A-Za-z0-9_]*\\b")),
            Rule(TokenType.FUNCTION, Regex("\\b[a-zA-Z_][A-Za-z0-9_]*(?=\\s*[(<])")),
            Rule(TokenType.OPERATOR, Regex("=>|\\?\\?|[-+*/%=!<>&|^~?]+")),
            Rule(TokenType.PUNCTUATION, Regex("[(){}\\[\\],;:.]")),
            Rule(TokenType.IDENTIFIER, Regex("[A-Za-z_][A-Za-z0-9_]*")),
        )

        Language.LUA -> listOf(
            Rule(TokenType.COMMENT, Regex("--\\[\\[[\\s\\S]*?\\]\\]|--[^\\n]*")),
            Rule(TokenType.STRING, Regex("\\[\\[[\\s\\S]*?\\]\\]|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'")),
            Rule(TokenType.NUMBER, Regex("\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b")),
            Rule(TokenType.KEYWORD, keywords(luaKeywords)),
            Rule(TokenType.FUNCTION, Regex("\\b[a-zA-Z_][A-Za-z0-9_]*(?=\\s*\\()")),
            Rule(TokenType.OPERATOR, Regex("\\.\\.|[-+*/%=!<>&|^~#]+")),
            Rule(TokenType.PUNCTUATION, Regex("[(){}\\[\\],;:.]")),
            Rule(TokenType.IDENTIFIER, Regex("[A-Za-z_][A-Za-z0-9_]*")),
        )

        Language.PHP -> listOf(
            Rule(TokenType.COMMENT, Regex("//[^\\n]*|#[^\\n]*|/\\*[\\s\\S]*?\\*/")),
            Rule(TokenType.STRING, Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'")),
            Rule(TokenType.NUMBER, Regex("\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?)\\b")),
            Rule(TokenType.KEYWORD, keywords(phpKeywords)),
            Rule(TokenType.TYPE, Regex("\\b[A-Z][A-Za-z0-9_]*\\b")),
            Rule(TokenType.FUNCTION, Regex("\\b[a-zA-Z_][A-Za-z0-9_]*(?=\\s*\\()")),
            Rule(TokenType.OPERATOR, Regex("->|::|[-+*/%=!<>&|^~?]+")),
            Rule(TokenType.PUNCTUATION, Regex("[(){}\\[\\],;:.$@]")),
            Rule(TokenType.IDENTIFIER, Regex("[A-Za-z_$][A-Za-z0-9_]*")),
        )

        Language.RUBY -> listOf(
            Rule(TokenType.COMMENT, Regex("#[^\\n]*")),
            Rule(TokenType.STRING, Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'")),
            Rule(TokenType.NUMBER, Regex("\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?)\\b")),
            Rule(TokenType.KEYWORD, keywords(rubyKeywords)),
            Rule(TokenType.TYPE, Regex("\\b[A-Z][A-Za-z0-9_]*\\b")),
            Rule(TokenType.FUNCTION, Regex("\\b[a-z_][A-Za-z0-9_]*[?!]?(?=\\s*[(\\s])")),
            Rule(TokenType.OPERATOR, Regex("=>|::|\\.\\.\\.|[-+*/%=!<>&|^~?]+")),
            Rule(TokenType.PUNCTUATION, Regex("[(){}\\[\\],;:.@]")),
            Rule(TokenType.IDENTIFIER, Regex("[A-Za-z_][A-Za-z0-9_]*[?!]?")),
        )

        Language.DART -> listOf(
            Rule(TokenType.COMMENT, Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/")),
            Rule(TokenType.STRING, Regex("\"\"\"[\\s\\S]*?\"\"\"|'''(?:[\\s\\S]*?)'''|r?\"(?:\\\\.|[^\"\\\\\\n])*\"|r?'(?:\\\\.|[^'\\\\\\n])*'")),
            Rule(TokenType.ANNOTATION, Regex("@[A-Za-z_][A-Za-z0-9_]*")),
            Rule(TokenType.NUMBER, Regex("\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b")),
            Rule(TokenType.KEYWORD, keywords(dartKeywords)),
            Rule(TokenType.TYPE, Regex("\\b[A-Z][A-Za-z0-9_]*\\b")),
            Rule(TokenType.FUNCTION, Regex("\\b[a-zA-Z_][A-Za-z0-9_]*(?=\\s*[(<])")),
            Rule(TokenType.OPERATOR, Regex("""=>|\?\.|\?\?|[-+*/%=!<>&|^~?]+""")),
            Rule(TokenType.PUNCTUATION, Regex("[(){}\\[\\],;:.]")),
            Rule(TokenType.IDENTIFIER, Regex("[A-Za-z_$][A-Za-z0-9_$]*")),
        )

        Language.HTML, Language.XML -> listOf(
            Rule(TokenType.COMMENT, Regex("<!--[\\s\\S]*?-->")),
            Rule(TokenType.STRING, Regex("\"[^\"]*\"|'[^']*'")),
            Rule(TokenType.TYPE, Regex("</?[A-Za-z][A-Za-z0-9:-]*|/?>")),
            Rule(TokenType.KEYWORD, Regex("\\b[A-Za-z][A-Za-z0-9:-]*(?=\\s*=)")),
            Rule(TokenType.PUNCTUATION, Regex("[<>=/]")),
            Rule(TokenType.PLAIN, Regex("[^<>\"'=]+")),
        )

        Language.CSS -> listOf(
            Rule(TokenType.COMMENT, Regex("/\\*[\\s\\S]*?\\*/")),
            Rule(TokenType.STRING, Regex("\"[^\"]*\"|'[^']*'")),
            Rule(TokenType.NUMBER, Regex("#[0-9a-fA-F]{3,8}\\b|\\b\\d+(?:\\.\\d+)?(?:px|em|rem|vh|vw|%|s|ms|deg)?\\b")),
            Rule(TokenType.TYPE, Regex("[.#][A-Za-z_-][A-Za-z0-9_-]*|::?[a-z-]+")),
            Rule(TokenType.KEYWORD, Regex("\\b[A-Za-z-]+(?=\\s*:)")),
            Rule(TokenType.FUNCTION, Regex("\\b[a-zA-Z-]+(?=\\s*\\()")),
            Rule(TokenType.PUNCTUATION, Regex("[{}();:,]")),
            Rule(TokenType.IDENTIFIER, Regex("[A-Za-z_-][A-Za-z0-9_-]*")),
        )

        Language.JSON -> listOf(
            Rule(TokenType.STRING, Regex("\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:)")),
            Rule(TokenType.STRING, Regex("\"(?:\\\\.|[^\"\\\\])*\"")),
            Rule(TokenType.NUMBER, Regex("-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b")),
            Rule(TokenType.KEYWORD, Regex("\\b(?:true|false|null)\\b")),
            Rule(TokenType.PUNCTUATION, Regex("[{}\\[\\],:]")),
        )

        Language.YAML -> listOf(
            Rule(TokenType.COMMENT, Regex("#[^\\n]*")),
            Rule(TokenType.STRING, Regex("\"[^\"]*\"|'[^']*'")),
            Rule(TokenType.NUMBER, Regex("\\b\\d+(?:\\.\\d+)?\\b")),
            Rule(TokenType.KEYWORD, Regex("^[ \\t-]*[A-Za-z_][A-Za-z0-9_.-]*(?=\\s*:)")),
            Rule(TokenType.KEYWORD, Regex("\\b(?:true|false|null|yes|no)\\b")),
            Rule(TokenType.PUNCTUATION, Regex("[-:\\[\\]{},|>&*]")),
            Rule(TokenType.PLAIN, Regex("[^\\n#:\"'\\[\\]{},|>&*]+")),
        )

        Language.SQL -> listOf(
            Rule(TokenType.COMMENT, Regex("--[^\\n]*|/\\*[\\s\\S]*?\\*/")),
            Rule(TokenType.STRING, Regex("'(?:''|[^'])*'|\"(?:[^\"]|\"\")*\"")),
            Rule(TokenType.NUMBER, Regex("\\b\\d+(?:\\.\\d+)?\\b")),
            Rule(TokenType.KEYWORD, Regex("\\b(?i:${sqlKeywords.joinToString("|")})\\b")),
            Rule(TokenType.PUNCTUATION, Regex("[(),;.]")),
            Rule(TokenType.OPERATOR, Regex("[=<>!+*/%-]+")),
            Rule(TokenType.IDENTIFIER, Regex("[A-Za-z_][A-Za-z0-9_]*")),
        )

        Language.SHELL -> listOf(
            Rule(TokenType.COMMENT, Regex("#[^\\n]*")),
            Rule(TokenType.STRING, Regex("\"(?:\\\\.|[^\"\\\\])*\"|'[^']*'")),
            Rule(TokenType.ANNOTATION, Regex("\\$\\{[^}]*\\}|\\$[A-Za-z_][A-Za-z0-9_]*|\\$[0-9@*?#!]")),
            Rule(TokenType.KEYWORD, keywords(shellKeywords)),
            Rule(TokenType.FUNCTION, Regex("\\b[a-zA-Z_][A-Za-z0-9_-]*(?=\\s*\\(\\))")),
            Rule(TokenType.OPERATOR, Regex("&&|\\|\\||>>|<<|[-+*/%=!<>&|^~]+")),
            Rule(TokenType.PUNCTUATION, Regex("[(){}\\[\\],;.]")),
            Rule(TokenType.IDENTIFIER, Regex("[A-Za-z_][A-Za-z0-9_-]*")),
        )

        Language.TOML, Language.PROPERTIES -> listOf(
            Rule(TokenType.COMMENT, Regex("#[^\\n]*")),
            Rule(TokenType.TYPE, Regex("^\\s*\\[[^\\]]*\\]")),
            Rule(TokenType.STRING, Regex("\"(?:\\\\.|[^\"\\\\])*\"|'[^']*'")),
            Rule(TokenType.NUMBER, Regex("\\b\\d+(?:\\.\\d+)?\\b")),
            Rule(TokenType.KEYWORD, Regex("^\\s*[A-Za-z0-9_.-]+(?=\\s*=)")),
            Rule(TokenType.KEYWORD, Regex("\\b(?:true|false)\\b")),
            Rule(TokenType.PUNCTUATION, Regex("[=.,\\[\\]{}]")),
            Rule(TokenType.PLAIN, Regex("[^\\n#=\"'\\[\\]{},.]+")),
        )

        Language.MARKDOWN -> listOf(
            Rule(TokenType.TYPE, Regex("^#{1,6}[^\\n]*")),
            Rule(TokenType.STRING, Regex("```[\\s\\S]*?```|`[^`\\n]+`")),
            Rule(TokenType.FUNCTION, Regex("\\[[^\\]]*\\]\\([^)]*\\)")),
            Rule(TokenType.KEYWORD, Regex("^\\s*[-*+]\\s|^\\s*\\d+\\.\\s")),
            Rule(TokenType.OPERATOR, Regex("\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__|\\*[^*\\n]+\\*")),
            Rule(TokenType.COMMENT, Regex("<!--[\\s\\S]*?-->")),
            Rule(TokenType.PLAIN, Regex("[^\\n]+")),
        )

        Language.PLAINTEXT -> emptyList()
    }

    private fun keywords(words: List<String>): Regex =
        Regex("\\b(?:${words.joinToString("|") { Regex.escape(it) }})\\b")

    private val kotlinKeywords = listOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
        "true", "try", "typealias", "typeof", "val", "var", "when", "while", "by", "catch",
        "constructor", "delegate", "dynamic", "field", "file", "finally", "get", "import",
        "init", "param", "property", "receiver", "set", "setparam", "where", "abstract",
        "actual", "annotation", "companion", "const", "crossinline", "data", "enum", "expect",
        "external", "final", "infix", "inline", "inner", "internal", "lateinit", "noinline",
        "open", "operator", "out", "override", "private", "protected", "public", "reified",
        "sealed", "suspend", "tailrec", "vararg", "it",
    )

    private val javaKeywords = listOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
        "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private", "protected", "public",
        "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while", "var", "record",
        "sealed", "permits", "yield", "true", "false", "null",
    )

    private val pythonKeywords = listOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
        "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in",
        "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while",
        "with", "yield", "True", "False", "None", "self", "match", "case",
    )

    private val jsKeywords = listOf(
        "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
        "default", "delete", "do", "else", "export", "extends", "finally", "for", "function",
        "if", "import", "in", "instanceof", "let", "new", "of", "return", "static", "super",
        "switch", "this", "throw", "try", "typeof", "var", "void", "while", "with", "yield",
        "interface", "type", "enum", "implements", "namespace", "declare", "readonly", "public",
        "private", "protected", "abstract", "as", "satisfies", "true", "false", "null", "undefined",
    )

    private val cKeywords = listOf(
        "auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else",
        "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long", "register",
        "restrict", "return", "short", "signed", "sizeof", "static", "struct", "switch",
        "typedef", "union", "unsigned", "void", "volatile", "while", "NULL", "true", "false",
    )

    private val cppKeywords = cKeywords + listOf(
        "alignas", "alignof", "and", "asm", "bool", "catch", "class", "concept", "constexpr",
        "consteval", "constinit", "const_cast", "co_await", "co_return", "co_yield", "decltype",
        "delete", "dynamic_cast", "explicit", "export", "final", "friend", "mutable",
        "namespace", "new", "noexcept", "not", "nullptr", "operator", "or", "override",
        "private", "protected", "public", "reinterpret_cast", "requires", "static_assert",
        "static_cast", "template", "this", "throw", "try", "typeid", "typename", "using",
        "virtual", "nullptr_t", "std", "string", "vector",
    )

    private val rustKeywords = listOf(
        "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
        "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod",
        "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super",
        "trait", "true", "type", "unsafe", "use", "where", "while", "union", "macro_rules",
    )

    private val goKeywords = listOf(
        "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
        "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range",
        "return", "select", "struct", "switch", "type", "var", "nil", "true", "false", "iota",
    )

    private val csharpKeywords = listOf(
        "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked",
        "class", "const", "continue", "decimal", "default", "delegate", "do", "double", "else",
        "enum", "event", "explicit", "extern", "false", "finally", "fixed", "float", "for",
        "foreach", "goto", "if", "implicit", "in", "int", "interface", "internal", "is", "lock",
        "long", "namespace", "new", "null", "object", "operator", "out", "override", "params",
        "private", "protected", "public", "readonly", "ref", "return", "sbyte", "sealed",
        "short", "sizeof", "stackalloc", "static", "string", "struct", "switch", "this",
        "throw", "true", "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort",
        "using", "var", "virtual", "void", "volatile", "while", "async", "await", "record",
    )

    private val luaKeywords = listOf(
        "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto",
        "if", "in", "local", "nil", "not", "or", "repeat", "return", "then", "true", "until",
        "while", "self",
    )

    private val phpKeywords = listOf(
        "abstract", "and", "array", "as", "break", "callable", "case", "catch", "class",
        "clone", "const", "continue", "declare", "default", "do", "echo", "else", "elseif",
        "empty", "enddeclare", "endfor", "endforeach", "endif", "endswitch", "endwhile",
        "extends", "final", "finally", "fn", "for", "foreach", "function", "global", "goto",
        "if", "implements", "include", "include_once", "instanceof", "insteadof", "interface",
        "isset", "list", "match", "namespace", "new", "or", "print", "private", "protected",
        "public", "readonly", "require", "require_once", "return", "static", "switch", "throw",
        "trait", "try", "unset", "use", "var", "while", "yield", "true", "false", "null",
    )

    private val rubyKeywords = listOf(
        "alias", "and", "begin", "break", "case", "class", "def", "defined?", "do", "else",
        "elsif", "end", "ensure", "false", "for", "if", "in", "module", "next", "nil", "not",
        "or", "redo", "rescue", "retry", "return", "self", "super", "then", "true", "undef",
        "unless", "until", "when", "while", "yield", "require", "require_relative", "attr_accessor",
    )

    private val dartKeywords = listOf(
        "abstract", "as", "assert", "async", "await", "base", "break", "case", "catch", "class",
        "const", "continue", "covariant", "default", "deferred", "do", "dynamic", "else", "enum",
        "export", "extends", "extension", "external", "factory", "false", "final", "finally",
        "for", "get", "hide", "if", "implements", "import", "in", "interface", "is", "late",
        "library", "mixin", "new", "null", "on", "operator", "part", "required", "rethrow",
        "return", "sealed", "set", "show", "static", "super", "switch", "sync", "this", "throw",
        "true", "try", "typedef", "var", "void", "when", "while", "with", "yield",
    )

    private val shellKeywords = listOf(
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case",
        "esac", "function", "in", "select", "time", "coproc", "return", "exit", "export",
        "local", "readonly", "declare", "unset", "shift", "source", "alias", "eval", "exec",
        "set", "trap", "cd", "echo", "printf", "read", "test", "true", "false",
    )

    private val sqlKeywords = listOf(
        "select", "from", "where", "insert", "into", "values", "update", "set", "delete",
        "create", "table", "alter", "drop", "index", "view", "join", "left", "right", "inner",
        "outer", "full", "cross", "on", "group", "by", "order", "having", "limit", "offset",
        "union", "all", "distinct", "as", "and", "or", "not", "null", "is", "in", "between",
        "like", "exists", "case", "when", "then", "else", "end", "primary", "key", "foreign",
        "references", "constraint", "default", "unique", "check", "auto_increment", "with",
        "begin", "commit", "rollback", "transaction", "if", "integer", "text", "varchar",
        "boolean", "timestamp", "blob", "real", "count", "sum", "avg", "min", "max",
    )

    /**
     * Tokenizes [text] for [language]. Rules are tried in order at each position; the
     * first match that starts at the cursor wins, otherwise one character is skipped as
     * PLAINTEXT so no input can cause an infinite loop.
     */
    fun tokenize(text: String, language: Language): List<Token> {
        if (text.isEmpty() || language == Language.PLAINTEXT) return emptyList()
        val rules = rulesFor(language)
        val tokens = ArrayList<Token>(text.length / 4)
        var index = 0

        while (index < text.length) {
            var matched = false
            for (rule in rules) {
                val match = rule.regex.find(text, index)
                if (match != null && match.range.first == index) {
                    tokens += Token(rule.type, match.range.first, match.range.last + 1)
                    index = match.range.last + 1
                    matched = true
                    break
                }
            }
            if (!matched) index++
        }
        return tokens
    }

    /** Token covering [offset], used for hover and context-aware actions. */
    fun tokenAt(tokens: List<Token>, offset: Int): Token? =
        tokens.firstOrNull { offset >= it.start && offset < it.end }

    /** The identifier or keyword under [offset], read straight from the source. */
    fun wordAt(text: String, offset: Int): String? {
        if (offset < 0 || offset > text.length) return null
        var start = offset.coerceAtMost(text.length - 1)
        if (start < 0) return null
        if (!text[start].isWordChar()) {
            if (start == 0) return null
            start--
            if (!text[start].isWordChar()) return null
        }
        var end = start
        while (start > 0 && text[start - 1].isWordChar()) start--
        while (end < text.length - 1 && text[end + 1].isWordChar()) end++
        return text.substring(start, end + 1)
    }

    private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '_'
}
