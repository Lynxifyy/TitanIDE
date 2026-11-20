package dev.titanide.core.model

import kotlinx.serialization.Serializable

@Serializable
data class GitRepository(
    val id: Long,
    val name: String,
    val fullName: String,
    val owner: String,
    val description: String?,
    val language: String?,
    val defaultBranch: String,
    val isPrivate: Boolean,
    val updatedAt: String,
    val stars: Int,
    val cloneUrl: String,
    val htmlUrl: String,
)

@Serializable
data class GitBranch(val name: String, val isDefault: Boolean, val sha: String)

@Serializable
data class GitCommit(
    val sha: String,
    val message: String,
    val author: String,
    val authorEmail: String,
    val timestamp: Long,
)

@Serializable
data class GitStatusEntry(
    val path: String,
    val status: GitFileStatus,
    val staged: Boolean,
) {
    val isConflict: Boolean get() = status == GitFileStatus.CONFLICTED
}

@Serializable
enum class GitFileStatus(val code: Char, val label: String) {
    ADDED('A', "Added"),
    MODIFIED('M', "Modified"),
    DELETED('D', "Deleted"),
    RENAMED('R', "Renamed"),
    UNTRACKED('?', "Untracked"),
    CONFLICTED('U', "Conflicted"),
    ;

    companion object {
        fun fromCode(code: Char): GitFileStatus =
            entries.firstOrNull { it.code == code } ?: MODIFIED
    }
}

@Serializable
data class GitDiffLine(val type: DiffLineType, val text: String, val oldLine: Int?, val newLine: Int?)

@Serializable
enum class DiffLineType { CONTEXT, ADDED, REMOVED, HEADER }

@Serializable
data class GitDiffHunk(
    val header: String,
    val oldStart: Int,
    val newStart: Int,
    val lines: List<GitDiffLine>,
)

@Serializable
data class FileDiff(val path: String, val hunks: List<GitDiffHunk>, val binary: Boolean = false)

@Serializable
data class GitSyncState(
    val branch: String?,
    val ahead: Int,
    val behind: Int,
    val remoteUrl: String?,
    val hasUncommittedChanges: Boolean,
)
