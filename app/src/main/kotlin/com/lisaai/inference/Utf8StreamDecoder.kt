package com.lisaai.inference

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * llama.cpp emits raw token bytes. A multi-byte UTF-8 character can be split across
 * two tokens, so decoding must keep state between calls.
 */
class Utf8StreamDecoder {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    fun decode(bytes: ByteArray): String = try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (t: Throwable) {
        ""
    }
}
