package com.masterofchessstrategy.engine

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Standard UTF-8, never JNI modified UTF-8 or silent surrogate replacement. */
internal object GoSgfTextCodec {
    const val MAX_BYTES = 1_000_000

    fun encodeOrNull(text: String): ByteArray? {
        if (text.length > MAX_BYTES) return null
        return try {
            val buffer = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text))
            if (buffer.remaining() > MAX_BYTES) null
            else ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (_: CharacterCodingException) {
            null
        }
    }

    fun decode(data: ByteArray): String {
        check(data.size <= MAX_BYTES) { "Oversized native Go SGF" }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data)).toString()
    }
}
