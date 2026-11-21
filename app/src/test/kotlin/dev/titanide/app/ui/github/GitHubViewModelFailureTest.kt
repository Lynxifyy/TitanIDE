package dev.titanide.app.ui.github

import androidx.test.core.app.ApplicationProvider
import dev.titanide.app.di.TitanContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The repository browser must tell "this repository has no files" apart from "GitHub would not
 * answer". Collapsing both into an empty list makes an expired token or a rate limit look like an
 * empty repository, which invites the user to conclude their code is gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GitHubViewModelFailureTest {

    private lateinit var server: ServerSocket
    private val requests: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    @Before
    fun start() {
        Dispatchers.setMain(Dispatchers.Default)
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                thread(isDaemon = true) {
                    socket.use {
                        val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                        val requestLine = reader.readLine().orEmpty()
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                        }
                        val path = requestLine.split(' ').getOrNull(1).orEmpty()
                        requests += requestLine
                        val (code, body) = respond(path)
                        val bytes = body.toByteArray()
                        val head = "HTTP/1.1 $code Nope\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        it.getOutputStream().apply {
                            write(head.toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
        }
    }

    @After
    fun stop() {
        server.close()
        Dispatchers.resetMain()
    }

    /** The repository itself resolves; every listing inside it fails as GitHub does when limited. */
    private fun respond(path: String): Pair<Int, String> = when (path) {
        "/user" -> 200 to """{"login":"me","id":1}"""
        "/repos/me/limited" -> 200 to """
            {"id":1,"name":"limited","full_name":"me/limited","default_branch":"main",
             "clone_url":"https://example.test/me/limited.git","owner":{"login":"me"}}
        """.trimIndent()
        else -> 403 to """{"message":"API rate limit exceeded"}"""
    }

    private fun container() = TitanContainer(
        ApplicationProvider.getApplicationContext(),
        gitHubApiBase = "http://127.0.0.1:${server.localPort}",
    )

    @Test
    fun `a failing listing is reported instead of showing an empty repository`() = runBlocking {
        val container = container()
        // The container restores the saved token asynchronously; setting one before that
        // finishes would be overwritten by the restore.
        withTimeout(15_000) { container.gitHubAuth.first { it.isSettled } }
        container.setGitHubToken("token")

        val viewModel = GitHubViewModel(container, repoFullName = "me/limited")
        val state = withTimeout(15_000) {
            viewModel.detail.first { !it.loading && (it.error != null || it.entries.isNotEmpty()) }
        }

        assertTrue(
            "the 403 must reach the screen, saw entries=${state.entries.size} error=${state.error}",
            state.error?.contains("403") == true,
        )
        assertTrue("the file list must stay empty", state.entries.isEmpty())
    }
}
