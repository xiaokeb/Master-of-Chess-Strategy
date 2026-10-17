package com.masterofchessstrategy.games.go

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** File providers may omit or misreport size; enforce the native limit while reading. */
internal object GoSgfDocument {
    const val MAX_BYTES = 1_000_000

    fun read(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - bytes.size()))
            if (count < 0) break
            if (count == 0) {
                val next = input.read()
                if (next < 0) break
                bytes.write(next)
            } else bytes.write(buffer, 0, count)
            if (bytes.size() > MAX_BYTES) throw IOException("SGF 文件超过 1,000,000 字节")
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString().removePrefix("\uFEFF")
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw IOException("SGF 文件必须使用有效的 UTF-8 编码", error)
        }
        encode(text)
        return text
    }

    fun encode(text: String): ByteArray {
        if (text.isBlank() || '\u0000' in text) throw IOException("SGF 文件为空或包含无效字符")
        // UTF-8 needs at least one byte per UTF-16 code unit for valid input.
        if (text.length > MAX_BYTES) throw IOException("SGF 文件超过 1,000,000 字节")
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = try {
            encoder.encode(CharBuffer.wrap(text))
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw IOException("SGF 文本包含无效的 Unicode 字符", error)
        }
        if (encoded.remaining() > MAX_BYTES) throw IOException("SGF 文件超过 1,000,000 字节")
        return ByteArray(encoded.remaining()).also(encoded::get)
    }

    fun write(text: String, output: OutputStream) {
        output.write(encode(text))
        output.flush()
    }
}
