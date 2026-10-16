package com.masterofchessstrategy.games.go

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

internal data class GoAiModelSpec(val fileName: String, val bytes: Long, val sha256: String) {
    init {
        require(fileName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.txt\\.gz")) && !fileName.contains(".."))
        require(bytes in 1..100_000_000 && sha256.matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        val OFFICIAL_15B = GoAiModelSpec(
            "kata1-b15c192-s1672170752-d466197061.txt.gz", 48_038_914,
            "09456899f1b9155217f4ca059cf8a68f79b7eba22cf6b7c00ffb7f17ce067967",
        )
    }
}

/** Compressed asset bytes, not decompressed weights. Streams remain owned by the caller. */
internal object GoAiModelIntegrity {
    fun copyVerified(
        source: InputStream,
        target: OutputStream?,
        spec: GoAiModelSpec = GoAiModelSpec.OFFICIAL_15B,
        cancelled: () -> Boolean = { false },
    ) {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var length = 0L
        while (true) {
            checkCancelled(cancelled)
            var count = source.read(buffer)
            if (count == 0) {
                val one = source.read()
                if (one < 0) break
                buffer[0] = one.toByte()
                count = 1
            }
            if (count < 0) break
            length += count
            if (length > spec.bytes) throw IOException("围棋 AI 模型文件超出已核验长度。")
            hash.update(buffer, 0, count)
            target?.write(buffer, 0, count)
        }
        checkCancelled(cancelled)
        val actual = hash.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        if (length != spec.bytes || actual != spec.sha256) throw IOException("围棋 AI 模型完整性校验失败。")
        target?.flush()
    }

    fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Go AI loading cancelled")
    }
}
