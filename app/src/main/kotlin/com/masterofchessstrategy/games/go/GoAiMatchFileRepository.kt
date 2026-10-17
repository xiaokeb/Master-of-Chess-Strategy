package com.masterofchessstrategy.games.go

import android.content.Context
import android.util.AtomicFile
import com.masterofchessstrategy.engine.GoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/** GAI1 v6 persists bounded automatic continuation; v1-v5 remain readable. */
internal object GoAiMatchFileCodec {
    private const val MAGIC = 0x31494147
    private const val HEADER = 44
    const val MAX_BYTES = HEADER + 128 + 2_000_000

    fun encode(match: GoAiSavedMatch): ByteArray {
        val id = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(match.matchId))
        val idBytes = ByteArray(id.remaining()).also(id::get)
        val state = match.copyBytes()
        val opening = match.copyOpeningBytes() ?: ByteArray(0)
        require(idBytes.size in 1..128)
        val output = ByteBuffer.allocate(HEADER + idBytes.size + state.size + opening.size).order(ByteOrder.LITTLE_ENDIAN)
        output.putInt(MAGIC).put(6).put(match.config.boardSize.toByte()).put(match.config.handicapStones.toByte())
            .put((match.config.humanPlayer?.code ?: 255).toByte()).put(match.config.difficulty.ordinal.toByte())
            .put(byteArrayOf(((if (match.completionReserved) 1 else 0) or (if (match.automaticUsed) 2 else 0) or
                (if (match.hasSpecifiedOpening) 4 else 0) or (if (match.autoContinueEnabled) 8 else 0)).toByte(),
                match.rememberedHuman.code.toByte(), 0))
            .putInt(match.undoUsed).putInt(idBytes.size).putInt(state.size).putInt(match.hintUsed).putInt(opening.size)
            .putInt(match.autoContinueMaxGames).putInt(match.autoContinueGameNumber).putInt(0)
            .put(idBytes).put(state).put(opening)
        val bytes = output.array()
        output.putInt(40, checksum(bytes, HEADER))
        return bytes
    }

    fun decode(bytes: ByteArray): GoAiSavedMatch? {
        if (bytes.size !in 28..MAX_BYTES) return null
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (input.int != MAGIC) return null
        val version = input.get().toInt()
        if (version !in 1..6) return null
        val header = when (version) { 1 -> 28; in 2..4 -> 32; 5 -> 36; else -> HEADER }
        if (bytes.size < header) return null
        val size = input.get().toInt() and 255
        val handicap = input.get().toInt() and 255
        val human = input.get().toInt() and 255
        val difficulty = input.get().toInt() and 255
        val flags = input.get().toInt() and 255
        val remembered = input.get().toInt() and 255
        val reserved = input.get().toInt()
        val maxFlags = when (version) { 6 -> 15; 5 -> 7; 4 -> 3; 3 -> 1; else -> 0 }
        if (size !in setOf(9, 13, 19) || (handicap != 0 && handicap !in 2..9) || human !in setOf(0, 1, 255) ||
            difficulty !in GoAiDifficulty.entries.indices || flags !in 0..maxFlags ||
            remembered !in 0..(if (version >= 4) 1 else 0) || reserved != 0) return null
        val undoUsed = input.int
        val idLength = input.int
        val stateLength = input.int
        val hintUsed = if (version == 1) 0 else input.int
        val openingLength = if (version >= 5) input.int else 0
        val maxGames = if (version >= 6) input.int else 1
        val gameNumber = if (version >= 6) input.int else 1
        if (input.int != checksum(bytes, header) || idLength !in 1..128 || stateLength !in 1..1_000_000 ||
            openingLength !in 0..1_000_000 || idLength.toLong() + stateLength + openingLength != input.remaining().toLong() ||
            (flags and 4 != 0) != (openingLength > 0)) return null
        val config = GoAiMatchConfig(size, handicap, if (human == 255) null else GoPlayer.entries[human], GoAiDifficulty.entries[difficulty])
        val rememberedHuman = if (version >= 4) GoPlayer.entries[remembered] else config.humanPlayer ?: GoPlayer.BLACK
        val automaticUsed = if (version >= 4) flags and 2 != 0 else config.automatic
        val autoContinue = version >= 6 && flags and 8 != 0
        if ((config.automatic && !automaticUsed) || (!config.automatic && config.humanPlayer != rememberedHuman)) return null
        if (autoContinue) {
            if (!config.automatic || maxGames !in 2..100 || gameNumber !in 1..maxGames) return null
        } else if (maxGames != 1 || gameNumber != 1) return null
        if (undoUsed !in 0..config.difficulty.undoLimit || hintUsed !in 0..config.difficulty.hintLimit) return null
        val idBytes = ByteArray(idLength).also(input::get)
        val id = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(idBytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { return null }
        if (id.isBlank() || '\u0000' in id) return null
        val state = ByteArray(stateLength).also(input::get)
        val opening = ByteArray(openingLength).also(input::get).takeIf { openingLength > 0 }
        return GoAiSavedMatch(id, config, state, undoUsed, hintUsed,
            flags and 1 != 0, rememberedHuman, automaticUsed, opening,
            autoContinue, maxGames, gameNumber)
    }

    private fun checksum(bytes: ByteArray, header: Int) = CRC32().apply {
        update(bytes, 0, header - 4)
        update(bytes, header, bytes.size - header)
    }.value.toInt()
}

/** Own active-match file; local two-player go-session-v1.bin is untouched. */
class GoAiMatchFileRepository internal constructor(file: File) : GoAiMatchRepository {
    constructor(context: Context) : this(context.applicationContext.getFileStreamPath("go-ai-match-v1.bin"))
    private val atomic = AtomicFile(file)
    private val mutex = locks.computeIfAbsent(file.canonicalPath) { Mutex() }

    override suspend fun load(): GoAiSavedMatch? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val input = try { atomic.openRead() } catch (_: FileNotFoundException) { return@withLock null }
            val bytes = input.use { readBounded(it, GoAiMatchFileCodec.MAX_BYTES) }
            GoAiMatchFileCodec.decode(bytes) ?: throw IOException("围棋人机存档校验失败")
        }
    }

    override suspend fun save(match: GoAiSavedMatch) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val bytes = GoAiMatchFileCodec.encode(match)
            val output = atomic.startWrite()
            var finished = false
            try {
                output.write(bytes)
                atomic.finishWrite(output)
                finished = true
                // Some AtomicFile rename failures only log; successful return requires read-back.
                val verified = atomic.openRead().use { readBounded(it, bytes.size) }
                if (!verified.contentEquals(bytes)) throw IOException("围棋人机存档写入校验失败")
            } catch (failure: Exception) {
                if (!finished) {
                    try { atomic.failWrite(output) } catch (rollback: Exception) { failure.addSuppressed(rollback) }
                }
                throw failure
            }
        }
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count < 0) break
            if (count == 0) {
                val next = input.read()
                if (next < 0) break
                output.write(next)
            } else output.write(buffer, 0, count)
            if (output.size() > limit) throw IOException("围棋人机存档超过容量限制")
        }
        return output.toByteArray()
    }

    private companion object { val locks = ConcurrentHashMap<String, Mutex>() }
}
