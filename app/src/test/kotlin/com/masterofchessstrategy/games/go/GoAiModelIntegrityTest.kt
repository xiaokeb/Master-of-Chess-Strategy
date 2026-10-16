package com.masterofchessstrategy.games.go

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Only extraction/integrity, no neural-network compatibility or strength claim. */
class GoAiModelIntegrityTest {
    private val bytes = ByteArray(70_000) { (it % 251).toByte() }
    private fun spec(payload: ByteArray = bytes) = GoAiModelSpec("test.txt.gz", payload.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        })

    @Test fun copiesExactBytesAndDoesNotCloseCallerStreams() {
        var sourceClosed = false
        var targetClosed = false
        val source = object : ByteArrayInputStream(bytes) { override fun close() { sourceClosed = true } }
        val target = object : ByteArrayOutputStream() { override fun close() { targetClosed = true } }
        GoAiModelIntegrity.copyVerified(source, target, spec())
        assertArrayEquals(bytes, target.toByteArray())
        assertFalse(sourceClosed)
        assertFalse(targetClosed)
    }

    @Test fun rejectsTruncationAndOversizeAndCorruption() {
        for (payload in listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(1),
            bytes.copyOf().apply { this[10] = (this[10] + 1).toByte() })) {
            try {
                GoAiModelIntegrity.copyVerified(ByteArrayInputStream(payload), ByteArrayOutputStream(), spec())
                fail("Unverified asset accepted")
            } catch (_: IOException) { }
        }
    }

    @Test fun handlesZeroBulkReadsWithoutLosingBytes() {
        val source = object : InputStream() {
            val bytes = ByteArrayInputStream(this@GoAiModelIntegrityTest.bytes)
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
            override fun read(): Int = bytes.read()
        }
        val target = ByteArrayOutputStream()
        GoAiModelIntegrity.copyVerified(source, target, spec())
        assertArrayEquals(bytes, target.toByteArray())
    }

    @Test fun preCancellationDoesNotReadOrWrite() {
        val source = object : InputStream() { override fun read(): Int { fail("Read after cancellation"); return -1 } }
        try {
            GoAiModelIntegrity.copyVerified(source, null, spec(), cancelled = { true })
            fail("Cancellation ignored")
        } catch (_: CancellationException) { }
    }

    @Test fun cancellationBetweenChunksDoesNotReportSuccessfulCopy() {
        val target = ByteArrayOutputStream()
        try {
            GoAiModelIntegrity.copyVerified(ByteArrayInputStream(bytes), target, spec(), cancelled = { target.size() > 0 })
            fail("Cancelled partial write accepted")
        } catch (_: CancellationException) { }
        assertTrue(target.size() in 1 until bytes.size)
    }

    @Test fun writeAndFlushFailuresPropagate() {
        val writeFailure = object : OutputStream() { override fun write(value: Int) { throw IOException("write") } }
        val flushFailure = object : ByteArrayOutputStream() { override fun flush() { throw IOException("flush") } }
        for (target in listOf(writeFailure, flushFailure)) {
            try {
                GoAiModelIntegrity.copyVerified(ByteArrayInputStream(bytes), target, spec())
                fail("Failed write reported success")
            } catch (_: IOException) { }
        }
    }

    @Test fun descriptorRejectsEscapingPathsAndInvalidLimits() {
        for (name in listOf("../test.txt.gz", "dir/test.txt.gz", "dir\\test.txt.gz", "..test.txt.gz", "/test.txt.gz")) {
            try { spec().copy(fileName = name); fail("Escaping model name accepted") }
            catch (_: IllegalArgumentException) { }
        }
        for (length in listOf(0L, 100_000_001L)) {
            try { spec().copy(bytes = length); fail("Invalid byte limit accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
