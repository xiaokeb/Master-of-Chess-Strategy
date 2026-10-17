package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.games.go.GoLearningLibrary
import com.masterofchessstrategy.games.go.GoLearningProgress
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.CRC32

class GoLearningProgressCodecTest {
    private val ids = GoLearningLibrary.lessons.map { it.id }

    @Test fun everySubsetAndLastLessonRoundTripWithoutFourStepCompression() {
        for (mask in 0 until (1 shl ids.size)) {
            val completed = ids.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            val original = GoLearningProgress(completed, ids[mask % ids.size])
            val loaded = GoLearningProgressCodec.decode(GoLearningProgressCodec.encode(original))!!
            assertEquals(original, loaded)
            assertEquals(completed.size == ids.size, loaded.isCompleted)
        }
        assertFalse(GoLearningProgress(ids.take(4).toSet()).isCompleted)
        assertTrue(GoLearningProgress(ids.toSet()).isCompleted)
    }

    @Test fun canonicalEncodingPreservesIdentityOrderAndOwnsCompletedSet() {
        val mutable = ids.reversed().toMutableSet()
        val progress = GoLearningProgress(mutable, ids.last())
        val encoded = GoLearningProgressCodec.encode(progress)
        mutable.clear()
        val restored = GoLearningProgressCodec.decode(encoded)!!
        assertEquals(ids.toSet(), restored.completedLessonIds)
        assertEquals(ids.joinToString(","), encoded.toString(Charsets.UTF_8).split('\n')[4])
        assertThrows(UnsupportedOperationException::class.java) { (restored.completedLessonIds as MutableSet<String>).clear() }
    }

    @Test fun everyByteCorruptionTruncationAndTrailingDataAreRejected() {
        val encoded = GoLearningProgressCodec.encode(GoLearningProgress(ids.take(3).toSet(), ids[2]))
        for (index in encoded.indices) {
            val changed = encoded.copyOf().apply { this[index] = (this[index].toInt() xor 1).toByte() }
            assertNull("corruption $index", GoLearningProgressCodec.decode(changed))
        }
        for (length in 0 until encoded.size) assertNull(GoLearningProgressCodec.decode(encoded.copyOf(length)))
        assertNull(GoLearningProgressCodec.decode(encoded + 0.toByte()))
        assertNull(GoLearningProgressCodec.decode(ByteArray(4097)))
        assertNull(GoLearningProgressCodec.decode(byteArrayOf(0xc3.toByte(), 0x28)))
    }

    @Test fun validChecksumDoesNotPermitUnknownVersionIdsDuplicatesOrReordering() {
        val cases = listOf(
            "GLP1\n2\n1\n${ids[0]}\n\n", "GLP1\n1\n2\n${ids[0]}\n\n",
            "GLP1\n1\n1\nunknown\n\n", "GLP1\n1\n1\n${ids[0]}\nunknown\n",
            "GLP1\n1\n1\n${ids[0]}\n${ids[0]},${ids[0]}\n",
            "GLP1\n1\n1\n${ids[0]}\n${ids[1]},${ids[0]}\n",
        )
        for (payload in cases) {
            val crc = CRC32().apply { update(payload.toByteArray()) }.value.toString(16).padStart(8, '0')
            assertNull(GoLearningProgressCodec.decode("$payload$crc\n".toByteArray()))
        }
    }
}
