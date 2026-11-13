package com.masterofchessstrategy.games.competition_mahjong.challenge

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

enum class CompetitionMahjongChallengeMode(val label: String) {
    MAINLINE("主线"), THEME("主题练习"), DAILY("每日牌型"), RANDOM("随机挑战"),
}

class CompetitionMahjongChallengeProgress(
    completedIds: Set<String> = emptySet(),
    val lastChallengeId: String = CompetitionMahjongChallengeLibrary.challenges.first().id,
    val lastMode: CompetitionMahjongChallengeMode = CompetitionMahjongChallengeMode.MAINLINE,
    val dailyDate: LocalDate? = null,
    val dailyChallengeId: String? = null,
) {
    val completedIds: Set<String> = Collections.unmodifiableSet(completedIds.toSet())
    init {
        val ids = CompetitionMahjongChallengeLibrary.challenges.map { it.id }
        require(this.completedIds == ids.take(this.completedIds.size).toSet()) { "牌型训练进度不是连续主线" }
        require(isUnlocked(lastChallengeId)) { "牌型训练返回位置尚未开放" }
        require((dailyDate == null) == (dailyChallengeId == null)) { "每日牌型字段不完整" }
        require(dailyChallengeId == null || isUnlocked(dailyChallengeId)) { "每日牌型尚未开放" }
        require(lastMode != CompetitionMahjongChallengeMode.DAILY ||
            dailyChallengeId == lastChallengeId && dailyDate != null)
    }

    fun isUnlocked(id: String): Boolean {
        val index = CompetitionMahjongChallengeLibrary.challenges.indexOfFirst { it.id == id }
        return index >= 0 && index <= completedIds.size
    }

    fun select(id: String, mode: CompetitionMahjongChallengeMode = CompetitionMahjongChallengeMode.MAINLINE) =
        CompetitionMahjongChallengeProgress(completedIds, id, mode, dailyDate, dailyChallengeId)

    fun selectDaily(date: LocalDate, id: String): CompetitionMahjongChallengeProgress {
        require(CompetitionMahjongChallenges.daily(this, date).id == id) { "每日牌型选择已变化，请重试" }
        return CompetitionMahjongChallengeProgress(completedIds, id, CompetitionMahjongChallengeMode.DAILY, date, id)
    }

    fun complete(id: String): CompetitionMahjongChallengeProgress {
        require(isUnlocked(id)) { "不能完成尚未开放的牌型题" }
        return CompetitionMahjongChallengeProgress(completedIds + id, lastChallengeId, lastMode,
            dailyDate, dailyChallengeId)
    }
}

interface CompetitionMahjongChallengeProgressRepository {
    suspend fun load(): CompetitionMahjongChallengeProgress
    suspend fun select(id: String, mode: CompetitionMahjongChallengeMode = CompetitionMahjongChallengeMode.MAINLINE):
        CompetitionMahjongChallengeProgress
    suspend fun selectDaily(date: LocalDate, id: String): CompetitionMahjongChallengeProgress
    suspend fun complete(id: String): CompetitionMahjongChallengeProgress
}

internal object CompetitionMahjongChallengeProgressCodec {
    const val MAX_BYTES = 4_096
    private const val MAGIC = "MCPZ1"

    fun encode(progress: CompetitionMahjongChallengeProgress): ByteArray {
        val ordered = CompetitionMahjongChallengeLibrary.challenges.map { it.id }
            .filter { it in progress.completedIds }
        val payload = "$MAGIC\n1\n${CompetitionMahjongChallengeLibrary.CONTENT_VERSION}\n" +
            "${progress.lastChallengeId}\n${ordered.joinToString(",")}\n${progress.lastMode.name}\n" +
            "${progress.dailyDate ?: "-"}\n${progress.dailyChallengeId ?: "-"}\n"
        val body = payload.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(body) }.value.toString(16).padStart(8, '0')
        return (payload + crc + "\n").toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): CompetitionMahjongChallengeProgress? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val lines = text.split('\n')
            if (lines.size != 10 || lines[0] != MAGIC || lines[1] != "1" ||
                lines[2] != CompetitionMahjongChallengeLibrary.CONTENT_VERSION.toString() ||
                lines[9].isNotEmpty() || !lines[8].matches(Regex("[0-9a-f]{8}"))) return null
            val completed = if (lines[4].isEmpty()) emptyList() else lines[4].split(',')
            if (completed.size != completed.toSet().size) return null
            val progress = CompetitionMahjongChallengeProgress(
                completed.toSet(), lines[3], CompetitionMahjongChallengeMode.valueOf(lines[5]),
                lines[6].takeUnless { it == "-" }?.let(LocalDate::parse),
                lines[7].takeUnless { it == "-" },
            )
            val canonical = CompetitionMahjongChallengeLibrary.challenges.map { it.id }
                .filter { it in progress.completedIds }
            progress.takeIf { completed == canonical && encode(it).contentEquals(bytes) }
        } catch (_: IllegalArgumentException) { null }
        catch (_: java.time.DateTimeException) { null }
        catch (_: java.nio.charset.CharacterCodingException) { null }
    }
}

/** Dedicated atomic progress until the coordinator maps this game to shared progress and backup. */
class CompetitionMahjongChallengeFileRepository internal constructor(private val file: File) :
    CompetitionMahjongChallengeProgressRepository {
    constructor(context: Context) : this(context.applicationContext.getFileStreamPath(
        "competition-mahjong-challenge-progress-v1.bin"))
    private val atomic = AtomicFile(file)
    private val lock = locks.computeIfAbsent(file.canonicalPath) { Mutex() }

    override suspend fun load(): CompetitionMahjongChallengeProgress = withContext(Dispatchers.IO) {
        lock.withLock { read() }
    }
    override suspend fun select(id: String, mode: CompetitionMahjongChallengeMode) = update { it.select(id, mode) }
    override suspend fun selectDaily(date: LocalDate, id: String) = update { it.selectDaily(date, id) }
    override suspend fun complete(id: String) = update { it.complete(id) }

    private suspend fun update(transform: (CompetitionMahjongChallengeProgress) -> CompetitionMahjongChallengeProgress) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val next = transform(read())
                val bytes = CompetitionMahjongChallengeProgressCodec.encode(next)
                val output = atomic.startWrite()
                var finished = false
                try {
                    output.write(bytes)
                    atomic.finishWrite(output)
                    finished = true
                    if (!readBytes().contentEquals(bytes)) throw IOException("国标麻将牌型训练进度写入校验失败")
                } catch (failure: Exception) {
                    if (!finished) try { atomic.failWrite(output) }
                    catch (rollback: Exception) { failure.addSuppressed(rollback) }
                    throw failure
                }
                next
            }
        }

    private fun read(): CompetitionMahjongChallengeProgress {
        val bytes = try { readBytes() } catch (error: FileNotFoundException) {
            if (file.exists() || File(file.path + ".bak").exists()) throw error
            return CompetitionMahjongChallengeProgress()
        }
        return CompetitionMahjongChallengeProgressCodec.decode(bytes)
            ?: throw IOException("国标麻将牌型训练进度无效或版本不兼容，原数据已保留")
    }

    private fun readBytes(): ByteArray = atomic.openRead().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(512)
        while (true) {
            val remaining = MAX_READ + 1 - output.size()
            if (remaining <= 0) throw IOException("国标麻将牌型训练进度超过容量限制")
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            if (count == 0) {
                val next = input.read()
                if (next < 0) break
                output.write(next)
            } else output.write(buffer, 0, count)
            if (output.size() > MAX_READ) throw IOException("国标麻将牌型训练进度超过容量限制")
        }
        output.toByteArray()
    }

    private companion object {
        const val MAX_READ = CompetitionMahjongChallengeProgressCodec.MAX_BYTES
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}
