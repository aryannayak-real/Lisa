package com.lisaai.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lisaai.data.ModelDownloader
import com.lisaai.inference.LlamaEngine
import com.lisaai.inference.Message
import com.lisaai.inference.Prompt
import com.lisaai.inference.Role
import java.io.File
import kotlinx.coroutines.Dispatchers
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
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = LlamaEngine()
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val systemPrompt = "You are Lisa, a concise and helpful assistant running fully on this device."

    fun onInput(value: String) = _state.update { it.copy(input = value) }

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

            engine.load(file, contextSize = 2048, threads = threads)
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

        val withPlaceholder = snapshot.messages + Message(Role.User, prompt) + Message(Role.Assistant, "")
        _state.update { it.copy(input = "", generating = true, messages = withPlaceholder) }

        val history = withPlaceholder.dropLast(1)
        val formatted = Prompt.chat(systemPrompt, history)

        viewModelScope.launch {
            var raw = ""
            try {
                engine.generate(formatted, maxTokens = 512, temperature = 0.7f).collect { chunk ->
                    raw += chunk
                    val visible = Prompt.stripThinking(raw)
                    _state.update { current ->
                        current.copy(messages = current.messages.replaceLast(visible))
                    }
                }
            } catch (t: Throwable) {
                _state.update { it.copy(status = "Generation error: ${t.message}") }
            } finally {
                _state.update { it.copy(generating = false) }
            }
        }
    }

    fun stop() {
        engine.stop()
        _state.update { it.copy(generating = false) }
    }

    private fun List<Message>.replaceLast(text: String): List<Message> =
        dropLast(1) + Message(Role.Assistant, text)

    override fun onCleared() {
        engine.close()
    }

    companion object {
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf"
    }
}
