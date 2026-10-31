package com.masterofchessstrategy.games.competition_mahjong.statistics

import android.content.Context
import android.util.AtomicFile
import com.masterofchessstrategy.engine.Difficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

data class CompetitionMahjongRecordStatisticsResult(
    val wasRecorded: Boolean,
    val statistics: CompetitionMahjongStatistics,
)

interface CompetitionMahjongStatisticsStore {
    suspend fun load(viewerPlayerId: Int): CompetitionMahjongStatistics
    suspend fun record(outcome: CompetitionMahjongSeriesOutcome): CompetitionMahjongRecordStatisticsResult
    /** Undoing the final hand removes its provisional series result; missing IDs are harmless. */
    suspend fun withdraw(viewerPlayerId: Int, matchId: String): CompetitionMahjongStatistics
}

internal object CompetitionMahjongStatisticsCodec {
    private const val MAGIC = 0x4d435354 // MCST
    private const val VERSION = 1
    private const val HEADER_BYTES = 12
    private const val RECORD_BYTES = 68
    private const val FOOTER_BYTES = 4
    const val MAX_RECORDS = 10_000
    const val MAX_BYTES = HEADER_BYTES + RECORD_BYTES * MAX_RECORDS + FOOTER_BYTES

    fun encode(viewerPlayerId: Int, outcomes: List<CompetitionMahjongSeriesOutcome>): ByteArray {
        require(viewerPlayerId in 0..3 && outcomes.size <= MAX_RECORDS)
        require(outcomes.all { it.viewerPlayerId == viewerPlayerId })
        val ordered = CompetitionMahjongStatistics.from(outcomes).outcomes
        val output = ByteBuffer.allocate(HEADER_BYTES + ordered.size * RECORD_BYTES + FOOTER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
        output.putInt(MAGIC).put(VERSION.toByte()).put(viewerPlayerId.toByte()).putShort(0)
            .putInt(ordered.size)
        ordered.forEach { value ->
            output.put(value.matchId.toByteArray(StandardCharsets.US_ASCII))
            output.put(value.difficulty.code.toByte()).put(value.rank.toByte())
                .put(value.standardPointsNumerator.toByte()).put(value.standardPointsDenominator.toByte())
                .putLong(value.settledAtEpochMillis).putLong(value.score)
                .put(value.handWins.toByte()).put(value.selfDrawWins.toByte()).put(value.dealInCount.toByte()).put(0)
                .putInt(value.totalFanWon).putInt(value.highestFan)
        }
        output.putInt(CRC32().apply { update(output.array(), 0, output.position()) }.value.toInt())
        return output.array()
    }

    fun decode(bytes: ByteArray, viewerPlayerId: Int): CompetitionMahjongStatistics? {
        require(viewerPlayerId in 0..3)
        if (bytes.size !in HEADER_BYTES + FOOTER_BYTES..MAX_BYTES ||
            (bytes.size - HEADER_BYTES - FOOTER_BYTES) % RECORD_BYTES != 0) return null
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (input.int != MAGIC || input.get().toInt() != VERSION ||
            (input.get().toInt() and 255) != viewerPlayerId || input.short.toInt() != 0) return null
        val count = input.int
        if (count !in 0..MAX_RECORDS || bytes.size != HEADER_BYTES + count * RECORD_BYTES + FOOTER_BYTES ||
            input.getInt(bytes.size - FOOTER_BYTES) != CRC32().apply {
                update(bytes, 0, bytes.size - FOOTER_BYTES)
            }.value.toInt()) return null
        return try {
            val outcomes = List(count) {
                val id = ByteArray(36).also(input::get).toString(StandardCharsets.US_ASCII)
                val difficultyCode = input.get().toInt()
                val difficulty = Difficulty.entries.firstOrNull { it.code == difficultyCode } ?: return null
                val rank = input.get().toInt()
                val numerator = input.get().toInt()
                val denominator = input.get().toInt()
                val settled = input.long
                val score = input.long
                val wins = input.get().toInt()
                val selfDraws = input.get().toInt()
                val dealIns = input.get().toInt()
                if (input.get().toInt() != 0) return null
                CompetitionMahjongSeriesOutcome(id, viewerPlayerId, difficulty, settled, rank, score,
                    numerator, denominator, wins, selfDraws, dealIns, input.int, input.int)
            }
            input.position(bytes.size - FOOTER_BYTES)
            val statistics = CompetitionMahjongStatistics.from(outcomes)
            statistics.takeIf { encode(viewerPlayerId, it.outcomes).contentEquals(bytes) }
        } catch (_: IllegalArgumentException) { null }
        catch (_: ArithmeticException) { null }
    }
}

class CompetitionMahjongStatisticsFileStore internal constructor(private val directory: File) :
    CompetitionMahjongStatisticsStore {
    constructor(context: Context) : this(context.applicationContext.filesDir)

    override suspend fun load(viewerPlayerId: Int): CompetitionMahjongStatistics = withContext(Dispatchers.IO) {
        require(viewerPlayerId in 0..3)
        val file = file(viewerPlayerId)
        lock(file).withLock { read(file, viewerPlayerId) }
    }

    override suspend fun record(outcome: CompetitionMahjongSeriesOutcome): CompetitionMahjongRecordStatisticsResult =
        withContext(Dispatchers.IO) {
            val file = file(outcome.viewerPlayerId)
            lock(file).withLock {
                val current = read(file, outcome.viewerPlayerId)
                val duplicate = current.outcomes.firstOrNull { it.matchId == outcome.matchId }
                if (duplicate != null) {
                    require(duplicate.sameResult(outcome)) { "相同赛事编号的统计结果冲突" }
                    return@withLock CompetitionMahjongRecordStatisticsResult(false, current)
                }
                require(current.outcomes.size < CompetitionMahjongStatisticsCodec.MAX_RECORDS) {
                    "国标麻将统计已达容量上限"
                }
                val next = CompetitionMahjongStatistics.from(current.outcomes + outcome)
                write(file, CompetitionMahjongStatisticsCodec.encode(outcome.viewerPlayerId, next.outcomes))
                CompetitionMahjongRecordStatisticsResult(true, next)
            }
        }

    override suspend fun withdraw(viewerPlayerId: Int, matchId: String): CompetitionMahjongStatistics =
        withContext(Dispatchers.IO) {
            require(viewerPlayerId in 0..3)
            val file = file(viewerPlayerId)
            lock(file).withLock {
                val current = read(file, viewerPlayerId)
                if (current.outcomes.none { it.matchId == matchId }) return@withLock current
                val next = CompetitionMahjongStatistics.from(current.outcomes.filterNot { it.matchId == matchId })
                write(file, CompetitionMahjongStatisticsCodec.encode(viewerPlayerId, next.outcomes))
                next
            }
        }

    private fun read(file: File, viewer: Int): CompetitionMahjongStatistics {
        val bytes = try { readBytes(file) } catch (error: FileNotFoundException) {
            if (file.exists() || File(file.path + ".bak").exists()) throw error
            return CompetitionMahjongStatistics.empty()
        }
        return CompetitionMahjongStatisticsCodec.decode(bytes, viewer)
            ?: throw IOException("国标麻将统计损坏或版本不兼容，原数据已保留")
    }

    private fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        var finished = false
        try {
            output.write(bytes)
            atomic.finishWrite(output)
            finished = true
            if (!readBytes(file).contentEquals(bytes)) throw IOException("国标麻将统计写入校验失败")
        } catch (failure: Exception) {
            if (!finished) try { atomic.failWrite(output) }
            catch (rollback: Exception) { failure.addSuppressed(rollback) }
            throw failure
        }
    }

    private fun readBytes(file: File): ByteArray = AtomicFile(file).openRead().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val remaining = CompetitionMahjongStatisticsCodec.MAX_BYTES + 1 - output.size()
            if (remaining <= 0) throw IOException("国标麻将统计超过容量限制")
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            if (count == 0) {
                val next = input.read()
                if (next < 0) break
                output.write(next)
            } else output.write(buffer, 0, count)
            if (output.size() > CompetitionMahjongStatisticsCodec.MAX_BYTES)
                throw IOException("国标麻将统计超过容量限制")
        }
        output.toByteArray()
    }

    private fun file(viewer: Int) = File(directory, "competition-mahjong-statistics-v1-$viewer.bin")
    private fun lock(file: File) = locks.computeIfAbsent(file.canonicalPath) { Mutex() }

    private companion object { val locks = ConcurrentHashMap<String, Mutex>() }
}
