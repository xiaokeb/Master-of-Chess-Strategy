package com.masterofchessstrategy.engine.games.lanzhou_fangqi

import org.junit.Assert.*
import org.junit.Test

/** Requires the candidate JNI library. Source presence or compilation is not device evidence. */
class NativeFangqiRestoreBoundsInstrumentedTest {
    @Test fun emptyAndOversizedInputPreserveAPlayableHistory() {
        NativeLanzhouFangqiSession().use { session ->
            assertTrue(session.apply(session.legalActions().first()).accepted)
            val before = session.serialize()
            val position = session.snapshot()
            val legal = session.legalActions()
            assertFalse(session.restore(byteArrayOf()))
            assertArrayEquals(before, session.serialize())
            assertFalse(session.restore(ByteArray(1024 * 1024 + 1) { 'x'.code.toByte() }))
            assertArrayEquals(before, session.serialize())
            assertEquals(position, session.snapshot())
            assertEquals(legal, session.legalActions())
            assertTrue(session.apply(legal.first()).accepted)
            assertTrue(session.undo())
            assertArrayEquals(before, session.serialize())
        }
    }

    @Test fun exactLimitMalformedInputStillRejectsAndAValidRestoreCanFollow() {
        NativeLanzhouFangqiSession().use { session ->
            assertTrue(session.apply(session.legalActions().first()).accepted)
            val before = session.serialize()
            val position = session.snapshot()
            assertFalse(session.restore(ByteArray(1024 * 1024) { 'x'.code.toByte() }))
            assertArrayEquals(before, session.serialize())
            assertEquals(position, session.snapshot())
            session.reset()
            assertTrue(session.restore(before))
            assertArrayEquals(before, session.serialize())
            assertEquals(position, session.snapshot())
        }
    }
}
