package dev.titanide.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: String,
    val name: String,
    val packageName: String,
    /** Absolute path inside app-private storage. */
    val rootPath: String,
    val category: ProjectCategory,
    val templateId: String,
    val languages: List<Language>,
    val buildSystem: BuildSystem,
    val createdAt: Long,
    val lastOpenedAt: Long,
    val gitRemote: String? = null,
    val gitBranch: String? = null,
    val favorite: Boolean = false,
    val sdkConfig: SdkConfig? = null,
)

@Serializable
data class SdkConfig(
    val minSdk: Int = 24,
    val targetSdk: Int = 36,
    val compileSdk: Int = 36,
    val applicationId: String,
    val kotlinVersion: String = "2.2.21",
)

@Serializable
enum class FileKind { FILE, DIRECTORY, SYMLINK }

@Serializable
data class ProjectFile(
    val name: String,
    val path: String,
    val kind: FileKind,
    val sizeBytes: Long = 0,
    val lastModified: Long = 0,
    val language: Language = Language.PLAINTEXT,
    val isGitIgnored: Boolean = false,
)

@Serializable
enum class Severity { ERROR, WARNING, INFO, HINT }

/** A diagnostic from a real parser, compiler, or linter — never fabricated. */
@Serializable
data class Diagnostic(
    val filePath: String,
    val line: Int,
    val column: Int,
    val endLine: Int = line,
    val endColumn: Int = column,
    val severity: Severity,
    val message: String,
    val source: String,
    val code: String? = null,
    val quickFix: QuickFix? = null,
)

@Serializable
data class QuickFix(
    val title: String,
    val kind: QuickFixKind,
    val replacement: String? = null,
    val startOffset: Int = -1,
    val endOffset: Int = -1,
)

@Serializable
enum class QuickFixKind { REPLACE_RANGE, INSERT_TEXT, DELETE_RANGE, AI_ASSISTED }

@Serializable
data class TextRange(val start: Int, val end: Int) {
    init {
        require(start <= end) { "start ($start) must not exceed end ($end)" }
    }
    val isEmpty: Boolean get() = start == end
    fun contains(offset: Int): Boolean = offset in start until end
}

@Serializable
data class Position(val line: Int, val column: Int)

@Serializable
data class CodeSymbol(
    val name: String,
    val kind: SymbolKind,
    val filePath: String,
    val line: Int,
    val column: Int,
    val containerName: String? = null,
)

@Serializable
enum class SymbolKind { CLASS, INTERFACE, OBJECT, FUNCTION, METHOD, PROPERTY, FIELD, ENUM, ENUM_CONSTANT, TYPE_ALIAS }
