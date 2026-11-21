package dev.titanide.core.git.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The auth state used to be a nullable token plus a racy boolean, so a screen could read
 * "signed out" while the Keystore read was still running, and a revoked token looked
 * identical to never having signed in. These pin the state machine itself.
 */
class GitHubAuthStateTest {

    private val unauthorized: (Throwable) -> Boolean = { it.message?.contains("401") == true }
    private val offline: (Throwable) -> Boolean = { it is IOException }

    @Test
    fun `successful lookup reports the account`() = runBlocking {
        val state = resolveGitHubAuth(Result.success("octocat"), unauthorized, offline) {}
        assertEquals(GitHubAuthState.SignedIn(login = "octocat", avatarUrl = null), state)
        assertTrue(state.isSignedIn)
        assertTrue(state.hasUsableToken)
    }

    @Test
    fun `a rejected token becomes expired and is erased`() = runBlocking {
        var erased = false
        val state = resolveGitHubAuth(
            result = Result.failure(IllegalStateException("GitHub rejected the token (401). Sign in again.")),
            isUnauthorized = unauthorized,
            isOffline = offline,
            onTokenRejected = { erased = true },
        )
        assertEquals(GitHubAuthState.Expired, state)
        assertTrue("a dead token must be cleared from storage", erased)
        assertFalse("expired must not look signed in", state.isSignedIn)
        assertFalse("expired must not offer a usable token", state.hasUsableToken)
    }

    @Test
    fun `offline keeps the session instead of forcing a re-login`() = runBlocking {
        var erased = false
        val state = resolveGitHubAuth(
            result = Result.failure(IOException("Unable to resolve host api.github.com")),
            isUnauthorized = unauthorized,
            isOffline = offline,
            onTokenRejected = { erased = true },
        )
        assertTrue("no network is not proof the token is bad", state.isSignedIn)
        assertFalse("a network blip must not erase a valid token", erased)
    }

    @Test
    fun `an unexpected failure surfaces as an error`() = runBlocking {
        val state = resolveGitHubAuth(
            result = Result.failure(IllegalStateException("GitHub rate limit or permission problem (403)")),
            isUnauthorized = unauthorized,
            isOffline = offline,
        ) {}
        assertTrue(state is GitHubAuthState.Error)
        assertFalse(state.isSignedIn)
    }

    @Test
    fun `only signed in offers a usable token`() {
        assertFalse(GitHubAuthState.Initializing.isSettled)
        assertTrue(GitHubAuthState.SignedOut.isSettled)
        assertTrue(GitHubAuthState.Expired.isSettled)
        assertTrue(GitHubAuthState.Error("x").isSettled)
        assertFalse(GitHubAuthState.SignedOut.hasUsableToken)
        assertFalse(
            GitHubAuthState.SigningIn("CODE-1", "https://github.com/login/device", 0L).hasUsableToken,
        )
    }
}
