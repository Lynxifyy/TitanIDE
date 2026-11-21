package dev.titanide.core.ai

import dev.titanide.core.ai.agent.AgentEvent
import dev.titanide.core.ai.agent.AgentRunner
import dev.titanide.core.ai.agent.AgentToolbox
import dev.titanide.core.ai.agent.ToolPolicy
import dev.titanide.core.ai.agent.AgentTool
import dev.titanide.core.ai.agent.ToolResult
import dev.titanide.core.ai.providers.AnthropicProvider
import dev.titanide.core.ai.providers.OpenAiCompatibleProvider
import dev.titanide.core.ai.context.ContextBuilder
import dev.titanide.core.ai.providers.UnconfiguredProvider
import dev.titanide.core.data.repo.FileTreeRepository
import dev.titanide.core.model.BuildSystem
import dev.titanide.core.model.Language
import dev.titanide.core.model.Project
import dev.titanide.core.model.ProjectCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EditExtractorTest {

    @Test
    fun `fenced block with a path becomes a proposed edit`() {
        val reply = """
            Here is the fix.

            ```src/main/kotlin/Main.kt
            fun main() = println("fixed")
            ```
        """.trimIndent()
        val edits = EditExtractor.extract(reply)
        assertEquals(1, edits.size)
        assertEquals("src/main/kotlin/Main.kt", edits.first().filePath)
        assertTrue(edits.first().newContent.contains("fixed"))
    }

    @Test
    fun `multiple files are extracted`() {
        val reply = """
            ```a/B.kt
            class B
            ```
            ```a/C.kt
            class C
            ```
        """.trimIndent()
        assertEquals(2, EditExtractor.extract(reply).size)
    }

    @Test
    fun `plain code fence without a path is ignored`() {
        val reply = """
            ```kotlin
            fun main() {}
            ```
        """.trimIndent()
        assertTrue(EditExtractor.extract(reply).isEmpty())
    }

    @Test
    fun `prose before blocks is separated`() {
        val reply = "I changed two things.\n\n```a/B.kt\nclass B\n```"
        assertEquals("I changed two things.", EditExtractor.proseBeforeBlocks(reply))
    }

    @Test
    fun `path with spaces is rejected`() {
        val reply = "```not a path.kt\nx\n```"
        assertTrue(EditExtractor.extract(reply).isEmpty())
    }

    @Test
    fun `path traversal in a fence is rejected by the extractor`() {
        assertTrue(EditExtractor.extract("```../../etc/passwd\nroot:x\n```").isEmpty())
    }
}

class OpenAiCompatibleProviderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun provider(key: String? = "test-key") = OpenAiCompatibleProvider(
        displayName = "Test",
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKeyProvider = { key },
        models = listOf("test-model"),
    )

    @Test
    fun `successful response is parsed with token usage`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"model":"test-model","choices":[{"message":{"role":"assistant","content":"hello there"},"finish_reason":"stop"}],"usage":{"prompt_tokens":11,"completion_tokens":7}}
                """.trimIndent(),
            ),
        )
        val result = provider().chat(
            ChatRequest("test-model", listOf(ChatMessage(ChatMessage.Role.USER, "hi"))),
        )
        assertTrue(result is ChatResult.Success)
        val response = (result as ChatResult.Success).response
        assertEquals("hello there", response.content)
        assertEquals(11, response.promptTokens)
        assertEquals(7, response.completionTokens)
        assertEquals(18, response.totalTokens)
    }

    @Test
    fun `request carries the bearer token and model`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
        provider("secret-token").chat(ChatRequest("test-model", listOf(ChatMessage(ChatMessage.Role.USER, "hi"))))
        val recorded = server.takeRequest()
        assertEquals("Bearer secret-token", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"model\":\"test-model\""))
        assertTrue(body.contains("\"role\":\"user\""))
    }

    @Test
    fun `error body message is surfaced`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"model not found"}}"""),
        )
        val result = provider().chat(ChatRequest("bad", listOf(ChatMessage(ChatMessage.Role.USER, "hi"))))
        assertTrue(result is ChatResult.Failure)
        assertEquals("model not found", (result as ChatResult.Failure).reason)
    }

    @Test
    fun `rate limit is marked retryable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        val result = provider().chat(ChatRequest("m", listOf(ChatMessage(ChatMessage.Role.USER, "hi"))))
        assertTrue(result is ChatResult.Failure)
        assertTrue((result as ChatResult.Failure).retryable)
    }

    @Test
    fun `unreachable endpoint fails instead of hanging`() = runBlocking {
        server.shutdown()
        val result = provider().chat(ChatRequest("m", listOf(ChatMessage(ChatMessage.Role.USER, "hi"))))
        assertTrue(result is ChatResult.Failure)
    }

    @Test
    fun `blank endpoint fails immediately`() = runBlocking {
        val result = OpenAiCompatibleProvider("x", "", { null }).chat(
            ChatRequest("m", listOf(ChatMessage(ChatMessage.Role.USER, "hi"))),
        )
        assertTrue(result is ChatResult.Failure)
        assertTrue((result as ChatResult.Failure).reason.contains("endpoint"))
    }

    @Test
    fun `no api key means no authorization header`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
        provider(null).chat(ChatRequest("m", listOf(ChatMessage(ChatMessage.Role.USER, "hi"))))
        assertEquals(null, server.takeRequest().getHeader("Authorization"))
    }
}

class AnthropicProviderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun provider(key: String? = "sk-test") = AnthropicProvider(
        baseUrl = server.url("/").toString().trimEnd('/'),
        apiKeyProvider = { key },
        models = listOf("claude-test"),
    )

    @Test
    fun `system prompt is separated from messages`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"model":"claude-test","content":[{"type":"text","text":"answer"}],"usage":{"input_tokens":5,"output_tokens":3}}""",
            ),
        )
        val result = provider().chat(
            ChatRequest(
                "claude-test",
                listOf(
                    ChatMessage(ChatMessage.Role.SYSTEM, "be terse"),
                    ChatMessage(ChatMessage.Role.USER, "hi"),
                ),
            ),
        )
        assertTrue(result is ChatResult.Success)
        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"system\":\"be terse\""))
        assertTrue(!body.contains("\"role\":\"system\""))
        assertEquals("sk-test", request.getHeader("x-api-key"))
        assertNotNull(request.getHeader("anthropic-version"))
    }

    @Test
    fun `missing api key is refused before any request`() = runBlocking {
        val result = provider(null).chat(ChatRequest("m", listOf(ChatMessage(ChatMessage.Role.USER, "hi"))))
        assertTrue(result is ChatResult.Failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `empty conversation is refused`() = runBlocking {
        val result = provider().chat(ChatRequest("m", listOf(ChatMessage(ChatMessage.Role.SYSTEM, "only system"))))
        assertTrue(result is ChatResult.Failure)
    }

    @Test
    fun `token usage maps input to prompt`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"content":[{"type":"text","text":"ok"}],"usage":{"input_tokens":9,"output_tokens":4}}""",
            ),
        )
        val result = provider().chat(ChatRequest("m", listOf(ChatMessage(ChatMessage.Role.USER, "hi"))))
        val response = (result as ChatResult.Success).response
        assertEquals(9, response.promptTokens)
        assertEquals(4, response.completionTokens)
    }
}

class UnconfiguredProviderTest {

    @Test
    fun `unconfigured provider explains how to configure it`() = runBlocking {
        val result = UnconfiguredProvider().chat(ChatRequest("m", emptyList()))
        assertTrue(result is ChatResult.Failure)
        val reason = (result as ChatResult.Failure).reason
        assertTrue(reason.contains("Settings"))
        assertTrue(reason.contains("Ollama") || reason.contains("local"))
    }
}

/** A scripted provider so the agent loop can be tested without a network. */
private class ScriptedProvider(private val replies: List<String>) : AiProvider {
    override val id = "scripted"
    override val displayName = "Scripted"
    override val requiresApiKey = false
    override val supportsStreaming = false
    override val models = listOf("scripted")
    private var index = 0

    override suspend fun chat(request: ChatRequest): ChatResult {
        val reply = replies.getOrElse(index) { "DONE: out of script" }
        index++
        return ChatResult.Success(ChatResponse(reply, "scripted"))
    }
}

private class FakeTool(
    override val name: String,
    override val description: String,
    private val result: ToolResult,
) : AgentTool {
    var invocations = 0
        private set

    override suspend fun invoke(arguments: JsonObject): ToolResult {
        invocations++
        return result
    }
}

class AgentRunnerTest {

    private fun runner(
        replies: List<String>,
        tools: List<AgentTool>,
        maxSteps: Int = 6,
        approve: suspend (String, JsonObject) -> Boolean = { _, _ -> true },
    ): AgentRunner {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val provider = ScriptedProvider(replies)
        return AgentRunner(scope, { provider }, "scripted", AgentToolbox(tools, approve), maxSteps)
    }

    private suspend fun awaitFinished(runner: AgentRunner, timeoutMs: Long = 5000): List<AgentEvent> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!runner.state.value.running) return runner.state.value.steps
            kotlinx.coroutines.delay(20)
        }
        return runner.state.value.steps
    }

    @Test
    fun `agent that verifies with a passing build reports verified completion`() = runBlocking {
        val build = FakeTool("run_build", "build", ToolResult(true, "BUILD SUCCESSFUL"))
        val runner = runner(
            listOf(
                "TOOL: run_build\nARGS: {}",
                "DONE: built and verified",
            ),
            listOf(build),
        )
        runner.start("make it compile", null)
        val steps = awaitFinished(runner)

        assertTrue("steps=$steps", steps.any { it is AgentEvent.Completed && it.verified })
        assertEquals(1, build.invocations)
    }

    @Test
    fun `agent cannot claim success without running a build`() = runBlocking {
        val runner = runner(listOf("DONE: all finished, trust me"), emptyList())
        runner.start("do something", null)
        val steps = awaitFinished(runner)

        assertTrue("expected a Blocked event, got $steps", steps.any { it is AgentEvent.Blocked })
        assertTrue(steps.none { it is AgentEvent.Completed })
        val blocked = steps.first { it is AgentEvent.Blocked } as AgentEvent.Blocked
        assertTrue(blocked.reason.contains("without"))
    }

    @Test
    fun `failing build does not count as verification`() = runBlocking {
        val build = FakeTool("run_build", "build", ToolResult(false, "error: unresolved reference"))
        val runner = runner(
            listOf(
                "TOOL: run_build\nARGS: {}",
                "DONE: fixed it",
            ),
            listOf(build),
        )
        runner.start("fix the build", null)
        val steps = awaitFinished(runner)

        assertTrue(steps.none { it is AgentEvent.Completed })
        assertTrue(steps.any { it is AgentEvent.Blocked })
    }

    @Test
    fun `tool failure is reported as an observation`() = runBlocking {
        val tool = FakeTool("read_file", "read", ToolResult(false, "file not found"))
        val runner = runner(
            listOf(
                "TOOL: read_file\nARGS: {\"path\":\"nope.kt\"}",
                "TOOL: run_build\nARGS: {}",
                "DONE: done",
            ),
            listOf(tool, FakeTool("run_build", "build", ToolResult(true, "ok"))),
        )
        runner.start("read a file", null)
        val steps = awaitFinished(runner)

        val observation = steps.filterIsInstance<AgentEvent.Observation>().first()
        assertTrue(!observation.ok)
        assertTrue(observation.output.contains("not found"))
    }

    @Test
    fun `unknown tool is rejected without throwing`() = runBlocking {
        val runner = runner(
            listOf("TOOL: delete_everything\nARGS: {}", "DONE: done"),
            emptyList(),
        )
        runner.start("try something", null)
        val steps = awaitFinished(runner)

        val observation = steps.filterIsInstance<AgentEvent.Observation>().first()
        assertTrue(!observation.ok)
        assertTrue(observation.output.contains("Unknown tool"))
    }

    @Test
    fun `declined approval stops the tool from running`() = runBlocking {
        val tool = FakeTool("write_file", "write", ToolResult(true, "wrote"))
        val runner = runner(
            listOf("TOOL: write_file\nARGS: {\"path\":\"a.kt\"}", "DONE: done"),
            listOf(tool),
            approve = { _, _ -> false },
        )
        runner.start("write a file", null)
        val steps = awaitFinished(runner)

        assertEquals(0, tool.invocations)
        val observation = steps.filterIsInstance<AgentEvent.Observation>().first()
        assertTrue(observation.output.contains("declined"))
    }

    @Test
    fun `malformed json arguments are reported not thrown`() = runBlocking {
        val runner = runner(listOf("TOOL: read_file\nARGS: {not json", "DONE: done"), emptyList())
        runner.start("read", null)
        val steps = awaitFinished(runner)

        assertTrue(steps.any { it is AgentEvent.Observation && it.output.contains("not valid JSON") })
    }

    @Test
    fun `step limit blocks completion and says why`() = runBlocking {
        val tool = FakeTool("read_file", "read", ToolResult(true, "contents"))
        val runner = runner(
            List(4) { "TOOL: read_file\nARGS: {}" },
            listOf(tool),
            maxSteps = 3,
        )
        runner.start("loop forever", null)
        val steps = awaitFinished(runner)

        assertEquals(3, tool.invocations)
        val blocked = steps.last()
        assertTrue(blocked is AgentEvent.Blocked)
        assertTrue((blocked as AgentEvent.Blocked).reason.contains("step limit"))
    }
}

/**
 * The approval gate is the only thing between an autonomous agent and the user's source
 * tree, so the interesting question is not whether it can say no — it is whether saying yes
 * actually lets the tool run, and whether the agent's loop really waits for the answer.
 */
class AgentApprovalGateTest {

    private fun runner(
        replies: List<String>,
        tools: List<AgentTool>,
        approve: suspend (String, JsonObject) -> Boolean,
        maxSteps: Int = 6,
    ): AgentRunner {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val provider = ScriptedProvider(replies)
        return AgentRunner(scope, { provider }, "scripted", AgentToolbox(tools, approve), maxSteps)
    }

    private suspend fun awaitFinished(runner: AgentRunner, timeoutMs: Long = 5000): List<AgentEvent> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!runner.state.value.running) return runner.state.value.steps
            kotlinx.coroutines.delay(20)
        }
        return runner.state.value.steps
    }

    @Test
    fun `approving a write lets the tool change the project`() = runBlocking {
        val write = FakeTool("write_file", "write", ToolResult(true, "wrote 1 file"))
        val runner = runner(
            listOf("TOOL: write_file\nARGS: {}", "DONE: edited"),
            listOf(write),
            approve = { _, _ -> true },
        )
        runner.start("edit the file", null)
        awaitFinished(runner)
        assertEquals("a yes must actually run the tool", 1, write.invocations)
    }

    @Test
    fun `denying a write leaves the project untouched and tells the agent`() = runBlocking {
        val write = FakeTool("write_file", "write", ToolResult(true, "wrote 1 file"))
        val runner = runner(
            listOf("TOOL: write_file\nARGS: {}", "DONE: stopped"),
            listOf(write),
            approve = { _, _ -> false },
        )
        runner.start("edit the file", null)
        val steps = awaitFinished(runner)
        assertEquals("a no must not run the tool", 0, write.invocations)
        val observation = steps.filterIsInstance<AgentEvent.Observation>().firstOrNull()
        assertTrue("the agent must be told why: $steps", observation?.output?.contains("declined") == true)
    }

    @Test
    fun `the loop waits for the answer instead of running ahead`() = runBlocking {
        val write = FakeTool("write_file", "write", ToolResult(true, "wrote 1 file"))
        val asked = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Boolean>()
        val runner = runner(
            listOf("TOOL: write_file\nARGS: {}", "DONE: edited"),
            listOf(write),
            approve = { _, _ ->
                asked.complete(Unit)
                answer.await()
            },
        )
        runner.start("edit the file", null)

        withTimeout(2000) { asked.await() }
        assertTrue("the agent must still be running while it waits", runner.state.value.running)
        assertEquals("nothing may be written before the answer", 0, write.invocations)

        answer.complete(true)
        awaitFinished(runner)
        assertEquals("the write happens once allowed", 1, write.invocations)
    }

    @Test
    fun `reads and builds are unattended but writes are not`() {
        listOf("list_files", "read_file", "search_code", "run_build", "run_tests").forEach {
            assertFalse("$it cannot change the project", ToolPolicy.requiresApproval(it))
        }
        listOf("write_file", "delete_file").forEach {
            assertTrue("$it changes the project", ToolPolicy.requiresApproval(it))
        }
    }
}

/**
 * `run` guards the busy flag with `finally` but had no `catch`, so a provider that threw
 * instead of returning a [ChatResult.Failure] took the coroutine down with it: the caller in
 * the AI screen never received an outcome and its composer stayed disabled for good. A
 * transport problem must always come back as an [AiOutcome.Failure] the user can read.
 */
class TitanIntelligenceFailureTest {

    private class ThrowingProvider(private val error: Throwable) : AiProvider {
        override val id = "throwing"
        override val displayName = "Throwing"
        override val requiresApiKey = false
        override val supportsStreaming = false
        override val models = listOf("throwing")
        override suspend fun chat(request: ChatRequest): ChatResult = throw error
    }

    private fun intelligence(provider: AiProvider): Pair<TitanIntelligence, Project> {
        val root = kotlin.io.path.createTempDirectory("ai-failure").toFile()
        val project = Project(
            id = "p1",
            name = "Failure",
            packageName = "dev.titanide.failure",
            rootPath = root.absolutePath,
            category = ProjectCategory.BLANK,
            templateId = "blank",
            languages = listOf(Language.KOTLIN),
            buildSystem = BuildSystem.NONE,
            createdAt = 0L,
            lastOpenedAt = 0L,
        )
        val files = FileTreeRepository()
        return TitanIntelligence(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            providerProvider = { provider },
            model = "throwing",
            contextBuilder = ContextBuilder(files),
            files = files,
            editApplier = EditApplier(files),
        ) to project
    }

    @Test
    fun `a provider that throws is reported instead of escaping`() = runBlocking {
        val (intelligence, project) = intelligence(ThrowingProvider(IllegalStateException("socket closed")))
        val outcome = intelligence.chat(project, listOf(ChatMessage(ChatMessage.Role.USER, "hi")), includeContext = false)

        assertTrue("expected a Failure, got $outcome", outcome is AiOutcome.Failure)
        assertTrue(
            "the reason must carry the real error: $outcome",
            (outcome as AiOutcome.Failure).reason.contains("socket closed"),
        )
        assertFalse("the busy flag must be released", intelligence.busy.value)
    }
}
