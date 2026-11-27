package com.lisaai.inference

/**
 * Raw JNI surface. Use [LlamaEngine] instead of calling this directly.
 */
object LlamaNative {
    private val loadError: Throwable? = try {
        System.loadLibrary("lisa_llama")
        null
    } catch (t: Throwable) {
        t
    }

    val available: Boolean get() = loadError == null
    val error: String? get() = loadError?.message

    external fun systemInfo(): String

    external fun nativeLoad(path: String, nCtx: Int, threads: Int): Long

    external fun nativeFree(handle: Long)

    external fun nativeCancel(handle: Long)

    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        temperature: Float,
        topK: Int,
        topP: Float,
        maxTokens: Int,
        seed: Int,
        callback: TokenCallback,
    ): Int

    external fun nativeTokenCount(handle: Long, text: String): Int
}

fun interface TokenCallback {
    /** @return false to stop generation immediately. */
    fun onToken(piece: ByteArray): Boolean
}
