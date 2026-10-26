package com.masterofchessstrategy.engine.games.lanzhou_mahjong

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.engine.NativeLanzhouMahjongSession
import com.masterofchessstrategy.engine.internal.NativeBindings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual shared registry and the Kotlin owner; no fault hooks enter JNI. */
@RunWith(AndroidJUnit4::class)
class NativeLanzhouMahjongOwnershipInstrumentedTest {
    @Test fun foreignTypeReleaseNeverRemovesLanzhouChessOrGoHandles() {
        val mahjong = NativeBindings.createLanzhouMahjong(321, 0, 2, 0)
        var chess = 0L
        var go = 0L
        try {
            chess = NativeBindings.createChineseChessEngine()
            go = NativeBindings.createGoEngine(9, 0, 0)
            val mahjongBefore = NativeBindings.serializeLanzhouMahjong(mahjong)
            val chessBefore = NativeBindings.serializeChineseChess(chess)
            val goBefore = NativeBindings.serializeGo(go)
            NativeBindings.releaseLanzhouMahjong(chess)
            NativeBindings.releaseLanzhouMahjong(go)
            NativeBindings.releaseChineseChessEngine(mahjong)
            NativeBindings.releaseGoEngine(mahjong)
            assertArrayEquals(mahjongBefore, NativeBindings.serializeLanzhouMahjong(mahjong))
            assertArrayEquals(chessBefore, NativeBindings.serializeChineseChess(chess))
            assertArrayEquals(goBefore, NativeBindings.serializeGo(go))
        } finally {
            NativeBindings.releaseLanzhouMahjong(mahjong)
            if (chess > 0) NativeBindings.releaseChineseChessEngine(chess)
            if (go > 0) NativeBindings.releaseGoEngine(go)
        }
    }

    @Test fun concurrentReadsAndCloseUseOneSessionLockAndRejectLaterOperations() {
        val session = NativeLanzhouMahjongSession(321)
        val firstRead = CountDownLatch(1)
        val continueReads = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val reader = thread(name = "lzmj-owned-reader") {
            try {
                assertEquals(4, session.snapshot().seats.size)
                firstRead.countDown()
                check(continueReads.await(10, TimeUnit.SECONDS))
                repeat(40) {
                    try { assertTrue(session.serialize().isNotEmpty()) }
                    catch (_: IllegalStateException) { /* Closing wins the same lock; no stale handle escapes. */ }
                }
            } catch (error: Throwable) { failure.set(error); firstRead.countDown() }
        }
        try {
            assertTrue(firstRead.await(10, TimeUnit.SECONDS))
            continueReads.countDown()
            session.close()
            reader.join(10_000)
            assertFalse("Reader must finish after close", reader.isAlive)
            failure.get()?.let { throw AssertionError("Concurrent Native read failed", it) }
            session.close()
            assertThrows(IllegalStateException::class.java) { session.snapshot() }
        } finally {
            continueReads.countDown()
            session.close()
            reader.join(10_000)
        }
    }
}
