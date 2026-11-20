package dev.titanide.core.ai

/**
 * Prompt construction. Kept in one place so the system prompt always states the same
 * rules, particularly the rule that the model must not claim work it did not verify.
 */
object Prompts {

    val SYSTEM = """
        You are TITAN Intelligence, the assistant inside TITAN IDE, a mobile Android development environment.

        Rules you must follow:
        1. Never claim a change works unless you ran the build or tests and saw the result. If you could not run them, say so explicitly.
        2. When you propose code, return complete, compilable files, not fragments, unless the user asked for a fragment.
        3. Respect the project's existing language, build system and dependency versions.
        4. Never invent file contents, test results, or log output.
        5. If a task needs a tool that is unavailable on this device, say which tool and what the user must do instead.
        6. Prefer the smallest correct change over a rewrite.
        7. Do not add comments that restate the code.

        Output format when proposing file changes: wrap each file in a fenced block whose info string is
        the relative path, like:
        ```path/to/File.kt
        <full file contents>
        ```
        Add a short explanation before the blocks.
    """.trimIndent()

    fun explain(language: String?, code: String, context: AiContext?): List<ChatMessage> = buildList {
        add(ChatMessage(ChatMessage.Role.SYSTEM, SYSTEM))
        add(
            ChatMessage(
                ChatMessage.Role.USER,
                buildString {
                    appendLine("Explain what this ${language ?: ""} code does.")
                    appendLine("Cover: purpose, control flow, notable edge cases, and anything suspicious.")
                    if (context != null) {
                        appendLine()
                        appendLine(context.render())
                    }
                    appendLine("```")
                    appendLine(code)
                    appendLine("```")
                },
            ),
        )
    }

    fun inlineEdit(instruction: String, code: String, language: String?): List<ChatMessage> = listOf(
        ChatMessage(ChatMessage.Role.SYSTEM, SYSTEM),
        ChatMessage(
            ChatMessage.Role.USER,
            buildString {
                appendLine("Rewrite the code below according to this instruction:")
                appendLine(instruction)
                appendLine()
                appendLine("Return only the replacement code for the selection, no prose.")
                appendLine("```${language ?: ""}")
                appendLine(code)
                appendLine("```")
            },
        ),
    )

    fun refactor(code: String, language: String?, goal: String?): List<ChatMessage> = listOf(
        ChatMessage(ChatMessage.Role.SYSTEM, SYSTEM),
        ChatMessage(
            ChatMessage.Role.USER,
            buildString {
                appendLine("Refactor this ${language ?: ""} code without changing observable behaviour.")
                if (goal != null) appendLine("Goal: $goal")
                appendLine("Return only the refactored code.")
                appendLine("```")
                appendLine(code)
                appendLine("```")
            },
        ),
    )

    fun debug(
        failure: String,
        logs: List<String>,
        filePath: String?,
        fileContent: String?,
        context: AiContext?,
    ): List<ChatMessage> = listOf(
        ChatMessage(ChatMessage.Role.SYSTEM, SYSTEM),
        ChatMessage(
            ChatMessage.Role.USER,
            buildString {
                appendLine("Diagnose this build or test failure and give the corrected file.")
                appendLine("Failure: $failure")
                if (logs.isNotEmpty()) {
                    appendLine("Relevant log:")
                    appendLine("```")
                    appendLine(logs.takeLast(120).joinToString("\n"))
                    appendLine("```")
                }
                if (context != null) {
                    appendLine()
                    appendLine(context.render())
                }
                if (filePath != null && fileContent != null) {
                    appendLine("File: $filePath")
                    appendLine("```")
                    appendLine(fileContent)
                    appendLine("```")
                }
            },
        ),
    )

    fun generateTests(code: String, language: String?, filePath: String?): List<ChatMessage> = listOf(
        ChatMessage(ChatMessage.Role.SYSTEM, SYSTEM),
        ChatMessage(
            ChatMessage.Role.USER,
            buildString {
                appendLine("Write unit tests for the code below using the idiomatic test framework for ${language ?: "this language"}.")
                appendLine("Cover normal cases, edge cases, and one failure path.")
                if (filePath != null) appendLine("Source file: $filePath")
                appendLine("```")
                appendLine(code)
                appendLine("```")
            },
        ),
    )

    fun reviewPullRequest(diff: String, context: AiContext?): List<ChatMessage> = listOf(
        ChatMessage(ChatMessage.Role.SYSTEM, SYSTEM),
        ChatMessage(
            ChatMessage.Role.USER,
            buildString {
                appendLine("Review this diff. Report correctness bugs, security problems, and missing tests.")
                appendLine("Order findings by severity. Be specific about file and line.")
                if (context != null) {
                    appendLine()
                    appendLine(context.render())
                }
                appendLine("```diff")
                appendLine(diff)
                appendLine("```")
            },
        ),
    )

    fun securityAudit(code: String, language: String?): List<ChatMessage> = listOf(
        ChatMessage(ChatMessage.Role.SYSTEM, SYSTEM),
        ChatMessage(
            ChatMessage.Role.USER,
            buildString {
                appendLine("Audit this ${language ?: ""} code for security defects: injection, unsafe deserialisation,")
                appendLine("path traversal, credential exposure, and missing input validation.")
                appendLine("For each finding give the line, the risk, and a concrete fix.")
                appendLine("```")
                appendLine(code)
                appendLine("```")
            },
        ),
    )

    fun agentStep(goal: String, context: AiContext?, history: List<String>, tools: List<String>): List<ChatMessage> = listOf(
        ChatMessage(ChatMessage.Role.SYSTEM, AGENT_SYSTEM),
        ChatMessage(
            ChatMessage.Role.USER,
            buildString {
                appendLine("Goal: $goal")
                appendLine()
                appendLine("Available tools:")
                tools.forEach { appendLine("- $it") }
                appendLine()
                if (history.isNotEmpty()) {
                    appendLine("Steps taken so far:")
                    history.forEach { appendLine(it) }
                    appendLine()
                }
                if (context != null) {
                    appendLine(context.render())
                }
                appendLine()
                appendLine("Reply with exactly one tool call in this format:")
                appendLine("TOOL: <tool name>")
                appendLine("ARGS: <single-line JSON object>")
                appendLine("Or, when the goal is complete and verified, reply with:")
                appendLine("DONE: <summary of what was verified>")
            },
        ),
    )

    private val AGENT_SYSTEM = """
        You are TITAN Intelligence running in autonomous agent mode inside TITAN IDE.

        You act one step at a time by calling exactly one tool. After each tool result you
        receive, decide the next step.

        Hard rules:
        - You may only claim success after a build or test tool reported success. If the
          tool result is a failure, fix the cause and try again.
        - Never state that a file was changed unless a write tool reported success.
        - Stop with DONE only when the goal is genuinely achieved and verified.
        - If you are blocked by a missing toolchain, reply with DONE and explain exactly
          what is missing. Do not pretend the work succeeded.
    """.trimIndent()
}
