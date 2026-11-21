package dev.titanide.core.git.api

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Exercises the client against a real socket rather than a stubbed client, so the URL that is
 * actually requested is part of what is under test.
 */
class GitHubApiClientTest {

    private lateinit var server: ServerSocket
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun start() {
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
                        requests += path
                        val (code, body) = respond(path)
                        val bytes = body.toByteArray()
                        val head = "HTTP/1.1 $code OK\r\n" +
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
    fun stop() = server.close()

    private fun respond(path: String): Pair<Int, String> = when (path) {
        "/repos/other/project" -> 200 to """
            {"id":7,"name":"project","full_name":"other/project","default_branch":"trunk",
             "language":"Rust","clone_url":"https://example.test/other/project.git",
             "owner":{"login":"other"}}
        """.trimIndent()
        "/user/repos" -> 200 to """
            [{"id":1,"name":"mine","full_name":"me/mine","default_branch":"main",
              "language":"Kotlin","clone_url":"https://example.test/me/mine.git",
              "owner":{"login":"me"}}]
        """.trimIndent()
        else -> 404 to """{"message":"Not Found"}"""
    }

    private fun client() = GitHubApiClient(
        tokenProvider = { "token" },
        apiBase = "http://127.0.0.1:${server.localPort}",
    )

    @Test
    fun `a repository outside the user's list is fetched, not substituted`() = runBlocking {
        val repo = client().getRepository("other", "project").getOrThrow()

        assertEquals("other/project", repo.fullName)
        assertEquals("Rust", repo.language)
        assertEquals("trunk", repo.defaultBranch)
        assertTrue(
            "the requested repository must be the one fetched, saw $requests",
            requests.contains("/repos/other/project"),
        )
    }

    @Test
    fun `a missing repository fails instead of returning something else`() = runBlocking {
        val result = client().getRepository("nobody", "nothing")
        assertTrue("404 must surface as a failure", result.isFailure)
    }
}
