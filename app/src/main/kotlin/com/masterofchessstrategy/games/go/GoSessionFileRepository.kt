package com.masterofchessstrategy.games.go

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.concurrent.ConcurrentHashMap

/** Small envelope around the native MOCG record; the engine validates the record itself. */
internal object GoSessionFileCodec {
    private const val MAGIC = 0x474F5331 // GOS1
    private const val LEGACY_HEADER_BYTES = 16
    private const val HEADER_BYTES = 20
    private const val V3_HEADER_BYTES = 29
    private const val MAX_ENGINE_BYTES = 1_048_576
    internal const val MAX_FILE_BYTES = V3_HEADER_BYTES + GoSessionIdentity.MAX_ID_BYTES + MAX_ENGINE_BYTES

    fun encode(session: GoSavedSession): ByteArray {
        require(session.boardSize in setOf(9, 13, 19))
        require(session.engineState.isNotEmpty() && session.engineState.size <= MAX_ENGINE_BYTES)
        require(session.lastMoveIndex == null || session.lastMoveIndex in 0 until session.boardSize * session.boardSize)
        val state = session.engineState.copyOf()
        val id = session.identity.sessionId.toByteArray(Charsets.US_ASCII)
        val result = ByteBuffer.allocate(V3_HEADER_BYTES + id.size + state.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        result.putInt(MAGIC)
        result.put(3.toByte()) // envelope version; independent of native MOCG version
        result.put(session.boardSize.toByte())
        result.put(session.displayMode.ordinal.toByte())
        result.put(0.toByte()) // reserved
        result.putInt(state.size)
        result.putInt(0) // CRC32 slot
        result.putInt(session.lastMoveIndex ?: -1)
        result.put(session.identity.origin.code.toByte())
        result.put(byteArrayOf(0, 0, 0))
        result.putInt(session.identity.archiveRevision)
        result.put(id.size.toByte())
        result.put(id)
        result.put(state)
        return result.array().also { result.putInt(12, checksumV3(it)) }
    }

    fun decode(bytes: ByteArray): GoSavedSession? {
        if (bytes.size < LEGACY_HEADER_BYTES || bytes.size > MAX_FILE_BYTES) return null
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (input.int != MAGIC) return null
        val version = input.get().toInt()
        val header = when (version) { 1 -> LEGACY_HEADER_BYTES; 2 -> HEADER_BYTES; 3 -> V3_HEADER_BYTES; else -> return null }
        if (bytes.size < header) return null
        val size = input.get().toInt() and 0xff
        val mode = input.get().toInt() and 0xff
        if (size !in setOf(9, 13, 19) || mode !in GoScoreDisplayMode.entries.indices ||
            input.get().toInt() != 0) return null
        val length = input.int
        val checksum = input.int
        val lastMove = if (version >= 2) input.int else -1
        if (lastMove < -1 || lastMove >= size * size) return null
        val identity = if (version == 3) {
            if (checksum != checksumV3(bytes)) return null
            val originCode = input.get().toInt() and 255
            val origin = GoSessionOrigin.entries.singleOrNull { it.code == originCode } ?: return null
            if (List(3) { input.get().toInt() }.any { it != 0 }) return null
            val revision = input.int
            val idLength = input.get().toInt() and 255
            if (revision < 0 || idLength !in 1..GoSessionIdentity.MAX_ID_BYTES || idLength > input.remaining()) return null
            val id = ByteArray(idLength).also(input::get).toString(Charsets.US_ASCII)
            try { GoSessionIdentity(id, origin, revision) } catch (_: IllegalArgumentException) { return null }
        } else null
        if (length <= 0 || length > MAX_ENGINE_BYTES || length != input.remaining()) return null
        val engineState = ByteArray(length)
        input.get(engineState)
        if (version < 3 && CRC32().apply { update(engineState) }.value.toInt() != checksum) return null
        return GoSavedSession(size, engineState, GoScoreDisplayMode.entries[mode],
            lastMove.takeIf { it >= 0 }, identity ?: GoSessionIdentity.fromLegacy(bytes))
    }

    private fun checksumV3(bytes: ByteArray) = CRC32().apply {
        update(bytes, 0, 12)
        update(bytes, 16, bytes.size - 16)
    }.value.toInt()
}

/** Android AtomicFile keeps the previous valid session if a write is interrupted. */
class GoSessionFileRepository internal constructor(target: File) : GoSessionRepository {
    constructor(context: Context) : this(
        context.applicationContext.getFileStreamPath("go-session-v1.bin"),
    )
    private val file = AtomicFile(target)
    private val mutex = locks.computeIfAbsent(target.canonicalPath) { Mutex() }

    override suspend fun load(): GoSavedSession? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val input = try { file.openRead() } catch (_: FileNotFoundException) { return@withLock null }
            val bytes = input.use { readBounded(it, GoSessionFileCodec.MAX_FILE_BYTES) }
            GoSessionFileCodec.decode(bytes) ?: throw IOException("围棋存档校验失败")
        }
    }

    override suspend fun save(session: GoSavedSession) {
        // Freeze bytes and identity before waiting for either IO dispatch or another writer.
        val bytes = GoSessionFileCodec.encode(session)
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val output = file.startWrite()
                var finished = false
                try {
                    output.write(bytes)
                    file.finishWrite(output)
                    finished = true
                    // AtomicFile can log a rename failure without throwing. Do not publish that save.
                    val verified = file.openRead().use { readBounded(it, bytes.size) }
                    if (!verified.contentEquals(bytes)) throw IOException("围棋存档写入校验失败")
                } catch (error: Exception) {
                    if (!finished) {
                        try { file.failWrite(output) } catch (rollback: Exception) { error.addSuppressed(rollback) }
                    }
                    throw error
                }
            }
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            file.delete()
            if (file.baseFile.exists()) throw IOException("无法删除围棋存档")
        }
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count < 0) break
            if (count == 0) {
                val next = input.read()
                if (next < 0) break
                output.write(next)
            } else output.write(buffer, 0, count)
            if (output.size() > limit) throw IOException("围棋存档超过容量限制")
        }
        return output.toByteArray()
    }

    private companion object { val locks = ConcurrentHashMap<String, Mutex>() }
}
