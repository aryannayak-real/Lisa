package com.lisaai.inference

/** A command proposed by the model, plus the text that should remain visible. */
data class AgentProposal(val command: String, val text: String)

/**
 * The one-command-at-a-time protocol used in agent mode.
 *
 * Small local models are unreliable at native tool calling, so the model is taught a
 * tiny, unambiguous marker. A fenced `sh` block is accepted as a fallback for models
 * that ignore the marker.
 */
object Agent {

    const val MODE_PROMPT = """
You are Lisa, a local assistant running on an Android phone with a Termux-compatible shell.
You can run one shell command at a time in the user's terminal environment.

To run a command, reply with exactly this block and nothing else inside it:
<lisa-run>the command</lisa-run>

Rules:
- Propose one command at a time.
- The user sees and approves every command before it runs.
- Never run sudo, su, or destructive commands unless the user explicitly asks.
- Use non-interactive commands. Do not start long-running servers.
- After you receive the command output, either propose the next command or give the final answer.
- If no command is needed, answer normally without a <lisa-run> block.
""".trimIndent()

    private val RUN_BLOCK = Regex("(?s)<lisa-run>(.*?)</lisa-run>", RegexOption.IGNORE_CASE)
    private val FENCED = Regex("(?s)```(?:sh|bash|shell)\\s*\\n(.*?)```", RegexOption.IGNORE_CASE)

    fun parse(text: String): AgentProposal? {
        val match = RUN_BLOCK.find(text) ?: FENCED.find(text) ?: return null
        val command = match.groupValues[1].trim()
        if (command.isEmpty()) return null
        val visible = text.removeRange(match.range).trim()
        return AgentProposal(command = command, text = visible)
    }
}
