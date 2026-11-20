package dev.titanide.core.git

import dev.titanide.core.git.local.GitRepositoryService
import dev.titanide.core.model.DiffLineType
import dev.titanide.core.model.GitFileStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Exercises the real JGit-backed service: init, stage, commit, branch, log, status and
 * diff all run against an actual on-disk repository.
 */
class GitRepositoryServiceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val service = GitRepositoryService()

    private fun <T> unwrap(outcome: GitRepositoryService.Outcome<T>): T {
        return when (outcome) {
            is GitRepositoryService.Outcome.Ok -> outcome.value
            is GitRepositoryService.Outcome.Failed -> throw AssertionError("git failed: ${outcome.reason}")
        }
    }

    private suspend fun newRepo(): File {
        val root = temp.newFolder("repo")
        unwrap(service.init(root))
        return root
    }

    @Test
    fun `init creates a repository`() = runBlocking {
        val root = newRepo()
        assertTrue(service.isRepository(root))
        assertTrue(File(root, ".git").isDirectory)
    }

    @Test
    fun `non repository reports failure rather than throwing`() = runBlocking {
        val plain = temp.newFolder("plain")
        val outcome = service.status(plain)
        assertTrue(outcome is GitRepositoryService.Outcome.Failed)
    }

    @Test
    fun `untracked file appears in status`() = runBlocking {
        val root = newRepo()
        File(root, "Main.kt").writeText("fun main() {}")
        val entries = unwrap(service.status(root))
        val entry = entries.first { it.path == "Main.kt" }
        assertEquals(GitFileStatus.UNTRACKED, entry.status)
        assertTrue(!entry.staged)
    }

    @Test
    fun `staged file is reported as added`() = runBlocking {
        val root = newRepo()
        File(root, "Main.kt").writeText("fun main() {}")
        unwrap(service.stage(root, listOf("Main.kt")))
        val entries = unwrap(service.status(root))
        val entry = entries.first { it.path == "Main.kt" }
        assertEquals(GitFileStatus.ADDED, entry.status)
        assertTrue(entry.staged)
    }

    @Test
    fun `commit returns a sha and appears in log`() = runBlocking {
        val root = newRepo()
        File(root, "Main.kt").writeText("fun main() {}")
        unwrap(service.stage(root, listOf("Main.kt")))
        val sha = unwrap(service.commit(root, "Initial commit", "Test User", "test@example.com"))
        assertEquals(40, sha.length)

        val log = unwrap(service.log(root))
        assertEquals(1, log.size)
        assertEquals("Initial commit", log.first().message)
        assertEquals("Test User", log.first().author)
    }

    @Test
    fun `empty commit message is rejected`() = runBlocking {
        val root = newRepo()
        File(root, "a.txt").writeText("a")
        unwrap(service.stage(root, listOf("a.txt")))
        assertTrue(service.commit(root, "   ", null, null) is GitRepositoryService.Outcome.Failed)
    }

    @Test
    fun `commit with no staged changes fails cleanly`() = runBlocking {
        val root = newRepo()
        val outcome = service.commit(root, "nothing", null, null)
        assertTrue(outcome is GitRepositoryService.Outcome.Failed)
    }

    @Test
    fun `branch creation and checkout switch the current branch`() = runBlocking {
        val root = newRepo()
        File(root, "a.txt").writeText("a")
        unwrap(service.stage(root, listOf("a.txt")))
        unwrap(service.commit(root, "first", null, null))

        unwrap(service.createBranch(root, "feature/x", checkout = true))
        assertEquals("feature/x", service.currentBranch(root))

        val branches = unwrap(service.branches(root))
        assertTrue(branches.any { it.name == "feature/x" && it.isDefault })
    }

    @Test
    fun `checkout of a missing branch fails cleanly`() = runBlocking {
        val root = newRepo()
        File(root, "a.txt").writeText("a")
        unwrap(service.stage(root, listOf("a.txt")))
        unwrap(service.commit(root, "first", null, null))
        assertTrue(service.checkout(root, "does-not-exist") is GitRepositoryService.Outcome.Failed)
    }

    @Test
    fun `diff shows added lines with new line numbers`() = runBlocking {
        val root = newRepo()
        File(root, "a.txt").writeText("line one\nline two\n")
        unwrap(service.stage(root, listOf("a.txt")))
        unwrap(service.commit(root, "first", null, null))

        File(root, "a.txt").writeText("line one\nline two changed\n")
        val diff = unwrap(service.diff(root))
        val added = diff.filter { it.type == DiffLineType.ADDED }
        assertTrue("expected an added line in $diff", added.isNotEmpty())
        assertTrue(added.any { it.text.contains("line two changed") })
        assertTrue(added.first().newLine != null)
    }

    @Test
    fun `diff of a clean tree is empty`() = runBlocking {
        val root = newRepo()
        File(root, "a.txt").writeText("stable\n")
        unwrap(service.stage(root, listOf("a.txt")))
        unwrap(service.commit(root, "first", null, null))
        assertTrue(unwrap(service.diff(root)).isEmpty())
    }

    @Test
    fun `unstage removes a file from the index`() = runBlocking {
        val root = newRepo()
        File(root, "a.txt").writeText("a")
        unwrap(service.stage(root, listOf("a.txt")))
        unwrap(service.unstage(root, listOf("a.txt")))
        val entries = unwrap(service.status(root))
        val entry = entries.first { it.path == "a.txt" }
        assertTrue(!entry.staged)
    }

    @Test
    fun `remote can be added and read back`() = runBlocking {
        val root = newRepo()
        unwrap(service.addRemote(root, "origin", "https://example.com/repo.git"))
        val remotes = service.remotes(root)
        assertEquals("https://example.com/repo.git", remotes["origin"])
    }

    @Test
    fun `push without credentials fails instead of pretending to succeed`() = runBlocking {
        val root = newRepo()
        File(root, "a.txt").writeText("a")
        unwrap(service.stage(root, listOf("a.txt")))
        unwrap(service.commit(root, "first", null, null))
        unwrap(service.addRemote(root, "origin", "https://invalid.invalid/nope.git"))
        val outcome = service.push(root, "not-a-real-token")
        assertTrue(outcome is GitRepositoryService.Outcome.Failed)
    }

    @Test
    fun `clone into a non-empty directory is refused`() = runBlocking {
        val dest = temp.newFolder("occupied")
        File(dest, "existing.txt").writeText("keep")
        val outcome = service.clone("https://example.com/x.git", dest, null)
        assertTrue(outcome is GitRepositoryService.Outcome.Failed)
        assertEquals("keep", File(dest, "existing.txt").readText())
    }
}
