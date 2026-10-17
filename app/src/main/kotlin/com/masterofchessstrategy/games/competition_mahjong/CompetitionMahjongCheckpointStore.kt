package com.masterofchessstrategy.games.competition_mahjong

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

data class CompetitionMahjongMatchConfig(
    val seed: Long,
    val dealerSeat: Int = 0,
    val roundWind: Int = 0,
    val viewerSeat: Int = 0,
    val botLevel: Int = 1,
) {
    init {
        require(seed >= 0)
        require(dealerSeat in 0..3 && roundWind in 0..3 && viewerSeat in 0..3)
        require(botLevel in 0..3)
    }
}

/** Private engine bytes must remain in persistence; the table only receives public snapshots. */
class CompetitionMahjongCheckpoint(
    val config: CompetitionMahjongMatchConfig,
    engineRecord: ByteArray,
) {
    private val record = engineRecord.copyOf()
    val engineRecord: ByteArray get() = record.copyOf()

    override fun toString(): String = "CompetitionMahjongCheckpoint(config=$config)"
}

interface CompetitionMahjongCheckpointStore {
    /** Missing files return null; damaged records and a different viewer throw IOException. */
    suspend fun load(viewerSeat: Int): CompetitionMahjongCheckpoint?
    suspend fun save(checkpoint: CompetitionMahjongCheckpoint)
    suspend fun clear()
}

/** Envelope CRC covers metadata and MCMS bytes; native restore still validates the MOCM replay. */
internal object CompetitionMahjongCheckpointCodec {
    private const val MAGIC = 0x4b50434d // MCPK, little endian
    private const val VERSION = 1
    private const val HEADER_BYTES = 24
    private const val FOOTER_BYTES = 4
    private const val MAX_RECORD_BYTES = 1_048_576
    const val MAX_FILE_BYTES = HEADER_BYTES + MAX_RECORD_BYTES + FOOTER_BYTES

    fun encode(checkpoint: CompetitionMahjongCheckpoint): ByteArray {
        val record = checkpoint.engineRecord
        require(validEngineEnvelope(record, checkpoint.config.viewerSeat)) {
            "国标麻将私有记录无效"
        }
        val config = checkpoint.config
        val output = ByteBuffer.allocate(HEADER_BYTES + record.size + FOOTER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        output.putInt(MAGIC)
        output.put(VERSION.toByte())
        repeat(3) { output.put(0.toByte()) }
        output.putLong(config.seed)
        output.put(config.dealerSeat.toByte())
        output.put(config.roundWind.toByte())
        output.put(config.viewerSeat.toByte())
        output.put(config.botLevel.toByte())
        output.putInt(record.size)
        output.put(record)
        output.putInt(crc(output.array(), output.position()))
        return output.array()
    }

    fun decode(bytes: ByteArray, viewerSeat: Int): CompetitionMahjongCheckpoint? {
        require(viewerSeat in 0..3)
        if (bytes.size < HEADER_BYTES + 16 + FOOTER_BYTES || bytes.size > MAX_FILE_BYTES) return null
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (input.int != MAGIC || input.get().toInt() != VERSION) return null
        repeat(3) { if (input.get().toInt() != 0) return null }
        val seed = input.long
        val dealer = input.get().toInt() and 0xff
        val wind = input.get().toInt() and 0xff
        val viewer = input.get().toInt() and 0xff
        val level = input.get().toInt() and 0xff
        if (seed < 0 || dealer !in 0..3 || wind !in 0..3 || viewer != viewerSeat || level !in 0..3) return null
        val length = input.int
        if (length !in 16..MAX_RECORD_BYTES || length != input.remaining() - FOOTER_BYTES) return null
        if (crc(bytes, bytes.size - FOOTER_BYTES) != input.getInt(bytes.size - FOOTER_BYTES)) return null
        val record = ByteArray(length)
        input.get(record)
        if (!validEngineEnvelope(record, viewerSeat)) return null
        return CompetitionMahjongCheckpoint(
            CompetitionMahjongMatchConfig(seed, dealer, wind, viewer, level),
            record,
        )
    }

    private fun validEngineEnvelope(record: ByteArray, viewerSeat: Int): Boolean {
        if (record.size !in 17..MAX_RECORD_BYTES) return false
        val input = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
        if (input.int != 0x534d434d || input.get().toInt() != 1 ||
            (input.get().toInt() and 0xff) != viewerSeat ||
            input.get().toInt() != 0 || input.get().toInt() != 0) return false
        val payloadLength = input.int
        if (payloadLength <= 0 || payloadLength != record.size - 16) return false
        return crc(record, record.size - FOOTER_BYTES) == input.getInt(record.size - FOOTER_BYTES)
    }

    private fun crc(bytes: ByteArray, length: Int): Int =
        CRC32().apply { update(bytes, 0, length) }.value.toInt()
}

/** Injectable boundary for write/commit failures without depending on Android in JVM tests. */
internal interface CompetitionMahjongAtomicFileBackend {
    fun read(maxBytes: Int): ByteArray
    fun startWrite(): OutputStream
    fun finishWrite(output: OutputStream)
    fun failWrite(output: OutputStream)
    fun delete()
}

/** API 24-compatible AtomicFile keeps writes separate from the prior checkpoint. */
class FileCompetitionMahjongCheckpointStore internal constructor(
    private val backend: CompetitionMahjongAtomicFileBackend,
) : CompetitionMahjongCheckpointStore {
    constructor(context: Context) : this(
        context.applicationContext.getFileStreamPath("competition-mahjong-checkpoint-v1.bin"),
    )

    internal constructor(file: File) : this(AndroidCompetitionMahjongAtomicFileBackend(AtomicFile(file)))

    private val mutex = Mutex()

    override suspend fun load(viewerSeat: Int): CompetitionMahjongCheckpoint? {
        require(viewerSeat in 0..3)
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val bytes = try {
                    backend.read(CompetitionMahjongCheckpointCodec.MAX_FILE_BYTES)
                } catch (_: FileNotFoundException) {
                    return@withLock null
                }
                CompetitionMahjongCheckpointCodec.decode(bytes, viewerSeat)
                    ?: throw IOException("国标麻将存档损坏或席位不符")
            }
        }
    }

    override suspend fun save(checkpoint: CompetitionMahjongCheckpoint) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val bytes = CompetitionMahjongCheckpointCodec.encode(checkpoint)
            val output = backend.startWrite()
            try {
                output.write(bytes)
                backend.finishWrite(output)
            } catch (failure: Throwable) {
                try {
                    backend.failWrite(output)
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
                throw failure
            }
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { backend.delete() }
    }
}

private class AndroidCompetitionMahjongAtomicFileBackend(
    private val file: AtomicFile,
) : CompetitionMahjongAtomicFileBackend {
    override fun read(maxBytes: Int): ByteArray = file.openRead().use { input ->
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() > maxBytes - count) throw IOException("国标麻将存档超过尺寸限制")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    override fun startWrite(): OutputStream = file.startWrite()

    override fun finishWrite(output: OutputStream) {
        val stream = output as FileOutputStream
        stream.flush()
        // AtomicFile logs sync failures on some Android versions; report them before committing.
        stream.fd.sync()
        file.finishWrite(stream)
    }

    override fun failWrite(output: OutputStream) = file.failWrite(output as FileOutputStream)

    override fun delete() {
        file.delete()
        if (file.baseFile.exists()) throw IOException("无法删除国标麻将存档")
    }
}
