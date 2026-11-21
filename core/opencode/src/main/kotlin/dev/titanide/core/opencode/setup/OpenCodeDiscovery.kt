package dev.titanide.core.opencode.setup

import dev.titanide.core.opencode.model.ModelInfo
import dev.titanide.core.opencode.model.ProviderInfo
import dev.titanide.core.opencode.model.ServerInfo
import dev.titanide.core.opencode.net.OpenCodeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How far a candidate server got. Each step is a real network call, so the UI can say
 * exactly what is missing instead of "not connected".
 */
sealed interface DiscoveryOutcome {
    /** A reachable OpenCode server that reported at least one usable model. */
    data class Ready(
        val url: String,
        val server: ServerInfo,
        val providers: List<ProviderInfo>,
        val models: List<ModelInfo>,
    ) : DiscoveryOutcome {
        /** First model advertised by the server, used as the default selection. */
        val defaultModel: ModelInfo? get() = models.firstOrNull()
    }

    /** Reached a server, but it has no model configured, so no prompt can be sent. */
    data class NoModels(val url: String, val server: ServerInfo) : DiscoveryOutcome

    /** Reached a server that did not answer the info probe. */
    data class Unhealthy(val url: String, val reason: String) : DiscoveryOutcome

    /** Nothing answered at any candidate. */
    data object NotFound : DiscoveryOutcome
}

/**
 * Finds an OpenCode server TITAN can actually use.
 *
 * This is deliberately a set of real probes rather than an assumption. TITAN cannot bundle
 * an OpenCode runtime: the released binaries target glibc/musl Linux and Windows, and
 * Android uses bionic, so there is no supported on-device runtime to launch. Discovery
 * therefore checks, in order, the URL the user configured and the conventional localhost
 * port, and reports which of them answered.
 *
 * [candidates] is injectable so tests can point at MockWebServer instead of a real server.
 */
class OpenCodeDiscovery(
    private val clientFactory: (String, String?) -> OpenCodeClient = { url, token ->
        OpenCodeClient(url, token)
    },
    private val candidates: (String) -> List<String> = ::defaultCandidates,
) {

    /**
     * Probes each candidate until one reports a healthy server.
     *
     * [configuredUrl] is tried first when present so an explicit setting always wins over
     * the guess. A candidate that is reachable but has no models stops the search: it is
     * the server the user intended, it just needs a provider sign-in.
     */
    suspend fun discover(configuredUrl: String, token: String?): DiscoveryOutcome {
        val urls = candidates(configuredUrl).distinct().filter { it.isNotBlank() }
        var sawNoModels: DiscoveryOutcome.NoModels? = null
        var sawUnhealthy: DiscoveryOutcome.Unhealthy? = null

        for (url in urls) {
            val result = probe(url, token)
            when (result) {
                is DiscoveryOutcome.Ready -> return result
                is DiscoveryOutcome.NoModels -> sawNoModels = sawNoModels ?: result
                is DiscoveryOutcome.Unhealthy -> sawUnhealthy = sawUnhealthy ?: result
                DiscoveryOutcome.NotFound -> Unit
            }
        }
        return sawNoModels ?: sawUnhealthy ?: DiscoveryOutcome.NotFound
    }

    /** Runs the health and capability probes for a single URL. */
    suspend fun probe(url: String, token: String?): DiscoveryOutcome = withContext(Dispatchers.IO) {
        val client = clientFactory(url, token)
        val server = try {
            client.info()
        } catch (e: Exception) {
            return@withContext if (isUnreachable(e)) {
                DiscoveryOutcome.NotFound
            } else {
                DiscoveryOutcome.Unhealthy(url, e.message ?: "Server did not answer the info probe")
            }
        }

        val providers = runCatching { client.providers() }.getOrDefault(emptyList())
        val models = runCatching { client.models() }.getOrDefault(emptyList())
        val flattened = models.ifEmpty {
            // Some builds report models only under each provider.
            providers.flatMap { p ->
                p.models.map { m -> m.copy(providerID = m.providerID ?: p.id) }
            }
        }

        if (flattened.isEmpty()) {
            DiscoveryOutcome.NoModels(url, server)
        } else {
            DiscoveryOutcome.Ready(url, server, providers, flattened)
        }
    }

    /**
     * A refused connection or DNS failure means "not here"; anything else means "here but
     * broken", which is a different message for the user.
     */
    private fun isUnreachable(e: Exception): Boolean {
        val name = e.javaClass.simpleName
        val message = e.message.orEmpty()
        return name.contains("ConnectException") ||
            name.contains("UnknownHost") ||
            name.contains("SocketTimeout") ||
            message.contains("Failed to connect") ||
            message.contains("Connection refused")
    }

    companion object {
        /** The port the OpenCode CLI serves on by default. */
        const val DEFAULT_PORT = 4096

        /**
         * Explicit setting first, then the loopback default. Loopback is checked because a
         * user may be running the server through a port forward or a termux-side runtime.
         */
        fun defaultCandidates(configuredUrl: String): List<String> {
            val local = listOf(
                "http://127.0.0.1:$DEFAULT_PORT",
                "http://localhost:$DEFAULT_PORT",
            )
            return if (configuredUrl.isBlank()) local else listOf(configuredUrl.trimEnd('/')) + local
        }
    }
}
