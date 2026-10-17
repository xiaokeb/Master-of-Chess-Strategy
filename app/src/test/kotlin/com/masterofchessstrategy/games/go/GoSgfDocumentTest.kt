package com.masterofchessstrategy.games.go

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class GoSgfDocumentTest {
    @Test fun strictUtf8RoundTripAllowsBomAndEscapedChineseComment() {
        val text = "(;FF[4]GM[1]SZ[9]C[围棋\\]注释];B[aa])"
        assertEquals(text, GoSgfDocument.read(ByteArrayInputStream(("\uFEFF" + text).toByteArray())))
        val output = ByteArrayOutputStream()
        GoSgfDocument.write(text, output)
        assertArrayEquals(text.toByteArray(), output.toByteArray())
    }

    @Test fun rejectsMalformedEncodingEmptyNullAndUnpairedSurrogate() {
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(), "  ".toByteArray(),
            "(;C[a\u0000b])".toByteArray())) {
            assertThrows(IOException::class.java) { GoSgfDocument.read(ByteArrayInputStream(bytes)) }
        }
        assertThrows(IOException::class.java) { GoSgfDocument.encode("(;C[\uD800])") }
    }

    @Test fun enforcesByteLimitEvenWhenProviderReportsZeroAvailable() {
        val exact = "x".repeat(GoSgfDocument.MAX_BYTES)
        assertEquals(exact, GoSgfDocument.read(ByteArrayInputStream(exact.toByteArray())))
        var consumed = 0
        val infinite = object : InputStream() {
            override fun available() = 0
            override fun read(): Int { consumed++; return 'x'.code }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer.fill('x'.code.toByte(), offset, offset + length)
                consumed += length
                return length
            }
        }
        assertThrows(IOException::class.java) { GoSgfDocument.read(infinite) }
        assertEquals(GoSgfDocument.MAX_BYTES + 1, consumed)
        assertThrows(IOException::class.java) { GoSgfDocument.encode("棋".repeat(333_334)) }
    }

    @Test fun propagatesWriteFailureWithoutReportingSuccess() {
        val output = object : java.io.OutputStream() {
            override fun write(value: Int) { throw IOException("provider write failed") }
        }
        assertThrows(IOException::class.java) { GoSgfDocument.write("(;SZ[9])", output) }
    }
}
