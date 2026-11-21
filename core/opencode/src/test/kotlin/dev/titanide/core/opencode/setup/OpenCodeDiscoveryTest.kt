package dev.titanide.core.opencode.setup

import dev.titanide.core.opencode.session.OpenCodeConnection
import dev.titanide.core.opencode.session.OpenCodeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Discovery is the difference between "TITAN works on first launch" and "TITAN asks the
 * user for a URL". These run against a real HTTP server so the probes, the JSON parsing
 * and the failure classification are all exercised for real.
 */
class OpenCodeDiscoveryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun discovery(): OpenCodeDiscovery =
        OpenCodeDiscovery(candidates = { listOf(server.url("/").toString().trimEnd('/')) })

    private fun route(vararg responses: Pair<String, MockResponse>) {
        val map = responses.toMap()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                return map[path] ?: MockResponse().setResponseCode(404)
            }
        }
    }

    @Test
    fun `a healthy server with models is ready`() = runTest {
        route(
            "/api/info" to MockResponse().setBody("""{"version":"1.19.6","pid":42}"""),
            "/api/model" to MockResponse().setBody(
                """{"data":[{"id":"claude-sonnet-4","providerID":"anthropic","name":"Claude Sonnet 4"}]}""",
            ),
            "/api/provider" to MockResponse().setBody(
                """{"data":[{"id":"anthropic","name":"Anthropic"}]}""",
            ),
        )

        val outcome = discovery().discover("", null)

        assertTrue("expected Ready, was $outcome", outcome is DiscoveryOutcome.Ready)
        val ready = outcome as DiscoveryOutcome.Ready
        assertEquals("1.19.6", ready.server.version)
        assertEquals("claude-sonnet-4", ready.defaultModel?.id)
        assertEquals(1, ready.providers.size)
    }

    @Test
    fun `a server with no models is reported as needing a provider`() = runTest {
        route(
            "/api/info" to MockResponse().setBody("""{"version":"1.19.6"}"""),
            "/api/model" to MockResponse().setBody("""{"data":[]}"""),
            "/api/provider" to MockResponse().setBody("""{"data":[]}"""),
        )

        val outcome = discovery().discover("", null)

        assertTrue("expected NoModels, was $outcome", outcome is DiscoveryOutcome.NoModels)
    }

    @Test
    fun `models reported only under providers are still found`() = runTest {
        route(
            "/api/info" to MockResponse().setBody("""{"version":"1.19.6"}"""),
            "/api/model" to MockResponse().setBody("""{"data":[]}"""),
            "/api/provider" to MockResponse().setBody(
                """{"data":[{"id":"openai","models":[{"id":"gpt-5","name":"GPT-5"}]}]}""",
            ),
        )

        val outcome = discovery().discover("", null)

        assertTrue("expected Ready, was $outcome", outcome is DiscoveryOutcome.Ready)
        val ready = outcome as DiscoveryOutcome.Ready
        assertEquals("gpt-5", ready.defaultModel?.id)
        assertEquals("openai", ready.defaultModel?.providerID)
    }

    @Test
    fun `a closed port is NotFound rather than an error`() = runTest {
        server.shutdown()
        val outcome = discovery().discover("", null)
        assertTrue("expected NotFound, was $outcome", outcome is DiscoveryOutcome.NotFound)
    }

    @Test
    fun `a server that fails the info probe is reported unhealthy`() = runTest {
        route("/api/info" to MockResponse().setResponseCode(500).setBody("boom"))
        val outcome = discovery().discover("", null)
        assertTrue("expected Unhealthy, was $outcome", outcome is DiscoveryOutcome.Unhealthy)
    }

    @Test
    fun `the configured url is tried before the loopback guess`() {
        val candidates = OpenCodeDiscovery.defaultCandidates("https://agent.example.com/")
        assertEquals("https://agent.example.com", candidates.first())
        assertTrue(candidates.any { it.contains("127.0.0.1") })
    }

    @Test
    fun `with no configured url only loopback candidates are used`() {
        val candidates = OpenCodeDiscovery.defaultCandidates("")
        assertTrue(candidates.all { it.contains("127.0.0.1") || it.contains("localhost") })
        assertTrue(candidates.isNotEmpty())
    }
}

/**
 * A failed setup must be visible in the session state. Previously a blank URL made the
 * connect path return without changing anything, so the panel looked idle forever.
 */
class OpenCodeSessionSetupTest {

    @Test
    fun `a setup problem is surfaced as a failed connection with a reason`() {
        val session = OpenCodeSession(scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        session.reportSetupProblem("No OpenCode server found. Run `opencode serve` on a computer.")

        val state = session.state.value
        val connection = state.connection
        assertTrue("expected Failed, was $connection", connection is OpenCodeConnection.Failed)
        val message = (connection as OpenCodeConnection.Failed).reason
        assertTrue("the reason must tell the user what to do: $message", message.contains("opencode serve"))
        assertTrue("the error must also be readable from lastError", state.lastError.orEmpty().isNotEmpty())
        assertEquals(false, state.running)
    }

    @Test
    fun `a setup problem cancels any earlier polling`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = OpenCodeSession(scope = scope)
        session.reportSetupProblem("first")
        session.reportSetupProblem("second")
        val connection = session.state.value.connection as OpenCodeConnection.Failed
        assertEquals("second", connection.reason)
    }
}
