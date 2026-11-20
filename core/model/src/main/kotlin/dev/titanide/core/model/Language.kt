package dev.titanide.core.model

import kotlinx.serialization.Serializable

/**
 * Every language TITAN can highlight, lint, template, and (where a toolchain exists)
 * compile. [extensions] drives editor detection.
 */
@Serializable
enum class Language(
    val id: String,
    val displayName: String,
    val extensions: List<String>,
    val lineComment: String?,
    val blockComment: Pair<String, String>?,
    val indentUnit: String = "    ",
) {
    KOTLIN("kotlin", "Kotlin", listOf("kt", "kts"), "//", "/*" to "*/"),
    JAVA("java", "Java", listOf("java"), "//", "/*" to "*/"),
    PYTHON("python", "Python", listOf("py", "pyi"), "#", null),
    JAVASCRIPT("javascript", "JavaScript", listOf("js", "mjs", "cjs", "jsx"), "//", "/*" to "*/"),
    TYPESCRIPT("typescript", "TypeScript", listOf("ts", "tsx", "mts"), "//", "/*" to "*/"),
    C("c", "C", listOf("c", "h"), "//", "/*" to "*/"),
    CPP("cpp", "C++", listOf("cpp", "cc", "cxx", "hpp", "hh", "hxx"), "//", "/*" to "*/"),
    RUST("rust", "Rust", listOf("rs"), "//", "/*" to "*/"),
    GO("go", "Go", listOf("go"), "//", "/*" to "*/"),
    CSHARP("csharp", "C#", listOf("cs"), "//", "/*" to "*/"),
    LUA("lua", "Lua", listOf("lua"), "--", "--[[" to "]]"),
    PHP("php", "PHP", listOf("php"), "//", "/*" to "*/"),
    RUBY("ruby", "Ruby", listOf("rb"), "#", null),
    DART("dart", "Dart", listOf("dart"), "//", "/*" to "*/"),
    HTML("html", "HTML", listOf("html", "htm"), null, "<!--" to "-->"),
    CSS("css", "CSS", listOf("css", "scss"), null, "/*" to "*/"),
    JSON("json", "JSON", listOf("json", "jsonc"), null, null, indentUnit = "  "),
    YAML("yaml", "YAML", listOf("yml", "yaml"), "#", null, indentUnit = "  "),
    XML("xml", "XML", listOf("xml", "xsd", "svg"), null, "<!--" to "-->"),
    SQL("sql", "SQL", listOf("sql"), "--", "/*" to "*/", indentUnit = "  "),
    SHELL("shell", "Shell", listOf("sh", "bash", "zsh"), "#", null, indentUnit = "  "),
    GRADLE("gradle", "Gradle", listOf("gradle"), "//", "/*" to "*/"),
    TOML("toml", "TOML", listOf("toml"), "#", null, indentUnit = "  "),
    MARKDOWN("markdown", "Markdown", listOf("md", "markdown"), null, "<!--" to "-->", indentUnit = "  "),
    PROPERTIES("properties", "Properties", listOf("properties"), "#", null),
    PLAINTEXT("plaintext", "Plain Text", listOf("txt", "log"), null, null),
    ;

    val primaryExtension: String get() = extensions.first()

    companion object {
        private val byExtension: Map<String, Language> =
            entries.flatMap { lang -> lang.extensions.map { it to lang } }.toMap()

        fun fromFileName(name: String): Language {
            val base = name.substringAfterLast('/')
            val ext = base.substringAfterLast('.', "").lowercase()
            return byExtension[ext] ?: PLAINTEXT
        }

        fun fromId(id: String): Language = entries.firstOrNull { it.id == id } ?: PLAINTEXT

        /** Languages TITAN can actually compile locally or on a remote agent. */
        fun compilable(): List<Language> =
            listOf(KOTLIN, JAVA, PYTHON, JAVASCRIPT, TYPESCRIPT, C, CPP, RUST, GO, CSHARP, PHP, RUBY, DART)
    }
}
