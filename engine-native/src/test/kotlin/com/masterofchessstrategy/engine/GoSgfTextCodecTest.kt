package com.masterofchessstrategy.engine

import java.nio.charset.CharacterCodingException
import org.junit.Assert.*
import org.junit.Test

class GoSgfTextCodecTest {
    @Test fun standardUtf8PreservesChineseAstralCharactersAndNul() {
        val text = "(;C[中文\uD83D\uDE00\u0000])"
        val data = GoSgfTextCodec.encodeOrNull(text)!!
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), data)
        assertEquals(text, GoSgfTextCodec.decode(data))
        assertTrue(data.contains(0))
    }

    @Test fun unpairedSurrogatesAreRejectedWithoutReplacement() {
        for (text in listOf("\uD800", "\uDC00", "abc\uD800xyz"))
            assertNull(GoSgfTextCodec.encodeOrNull(text))
    }

    @Test fun limitsApplyToEncodedBytesNotOnlyCharacterCount() {
        assertEquals(1_000_000, GoSgfTextCodec.encodeOrNull("a".repeat(1_000_000))!!.size)
        assertNull(GoSgfTextCodec.encodeOrNull("a".repeat(1_000_001)))
        assertNull(GoSgfTextCodec.encodeOrNull("中".repeat(333_334)))
        assertThrows(IllegalStateException::class.java) {
            GoSgfTextCodec.decode(ByteArray(1_000_001))
        }
    }

    @Test fun malformedNativeUtf8DoesNotSilentlyCorruptText() {
        for (bytes in listOf(byteArrayOf(0xff.toByte()), byteArrayOf(0xc0.toByte(), 0x80.toByte()),
            byteArrayOf(0xe4.toByte(), 0xb8.toByte())))
            assertThrows(CharacterCodingException::class.java) { GoSgfTextCodec.decode(bytes) }
    }

    @Test fun invalidImportIsRejectedBeforeNativeInitialization() {
        assertEquals(GoSgfImportResult.Rejected(EngineError.CORRUPTED_DATA),
            NativeGoSgfImporter.importSgf(""))
        assertEquals(GoSgfImportResult.Rejected(EngineError.CORRUPTED_DATA),
            NativeGoSgfImporter.importSgf("\uD800"))
    }
}
