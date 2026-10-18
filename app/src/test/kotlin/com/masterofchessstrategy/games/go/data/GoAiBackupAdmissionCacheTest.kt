package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.GameBackupContextualPreparation
import com.masterofchessstrategy.data.GameBackupPreparationContext
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.games.go.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Admission is a counted JVM double, not Native legality or concurrency evidence. */
class GoAiBackupAdmissionCacheTest {
    private fun match(id: String = "cache", reserved: Boolean = false) =
        GoAiSavedMatch(id, GoAiMatchConfig(9), byteArrayOf(1, 2, 3), completionReserved = reserved)
    private fun admitted(match: GoAiSavedMatch) = GoAdmittedAiCheckpoint(match, GoPhase.FINISHED, GameResult.DRAW, 0, 2, 0)

    @Test fun identicalCanonicalContentsReuseOneAdmissionRegardlessOfArrayIdentity() {
        var calls = 0
        val cache = GoAiBackupAdmissionCache({ calls++; admitted(it) })
        val bytes = GoAiMatchFileCodec.encode(match())
        val first = cache.prepare(bytes)
        assertSame(first, cache.prepare(bytes.copyOf()))
        bytes.fill(0)
        assertSame(first, cache.prepare(GoAiMatchFileCodec.encode(match())))
        assertEquals(1, calls)
    }

    @Test fun fullEnvelopeControlsParticipateInTheCacheKey() {
        var calls = 0
        val cache = GoAiBackupAdmissionCache({ calls++; admitted(it) })
        val first = cache.prepare(GoAiMatchFileCodec.encode(match()))
        val second = cache.prepare(GoAiMatchFileCodec.encode(match(reserved = true)))
        assertNotSame(first, second)
        assertFalse(first.match().completionReserved)
        assertTrue(second.match().completionReserved)
        assertEquals(2, calls)
    }

    @Test fun collidingFingerprintBucketsStillRequireFullByteEquality() {
        var calls = 0
        val cache = GoAiBackupAdmissionCache({ calls++; admitted(it) }, { "same-sha-bucket-test-double" })
        val first = cache.prepare(GoAiMatchFileCodec.encode(match("a")))
        val second = cache.prepare(GoAiMatchFileCodec.encode(match("b")))
        assertNotSame(first, second)
        assertSame(first, cache.prepare(GoAiMatchFileCodec.encode(match("a"))))
        assertEquals(2, calls)
    }

    @Test fun failedAdmissionAndChangedCanonicalMetadataAreNeverCached() {
        var calls = 0
        val cache = GoAiBackupAdmissionCache({ calls++; throw IllegalArgumentException("native rejection") })
        repeat(2) { rejects { cache.prepare(GoAiMatchFileCodec.encode(match())) } }
        assertEquals(2, calls)
        var changedCalls = 0
        val changed = GoAiBackupAdmissionCache({ changedCalls++; admitted(match("different")) })
        repeat(2) { rejects { changed.prepare(GoAiMatchFileCodec.encode(match())) } }
        assertEquals(2, changedCalls)
    }

    @Test fun malformedOrLegacyEnvelopeNeverReachesAdmission() {
        var calls = 0
        val cache = GoAiBackupAdmissionCache({ calls++; admitted(it) })
        rejects { cache.prepare(byteArrayOf(1, 2, 3)) }
        val bytes = GoAiMatchFileCodec.encode(match())
        rejects { cache.prepare(bytes.copyOf().apply { this[4] = 5 }) }
        rejects { cache.prepare(bytes.copyOf().apply { this[lastIndex] = 0 }) }
        assertEquals(0, calls)
    }

    @Test fun pairSharesOneContextButAnotherCallDoesNotReuseTheOldAdmission() {
        val calls = AtomicInteger()
        val pair = createGoAiBackupAdapters { calls.incrementAndGet(); admitted(it) }
        val session = pair.filterIsInstance<GoAiSessionBackupAdapter>().single()
        val archive = pair.filterIsInstance<GoAiArchiveBackupAdapter>().single()
        val saved = match()
        val entry = GoAiCheckpointEntry(GoAiMatchFileCodec.encode(saved),
            GoAiCheckpointLineage(GoAiCheckpointOrigin.UNVERIFIED_SESSION))
        val activeWire = session.encodeRows(listOf(GoAiCheckpointRows.active(entry, 12))).single()
        val archiveWire = archive.encodeRows(listOf(GoAiCheckpointRows.archive(
            GoAiCheckpointArchive(admitted(saved).recordId, entry, 7)))).single()
        val context = GameBackupPreparationContext()
        val active = (session as GameBackupContextualPreparation).prepareRestore(activeWire, context) as PreparedGoAiSessionBackup
        val first = archive.prepareRestore(archiveWire, context) as PreparedGoAiArchiveBackup
        assertSame(active.admitted, first.admitted)
        assertEquals(1, calls.get())
        session.prepareRestore(activeWire, GameBackupPreparationContext())
        assertEquals(2, calls.get())
    }

    @Test fun concurrentPreparationsHaveIndependentCachesEvenWithTheSameAdapterPair() {
        val calls = AtomicInteger()
        val pair = createGoAiBackupAdapters { calls.incrementAndGet(); admitted(it) }
        val session = pair.filterIsInstance<GoAiSessionBackupAdapter>().single()
        val entry = GoAiCheckpointEntry(GoAiMatchFileCodec.encode(match()),
            GoAiCheckpointLineage(GoAiCheckpointOrigin.UNVERIFIED_SESSION))
        val wire = session.encodeRows(listOf(GoAiCheckpointRows.active(entry, 0))).single()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val tasks = List(2) { Callable {
                val context = GameBackupPreparationContext()
                val a = session.prepareRestore(wire, context) as PreparedGoAiSessionBackup
                val b = session.prepareRestore(wire, context) as PreparedGoAiSessionBackup
                assertSame(a.admitted, b.admitted)
                a.admitted
            } }
            val results = executor.invokeAll(tasks).map { it.get() }
            assertNotSame(results[0], results[1])
            assertEquals(2, calls.get())
        } finally { executor.shutdownNow() }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Invalid cache input accepted") } catch (_: IllegalArgumentException) { }
    }
}
