package dev.titanide.core.data.security

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Credential vault backed by Android Keystore (AES256-GCM master key) via
 * EncryptedSharedPreferences. Tokens never touch plain SharedPreferences, logcat, or
 * build output; [redact] is applied to anything that could reach a log line.
 */
class SecureCredentialStore(private val context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "titan_secure_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Stores [value]. Returns false when the keystore is unavailable, for example after a
     * device restore leaves the master key undecryptable. Callers must surface that instead
     * of reporting a token as saved.
     */
    suspend fun put(key: CredentialKey, value: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { prefs.edit().putString(key.storageKey, value).commit() }
            .onFailure { Log.w(TAG, "Credential write failed for ${key.storageKey}", it) }
            .getOrDefault(false)
    }

    suspend fun get(key: CredentialKey): String? = withContext(Dispatchers.IO) {
        runCatching { prefs.getString(key.storageKey, null) }
            .onFailure { Log.w(TAG, "Credential read failed for ${key.storageKey}") }
            .getOrNull()
    }

    suspend fun contains(key: CredentialKey): Boolean = !get(key).isNullOrBlank()

    suspend fun remove(key: CredentialKey): Boolean = withContext(Dispatchers.IO) {
        runCatching { prefs.edit().remove(key.storageKey).commit() }
            .onFailure { Log.w(TAG, "Credential removal failed for ${key.storageKey}", it) }
            .getOrDefault(false)
    }

    suspend fun clearAll(): Boolean = withContext(Dispatchers.IO) {
        runCatching { prefs.edit().clear().commit() }
            .onFailure { Log.w(TAG, "Credential clear failed", it) }
            .getOrDefault(false)
    }

    companion object {
        private const val TAG = "SecureCredentialStore"

        /** Masks a token for display: keeps a short prefix so users can identify it. */
        fun redact(secret: String?): String {
            if (secret.isNullOrBlank()) return "(none)"
            return if (secret.length <= 8) "****" else "${secret.take(4)}...${secret.takeLast(4)}"
        }

        /** Removes anything resembling a token from text destined for logs or AI prompts. */
        fun scrub(text: String): String {
            var scrubbed = text
            TOKEN_PATTERNS.forEach { pattern ->
                scrubbed = pattern.replace(scrubbed) { match ->
                    val value = match.value
                    val prefix = value.takeWhile { it != ':' && it != '=' && !it.isWhitespace() }
                    "$prefix<redacted>"
                }
            }
            return scrubbed
        }

        private val TOKEN_PATTERNS = listOf(
            Regex("gh[pousr]_[A-Za-z0-9]{16,}"),
            Regex("github_pat_[A-Za-z0-9_]{20,}"),
            Regex("sk-[A-Za-z0-9]{20,}"),
            Regex("(?i)bearer\\s+[A-Za-z0-9._-]{16,}"),
            Regex("(?i)authorization:\\s*\\S+"),
        )
    }
}

enum class CredentialKey(val storageKey: String) {
    GITHUB_TOKEN("github_oauth_token"),
    GITHUB_REFRESH("github_refresh_token"),
    AI_API_KEY("ai_api_key"),
    REMOTE_AGENT_TOKEN("remote_agent_token"),
    ;
}
