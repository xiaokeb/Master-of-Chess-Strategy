package com.masterofchessstrategy.games.go

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.time.LocalDate

/** Completion is a contiguous content prefix; selecting a locked or unknown puzzle is invalid. */
class GoEndgameProgress(
    completedIds: Set<String> = emptySet(),
    val lastPuzzleId: String = GoEndgameLibrary.puzzles.first().id,
    val lastMode: GoEndgameChallengeMode = GoEndgameChallengeMode.MAINLINE,
    val dailyDate: LocalDate? = null,
    val dailyPuzzleId: String? = null,
) {
    val completedIds: Set<String> = java.util.Collections.unmodifiableSet(completedIds.toSet())
    init {
        val ids = GoEndgameLibrary.puzzles.map { it.id }
        require(this.completedIds == ids.take(this.completedIds.size).toSet()) { "残局进度不是连续关卡" }
        require(isUnlocked(lastPuzzleId)) { "残局返回位置尚未开放" }
        require((dailyDate == null) == (dailyPuzzleId == null)) { "每日残局字段不完整" }
        require(dailyPuzzleId == null || isUnlocked(dailyPuzzleId)) { "每日残局尚未开放" }
        require(lastMode != GoEndgameChallengeMode.DAILY || dailyPuzzleId == lastPuzzleId && dailyDate != null)
    }
    fun isUnlocked(id: String): Boolean {
        val index = GoEndgameLibrary.puzzles.indexOfFirst { it.id == id }
        return index >= 0 && index <= completedIds.size
    }
    fun select(id: String, mode: GoEndgameChallengeMode = GoEndgameChallengeMode.MAINLINE) =
        GoEndgameProgress(completedIds, id, mode, dailyDate, dailyPuzzleId)
    fun selectDaily(date: LocalDate, id: String): GoEndgameProgress {
        require(GoEndgameChallenges.daily(this, date).id == id) { "每日残局选择已变化，请重试" }
        return GoEndgameProgress(completedIds, id, GoEndgameChallengeMode.DAILY, date, id)
    }
    fun complete(id: String): GoEndgameProgress {
        require(isUnlocked(id)) { "不能通关未开放的残局" }
        // Preserve the active selection when another instance repeats an older puzzle.
        return GoEndgameProgress(completedIds + id, lastPuzzleId, lastMode, dailyDate, dailyPuzzleId)
    }
}

/** Atomic read/merge/write operations prevent a stale screen from erasing another screen's progress.
 * Completion progress grants no stars, points, match wins or rank unlocks.
 */
interface GoEndgameProgressRepository {
    suspend fun load(): GoEndgameProgress
    suspend fun select(id: String, mode: GoEndgameChallengeMode = GoEndgameChallengeMode.MAINLINE): GoEndgameProgress
    suspend fun selectDaily(date: LocalDate, id: String): GoEndgameProgress
    suspend fun complete(id: String): GoEndgameProgress
}

internal object GoEndgameProgressCodec {
    const val MAX_BYTES = 4_096
    fun encode(progress: GoEndgameProgress): ByteArray {
        val ordered = GoEndgameLibrary.puzzles.map { it.id }.filter { it in progress.completedIds }
        val payload = "GEP1\n2\n${GoEndgameLibrary.CONTENT_VERSION}\n${progress.lastPuzzleId}\n${ordered.joinToString(",")}\n" +
            "${progress.lastMode.name}\n${progress.dailyDate ?: "-"}\n${progress.dailyPuzzleId ?: "-"}\n"
        return encodePayload(payload)
    }
    private fun encodePayload(payload: String): ByteArray {
        val crc = CRC32().apply { update(payload.toByteArray(Charsets.UTF_8)) }.value.toString(16).padStart(8, '0')
        return "$payload$crc\n".toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
    }
    fun decode(bytes: ByteArray): GoEndgameProgress? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val lines = text.split('\n')
            if (lines[0] != "GEP1") return null
            val old = lines.size == 6 && lines[1] == "1"
            if (!old && (lines.size != 10 || lines[1] != "2" || lines[2] != GoEndgameLibrary.CONTENT_VERSION.toString())) return null
            val crcIndex = if (old) 4 else 8
            if (lines[crcIndex + 1].isNotEmpty() || !lines[crcIndex].matches(Regex("[0-9a-f]{8}"))) return null
            val completedText = lines[if (old) 3 else 4]
            val completed = if (completedText.isEmpty()) emptyList() else completedText.split(',')
            if (completed.size != completed.toSet().size) return null
            val progress = if (old) GoEndgameProgress(completed.toSet(), lines[2]) else GoEndgameProgress(
                completed.toSet(), lines[3], GoEndgameChallengeMode.valueOf(lines[5]),
                lines[6].takeUnless { it == "-" }?.let(LocalDate::parse), lines[7].takeUnless { it == "-" })
            // Canonical encoding also rejects reordered IDs, stray fields and incorrect CRC.
            val expected = if (old) encodePayload("GEP1\n1\n${progress.lastPuzzleId}\n${completed.joinToString(",")}\n")
                else encode(progress)
            val ordered = GoEndgameLibrary.puzzles.map { it.id }.filter { it in progress.completedIds }
            progress.takeIf { completed == ordered && expected.contentEquals(bytes) }
        } catch (_: IllegalArgumentException) { null }
        catch (_: java.time.DateTimeException) { null }
        catch (_: java.nio.charset.CharacterCodingException) { null }
    }
}

class GoEndgameFileRepository internal constructor(
    private val file: File,
) : GoEndgameProgressRepository {
    constructor(context: Context) : this(context.applicationContext.getFileStreamPath("go-endgame-progress-v1.bin"))
    private val atomic = AtomicFile(file)
    private val lock = locks.computeIfAbsent(file.canonicalPath) { Mutex() }

    override suspend fun load(): GoEndgameProgress = withContext(Dispatchers.IO) {
        lock.withLock { read() }
    }
    override suspend fun select(id: String, mode: GoEndgameChallengeMode): GoEndgameProgress = update { it.select(id, mode) }
    override suspend fun selectDaily(date: LocalDate, id: String): GoEndgameProgress = update { it.selectDaily(date, id) }
    override suspend fun complete(id: String): GoEndgameProgress = update { it.complete(id) }

    private suspend fun update(transform: (GoEndgameProgress) -> GoEndgameProgress): GoEndgameProgress =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val next = transform(read())
                val bytes = GoEndgameProgressCodec.encode(next)
                val output = atomic.startWrite()
                var finished = false
                try {
                    output.write(bytes)
                    atomic.finishWrite(output)
                    finished = true
                    if (!readBytes().contentEquals(bytes)) throw IOException("围棋残局进度写入校验失败。")
                } catch (failure: Exception) {
                    if (!finished) try { atomic.failWrite(output) } catch (rollback: Exception) { failure.addSuppressed(rollback) }
                    throw failure
                }
                next
            }
        }

    private fun read(): GoEndgameProgress {
        val bytes = try { readBytes() } catch (error: FileNotFoundException) {
            if (file.exists() || File(file.path + ".bak").exists()) throw error
            return GoEndgameProgress()
        }
        return GoEndgameProgressCodec.decode(bytes) ?: throw IOException("围棋残局进度无效或版本不兼容，原数据已保留。")
    }

    private fun readBytes() = atomic.openRead().use { it.readBytesBounded() }
    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(512)
        while (true) {
            val count = read(buffer, 0, minOf(buffer.size, GoEndgameProgressCodec.MAX_BYTES + 1 - output.size()))
            if (count < 0) break
            if (count == 0) {
                val next = read()
                if (next < 0) break
                output.write(next)
            } else output.write(buffer, 0, count)
            if (output.size() > GoEndgameProgressCodec.MAX_BYTES) throw IOException("围棋残局进度超过容量限制。")
        }
        return output.toByteArray()
    }
    private companion object { val locks = ConcurrentHashMap<String, Mutex>() }
}
