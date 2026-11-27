package com.lisaai.inference

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serialized wrapper around a single loaded GGUF model.
 *
 * One model is loaded at a time. Load/unload/generate are all guarded by [mutex] because
 * the native session can only be touched from one thread at a time.
 */
class LlamaEngine {

    private val mutex = Mutex()

    @Volatile
    private var handle: Long = 0L

    val isLoaded: Boolean get() = handle != 0L

    val backendInfo: String
        get() = if (LlamaNative.available) LlamaNative.systemInfo() else "native library unavailable"

    suspend fun load(file: File, contextSize: Int, threads: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                releaseLocked()
                runCatching {
                    val loaded = LlamaNative.nativeLoad(file.absolutePath, contextSize, threads)
                    check(loaded != 0L) { "Native loader returned no handle" }
                    handle = loaded
                }
            }
        }

    fun generate(prompt: String, maxTokens: Int, temperature: Float): Flow<String> = callbackFlow {
        val active = handle
        if (active == 0L) {
            close(IllegalStateException("No model loaded"))
            return@callbackFlow
        }

        val decoder = Utf8StreamDecoder()

        mutex.withLock {
            LlamaNative.nativeGenerate(
                active,
                prompt,
                temperature,
                40,
                0.95f,
                maxTokens,
                -1,
            ) { bytes ->
                val piece = decoder.decode(bytes)
                if (piece.isNotEmpty()) trySend(piece)
                true
            }
        }

        close()
        awaitClose { LlamaNative.nativeCancel(active) }
    }.flowOn(Dispatchers.Default)

    fun stop() {
        val active = handle
        if (active != 0L) LlamaNative.nativeCancel(active)
    }

    fun close() {
        val active = handle
        handle = 0L
        if (active != 0L) {
            LlamaNative.nativeCancel(active)
            Thread { LlamaNative.nativeFree(active) }.start()
        }
    }

    private fun releaseLocked() {
        val active = handle
        handle = 0L
        if (active != 0L) LlamaNative.nativeFree(active)
    }
}
