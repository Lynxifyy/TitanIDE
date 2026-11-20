package dev.titanide.core.git.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * GitHub OAuth Device Flow.
 *
 * Device flow is used instead of the web-redirect flow because a mobile IDE cannot hold a
 * client secret, and the device flow is the only GitHub flow designed for public clients.
 * The user authorises on any browser; TITAN only ever sees the resulting token.
 */
class DeviceFlowAuth(
    private val clientId: String,
    private val scopes: String = "repo read:user workflow",
    private val http: OkHttpClient = defaultClient(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class DeviceCodeResponse(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_uri") val verificationUri: String,
        @SerialName("expires_in") val expiresIn: Int,
        @SerialName("interval") val interval: Int = 5,
    )

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("token_type") val tokenType: String? = null,
        @SerialName("scope") val scope: String? = null,
        @SerialName("error") val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null,
        @SerialName("interval") val interval: Int? = null,
    )

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresInSeconds: Int,
        val pollIntervalSeconds: Int,
    )

    sealed interface PollOutcome {
        data class Authorized(val token: String, val scope: String?) : PollOutcome
        data object Pending : PollOutcome
        data class SlowDown(val newIntervalSeconds: Int) : PollOutcome
        data class Failed(val reason: String) : PollOutcome
        data object Expired : PollOutcome
        data object Denied : PollOutcome
    }

    fun isConfigured(): Boolean = clientId.isNotBlank()

    suspend fun requestDeviceCode(): Result<DeviceCode> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(
                IllegalStateException(
                    "No GitHub OAuth client id is configured. Set GITHUB_CLIENT_ID in the build, " +
                        "or sign in with a personal access token instead.",
                ),
            )
        }
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", scopes)
            .build()
        val request = Request.Builder()
            .url("https://github.com/login/device/code")
            .header("Accept", "application/json")
            .post(body)
            .build()

        runCatching {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("GitHub returned HTTP ${response.code}")
                val parsed = json.decodeFromString(DeviceCodeResponse.serializer(), text)
                DeviceCode(
                    deviceCode = parsed.deviceCode,
                    userCode = parsed.userCode,
                    verificationUri = parsed.verificationUri,
                    expiresInSeconds = parsed.expiresIn,
                    pollIntervalSeconds = parsed.interval.coerceAtLeast(5),
                )
            }
        }
    }

    /** Polls once; the caller drives the loop so it can show live state in the UI. */
    suspend fun pollOnce(deviceCode: String): PollOutcome = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        val request = Request.Builder()
            .url("https://github.com/login/oauth/access_token")
            .header("Accept", "application/json")
            .post(body)
            .build()

        runCatching {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val parsed = json.decodeFromString(TokenResponse.serializer(), text)
                when {
                    parsed.accessToken != null -> PollOutcome.Authorized(parsed.accessToken, parsed.scope)
                    parsed.error == "authorization_pending" -> PollOutcome.Pending
                    parsed.error == "slow_down" -> PollOutcome.SlowDown(parsed.interval ?: 10)
                    parsed.error == "expired_token" -> PollOutcome.Expired
                    parsed.error == "access_denied" -> PollOutcome.Denied
                    else -> PollOutcome.Failed(parsed.errorDescription ?: parsed.error ?: "Unknown OAuth error")
                }
            }
        }.getOrElse { PollOutcome.Failed("Network error: ${it.message}") }
    }

    /**
     * Full flow: waits for the user to authorise, honouring GitHub's interval and expiry.
     * Returns the token or a failure with the reason the flow ended.
     */
    suspend fun awaitAuthorization(
        deviceCode: DeviceCode,
        onPending: (elapsedSeconds: Int) -> Unit = {},
    ): Result<String> {
        val deadline = System.currentTimeMillis() + deviceCode.expiresInSeconds * 1000L
        var interval = deviceCode.pollIntervalSeconds
        var elapsed = 0
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            elapsed += interval
            onPending(elapsed)
            when (val outcome = pollOnce(deviceCode.deviceCode)) {
                is PollOutcome.Authorized -> return Result.success(outcome.token)
                PollOutcome.Pending -> Unit
                is PollOutcome.SlowDown -> interval = outcome.newIntervalSeconds
                PollOutcome.Expired -> return Result.failure(IllegalStateException("The device code expired"))
                PollOutcome.Denied -> return Result.failure(IllegalStateException("Authorisation was denied"))
                is PollOutcome.Failed -> return Result.failure(IllegalStateException(outcome.reason))
            }
        }
        return Result.failure(IllegalStateException("Timed out waiting for GitHub authorisation"))
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
