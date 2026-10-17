package com.masterofchessstrategy.games.go.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class GoLegacyDiskSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun backupIsPreferredButBothFilesAndPendingNewFileRemainUntouched() {
        val base = temporary.newFile("go.bin").apply { writeBytes(byteArrayOf(9, 8)) }
        val backup = File(base.path + ".bak").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val pending = File(base.path + ".new").apply { writeBytes(byteArrayOf(7)) }
        assertArrayEquals(byteArrayOf(1, 2, 3), GoLegacyDiskSnapshot.read(base, 16))
        assertArrayEquals(byteArrayOf(9, 8), base.readBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3), backup.readBytes())
        assertArrayEquals(byteArrayOf(7), pending.readBytes())
    }

    @Test fun missingSourceReturnsNullAndUncommittedNewImageIsNotImported() {
        val base = File(temporary.root, "absent.bin")
        val pending = File(base.path + ".new").apply { writeBytes(byteArrayOf(1)) }
        assertNull(GoLegacyDiskSnapshot.read(base, 16))
        assertTrue(pending.exists())
    }

    @Test fun oversizedBackupFailsWithoutFallingBackOrConsumingEitherImage() {
        val base = temporary.newFile("source.bin").apply { writeBytes(byteArrayOf(1)) }
        val backup = File(base.path + ".bak").apply { writeBytes(ByteArray(17) { 4 }) }
        assertThrows(IOException::class.java) { GoLegacyDiskSnapshot.read(base, 16) }
        assertArrayEquals(byteArrayOf(1), base.readBytes())
        assertEquals(17L, backup.length())
    }
}
