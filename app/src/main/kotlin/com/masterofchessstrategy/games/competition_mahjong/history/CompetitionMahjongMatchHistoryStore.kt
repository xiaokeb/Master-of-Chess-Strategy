package com.masterofchessstrategy.games.competition_mahjong.history

import android.content.Context
import android.util.AtomicFile
import com.masterofchessstrategy.engine.CompetitionMahjongRoundPhase
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongHandResult
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongPlayerScores
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

data class CompetitionMahjongRecordHistoryResult(
    val wasRecorded: Boolean,
    val history: CompetitionMahjongMatchHistory,
)

interface CompetitionMahjongMatchHistoryStore {
    suspend fun load(viewerPlayerId: Int): CompetitionMahjongMatchHistory
    suspend fun record(entry: CompetitionMahjongMatchHistoryEntry): CompetitionMahjongRecordHistoryResult
    suspend fun setFavorite(viewerPlayerId: Int, matchId: String, favorite: Boolean): CompetitionMahjongMatchHistory
    suspend fun delete(viewerPlayerId: Int, matchId: String): CompetitionMahjongMatchHistory
    /** Retracts a provisional completed sheet after final-hand undo; a missing sheet is harmless. */
    suspend fun withdraw(viewerPlayerId: Int, matchId: String): CompetitionMahjongMatchHistory
}

internal object CompetitionMahjongMatchHistoryCodec {
    private const val MAGIC = 0x4d434852 // MCHR
    private const val VERSION = 1
    private const val HEADER_BYTES = 12
    private const val HAND_BYTES = 28
    private const val RECORD_BYTES = 48 + 16 * HAND_BYTES
    private const val FOOTER_BYTES = 4
    const val MAX_RECORDS = 2_000
    const val MAX_BYTES = HEADER_BYTES + RECORD_BYTES * MAX_RECORDS + FOOTER_BYTES

    fun encode(viewerPlayerId: Int, entries: List<CompetitionMahjongMatchHistoryEntry>): ByteArray {
        require(viewerPlayerId in 0..3 && entries.size <= MAX_RECORDS)
        require(entries.all { it.viewerPlayerId == viewerPlayerId })
        val ordered = CompetitionMahjongMatchHistory.from(entries).entries
        val output = ByteBuffer.allocate(HEADER_BYTES + ordered.size * RECORD_BYTES + FOOTER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
        output.putInt(MAGIC).put(VERSION.toByte()).put(viewerPlayerId.toByte()).putShort(0)
            .putInt(ordered.size)
        ordered.forEach { entry ->
            output.put(entry.matchId.toByteArray(StandardCharsets.US_ASCII))
                .put(entry.difficulty.code.toByte()).put(entry.botLevel.toByte())
                .put(if (entry.favorite) 1.toByte() else 0.toByte()).put(0).putLong(entry.settledAtEpochMillis)
            entry.hands.forEach { hand ->
                output.put(hand.phase.code.toByte()).put((hand.winnerPlayerId ?: 255).toByte())
                    .put((hand.payerPlayerId ?: 255).toByte()).put(0)
                repeat(4) { output.putInt(hand.scores[it].toInt()) }
                output.putInt(hand.fanWithoutFlowers).putInt(hand.totalFan)
            }
        }
        output.putInt(CRC32().apply { update(output.array(), 0, output.position()) }.value.toInt())
        return output.array()
    }

    fun decode(bytes: ByteArray, viewerPlayerId: Int): CompetitionMahjongMatchHistory? {
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
            val entries = List(count) {
                val id = ByteArray(36).also(input::get).toString(StandardCharsets.US_ASCII)
                val difficultyCode = input.get().toInt()
                val difficulty = Difficulty.entries.firstOrNull { value -> value.code == difficultyCode } ?: return null
                val botLevel = input.get().toInt()
                val favorite = when (input.get().toInt()) { 0 -> false; 1 -> true; else -> return null }
                if (input.get().toInt() != 0) return null
                val settled = input.long
                val hands = List(16) { index ->
                    val phase = when (input.get().toInt()) {
                        CompetitionMahjongRoundPhase.WON.code -> CompetitionMahjongRoundPhase.WON
                        CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW.code -> CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW
                        else -> return null
                    }
                    fun player(): Int? = (input.get().toInt() and 255).let { value ->
                        if (value == 255) null else value
                    }
                    val winner = player()
                    val payer = player()
                    if (input.get().toInt() != 0) return null
                    val scores = CompetitionMahjongPlayerScores(
                        input.int.toLong(), input.int.toLong(), input.int.toLong(), input.int.toLong())
                    CompetitionMahjongHandResult(index, phase, scores, winner, payer, input.int, input.int)
                }
                CompetitionMahjongMatchHistoryEntry(
                    id, viewerPlayerId, difficulty, botLevel, settled, favorite, hands)
            }
            input.position(bytes.size - FOOTER_BYTES)
            val history = CompetitionMahjongMatchHistory.from(entries)
            history.takeIf { encode(viewerPlayerId, it.entries).contentEquals(bytes) }
        } catch (_: IllegalArgumentException) { null }
        catch (_: ArithmeticException) { null }
    }
}

class CompetitionMahjongMatchHistoryFileStore internal constructor(private val directory: File) :
    CompetitionMahjongMatchHistoryStore {
    constructor(context: Context) : this(context.applicationContext.filesDir)

    override suspend fun load(viewerPlayerId: Int): CompetitionMahjongMatchHistory = withContext(Dispatchers.IO) {
        require(viewerPlayerId in 0..3)
        val file = file(viewerPlayerId)
        lock(file).withLock { read(file, viewerPlayerId) }
    }

    override suspend fun record(entry: CompetitionMahjongMatchHistoryEntry): CompetitionMahjongRecordHistoryResult =
        withContext(Dispatchers.IO) {
            val file = file(entry.viewerPlayerId)
            lock(file).withLock {
                val current = read(file, entry.viewerPlayerId)
                val duplicate = current.entries.firstOrNull { it.matchId == entry.matchId }
                if (duplicate != null) {
                    require(duplicate.sameResult(entry)) { "相同赛事编号的成绩单冲突" }
                    return@withLock CompetitionMahjongRecordHistoryResult(false, current)
                }
                require(current.entries.size < CompetitionMahjongMatchHistoryCodec.MAX_RECORDS) {
                    "国标麻将成绩历史已达容量上限"
                }
                val next = CompetitionMahjongMatchHistory.from(current.entries + entry)
                write(file, CompetitionMahjongMatchHistoryCodec.encode(entry.viewerPlayerId, next.entries))
                CompetitionMahjongRecordHistoryResult(true, next)
            }
        }

    override suspend fun setFavorite(viewerPlayerId: Int, matchId: String, favorite: Boolean): CompetitionMahjongMatchHistory =
        mutate(viewerPlayerId) { current ->
            require(current.entries.any { it.matchId == matchId }) { "赛事成绩单不存在" }
            CompetitionMahjongMatchHistory.from(current.entries.map {
                if (it.matchId == matchId) it.withFavorite(favorite) else it
            })
        }

    override suspend fun delete(viewerPlayerId: Int, matchId: String): CompetitionMahjongMatchHistory =
        mutate(viewerPlayerId) { current ->
            require(current.entries.any { it.matchId == matchId }) { "赛事成绩单不存在" }
            CompetitionMahjongMatchHistory.from(current.entries.filterNot { it.matchId == matchId })
        }

    override suspend fun withdraw(viewerPlayerId: Int, matchId: String): CompetitionMahjongMatchHistory =
        withContext(Dispatchers.IO) {
            require(viewerPlayerId in 0..3)
            val file = file(viewerPlayerId)
            lock(file).withLock {
                val current = read(file, viewerPlayerId)
                if (current.entries.none { it.matchId == matchId }) return@withLock current
                val next = CompetitionMahjongMatchHistory.from(current.entries.filterNot { it.matchId == matchId })
                write(file, CompetitionMahjongMatchHistoryCodec.encode(viewerPlayerId, next.entries))
                next
            }
        }

    private suspend fun mutate(viewer: Int,
                               transform: (CompetitionMahjongMatchHistory) -> CompetitionMahjongMatchHistory):
        CompetitionMahjongMatchHistory =
        withContext(Dispatchers.IO) {
            require(viewer in 0..3)
            val file = file(viewer)
            lock(file).withLock {
                val history = transform(read(file, viewer))
                write(file, CompetitionMahjongMatchHistoryCodec.encode(viewer, history.entries))
                history
            }
        }

    private fun read(file: File, viewer: Int): CompetitionMahjongMatchHistory {
        val bytes = try { readBytes(file) } catch (error: FileNotFoundException) {
            if (file.exists() || File(file.path + ".bak").exists()) throw error
            return CompetitionMahjongMatchHistory.empty()
        }
        return CompetitionMahjongMatchHistoryCodec.decode(bytes, viewer)
            ?: throw IOException("国标麻将成绩历史损坏或版本不兼容，原数据已保留")
    }

    private fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        var finished = false
        try {
            output.write(bytes)
            atomic.finishWrite(output)
            finished = true
            if (!readBytes(file).contentEquals(bytes)) throw IOException("国标麻将成绩历史写入校验失败")
        } catch (failure: Exception) {
            if (!finished) try { atomic.failWrite(output) } catch (rollback: Exception) { failure.addSuppressed(rollback) }
            throw failure
        }
    }

    private fun readBytes(file: File): ByteArray = AtomicFile(file).openRead().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val remaining = CompetitionMahjongMatchHistoryCodec.MAX_BYTES + 1 - output.size()
            if (remaining <= 0) throw IOException("国标麻将成绩历史超过容量限制")
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            if (count == 0) {
                val next = input.read()
                if (next < 0) break
                output.write(next)
            } else output.write(buffer, 0, count)
            if (output.size() > CompetitionMahjongMatchHistoryCodec.MAX_BYTES)
                throw IOException("国标麻将成绩历史超过容量限制")
        }
        output.toByteArray()
    }

    private fun file(viewer: Int) = File(directory, "competition-mahjong-match-history-v1-$viewer.bin")
    private fun lock(file: File) = locks.computeIfAbsent(file.canonicalPath) { Mutex() }

    private companion object { val locks = ConcurrentHashMap<String, Mutex>() }
}
