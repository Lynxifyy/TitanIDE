package dev.titanide.core.ai.agent

/**
 * Which agent tools may run without asking.
 *
 * A read cannot damage anything, and a build is how the agent verifies its own work, so
 * stopping on either would make autonomous mode pointless. Everything else changes the
 * project and waits for the user. The set is named here rather than in the UI because it is
 * the security boundary, and a boundary should not live somewhere it cannot be tested.
 */
object ToolPolicy {

    val UNATTENDED: Set<String> = setOf("list_files", "read_file", "search_code", "run_build", "run_tests")

    /** Tools that write to the project, listed so the UI can describe the risk accurately. */
    val MODIFIES_PROJECT: Set<String> = setOf("write_file", "delete_file")

    fun requiresApproval(tool: String): Boolean = tool !in UNATTENDED
}
