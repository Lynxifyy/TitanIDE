package dev.titanide.core.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `scrub` is the last line of defence between a process's output and a log line, an AI prompt
 * or a screenshot. It kept the wrong part of the match.
 *
 * The replacement kept "everything up to the first colon, equals sign or space" and then appended
 * `<redacted>`. For a token sitting on its own - the common case in a build log - that prefix is
 * the entire token, so the secret was printed in full followed by the word `<redacted>`.
 */
class CredentialScrubTest {

    private val ghp = "ghp_" + "a".repeat(36)
    private val gho = "gho_" + "b".repeat(36)
    private val pat = "github_pat_" + "c".repeat(24)
    private val sk = "sk-" + "d".repeat(32)

    @Test
    fun `a bare token is removed entirely`() {
        assertEquals("<redacted>", SecureCredentialStore.scrub(ghp))
        assertEquals("<redacted>", SecureCredentialStore.scrub(pat))
        assertEquals("<redacted>", SecureCredentialStore.scrub(sk))
    }

    @Test
    fun `a token inside a url loses the token but keeps the url`() {
        val line = "fatal: could not read https://x-access-token:$ghp@github.com/o/r.git"
        val out = SecureCredentialStore.scrub(line)

        assertFalse("the token must be gone: $out", out.contains(ghp))
        assertTrue("the rest of the line must survive: $out", out.contains("github.com/o/r.git"))
    }

    @Test
    fun `a token after a key keeps the key and drops the value`() {
        val out = SecureCredentialStore.scrub("token=$gho")

        assertFalse("the token must be gone: $out", out.contains(gho))
        assertTrue("the key must survive: $out", out.startsWith("token="))
    }

    @Test
    fun `a bearer header keeps its scheme and drops the credential`() {
        val secret = "e".repeat(40)
        val out = SecureCredentialStore.scrub("Authorization: Bearer $secret")

        assertFalse("the credential must be gone: $out", out.contains(secret))
        assertTrue("the header name must survive: $out", out.contains("Authorization"))
    }

    @Test
    fun `several tokens on one line are all removed`() {
        val out = SecureCredentialStore.scrub("a=$ghp b=$gho")

        assertFalse("first token must be gone: $out", out.contains(ghp))
        assertFalse("second token must be gone: $out", out.contains(gho))
    }

    @Test
    fun `ordinary text is left alone`() {
        val text = "BUILD SUCCESSFUL in 1m 2s\n42 actionable tasks: 42 executed"
        assertEquals(text, SecureCredentialStore.scrub(text))
    }

    @Test
    fun `redact keeps a short prefix for identification`() {
        assertEquals("ghp_...aaaa", SecureCredentialStore.redact(ghp))
        assertEquals("****", SecureCredentialStore.redact("short"))
        assertEquals("(none)", SecureCredentialStore.redact(null))
    }
}
