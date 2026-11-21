package dev.titanide.core.opencode

import dev.titanide.core.opencode.net.OpenCodeClient
import dev.titanide.core.opencode.net.OpenCodeException
import dev.titanide.core.opencode.session.OpenCodeConnection
import dev.titanide.core.opencode.session.OpenCodeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `POST /api/session` accepts no directory, so a fresh session runs wherever the server was
 * started. The client collected a project directory from the user and never sent it, which
 * meant the agent edited a different tree from the one the build used. These tests pin the
 * real fix: the documented `session.move` call, with the directory in the body.
 */
class OpenCodeSessionMoveTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val sessionId = "ses_move1"
    private val moves = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        moves.clear()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/event") -> MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody("")

                    path.startsWith("/api/session/$sessionId/move") -> {
                        moves.add(request.body.readUtf8())
                        MockResponse().setResponseCode(204)
                    }

                    path.startsWith("/api/session/$sessionId/message") ->
                        MockResponse().setBody("""{"data":[]}""")
                    path.startsWith("/api/session/$sessionId/permission") ->
                        MockResponse().setBody("""{"data":[]}""")
                    path.startsWith("/api/session/$sessionId/diff") ->
                        MockResponse().setBody("""{"data":[]}""")
                    path.startsWith("/api/session/$sessionId") ->
                        MockResponse().setBody("""{"data":{"id":"$sessionId","title":"TITAN: demo"}}""")
                    path.startsWith("/api/info") ->
                        MockResponse().setBody("""{"version":"1.0.0"}""")
                    path.startsWith("/api/session") ->
                        MockResponse().setBody("""{"data":{"id":"$sessionId","title":"TITAN: demo"}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun newSession() = OpenCodeSession(
        scope = scope,
        clientFactory = { url, token -> OpenCodeClient(url, token) },
        pollIntervalMs = 60_000,
    )

    @Test
    fun `opening a session binds it to the requested directory`() = runBlocking {
        val session = newSession()
        session.connect(server.url("/").toString(), null, directory = "/srv/work/demo")

        val moved = withTimeoutOrNull(5_000) {
            while (moves.isEmpty()) delay(20)
            true
        }
        assertTrue("the session must be moved to the project directory", moved == true)

        val body = Json.parseToJsonElement(moves.first()).jsonObject
        assertEquals(
            "the directory must be sent verbatim; the server resolves it",
            "/srv/work/demo",
            body["directory"]?.jsonPrimitive?.content,
        )
        session.disconnect()
    }

    @Test
    fun `a blank directory does not move the session`() = runBlocking {
        val session = newSession()
        session.connect(server.url("/").toString(), null, directory = "  ")

        val connected = withTimeoutOrNull(5_000) {
            while (session.state.value.connection !is OpenCodeConnection.Connected) delay(20)
            true
        }
        assertTrue(connected == true)
        assertEquals("a blank directory must not be sent", 0, moves.size)
        session.disconnect()
    }

    @Test
    fun `a rejected move is reported and does not stop the session`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/event") -> MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody("")
                    path.startsWith("/api/session/$sessionId/move") ->
                        MockResponse().setResponseCode(400).setBody(
                            """{"_tag":"InvalidRequestError","message":"no such directory"}""",
                        )
                    path.startsWith("/api/session/$sessionId/message") ->
                        MockResponse().setBody("""{"data":[]}""")
                    path.startsWith("/api/session/$sessionId/permission") ->
                        MockResponse().setBody("""{"data":[]}""")
                    path.startsWith("/api/session/$sessionId/diff") ->
                        MockResponse().setBody("""{"data":[]}""")
                    path.startsWith("/api/session/$sessionId") ->
                        MockResponse().setBody("""{"data":{"id":"$sessionId","title":"TITAN: demo"}}""")
                    path.startsWith("/api/info") ->
                        MockResponse().setBody("""{"version":"1.0.0"}""")
                    path.startsWith("/api/session") ->
                        MockResponse().setBody("""{"data":{"id":"$sessionId","title":"TITAN: demo"}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val session = newSession()
        session.connect(server.url("/").toString(), null, directory = "/nope")

        val reported = withTimeoutOrNull(5_000) {
            while (session.state.value.notice == null) delay(20)
            true
        }
        assertTrue("a rejected move must be surfaced, state was ${session.state.value}", reported == true)
        // A successful refresh must not erase the warning; that is why it is not lastError.
        delay(500)
        assertTrue(
            "the warning must survive a refresh, state was ${session.state.value}",
            session.state.value.notice != null,
        )
        assertTrue(
            "the session must still be usable, was ${session.state.value.connection}",
            session.state.value.connection is OpenCodeConnection.Connected,
        )
        session.disconnect()
    }

    @Test
    fun `the client posts the documented move body`() = runBlocking {
        val client = OpenCodeClient(server.url("/").toString())
        client.moveSession("ses_x", "/tmp/project")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/session/ses_x/move", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("/tmp/project", body["directory"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a move failure carries the server's own message`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                // The spec's error envelope, not a made-up shape.
                MockResponse().setResponseCode(404).setBody(
                    """{"_tag":"SessionNotFoundError","sessionID":"ses_missing",
                       "message":"session not found"}""",
                )
        }
        val client = OpenCodeClient(server.url("/").toString())
        val failure = runCatching { client.moveSession("ses_missing", "/tmp/x") }.exceptionOrNull()
        assertTrue("expected an OpenCodeException, got $failure", failure is OpenCodeException)
        assertTrue(
            "the server's message must survive, was ${failure?.message}",
            failure?.message?.contains("session not found") == true,
        )
    }
}
