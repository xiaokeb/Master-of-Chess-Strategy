package com.masterofchessstrategy.games.go.data

import android.content.Context
import com.masterofchessstrategy.games.go.GoAiMatchFileCodec
import com.masterofchessstrategy.games.go.GoAiSavedMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Migration cannot write a checkpoint, recover AtomicFile, or authenticate ranked participation. */
internal fun interface GoAiLegacySource { suspend fun readCommitted(): GoAiSavedMatch? }

internal class GoAiLegacyDiskSource(private val file: File) : GoAiLegacySource {
    constructor(context: Context) : this(context.applicationContext.getFileStreamPath("go-ai-match-v1.bin"))

    override suspend fun readCommitted(): GoAiSavedMatch? = withContext(Dispatchers.IO) {
        // Historical AtomicFile backups take precedence. A lone .new was never committed.
        val backup = File(file.path + ".bak")
        val source = if (backup.exists()) backup else file
        if (!source.exists()) return@withContext null
        val bytes = source.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            // Includes both one-million-byte native images and the complete GAI header/ID.
            val limit = GoAiMatchFileCodec.MAX_BYTES
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                if (count < 0) break
                if (count == 0) {
                    val next = input.read()
                    if (next < 0) break
                    output.write(next)
                } else output.write(buffer, 0, count)
                if (output.size() > limit) throw IOException("围棋旧人机存档超过容量限制，原文件已保留")
            }
            output.toByteArray()
        }
        GoAiMatchFileCodec.decode(bytes) ?: throw IOException("围棋旧人机存档校验失败，原文件已保留")
    }
}
