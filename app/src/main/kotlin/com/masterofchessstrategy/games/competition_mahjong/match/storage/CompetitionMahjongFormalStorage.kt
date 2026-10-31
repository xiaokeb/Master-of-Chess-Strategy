package com.masterofchessstrategy.games.competition_mahjong.match.storage

import android.util.AtomicFile
import kotlinx.coroutines.sync.Mutex
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A backend must atomically replace one bounded value and share its lock across instances. */
internal interface CompetitionMahjongFormalStorage {
    val mutex: Mutex
    fun read(): ByteArray?
    fun replace(bytes: ByteArray)
}

internal class CompetitionMahjongFormalFileStorage(directory: File, viewerPlayerId: Int) :
    CompetitionMahjongFormalStorage {
    init { require(viewerPlayerId in 0..3) }
    private val file = File(directory, "competition-mahjong-formal-v1-$viewerPlayerId.bin")
    override val mutex: Mutex = locks.computeIfAbsent(file.canonicalPath) { Mutex() }

    override fun read(): ByteArray? {
        val input = try { AtomicFile(file).openRead() } catch (failure: FileNotFoundException) {
            if (file.exists() || File(file.path + ".bak").exists()) throw failure
            return null
        }
        return input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val remaining = CompetitionMahjongFormalSnapshotCodec.MAX_BYTES + 1 - output.size()
                if (remaining <= 0) throw IOException("国标麻将正式事务存档超过容量限制")
                val count = it.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                if (count == 0) {
                    val next = it.read()
                    if (next < 0) break
                    output.write(next)
                } else output.write(buffer, 0, count)
                if (output.size() > CompetitionMahjongFormalSnapshotCodec.MAX_BYTES)
                    throw IOException("国标麻将正式事务存档超过容量限制")
            }
            output.toByteArray()
        }
    }

    override fun replace(bytes: ByteArray) {
        require(bytes.size <= CompetitionMahjongFormalSnapshotCodec.MAX_BYTES)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        var finished = false
        try {
            output.write(bytes)
            atomic.finishWrite(output)
            finished = true
            if (read()?.contentEquals(bytes) != true) throw IOException("国标麻将正式事务写入校验失败")
        } catch (failure: Exception) {
            if (!finished) try { atomic.failWrite(output) }
            catch (rollback: Exception) { failure.addSuppressed(rollback) }
            throw failure
        }
    }

    private companion object { val locks = ConcurrentHashMap<String, Mutex>() }
}
