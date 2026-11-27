package com.lisaai.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lisaai.data.ModelDownloader
import com.lisaai.inference.Agent
import com.lisaai.inference.LlamaEngine
import com.lisaai.inference.Message
import com.lisaai.inference.MessageKind
import com.lisaai.inference.Prompt
import com.lisaai.inference.Role
import com.lisaai.terminal.ShellExecutor
import com.lisaai.terminal.TerminalBootstrap
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val input: String = "",
    val generating: Boolean = false,
    val importing: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val modelLoaded: Boolean = false,
    val status: String = "No model loaded",
    val agentMode: Boolean = false,
    val pendingCommand: String? = null,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = LlamaEngine()
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val systemPrompt = "You are Lisa, a concise and helpful assistant running fully on this device."

    private var generationJob: Job? = null
    private var stopRequested = false
    private var agentSteps = 0

    fun onInput(value: String) = _state.update { it.copy(input = value) }

    fun setAgentMode(enabled: Boolean) {
        val current = _state.value
        if (current.generating) return

        if (!enabled) {
            _state.update { it.copy(agentMode = false, pendingCommand = null, status = "Ready") }
            return
        }

        if (!TerminalBootstrap.isInstalled()) {
            _state.update {
                it.copy(
                    agentMode = false,
                    status = "Open Terminal once to set up the shell",
                )
            }
            return
        }

        _state.update {
            it.copy(agentMode = true, pendingCommand = null, status = "Ready")
        }
    }

    fun downloadDefaultModel() {
        if (_state.value.downloading || _state.value.generating) return

        val app = getApplication<Application>()
        viewModelScope.launch {
            _state.update {
                it.copy(downloading = true, downloadProgress = 0f, status = "Downloading Qwen3.5 0.8B...")
            }

            val result = runCatching {
                val folder = File(app.filesDir, "models").apply { mkdirs() }
                val target = File(folder, "model.gguf")
                withContext(Dispatchers.IO) {
                    ModelDownloader.download(DEFAULT_MODEL_URL, target) { fraction ->
                        _state.update { current -> current.copy(downloadProgress = fraction) }
                    }
                }
                target
            }

            result
                .onSuccess { file ->
                    _state.update {
                        it.copy(downloading = false, downloadProgress = 1f, status = "Download complete")
                    }
                    load(file)
                }
                .onFailure {
                    _state.update { current ->
                        current.copy(downloading = false, status = "Download failed: ${it.message}")
                    }
                }
        }
    }

    fun importModel(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _state.update { it.copy(importing = true, status = "Importing model...") }

            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    val folder = File(app.filesDir, "models").apply { mkdirs() }
                    val target = File(folder, "model.gguf")
                    app.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Could not open the selected file" }
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target
                }
            }

            imported
                .onSuccess { load(it) }
                .onFailure {
                    _state.update { current ->
                        current.copy(importing = false, status = "Import failed: ${it.message}")
                    }
                }
        }
    }

    private fun load(file: File) {
        viewModelScope.launch {
            _state.update { it.copy(status = "Loading model...") }
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

            engine.load(file, contextSize = CONTEXT_SIZE, threads = threads)
                .onSuccess {
                    _state.update { current ->
                        current.copy(importing = false, modelLoaded = true, status = "Ready")
                    }
                }
                .onFailure {
                    _state.update { current ->
                        current.copy(
                            importing = false,
                            modelLoaded = false,
                            status = "Load failed: ${it.message}",
                        )
                    }
                }
        }
    }

    fun send() {
        val snapshot = _state.value
        val prompt = snapshot.input.trim()
        if (prompt.isEmpty() || !snapshot.modelLoaded || snapshot.generating) return
        if (snapshot.pendingCommand != null) return

        if (snapshot.agentMode && !TerminalBootstrap.isInstalled()) {
            _state.update { it.copy(status = "Open Terminal once to set up the shell") }
            return
        }

        if (snapshot.agentMode) agentSteps = 0

        val history = snapshot.messages + Message(Role.User, prompt)
        _state.update {
            it.copy(input = "", generating = true, pendingCommand = null)
        }
        generateReply(history, if (snapshot.agentMode) Agent.MODE_PROMPT else systemPrompt)
    }

    fun approvePendingCommand() {
        val snapshot = _state.value
        val command = snapshot.pendingCommand ?: return
        if (!snapshot.modelLoaded || snapshot.generating) return

        if (!TerminalBootstrap.isInstalled()) {
            _state.update { it.copy(pendingCommand = null, status = "Open Terminal once to set up the shell") }
            return
        }

        stopRequested = false
        _state.update {
            it.copy(
                pendingCommand = null,
                generating = true,
                status = "Running command…",
                messages = it.messages + Message(Role.Assistant, command, MessageKind.Command),
            )
        }

        generationJob = viewModelScope.launch {
            val result = ShellExecutor.run(command)

            val output = buildString {
                append("Command output")
                if (result.timedOut) append(" (timed out)")
                if (result.exitCode != 0) append(" (exit ${result.exitCode})")
                append(":\n")
                append(result.output.ifBlank { "(no output)" })
            }

            agentSteps += 1
            _state.update {
                it.copy(
                    messages = it.messages + Message(Role.User, output, MessageKind.Output),
                    status = "Ready",
                )
            }

            if (stopRequested) {
                stopRequested = false
                _state.update { it.copy(generating = false, status = "Stopped") }
                return@launch
            }

            if (agentSteps >= MAX_AGENT_STEPS) {
                _state.update {
                    it.copy(
                        generating = false,
                        status = "Agent step limit reached",
                        messages = it.messages + Message(
                            Role.Assistant,
                            "Stopped after $MAX_AGENT_STEPS commands. Send a new message to continue.",
                            MessageKind.Note,
                        ),
                    )
                }
                return@launch
            }

            generateReply(_state.value.messages, Agent.MODE_PROMPT)
        }
    }

    fun skipPendingCommand() {
        val command = _state.value.pendingCommand ?: return
        _state.update {
            it.copy(
                pendingCommand = null,
                status = "Ready",
                messages = it.messages + Message(
                    Role.Assistant,
                    "Skipped command: $command",
                    MessageKind.Note,
                ),
            )
        }
    }

    private fun generateReply(history: List<Message>, system: String) {
        if (!_state.value.modelLoaded) return

        stopRequested = false
        val agent = _state.value.agentMode
        val withPlaceholder = history + Message(Role.Assistant, "")
        _state.update {
            it.copy(
                generating = true,
                pendingCommand = null,
                status = if (agent) "Thinking…" else "Generating…",
                messages = withPlaceholder,
            )
        }

        generationJob = viewModelScope.launch {
            var raw = ""
            var failure: Throwable? = null

            try {
                val formatted = buildPrompt(history, system)
                engine.generate(
                    prompt = formatted,
                    maxTokens = if (agent) MAX_AGENT_NEW_TOKENS else MAX_CHAT_NEW_TOKENS,
                    temperature = 0.7f,
                ).collect { chunk ->
                    raw += chunk
                    val visible = Prompt.stripThinking(raw)
                    _state.update { current ->
                        current.copy(messages = current.messages.replaceLast(visible))
                    }
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                failure = t
            }

            val visible = Prompt.stripThinking(raw).trim()
            val error = failure
            if (error != null) {
                _state.update {
                    it.copy(
                        generating = false,
                        status = "Generation error: ${error.message}",
                        messages = it.messages.replaceLast(
                            visible.ifEmpty { "Generation failed: ${error.message}" },
                        ),
                    )
                }
                return@launch
            }

            if (stopRequested) {
                stopRequested = false
                _state.update {
                    it.copy(
                        generating = false,
                        status = "Stopped",
                        messages = it.messages.replaceLast(visible.ifEmpty { "(stopped)" }),
                    )
                }
                return@launch
            }

            val proposal = if (agent) Agent.parse(visible) else null
            if (proposal != null) {
                _state.update {
                    it.copy(
                        generating = false,
                        status = "Approve or skip the command",
                        messages = it.messages.replaceLast(
                            proposal.text.ifEmpty { "I can run this command:" },
                        ),
                        pendingCommand = proposal.command,
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        generating = false,
                        status = "Ready",
                        messages = it.messages.replaceLast(visible.ifEmpty { "(no response)" }),
                    )
                }
            }
        }
    }

    /**
     * Builds a prompt that fits the model context. Older turns are dropped first; if a
     * single turn is still too large, its text is shortened.
     */
    private suspend fun buildPrompt(history: List<Message>, system: String): String {
        var messages = history.filter { it.kind != MessageKind.Note }
        val budget = CONTEXT_SIZE - MAX_AGENT_NEW_TOKENS - 64

        while (messages.size > 1) {
            val formatted = Prompt.chat(system, messages)
            val tokens = engine.countTokens(formatted) ?: return formatted
            if (tokens <= budget) return formatted
            messages = messages.drop(1)
        }

        var formatted = Prompt.chat(system, messages)
        val tokens = engine.countTokens(formatted) ?: return formatted
        if (tokens > budget && messages.isNotEmpty()) {
            val maxChars = maxOf(200, budget * 3)
            val last = messages.last()
            messages = listOf(last.copy(text = last.text.take(maxChars)))
            formatted = Prompt.chat(system, messages)
        }
        return formatted
    }

    fun stop() {
        stopRequested = true
        engine.stop()
        _state.update { it.copy(status = "Stopping…") }
    }

    private fun List<Message>.replaceLast(text: String): List<Message> =
        dropLast(1) + Message(Role.Assistant, text)

    override fun onCleared() {
        generationJob?.cancel()
        engine.close()
    }

    companion object {
        private const val CONTEXT_SIZE = 2048
        private const val MAX_CHAT_NEW_TOKENS = 512
        private const val MAX_AGENT_NEW_TOKENS = 768
        private const val MAX_AGENT_STEPS = 6

        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf"
    }
}
