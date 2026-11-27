package com.lisaai.data

import android.content.Context
import com.lisaai.inference.Message
import com.lisaai.inference.MessageKind
import com.lisaai.inference.Role
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class ChatSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
)

data class ChatSession(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<Message>,
)

/**
 * Small, file-backed chat history. One JSON file per chat under `files/chats`.
 * Chats are plain text on disk; no database or external storage is needed.
 */
object ChatStore {
    private const val PREFS = "lisa_chats"
    private const val CURRENT_ID = "current_id"

    fun newId(): String = UUID.randomUUID().toString()

    fun currentId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CURRENT_ID, null)

    fun setCurrentId(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(CURRENT_ID, id)
            .apply()
    }

    fun titleFor(messages: List<Message>): String {
        val first = messages.firstOrNull {
            it.role == Role.User && it.kind == MessageKind.Text
        }?.text?.trim().orEmpty()

        if (first.isEmpty()) return "New chat"
        val line = first.lineSequence().first().trim()
        return if (line.length <= 48) line else line.take(47) + "…"
    }

    fun save(context: Context, session: ChatSession) {
        val json = JSONObject().apply {
            put("id", session.id)
            put("title", session.title)
            put("updatedAt", session.updatedAt)
            put(
                "messages",
                JSONArray().apply {
                    for (message in session.messages) {
                        put(
                            JSONObject().apply {
                                put("role", if (message.role == Role.User) "user" else "assistant")
                                put("kind", message.kind.name)
                                put("text", message.text)
                            },
                        )
                    }
                },
            )
        }.toString()

        val folder = folder(context)
        val target = File(folder, "${session.id}.json")
        val tmp = File(folder, "${session.id}.${System.nanoTime()}.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(target)) {
            target.writeText(json)
            tmp.delete()
        }
    }

    fun load(context: Context, id: String): ChatSession? {
        val file = File(folder(context), "$id.json")
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            ChatSession(
                id = json.optString("id", id),
                title = json.optString("title", "Chat"),
                updatedAt = json.optLong("updatedAt", file.lastModified()),
                messages = parseMessages(json.optJSONArray("messages")),
            )
        }.getOrNull()
    }

    fun loadCurrent(context: Context): ChatSession? =
        currentId(context)?.let { load(context, it) }

    fun list(context: Context): List<ChatSummary> {
        val files = folder(context).listFiles { file ->
            file.isFile && file.name.endsWith(".json")
        } ?: return emptyList()

        return files.mapNotNull { file ->
            runCatching {
                val json = JSONObject(file.readText())
                ChatSummary(
                    id = json.getString("id"),
                    title = json.optString("title", "Chat"),
                    updatedAt = json.optLong("updatedAt", file.lastModified()),
                )
            }.getOrNull()
        }.sortedByDescending { it.updatedAt }
    }

    private fun folder(context: Context): File =
        File(context.filesDir, "chats").apply { mkdirs() }

    private fun parseMessages(array: JSONArray?): List<Message> {
        if (array == null) return emptyList()
        val messages = ArrayList<Message>(array.length())
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val role = if (json.optString("role") == "user") Role.User else Role.Assistant
            val kind = runCatching {
                MessageKind.valueOf(json.optString("kind", MessageKind.Text.name))
            }.getOrDefault(MessageKind.Text)
            messages.add(Message(role, json.optString("text"), kind))
        }
        return messages
    }
}
