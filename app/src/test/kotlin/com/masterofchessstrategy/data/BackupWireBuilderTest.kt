package com.masterofchessstrategy.data

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class BackupWireBuilderTest {
    private val header = "MOCS-BACKUP|4"
    private val created = "CREATED|9"
    private fun signed(vararg lines: String): ByteArray {
        val body = lines.joinToString("\n", postfix = "\n")
        val checksum = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return (body + "SHA256|$checksum\n").toByteArray(Charsets.UTF_8)
    }

    @Test fun exactFinalBudgetIncludesEveryByteAndChecksum() {
        val expected = signed(header, created)
        val writer = BackupWireBuilder(expected.size)
        writer.add(header); writer.add(created)
        assertArrayEquals(expected, writer.finish())
        val short = BackupWireBuilder(expected.size - 1)
        short.add(header)
        assertThrows(IllegalArgumentException::class.java) { short.add(created) }
    }

    @Test fun failedAppendDoesNotMutateExistingBytesOrDigest() {
        val expected = signed(header, created)
        val writer = BackupWireBuilder(expected.size)
        writer.add(header); writer.add(created)
        assertThrows(IllegalArgumentException::class.java) { writer.add("EXTRA|1") }
        assertArrayEquals(expected, writer.finish())
    }

    @Test fun byteBudgetUsesUtf8NotCharacterCount() {
        val expected = signed(header, "TEXT|字符")
        val writer = BackupWireBuilder(expected.size)
        writer.add(header); writer.add("TEXT|字符")
        assertArrayEquals(expected, writer.finish())
        val short = BackupWireBuilder(expected.size - 1)
        short.add(header)
        assertThrows(IllegalArgumentException::class.java) { short.add("TEXT|字符") }
    }

    @Test fun finalizationAndLineLimitCannotBeBypassed() {
        val writer = BackupWireBuilder(maximumLines = 2)
        writer.add(header); writer.add(created)
        assertThrows(IllegalArgumentException::class.java) { writer.add("EXTRA|1") }
        assertArrayEquals(signed(header, created), writer.finish())
        assertThrows(IllegalStateException::class.java) { writer.add("EXTRA|1") }
        assertThrows(IllegalStateException::class.java) { writer.finish() }
    }

    @Test fun malformedLinesAndOversizedBudgetsAreRejected() {
        for (line in listOf("", "A\rB", "A\nB", "A\u0000B", "\uD800")) {
            assertThrows(IllegalArgumentException::class.java) { BackupWireBuilder().add(line) }
        }
        assertThrows(IllegalArgumentException::class.java) { BackupWireBuilder(GameBackupRecord.MAX_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { BackupWireBuilder(maximumLines = 1) }
        assertThrows(IllegalArgumentException::class.java) { BackupWireBuilder().finish() }
    }
}
