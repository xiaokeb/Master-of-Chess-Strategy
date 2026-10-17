package com.masterofchessstrategy.games.go.data

import android.content.Context
import com.masterofchessstrategy.games.go.GoEndgameProgress
import com.masterofchessstrategy.games.go.GoEndgameProgressCodec
import com.masterofchessstrategy.games.go.GoSavedSession
import com.masterofchessstrategy.games.go.GoSessionFileCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Read-only migration APIs deliberately cannot call AtomicFile recovery or a legacy writer. */
internal fun interface GoLocalLegacySource { suspend fun readCommitted(): GoSavedSession? }
internal fun interface GoEndgameLegacySource { suspend fun readCommitted(): GoEndgameProgress }

internal class GoLocalSessionDiskSource(private val file: File) : GoLocalLegacySource {
    constructor(context: Context) : this(context.applicationContext.getFileStreamPath("go-session-v1.bin"))
    override suspend fun readCommitted(): GoSavedSession? = withContext(Dispatchers.IO) {
        val bytes = GoLegacyDiskSnapshot.read(file, GoSessionFileCodec.MAX_FILE_BYTES) ?: return@withContext null
        GoSessionFileCodec.decode(bytes) ?: throw IOException("围棋旧对局无效，原文件已保留")
    }
}

internal class GoEndgameDiskSource(private val file: File) : GoEndgameLegacySource {
    constructor(context: Context) : this(context.applicationContext.getFileStreamPath("go-endgame-progress-v1.bin"))
    override suspend fun readCommitted(): GoEndgameProgress = withContext(Dispatchers.IO) {
        val bytes = GoLegacyDiskSnapshot.read(file, GoEndgameProgressCodec.MAX_BYTES)
            ?: return@withContext GoEndgameProgress()
        GoEndgameProgressCodec.decode(bytes) ?: throw IOException("围棋旧残局进度无效，原文件已保留")
    }
}

/** Prefer the prior committed .bak image, but never rename, delete or replace either source. */
internal object GoLegacyDiskSnapshot {
    fun read(file: File, limit: Int): ByteArray? {
        require(limit in 1..2_000_000)
        val backup = File(file.path + ".bak")
        val source = if (backup.exists()) backup else file
        if (!source.exists()) return null
        return source.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                if (count < 0) break
                if (count == 0) {
                    val next = input.read()
                    if (next < 0) break
                    output.write(next)
                } else output.write(buffer, 0, count)
                if (output.size() > limit) throw IOException("围棋旧数据超过容量限制，原文件已保留")
            }
            output.toByteArray()
        }
    }
}
