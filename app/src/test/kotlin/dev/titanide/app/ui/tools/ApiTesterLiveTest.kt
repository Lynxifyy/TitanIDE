package dev.titanide.app.ui.tools

import dev.titanide.app.testing.CloseContainersRule
import dev.titanide.app.testing.TestContainers
import dev.titanide.app.testing.ViewModelTracker
import dev.titanide.app.testing.resetMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/**
 * The API tester claims to send real requests. These cases run it against a real HTTP server
 * on loopback, so the response status, headers and body come from a socket rather than from
 * the view model's own imagination.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApiTesterLiveTest {

    @get:Rule
    val containers = CloseContainersRule()

    private val container = TestContainers.create()

    /**
     * A minimal HTTP/1.1 server over a plain socket. The Android SDK has no
     * `com.sun.net.httpserver`, so the test speaks the protocol itself; it is a few lines and
     * keeps the assertion honest, because the bytes really cross a socket.
     */
    private class TinyHttpServer {
        private val socket = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        private val thread = Thread {
            while (!socket.isClosed) {
                runCatching { serve(socket.accept()) }
            }
        }.apply { isDaemon = true; start() }

        val port: Int get() = socket.localPort

        private fun serve(client: Socket) {
            client.use { connection ->
                val reader = BufferedReader(InputStreamReader(connection.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val method = requestLine.substringBefore(' ')
                val path = requestLine.split(' ').getOrNull(1).orEmpty()
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                val sentBody = if (contentLength > 0) {
                    val buffer = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val count = reader.read(buffer, read, contentLength - read)
                        if (count < 0) break
                        read += count
                    }
                    String(buffer, 0, read)
                } else {
                    ""
                }
                val (status, body) = when (path) {
                    "/hello" -> 200 to """{"greeting":"titan"}"""
                    "/echo" -> 200 to """{"method":"$method","received":${sentBody.ifEmpty { "\"\"" }}}"""
                    else -> 404 to "nope"
                }
                val payload = body.toByteArray()
                val head = buildString {
                    append("HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\n")
                    append("Content-Type: application/json\r\n")
                    append("X-Titan-Test: present\r\n")
                    append("Content-Length: ${payload.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray()
                connection.getOutputStream().apply {
                    write(head)
                    write(payload)
                    flush()
                }
            }
        }

        fun stop() = runCatching { socket.close() }
    }

    private lateinit var server: TinyHttpServer

    @Before
    fun startServer() {
        Dispatchers.setMain(Dispatchers.Default)
        server = TinyHttpServer()
    }

    @After
    fun tearDown() {
        server.stop()
        ViewModelTracker.clearAll()
        resetMainDispatcher()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

    private fun ApiTesterViewModel.sendAndAwait(): HttpResult {
        send()
        return runBlocking {
            withTimeout(30_000) { state.first { !it.running && it.result != null } }.result!!
        }
    }

    @Test
    fun `a GET returns the real status, body and headers`() {
        val viewModel = ViewModelTracker.track(ApiTesterViewModel(container))
        viewModel.setUrl(url("/hello"))

        val result = viewModel.sendAndAwait()

        assertEquals(200, result.status)
        assertEquals("""{"greeting":"titan"}""", result.body)
        assertTrue(
            "response headers must come from the server, got: ${result.headers}",
            result.headers.any {
                it.first.equals("X-Titan-Test", ignoreCase = true) && it.second == "present"
            },
        )
    }

    @Test
    fun `a 404 is reported as a 404, not as a failure to send`() {
        val viewModel = ViewModelTracker.track(ApiTesterViewModel(container))
        viewModel.setUrl(url("/missing"))

        val result = viewModel.sendAndAwait()

        assertEquals("the server answered, so the status must be the server's", 404, result.status)
        assertEquals("nope", result.body)
        assertTrue("an answered request has no transport error", result.error == null)
    }

    @Test
    fun `a POST sends its body to the server`() {
        val viewModel = ViewModelTracker.track(ApiTesterViewModel(container))
        viewModel.setUrl(url("/echo"))
        viewModel.setMethod("POST")
        viewModel.setBody("""{"from":"titan"}""")

        val result = viewModel.sendAndAwait()

        assertEquals(200, result.status)
        assertTrue(
            "the server must receive the method and body, got: ${result.body}",
            result.body.contains("POST") && result.body.contains("from"),
        )
    }

    @Test
    fun `an unreachable host is reported as an error`() {
        val viewModel = ViewModelTracker.track(ApiTesterViewModel(container))
        // Port 1 on loopback has no listener, so the connection is refused rather than answered.
        viewModel.setUrl("http://127.0.0.1:1/nothing")

        val result = viewModel.sendAndAwait()

        assertEquals("no response was produced", 0, result.status)
        assertNotNull("a transport failure must carry a reason", result.error)
    }
}
