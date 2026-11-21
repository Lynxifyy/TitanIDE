package dev.titanide.core.opencode

import dev.titanide.core.opencode.net.OpenCodeClient
import dev.titanide.core.opencode.session.OpenCodeConnection
import dev.titanide.core.opencode.session.OpenCodeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The event stream existed but was never subscribed to, so the panel updated only on its
 * 700 ms poll. This exercises the real path: a MockWebServer serves an actual
 * `text/event-stream`, and the session must refresh its activity when a frame for its own
 * session arrives.
 *
 * The stream is deliberately not the only trigger — polling stays as a fallback — so these
 * tests check that an event causes a prompt refresh, not that it is the sole mechanism.
 */
class OpenCodeSessionEventsTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope

    private val sessionId = "ses_abc123"

    /** Number of message-list requests, used to prove an event triggered a fetch. */
    private val messageFetches = CopyOnWriteArrayList<Long>()

    /** Frames the event endpoint will emit, in order. */
    private val frames = CopyOnWriteArrayList<String>()

    /** Set once the stream has been subscribed to, so later fetches are event-driven. */
    @Volatile
    private var streamSubscribed = false

    /** Message fetches that happened after the stream was subscribed to. */
    private fun fetchesAfterSubscribe(): Int {
        val mark = subscribeMark
        return messageFetches.count { it > mark }
    }

    @Volatile
    private var subscribeMark = Long.MAX_VALUE

    @Before
    fun setUp() {
        messageFetches.clear()
        frames.clear()
        streamSubscribed = false
        subscribeMark = Long.MAX_VALUE
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/event") -> {
                        subscribeMark = System.nanoTime()
                        streamSubscribed = true
                        MockResponse()
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(frames.joinToString(""))
                    }

                    path.startsWith("/api/session/$sessionId/message") -> {
                        messageFetches.add(System.nanoTime())
                        MockResponse().setBody(
                            """{"data":[{"id":"msg_1","type":"assistant",
                               "time":{"created":1,"completed":2},
                               "content":[{"type":"text","text":"done"}]}]}""",
                        )
                    }

                    path.startsWith("/api/session/$sessionId/permission") ->
                        MockResponse().setBody("""{"data":[]}""")

                    path.startsWith("/api/session/$sessionId/diff") ->
                        MockResponse().setBody("""{"data":[]}""")

                    path.startsWith("/api/session/$sessionId") ->
                        MockResponse().setBody(
                            """{"data":{"id":"$sessionId","title":"TITAN: demo"}}""",
                        )

                    path.startsWith("/api/app") || path.startsWith("/api/info") ->
                        MockResponse().setBody("""{"version":"1.0.0"}""")

                    path.startsWith("/api/session") ->
                        MockResponse().setBody(
                            """{"data":{"id":"$sessionId","title":"TITAN: demo"}}""",
                        )

                    else -> MockResponse().setResponseCode(404).setBody("""{"error":"no route"}""")
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
    fun `connecting subscribes to the event stream`() = runBlocking {
        val session = newSession()
        session.connect(server.url("/").toString(), null, directory = "/work/demo")

        val connected = withTimeoutOrNull(5_000) {
            while (session.state.value.connection !is OpenCodeConnection.Connected) delay(20)
            true
        }
        assertTrue("session must reach Connected, was ${session.state.value.connection}", connected == true)

        val subscribed = withTimeoutOrNull(5_000) {
            while (!streamSubscribed) delay(20)
            true
        }
        assertTrue("the event endpoint must be requested", subscribed == true)
        session.disconnect()
    }

    @Test
    fun `an event for this session triggers a refresh`() = runBlocking {
        // The frame is present from the start: the stream is consumed as it is read.
        frames.add(
            """event: message.updated
data: {"type":"message.updated","properties":{"sessionID":"$sessionId"}}

""",
        )
        val session = newSession()
        session.connect(server.url("/").toString(), null, directory = "/work/demo")

        // The initial refresh happens before the stream exists, so only a fetch recorded
        // after the subscription can be attributed to the event.
        val refreshed = withTimeoutOrNull(5_000) {
            while (fetchesAfterSubscribe() == 0) delay(20)
            true
        }
        assertTrue("an event must cause the message list to be fetched", refreshed == true)

        val shown = withTimeoutOrNull(5_000) {
            while (session.state.value.activity.isEmpty()) delay(20)
            true
        }
        assertTrue("the fetched turn must reach the panel", shown == true)
        val activity = session.state.value.activity
        assertNotNull(activity)
        assertEquals("the assistant turn from the server must be shown", 1, activity.size)
        session.disconnect()
    }

    @Test
    fun `an event for another session is ignored`() = runBlocking {
        frames.add(
            """event: message.updated
data: {"type":"message.updated","properties":{"sessionID":"ses_other"}}

""",
        )
        val session = newSession()
        session.connect(server.url("/").toString(), null, directory = "/work/demo")

        val subscribed = withTimeoutOrNull(5_000) {
            while (!streamSubscribed) delay(20)
            true
        }
        assertTrue("the stream must be subscribed before the assertion", subscribed == true)
        // Give the stream time to deliver the frame; it belongs to another session.
        delay(1_500)
        assertEquals(
            "an unrelated session's event must not trigger a refresh",
            0,
            fetchesAfterSubscribe(),
        )
        session.disconnect()
    }

    @Test
    fun `a failed stream is reported instead of stalling silently`() = runBlocking {
        frames.clear()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/event") -> MockResponse().setResponseCode(503)
                    path.startsWith("/api/session/$sessionId/message") ->
                        MockResponse().setBody("""{"data":[]}""")
                    path.startsWith("/api/session/$sessionId/permission") ->
                        MockResponse().setBody("""{"data":[]}""")
                    path.startsWith("/api/session/$sessionId/diff") ->
                        MockResponse().setBody("""{"data":[]}""")
                    else -> MockResponse().setBody(
                        """{"data":{"id":"$sessionId","title":"TITAN: demo"}}""",
                    )
                }
            }
        }
        val session = newSession()
        session.connect(server.url("/").toString(), null, directory = "/work/demo")

        val reported = withTimeoutOrNull(5_000) {
            while (session.state.value.lastError == null) delay(20)
            true
        }
        assertTrue(
            "a rejected stream must surface an error, state was ${session.state.value}",
            reported == true,
        )
        session.disconnect()
    }
}
