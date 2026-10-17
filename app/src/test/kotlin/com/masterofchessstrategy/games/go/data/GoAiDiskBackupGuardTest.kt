package com.masterofchessstrategy.games.go.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class GoAiDiskBackupGuardTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun absentAiDataAllowsLocalBackupWithoutCreatingAnyFiles() = runTest {
        val file = File(temporary.root, "ai.bin")
        GoAiDiskBackupGuard(file).check()
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun anyBaseBackupOrPendingAiImageRejectsWithoutConsumingIt() = runTest {
        for (suffix in listOf("", ".bak", ".new")) {
            val directory = temporary.newFolder()
            val file = File(directory, "ai.bin")
            val image = File(file.path + suffix).apply { writeBytes(byteArrayOf(1, 2, 3)) }
            try { GoAiDiskBackupGuard(file).check(); fail("must not omit $suffix") }
            catch (error: IOException) { assertTrue(error.message!!.contains("原数据已保留")) }
            assertArrayEquals(byteArrayOf(1, 2, 3), image.readBytes())
            assertEquals(listOf(image), directory.listFiles()!!.toList())
        }
    }

    @Test fun evenEmptyOrUnreadableShapeIsPreservedRatherThanTreatedAsNoData() = runTest {
        val empty = temporary.newFile("ai.bin")
        try { GoAiDiskBackupGuard(empty).check(); fail("empty existing source is not absence") }
        catch (_: IOException) { }
        assertEquals(0L, empty.length())
        val directory = temporary.newFolder("not-a-file.bin")
        try { GoAiDiskBackupGuard(directory).check(); fail("directory is not safe absence") }
        catch (_: IOException) { }
        assertTrue(directory.isDirectory)
    }
}
