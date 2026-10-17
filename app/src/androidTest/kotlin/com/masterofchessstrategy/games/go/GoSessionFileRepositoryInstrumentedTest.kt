package com.masterofchessstrategy.games.go

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.RandomAccessFile
import java.util.zip.CRC32

@RunWith(AndroidJUnit4::class)
class GoSessionFileRepositoryInstrumentedTest {
    private lateinit var directory: File
    private lateinit var target: File
    private lateinit var repository: GoSessionFileRepository

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "go-session-test-${UUID.randomUUID()}")
        assertTrue(directory.mkdir())
        target = File(directory, "session.bin")
        repository = GoSessionFileRepository(target)
    }

    @After fun tearDown() {
        directory.listFiles()?.forEach { assertTrue(it.delete()) }
        assertTrue(directory.delete())
    }

    @Test fun roundTripAndClearTouchOnlyTheTestSession() = runBlocking {
        val saved = GoSavedSession(13, byteArrayOf(1, 2, 3), GoScoreDisplayMode.POINTS, 42)
        repository.save(saved)
        val loaded = repository.load()!!
        assertEquals(13, loaded.boardSize)
        assertEquals(42, loaded.lastMoveIndex)
        assertEquals(GoScoreDisplayMode.POINTS, loaded.displayMode)
        assertArrayEquals(saved.engineState, loaded.engineState)
        assertEquals(saved.identity, loaded.identity)
        repository.clear()
        assertFalse(target.exists())
        assertEquals(null, repository.load())
    }

    @Test fun failedAtomicWriteLeavesThePreviousSessionReadable() = runBlocking {
        val saved = GoSavedSession(9, byteArrayOf(4, 5, 6), GoScoreDisplayMode.STONES)
        repository.save(saved)
        val atomic = AtomicFile(target)
        val interrupted = atomic.startWrite()
        interrupted.write(byteArrayOf(0, 0))
        atomic.failWrite(interrupted)
        assertArrayEquals(saved.engineState, repository.load()!!.engineState)
        assertEquals(saved.identity, repository.load()!!.identity)
    }

    @Test fun corruptFileIsReportedAndKeptForRecovery() = runBlocking {
        val corrupted = byteArrayOf(1, 2, 3)
        target.writeBytes(corrupted)
        var failed = false
        try { repository.load() } catch (_: IOException) { failed = true }
        assertTrue(failed)
        assertArrayEquals(corrupted, target.readBytes())
    }

    @Test fun legacyLoadsAreReadOnlyAndNextSavePreservesUnknownIdentity() = runBlocking {
        for (version in 1..2) {
            val payload = byteArrayOf(1, 2, 3)
            val oldBytes = ByteBuffer.allocate((if (version == 1) 16 else 20) + payload.size)
                .order(ByteOrder.LITTLE_ENDIAN).apply {
                    putInt(0x474F5331); put(version.toByte()); put(9); put(1); put(0)
                    putInt(payload.size); putInt(CRC32().apply { update(payload) }.value.toInt())
                    if (version == 2) putInt(40)
                    put(payload)
                }.array()
            target.writeBytes(oldBytes)
            val restored = repository.load()!!
            assertEquals(GoSessionOrigin.LEGACY_UNKNOWN, restored.identity.origin)
            assertEquals(restored.identity, GoSessionFileRepository(target).load()!!.identity)
            assertArrayEquals(oldBytes, target.readBytes())
            repository.save(restored)
            assertEquals(3, target.readBytes()[4].toInt())
            assertEquals(restored.identity, repository.load()!!.identity)
            assertArrayEquals(payload, repository.load()!!.engineState)
        }
    }

    @Test fun metadataCorruptionAndOversizeAreRejectedWithoutErasingTheFile() = runBlocking {
        repository.save(GoSavedSession(9, byteArrayOf(1), GoScoreDisplayMode.STONES))
        val changed = target.readBytes().apply { this[20] = 2 }
        target.writeBytes(changed)
        try { repository.load(); throw AssertionError("corrupted identity must fail") } catch (_: IOException) { }
        assertArrayEquals(changed, target.readBytes())
        val oversizedLength = GoSessionFileCodec.MAX_FILE_BYTES.toLong() + 1
        RandomAccessFile(target, "rw").use { it.setLength(oversizedLength) }
        try { repository.load(); throw AssertionError("oversized file must fail") } catch (_: IOException) { }
        assertEquals(oversizedLength, target.length())
    }
}
