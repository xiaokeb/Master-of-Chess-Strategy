package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.engine.GoPlayer
import com.masterofchessstrategy.games.go.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Real host files and GAI codec only; Native and SQL migration are separate gates. */
class GoAiLegacyDiskSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun match(id: String = "legacy") = GoAiSavedMatch(id,
        GoAiMatchConfig(13, 5, null, GoAiDifficulty.MEDIUM), byteArrayOf(13, 5, 0),
        undoUsed = 2, hintUsed = 3, rememberedHuman = GoPlayer.WHITE, automaticUsed = true,
        openingBytes = byteArrayOf(13, 5), autoContinueEnabled = true,
        autoContinueMaxGames = 8, autoContinueGameNumber = 4)
    private fun image(match: GoAiSavedMatch = match()) = GoAiMatchFileCodec.encode(match)

    @Test fun committedBackupWinsWithoutRecoveringOrModifyingAnyImage() = runTest {
        val file = temporary.newFile("match.bin").apply { writeBytes(image(match("base"))) }
        val backup = File(file.path + ".bak").apply { writeBytes(image()) }
        val pending = File(file.path + ".new").apply { writeBytes(image(match("pending"))) }
        val before = listOf(file, backup, pending).map { it.readBytes() to it.lastModified() }
        val loaded = GoAiLegacyDiskSource(file).readCommitted()!!
        assertArrayEquals(image(), image(loaded))
        listOf(file, backup, pending).forEachIndexed { index, actual ->
            assertArrayEquals(before[index].first, actual.readBytes())
            assertEquals(before[index].second, actual.lastModified())
        }
    }

    @Test fun missingCommittedImageDoesNotPromoteOrDeletePendingNewFile() = runTest {
        val file = File(temporary.root, "missing.bin")
        val pending = File(file.path + ".new").apply { writeBytes(image()) }
        assertNull(GoAiLegacyDiskSource(file).readCommitted())
        assertFalse(file.exists())
        assertArrayEquals(image(), pending.readBytes())
    }

    @Test fun corruptBackupFailsClosedInsteadOfFallingBackToValidBase() = runTest {
        val file = temporary.newFile("match.bin").apply { writeBytes(image()) }
        val backup = File(file.path + ".bak").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        expectIo { GoAiLegacyDiskSource(file).readCommitted() }
        assertArrayEquals(image(), file.readBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3), backup.readBytes())
    }

    @Test fun acceptsTheEntireEnvelopeBudgetNotJustTheTwoNativeImages() = runTest {
        val full = GoAiSavedMatch("a".repeat(128), GoAiMatchConfig(19), ByteArray(1_000_000) { 1 },
            openingBytes = ByteArray(1_000_000) { 2 })
        val bytes = image(full)
        assertEquals(GoAiMatchFileCodec.MAX_BYTES, bytes.size)
        val file = temporary.newFile("large.bin").apply { writeBytes(bytes) }
        val loaded = GoAiLegacyDiskSource(file).readCommitted()!!
        assertArrayEquals(bytes, image(loaded))
        assertEquals(bytes.size.toLong(), file.length())
    }

    @Test fun oversizedBackupIsBoundedAndNeverConsumedOrBypassed() = runTest {
        val file = temporary.newFile("match.bin").apply { writeBytes(image()) }
        val backup = File(file.path + ".bak").apply { writeBytes(ByteArray(GoAiMatchFileCodec.MAX_BYTES + 1)) }
        expectIo { GoAiLegacyDiskSource(file).readCommitted() }
        assertEquals(GoAiMatchFileCodec.MAX_BYTES + 1L, backup.length())
        assertArrayEquals(image(), file.readBytes())
    }

    @Test fun unreadableCommittedPathDoesNotTurnIntoAnEmptySuccessfulMigration() = runTest {
        val file = temporary.newFile("match.bin").apply { writeBytes(image()) }
        assertTrue(File(file.path + ".bak").mkdir())
        expectIo { GoAiLegacyDiskSource(file).readCommitted() }
        assertArrayEquals(image(), file.readBytes())
    }

    @Test fun frozenVersionOneThroughSixLayoutsRemainReadOnlyAndKeepIdentityAndHistory() = runTest {
        for (version in 1..6) {
            val bytes = oldImage(version)
            val file = temporary.newFile("v$version.bin").apply { writeBytes(bytes) }
            val loaded = GoAiLegacyDiskSource(file).readCommitted()!!
            assertEquals("v$version", loaded.matchId)
            assertArrayEquals(byteArrayOf(9, 0, 1), loaded.copyBytes())
            assertEquals(2, loaded.undoUsed)
            assertEquals(if (version == 1) 0 else 1, loaded.hintUsed)
            assertFalse(loaded.completionReserved)
            assertFalse(loaded.automaticUsed)
            assertArrayEquals(bytes, file.readBytes())
            assertFalse(File(file.path + ".bak").exists())
            assertFalse(File(file.path + ".new").exists())
        }
    }

    @Test fun invalidBaseIsNotSilentlyRepairedFromPendingImage() = runTest {
        val file = temporary.newFile("match.bin").apply { writeBytes(byteArrayOf()) }
        val pending = File(file.path + ".new").apply { writeBytes(image()) }
        expectIo { GoAiLegacyDiskSource(file).readCommitted() }
        assertEquals(0L, file.length())
        assertArrayEquals(image(), pending.readBytes())
    }

    private suspend fun expectIo(block: suspend () -> Any?) {
        try { block(); fail("Invalid committed image accepted") } catch (_: IOException) { }
    }

    private fun oldImage(version: Int): ByteArray {
        val header = when (version) { 1 -> 28; in 2..4 -> 32; 5 -> 36; else -> 44 }
        val id = "v$version".toByteArray()
        val state = byteArrayOf(9, 0, 1)
        val buffer = ByteBuffer.allocate(header + id.size + state.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x31494147).put(version.toByte()).put(9).put(0).put(0).put(1)
            .put(byteArrayOf(0, 0, 0)).putInt(2).putInt(id.size).putInt(state.size)
        if (version >= 2) buffer.putInt(1)
        if (version >= 5) buffer.putInt(0)
        if (version >= 6) buffer.putInt(1).putInt(1)
        buffer.putInt(0).put(id).put(state)
        val bytes = buffer.array()
        buffer.putInt(header - 4, CRC32().apply {
            update(bytes, 0, header - 4); update(bytes, header, bytes.size - header)
        }.value.toInt())
        return bytes
    }
}
