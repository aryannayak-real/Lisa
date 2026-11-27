package com.lisaai.inference

enum class Role { User, Assistant }

/** How a message should be shown in the UI and, for commands, encoded for the model. */
enum class MessageKind { Text, Command, Output, Note }

data class Message(
    val role: Role,
    val text: String,
    val kind: MessageKind = MessageKind.Text,
)

/**
 * ChatML formatting used by the Qwen family. Thinking is disabled with `/no_think`
 * because this app targets small, speed-bound models where reasoning tokens are costly.
 */
object Prompt {

    private const val IM_START = "<|im_start|>"
    private const val IM_END = "<|im_end|>"

    fun chat(system: String, messages: List<Message>): String = buildString {
        append(IM_START).append("system\n")
        append(system.trim()).append(" /no_think")
        append(IM_END).append('\n')

        for (message in messages) {
            if (message.kind == MessageKind.Note) continue
            val content = when (message.kind) {
                MessageKind.Command -> "<lisa-run>${message.text.trim()}</lisa-run>"
                else -> message.text.trim()
            }
            append(IM_START)
            append(if (message.role == Role.User) "user" else "assistant")
            append('\n').append(content).append(IM_END).append('\n')
        }

        append(IM_START).append("assistant\n")
    }

    fun stripThinking(text: String): String {
        val open = "<" + "think>"
        val close = "</" + "think>"
        if (!text.contains(open)) return text
        val end = text.indexOf(close)
        return if (end < 0) "" else text.substring(end + close.length).trimStart()
    }
}
