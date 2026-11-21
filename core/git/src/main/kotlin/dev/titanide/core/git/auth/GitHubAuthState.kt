package dev.titanide.core.git.auth

/**
 * Single source of truth for GitHub authentication.
 *
 * The token used to be a nullable field that screens read directly, so any screen
 * that rendered before the Keystore read finished saw "signed out" and then flipped
 * to "signed in" a frame later. [Initializing] exists so callers can wait instead of
 * guessing, and [Expired] / [Error] exist so a stale token is distinguishable from
 * never having signed in.
 */
sealed interface GitHubAuthState {
    /** Keystore read has not finished yet; no decision is safe. */
    data object Initializing : GitHubAuthState

    data object SignedOut : GitHubAuthState

    /** Device flow is in progress; [userCode] is shown to the user while they authorise. */
    data class SigningIn(
        val userCode: String,
        val verificationUri: String,
        val expiresAtEpochSeconds: Long,
    ) : GitHubAuthState

    data class SignedIn(val login: String, val avatarUrl: String?) : GitHubAuthState

    /** A token was present but GitHub rejected it; the user must sign in again. */
    data object Expired : GitHubAuthState

    data class Error(val message: String) : GitHubAuthState

    val isSignedIn: Boolean get() = this is SignedIn

    /** True once the Keystore read has settled, so UI can stop showing a spinner. */
    val isSettled: Boolean get() = this !is Initializing

    /** True while a token is usable, i.e. a call to GitHub may be attempted. */
    val hasUsableToken: Boolean get() = this is SignedIn
}

/**
 * Turns the outcome of the "who am I" call into a settled state.
 *
 * Kept pure so the mapping is testable without a network, an Android Keystore or a
 * running server. [onTokenRejected] is invoked when the failure means the stored token
 * is dead, so the caller can erase it.
 */
suspend fun resolveGitHubAuth(
    result: Result<String>,
    isUnauthorized: (Throwable) -> Boolean,
    isOffline: (Throwable) -> Boolean,
    onTokenRejected: suspend () -> Unit,
): GitHubAuthState = result.fold(
    onSuccess = { login -> GitHubAuthState.SignedIn(login = login, avatarUrl = null) },
    onFailure = { e ->
        when {
            isUnauthorized(e) -> {
                onTokenRejected()
                GitHubAuthState.Expired
            }
            // Offline is not evidence the token is bad, so do not force a re-login.
            isOffline(e) -> GitHubAuthState.SignedIn(login = "", avatarUrl = null)
            else -> GitHubAuthState.Error(e.message ?: "Could not reach GitHub")
        }
    },
)
