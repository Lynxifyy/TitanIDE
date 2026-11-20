package dev.titanide.core.opencode

import dev.titanide.core.opencode.model.CreateSessionBody
import dev.titanide.core.opencode.model.PermissionDecision
import dev.titanide.core.opencode.model.PermissionReplyBody
import dev.titanide.core.opencode.net.OpenCodeClient
import dev.titanide.core.opencode.net.OpenCodeException
import dev.titanide.core.opencode.session.AgentActivity
import dev.titanide.core.opencode.session.MessageParser
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Protocol tests against MockWebServer.
 *
 * These assert the exact paths, verbs and JSON bodies from the published OpenCode
 * OpenAPI document. If someone later "simplifies" the client to a different shape, these
 * fail — which is the point, because a wrong endpoint is indistinguishable from a broken
 * agent at runtime.
 */
class OpenCodeClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenCodeClient
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenCodeClient(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `info parses the server envelope`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"version":"0.0.1","pid":4242,"hostname":"build-01","urls":["http://localhost:4096"],
                   "paths":{"tmp":"/tmp"},"capabilities":{"persistentPty":true}}""",
            ),
        )

        val info = client.info()

        assertEquals("0.0.1", info.version)
        assertEquals(4242, info.pid)
        assertEquals("build-01", info.hostname)
        assertTrue(info.capabilities?.persistentPty == true)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/info", recorded.path)
    }

    @Test
    fun `probe returns null when reachable and a reason when not`() = runTest {
        server.enqueue(MockResponse().setBody("""{"version":"1.2.3","pid":1}"""))
        assertNull(client.probe())

        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))
        val reason = client.probe()
        assertNotNull(reason)
        assertTrue("reason should mention the status: $reason", reason!!.contains("500"))
    }

    @Test
    fun `createSession posts title and unwraps data`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"id":"ses_abc","title":"TITAN: demo"}}"""))

        val session = client.createSession(CreateSessionBody(title = "TITAN: demo"))

        assertEquals("ses_abc", session.id)
        assertEquals("TITAN: demo", session.title)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/session", recorded.path)
        val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("TITAN: demo", body["title"].toString().trim('"'))
    }

    @Test
    fun `prompt uses the documented prompt endpoint and sends text`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"id":"msg_1","type":"user"}}"""))

        client.prompt("ses_abc", "add a test for the parser")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/session/ses_abc/prompt", recorded.path)
        val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("add a test for the parser", body["text"].toString().trim('"'))
    }

    @Test
    fun `interrupt is a POST with no body requirements`() = runTest {
        server.enqueue(MockResponse().setBody("""{}"""))

        client.interrupt("ses_abc")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/session/ses_abc/interrupt", recorded.path)
    }

    @Test
    fun `permission reply sends the decision enum value`() = runTest {
        server.enqueue(MockResponse().setBody("""{}"""))

        client.replyPermission("ses_abc", "per_1", PermissionReplyBody(PermissionDecision.ONCE))

        val recorded = server.takeRequest()
        assertEquals("/api/session/ses_abc/permission/per_1/reply", recorded.path)
        val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("once", body["decision"].toString().trim('"'))
    }

    @Test
    fun `permission reject maps to reject`() = runTest {
        server.enqueue(MockResponse().setBody("""{}"""))

        client.replyPermission("s", "p", PermissionReplyBody(PermissionDecision.REJECT))

        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("reject", body["decision"].toString().trim('"'))
    }

    @Test
    fun `diff parses the FileDiff list`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"data":[{"file":"app/src/Main.kt","patch":"@@ -1 +1 @@","additions":3,
                   "deletions":1,"status":"modified"}]}""",
            ),
        )

        val diffs = client.diff("ses_abc")

        assertEquals(1, diffs.size)
        assertEquals("app/src/Main.kt", diffs[0].file)
        assertEquals(3, diffs[0].additions)
        assertFalse(diffs[0].isNew)
    }

    @Test
    fun `http error surfaces the server message instead of a generic failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Session not found"}"""))

        val failure = runCatching { client.getSession("missing") }.exceptionOrNull()

        assertTrue(failure is OpenCodeException)
        assertTrue(
            "should include the server text, was: ${failure?.message}",
            failure!!.message!!.contains("Session not found"),
        )
        assertEquals(404, (failure as OpenCodeException).statusCode)
    }

    @Test
    fun `unreachable server reports the host rather than hanging`() = runTest {
        server.shutdown()
        val deadClient = OpenCodeClient(server.url("/").toString())

        val failure = runCatching { deadClient.info() }.exceptionOrNull()

        assertTrue(failure is OpenCodeException)
        assertTrue(failure!!.message!!.contains("Cannot reach OpenCode server"))
    }

    @Test
    fun `bearer token is attached when configured`() = runTest {
        val authed = OpenCodeClient(server.url("/").toString(), token = "secret-token")
        server.enqueue(MockResponse().setBody("""{"version":"1","pid":1}"""))

        authed.info()

        assertEquals("Bearer secret-token", server.takeRequest().getHeader("Authorization"))
    }
}

/**
 * Parser tests. The payloads below are trimmed copies of the real `Session.Message.Info`
 * shapes from the OpenAPI document.
 */
class MessageParserTest {

    private fun parse(json: String): List<AgentActivity> =
        MessageParser.parse(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `user message becomes a user activity`() {
        val result = parse("""{"id":"m1","type":"user","time":{"created":100},"text":"fix the build"}""")

        assertEquals(1, result.size)
        val user = result[0] as AgentActivity.UserMessage
        assertEquals("fix the build", user.text)
        assertEquals(100L, user.at)
    }

    @Test
    fun `assistant text and tool parts are both surfaced`() {
        val result = parse(
            """{"id":"m2","type":"assistant","time":{"created":200,"completed":300},
                "content":[
                  {"type":"text","text":"I will read the file."},
                  {"type":"tool","id":"t1","name":"read","state":{"status":"completed","output":"file body"}}
                ]}""",
        )

        assertEquals(2, result.size)
        val text = result[0] as AgentActivity.AssistantText
        assertEquals("I will read the file.", text.text)
        assertFalse("completed turn should not look streaming", text.streaming)

        val tool = result[1] as AgentActivity.ToolCall
        assertEquals("read", tool.name)
        assertEquals("completed", tool.state)
        assertTrue(tool.isDone)
        assertFalse(tool.isRunning)
    }

    @Test
    fun `assistant turn without completed timestamp is streaming`() {
        val result = parse(
            """{"id":"m3","type":"assistant","time":{"created":10},
                "content":[{"type":"text","text":"working"}]}""",
        )

        assertTrue((result[0] as AgentActivity.AssistantText).streaming)
    }

    @Test
    fun `running tool is reported as running`() {
        val result = parse(
            """{"id":"m4","type":"assistant","time":{"created":10},
                "content":[{"type":"tool","id":"t","name":"bash","state":{"status":"running"}}]}""",
        )

        val tool = result[0] as AgentActivity.ToolCall
        assertTrue(tool.isRunning)
    }

    @Test
    fun `error tool state is flagged`() {
        val result = parse(
            """{"id":"m5","type":"assistant","time":{"created":10,"completed":20},
                "content":[{"type":"tool","id":"t","name":"bash","state":{"status":"error","error":"exit 1"}}]}""",
        )

        val tool = result[0] as AgentActivity.ToolCall
        assertTrue(tool.isError)
        assertEquals("exit 1", tool.error)
    }

    @Test
    fun `shell message carries command exit and output`() {
        val result = parse(
            """{"id":"m6","type":"shell","time":{"created":5},"shellID":"sh1",
                "command":"./gradlew assembleDebug","status":"exited","exit":0,
                "output":{"output":"BUILD SUCCESSFUL","cursor":15,"size":15,"truncated":false}}""",
        )

        val shell = result[0] as AgentActivity.ShellCommand
        assertEquals("./gradlew assembleDebug", shell.command)
        assertEquals(0, shell.exit)
        assertEquals("BUILD SUCCESSFUL", shell.output)
    }

    @Test
    fun `unknown message type does not throw and does not lose the transcript`() {
        val result = parse("""{"id":"m7","type":"someFutureType","time":{"created":1}}""")

        assertEquals(1, result.size)
        assertEquals("someFutureType", (result[0] as AgentActivity.Other).type)
    }

    @Test
    fun `reasoning parts are preserved`() {
        val result = parse(
            """{"id":"m8","type":"assistant","time":{"created":1,"completed":2},
                "content":[{"type":"reasoning","text":"thinking about imports"}]}""",
        )

        assertEquals("thinking about imports", (result[0] as AgentActivity.Reasoning).text)
    }

    @Test
    fun `malformed message is skipped without killing the list`() {
        val messages = listOf(
            Json.parseToJsonElement("""{"id":"ok","type":"user","time":{"created":1},"text":"hi"}""").jsonObject,
            Json.parseToJsonElement("""{"noId":true}""").jsonObject,
            Json.parseToJsonElement("""{"id":"ok2","type":"user","time":{"created":2},"text":"there"}""").jsonObject,
        )

        val parsed = MessageParser.parseAll(messages)

        assertEquals(2, parsed.size)
        assertEquals("hi", (parsed[0] as AgentActivity.UserMessage).text)
        assertEquals("there", (parsed[1] as AgentActivity.UserMessage).text)
    }
}
