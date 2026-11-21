package dev.titanide.core.git.local

import dev.titanide.core.model.GitBranch
import dev.titanide.core.model.GitCommit
import dev.titanide.core.model.GitDiffLine
import dev.titanide.core.model.GitFileStatus
import dev.titanide.core.model.GitStatusEntry
import dev.titanide.core.model.DiffLineType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.File

/**
 * Real Git operations through JGit. Every method returns a [Result] so a Git failure is
 * reported to the user instead of being swallowed or, worse, reported as success.
 */
class GitRepositoryService {

    private val identity = PersonIdent("TITAN IDE", "titan-ide@localhost")

    sealed interface Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>
        data class Failed(val reason: String) : Outcome<Nothing>
    }

    private fun open(root: File): Repository? {
        val gitDir = File(root, Constants.DOT_GIT)
        if (!gitDir.exists()) return null
        return runCatching { FileRepositoryBuilder().setGitDir(gitDir).build() }.getOrNull()
    }

    fun isRepository(root: File): Boolean = File(root, Constants.DOT_GIT).exists()

    suspend fun init(root: File): Outcome<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (isRepository(root)) return@runCatching
            Git.init().setDirectory(root).call().use { }
        }.fold({ Outcome.Ok(Unit) }, { Outcome.Failed("git init failed: ${it.message}") })
    }

    /** Clones over HTTPS with a token; the token is never written to disk or logged. */
    suspend fun clone(
        remoteUrl: String,
        destination: File,
        token: String?,
        branch: String? = null,
        depth: Int = 1,
    ): Outcome<Unit> = withContext(Dispatchers.IO) {
        if (destination.exists() && destination.listFiles()?.isNotEmpty() == true) {
            return@withContext Outcome.Failed("Destination is not empty: ${destination.absolutePath}")
        }
        runCatching {
            val command = Git.cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(destination)
                .setCloneAllBranches(depth <= 0)
                .setDepth(if (depth > 0) depth else 0)
            if (branch != null) command.setBranch(branch)
            if (token != null) {
                command.setCredentialsProvider(UsernamePasswordCredentialsProvider(token, "x-oauth-basic"))
            }
            command.call().use { }
        }.fold({ Outcome.Ok(Unit) }, { e -> Outcome.Failed(describe("clone", e)) })
    }

    suspend fun status(root: File): Outcome<List<GitStatusEntry>> = withContext(Dispatchers.IO) {
        val repo = open(root) ?: return@withContext Outcome.Failed("Not a Git repository")
        runCatching {
            Git(repo).use { git ->
                val status = git.status().call()
                val entries = ArrayList<GitStatusEntry>()
                status.added.forEach { entries += GitStatusEntry(it, GitFileStatus.ADDED, true) }
                status.changed.forEach { entries += GitStatusEntry(it, GitFileStatus.MODIFIED, true) }
                status.removed.forEach { entries += GitStatusEntry(it, GitFileStatus.DELETED, true) }
                status.modified.forEach { entries += GitStatusEntry(it, GitFileStatus.MODIFIED, false) }
                status.untracked.forEach { entries += GitStatusEntry(it, GitFileStatus.UNTRACKED, false) }
                status.missing.forEach { entries += GitStatusEntry(it, GitFileStatus.DELETED, false) }
                status.conflicting.forEach { entries += GitStatusEntry(it, GitFileStatus.CONFLICTED, false) }
                entries.distinctBy { it.path to it.staged }.sortedBy { it.path }
            }
        }.fold({ Outcome.Ok(it) }, { Outcome.Failed(describe("status", it)) })
    }

    suspend fun stage(root: File, paths: List<String>): Outcome<Unit> = withContext(Dispatchers.IO) {
        val repo = open(root) ?: return@withContext Outcome.Failed("Not a Git repository")
        // JGit's add() ignores a pattern that matches nothing, where git reports a pathspec
        // error. Reporting it keeps a stage that did nothing from looking like a success.
        val index = runCatching { repo.readDirCache() }.getOrNull()
        val unmatched = paths.filter { path ->
            !File(root, path).exists() && index?.getEntry(path) == null
        }
        if (unmatched.isNotEmpty()) {
            return@withContext Outcome.Failed(
                "pathspec '${unmatched.first()}' did not match any files",
            )
        }
        runCatching {
            Git(repo).use { git ->
                val command = git.add()
                paths.forEach { command.addFilepattern(it) }
                command.call()
            }
        }.fold({ Outcome.Ok(Unit) }, { Outcome.Failed(describe("add", it)) })
    }

    suspend fun unstage(root: File, paths: List<String>): Outcome<Unit> = withContext(Dispatchers.IO) {
        val repo = open(root) ?: return@withContext Outcome.Failed("Not a Git repository")
        runCatching {
            Git(repo).use { git ->
                val builder = git.reset()
                paths.forEach { builder.addPath(it) }
                builder.call()
            }
        }.fold({ Outcome.Ok(Unit) }, { Outcome.Failed(describe("reset", it)) })
    }

    suspend fun commit(root: File, message: String, authorName: String?, authorEmail: String?): Outcome<String> =
        withContext(Dispatchers.IO) {
            val repo = open(root) ?: return@withContext Outcome.Failed("Not a Git repository")
            if (message.isBlank()) return@withContext Outcome.Failed("Commit message is required")
            runCatching {
                Git(repo).use { git ->
                    val staged = git.status().call()
                    val hasStaged = staged.added.isNotEmpty() || staged.changed.isNotEmpty() ||
                        staged.removed.isNotEmpty()
                    if (!hasStaged) error("Nothing is staged. Stage files before committing.")
                    val ident = if (authorName != null && authorEmail != null) {
                        PersonIdent(authorName, authorEmail)
                    } else {
                        identity
                    }
                    git.commit()
                        .setMessage(message)
                        .setAuthor(ident)
                        .setCommitter(ident)
                        .setAllowEmpty(false)
                        .call()
                        .name
                }
            }.fold({ Outcome.Ok(it) }, { Outcome.Failed(describe("commit", it)) })
        }

    suspend fun push(
        root: File,
        token: String,
        remote: String = "origin",
        branch: String? = null,
        force: Boolean = false,
    ): Outcome<Unit> = withContext(Dispatchers.IO) {
        val repo = open(root) ?: return@withContext Outcome.Failed("Not a Git repository")
        runCatching {
            Git(repo).use { git ->
                val current = branch ?: repo.branch ?: "main"
                git.push()
                    .setRemote(remote)
                    .add("refs/heads/$current:refs/heads/$current")
                    .setCredentialsProvider(UsernamePasswordCredentialsProvider(token, "x-oauth-basic"))
                    .setForce(force)
                    .call()
            }
        }.fold({ Outcome.Ok(Unit) }, { Outcome.Failed(describe("push", it)) })
    }

    suspend fun pull(root: File, token: String, remote: String = "origin"): Outcome<Unit> =
        withContext(Dispatchers.IO) {
            val repo = open(root) ?: return@withContext Outcome.Failed("Not a Git repository")
            runCatching {
                Git(repo).use { git ->
                    git.pull()
                        .setRemote(remote)
                        .setCredentialsProvider(UsernamePasswordCredentialsProvider(token, "x-oauth-basic"))
                        .call()
                }
            }.fold({ Outcome.Ok(Unit) }, { Outcome.Failed(describe("pull", it)) })
        }

    suspend fun currentBranch(root: File): String? = withContext(Dispatchers.IO) {
        open(root)?.use { it.branch }
    }

    suspend fun branches(root: File): Outcome<List<GitBranch>> = withContext(Dispatchers.IO) {
        val repo = open(root) ?: return@withContext Outcome.Failed("Not a Git repository")
        runCatching {
            val current = repo.branch
            Git(repo).use { git ->
                git.branchList().call().map { ref ->
                    val name = Repository.shortenRefName(ref.name)
                    GitBranch(
                        name = name,
                        isDefault = name == current,
                        sha = repo.resolve(ref.name)?.name.orEmpty(),
                    )
                }
            }
        }.fold({ Outcome.Ok(it) }, { Outcome.Failed(describe("branches", it)) })
    }

    suspend fun createBranch(root: File, name: String, checkout: Boolean = true): Outcome<Unit> =
        withContext(Dispatchers.IO) {
            val repo = open(root) ?: return@withContext Outcome.Failed("Not a Git repository")
            if (name.isBlank()) return@withContext Outcome.Failed("Branch name is required")
            runCatching {
                Git(repo).use { git ->
                    val command = git.branchCreate().setName(name)
                    if (checkout) command.setUpstreamMode(org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode.TRACK)
                    command.call()
                    if (checkout) git.checkout().setName(name).call()
                }
            }.fold({ Outcome.Ok(Unit) }, { Outcome.Failed(describe("branch create", it)) })
        }

    suspend fun checkout(root: File, name: String): Outcome<Unit> = withContext(Dispatchers.IO) {
        val repo = open(root) ?: return@withContext Outcome.Failed("Not a Git repository")
        runCatching {
            Git(repo).use { it.checkout().setName(name).call() }
        }.fold({ Outcome.Ok(Unit) }, { Outcome.Failed(describe("checkout", it)) })
    }

    suspend fun merge(root: File, branch: String): Outcome<Boolean> = withContext(Dispatchers.IO) {
        val repo = open(root) ?: return@withContext Outcome.Failed("Not a Git repository")
        runCatching {
            val result = Git(repo).use { git ->
                git.merge()
                    .include(repo.resolve(branch))
                    .setFastForward(MergeCommand.FastForwardMode.FF)
                    .call()
            }
            result.mergeStatus == org.eclipse.jgit.api.MergeResult.MergeStatus.MERGED ||
                result.mergeStatus == org.eclipse.jgit.api.MergeResult.MergeStatus.FAST_FORWARD ||
                result.mergeStatus == org.eclipse.jgit.api.MergeResult.MergeStatus.ALREADY_UP_TO_DATE
        }.fold({ Outcome.Ok(it) }, { Outcome.Failed(describe("merge", it)) })
    }

    suspend fun log(root: File, limit: Int = 50): Outcome<List<GitCommit>> = withContext(Dispatchers.IO) {
        val repo = open(root) ?: return@withContext Outcome.Failed("Not a Git repository")
        runCatching {
            Git(repo).use { git ->
                git.log().setMaxCount(limit).call().map { commit ->
                    GitCommit(
                        sha = commit.name,
                        message = commit.fullMessage.trim(),
                        author = commit.authorIdent.name,
                        authorEmail = commit.authorIdent.emailAddress,
                        timestamp = commit.commitTime.toLong() * 1000,
                    )
                }
            }
        }.fold({ Outcome.Ok(it) }, { Outcome.Failed(describe("log", it)) })
    }

    /** Working-tree diff against HEAD, or a staged-only diff when [staged] is true. */
    suspend fun diff(root: File, staged: Boolean = false): Outcome<List<GitDiffLine>> = withContext(Dispatchers.IO) {
        val repo = open(root) ?: return@withContext Outcome.Failed("Not a Git repository")
        runCatching {
            val capture = java.io.ByteArrayOutputStream()
            val formatter = DiffFormatter(capture).apply {
                setRepository(repo)
                setDetectRenames(true)
            }
            Git(repo).use { git ->
                val command = git.diff()
                if (staged) command.setCached(true)
                command.setOutputStream(capture).call()
            }
            formatter.close()

            val lines = ArrayList<GitDiffLine>()
            var oldLine = 0
            var newLine = 0
            val raw = capture.toString()
            if (raw.isBlank()) return@runCatching emptyList<GitDiffLine>()
            raw.split('\n').forEach { text ->
                when {
                    text.startsWith("@@") -> {
                        val match = HUNK.find(text)
                        if (match != null) {
                            oldLine = match.groupValues[1].toIntOrNull() ?: oldLine
                            newLine = match.groupValues[2].toIntOrNull() ?: newLine
                        }
                        lines += GitDiffLine(DiffLineType.HEADER, text, null, null)
                    }
                    text.startsWith("diff ") || text.startsWith("index ") ||
                        text.startsWith("--- ") || text.startsWith("+++ ") ||
                        text.startsWith("new file") || text.startsWith("deleted file") ||
                        text.startsWith("similarity") || text.startsWith("rename ") ||
                        text.startsWith("old mode") || text.startsWith("new mode") ->
                        lines += GitDiffLine(DiffLineType.HEADER, text, null, null)
                    text.startsWith("+") -> {
                        lines += GitDiffLine(DiffLineType.ADDED, text, null, newLine)
                        newLine++
                    }
                    text.startsWith("-") -> {
                        lines += GitDiffLine(DiffLineType.REMOVED, text, oldLine, null)
                        oldLine++
                    }
                    text.startsWith(" ") -> {
                        lines += GitDiffLine(DiffLineType.CONTEXT, text, oldLine, newLine)
                        oldLine++
                        newLine++
                    }
                }
            }
            lines
        }.fold({ Outcome.Ok(it) }, { Outcome.Failed(describe("diff", it)) })
    }

    suspend fun reset(root: File, hard: Boolean, to: String = "HEAD"): Outcome<Unit> = withContext(Dispatchers.IO) {
        val repo = open(root) ?: return@withContext Outcome.Failed("Not a Git repository")
        runCatching {
            Git(repo).use { git ->
                git.reset()
                    .setMode(if (hard) ResetCommand.ResetType.HARD else ResetCommand.ResetType.MIXED)
                    .setRef(to)
                    .call()
            }
        }.fold({ Outcome.Ok(Unit) }, { Outcome.Failed(describe("reset", it)) })
    }

    suspend fun remotes(root: File): Map<String, String> = withContext(Dispatchers.IO) {
        val repo = open(root) ?: return@withContext emptyMap()
        runCatching { repo.config.getSubsections("remote").associateWith { repo.config.getString("remote", it, "url") ?: "" } }
            .getOrDefault(emptyMap())
    }

    suspend fun addRemote(root: File, name: String, url: String): Outcome<Unit> = withContext(Dispatchers.IO) {
        val repo = open(root) ?: return@withContext Outcome.Failed("Not a Git repository")
        runCatching {
            Git(repo).use { git ->
                if (git.remoteList().call().any { it.name == name }) {
                    git.remoteSetUrl().setRemoteName(name).setRemoteUri(org.eclipse.jgit.transport.URIish(url)).call()
                } else {
                    git.remoteAdd().setName(name).setUri(org.eclipse.jgit.transport.URIish(url)).call()
                }
            }
        }.fold({ Outcome.Ok(Unit) }, { Outcome.Failed(describe("remote", it)) })
    }

    private fun describe(operation: String, error: Throwable): String {
        val detail = when (error) {
            is GitAPIException -> error.message ?: error.javaClass.simpleName
            else -> error.message ?: error.javaClass.simpleName
        }
        return "git $operation failed: $detail"
    }

    companion object {
        private val HUNK = Regex("""@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")
    }
}
