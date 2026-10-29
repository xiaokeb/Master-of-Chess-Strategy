package com.masterofchessstrategy.games.lanzhou_mahjong

import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.lanzhou_mahjong.data.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanzhouMahjongGameHostTest {
    @Test fun restoredFullRecordKeepsTimeUsageIdentityAndSeedWithoutNoOpWrite() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val config = LanzhouMahjongLaunchConfig(Difficulty.MEDIUM, 91, 2, 3, 2)
        val source = HostFakeSession(config).apply { actions += Triple(1, 2, 0) }
        val record = LanzhouMahjongSessionRecord(config.difficulty, 2, 91, 3, 2, 99,
            source.serialize(), LanzhouMahjongAssistanceUsage(Difficulty.MEDIUM, 1, 2),
            listOf(LanzhouMahjongDecisionPoint(0, 90)))
        f.store.saved = LanzhouMahjongStoredSession(record, 123)
        val host = f.host()
        host.open()
        assertEquals(config, f.sessions.single().config)
        assertEquals(listOf(99L), f.sessions.single().seeds)
        assertTrue(host.state.value.table!!.canUndo)
        assertEquals(2, host.state.value.table!!.assistance!!.hintUsed)
        assertEquals(123L, f.store.saved!!.updatedAtEpochMillis)
        assertTrue(f.store.attempts.isEmpty())
        host.closeAndJoin()
    }

    @Test fun failedSaveRetriesExactCompleteSnapshotAndFixedTime() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        f.store.failSave = true
        val host = f.host()
        host.open()
        val first = f.store.attempts.single()
        assertTrue(host.state.value.table!!.needsSave)
        host.apply(HostFakeSession.action)
        host.retryAdvance()
        host.retrySave()
        assertEquals(0, f.sessions.single().applied)
        assertSame(first, f.store.attempts.last())
        assertEquals(1, f.clockCalls)
        f.store.failSave = false
        host.retrySave()
        assertSame(first, f.store.saved)
        assertFalse(host.state.value.table!!.needsSave)
        assertEquals(1, f.sessions.single().seeds.size)
        host.closeAndJoin()
    }

    @Test fun hintAndDecisionUsageWaitForExactAcknowledgement() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler), Difficulty.MEDIUM)
        val host = f.host()
        host.open()
        f.store.failSave = true
        host.requestHint()
        val request = f.store.attempts.last()
        assertEquals(1, request.record.assistance.hintUsed)
        assertNull(host.state.value.table!!.hint)
        host.requestHint()
        assertSame(request, f.store.attempts.last())
        f.store.failSave = false
        host.retrySave()
        assertSame(request, f.store.saved)
        assertNotNull(host.state.value.table!!.hint)
        host.apply(HostFakeSession.action)
        assertEquals(listOf(LanzhouMahjongDecisionPoint(0, 7)), f.store.saved!!.record.decisions)
        host.undoDecision()
        assertEquals(0, LanzhouMahjongNativeHistory.actionCount(f.store.saved!!.record.nativeState))
        assertEquals(1, f.store.saved!!.record.assistance.undoUsed)
        assertEquals(1, f.store.saved!!.record.assistance.hintUsed)
        assertEquals(7L, f.store.saved!!.record.nextChoiceSeed)
        host.closeAndJoin()
    }

    @Test fun botExceptionPersistsOnlyActualPrefixAndAdvancesSeedBeforeContinue() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val host = f.host()
        host.open()
        val engine = f.sessions.single()
        engine.plans += HostBotPlan(3, failure = true)
        host.apply(HostFakeSession.action)
        assertEquals(4, LanzhouMahjongNativeHistory.actionCount(f.store.saved!!.record.nativeState))
        assertEquals(10L, f.store.saved!!.record.nextChoiceSeed)
        assertTrue(host.state.value.table!!.needsAdvance)
        assertFalse(host.state.value.table!!.needsSave)
        engine.plans += HostBotPlan(2)
        host.retryAdvance()
        assertEquals(12L, f.store.saved!!.record.nextChoiceSeed)
        assertEquals(6, LanzhouMahjongNativeHistory.actionCount(f.store.saved!!.record.nativeState))
        assertEquals(listOf(7L, 7L, 10L), engine.seeds)
        assertEquals(1, engine.applied)
        assertFalse(host.state.value.table!!.needsAdvance)
        host.closeAndJoin()
    }

    @Test fun failedPrefixSaveMustAcknowledgeBeforeMoreBotsAndReopenUsesAdvancedSeed() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val host = f.host()
        host.open()
        val engine = f.sessions.single()
        engine.plans += HostBotPlan(2, failure = true)
        f.store.failSave = true
        host.apply(HostFakeSession.action)
        val request = f.store.attempts.last()
        host.retryAdvance()
        assertEquals(2, engine.seeds.size)
        assertEquals(9L, request.record.nextChoiceSeed)
        f.store.failSave = false
        host.retrySave()
        assertSame(request, f.store.saved)
        assertTrue(host.leave())
        val resumed = f.host()
        resumed.open()
        assertEquals(listOf(9L), f.sessions.last().seeds)
        assertArrayEquals(request.record.nativeState, f.store.saved!!.record.nativeState)
        resumed.closeAndJoin()
    }

    @Test fun unmeasurablePrefixFailsClosedAndLeavesDurableRowUntouched() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val host = f.host()
        host.open()
        val original = f.store.saved
        f.sessions.single().plans += HostBotPlan(2, failure = true, breakSerialization = true)
        host.apply(HostFakeSession.action)
        assertTrue(host.state.value.table!!.blocked)
        assertTrue(host.state.value.table!!.actions.isEmpty())
        host.retryAdvance(); host.retrySave(); host.restart(); host.apply(HostFakeSession.action)
        assertSame(original, f.store.saved)
        assertEquals(2, f.sessions.single().seeds.size)
        assertTrue(host.leave())
        assertEquals(1, f.sessions.single().closeCalls)
    }

    @Test fun mismatchingBotCountOrChangedPrefixCannotBeSaved() = runTest {
        for (mode in listOf("count", "prefix")) {
            val f = HostFixture(StandardTestDispatcher(testScheduler))
            val host = f.host()
            host.open()
            val original = f.store.saved
            f.sessions.single().plans += HostBotPlan(1, wrongCount = mode == "count", rewritePrefix = mode == "prefix")
            host.apply(HostFakeSession.action)
            assertTrue(host.state.value.table!!.blocked)
            assertSame(original, f.store.saved)
            host.closeAndJoin()
        }
    }

    @Test fun corruptedLoadNeverCreatesOrOverwritesGameAndStillAllowsSafeExit() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val original = IOException("SQL/native private diagnostic")
        f.store.loadError = original
        val host = f.host()
        host.open(); host.restart(); host.retrySave(); host.retryAdvance()
        assertTrue(host.state.value.restoreFailed)
        assertEquals("对局恢复失败，原存档已保留，请返回后重试", host.state.value.error)
        assertSame(original, host.failures.single())
        assertFalse(host.state.value.toString().contains("private diagnostic"))
        assertTrue(f.sessions.isEmpty())
        assertTrue(f.store.attempts.isEmpty())
        assertTrue(host.leave())
    }

    @Test fun nativeRestoreRejectionClosesCandidateAndRetainsExistingRecord() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        f.store.saved = LanzhouMahjongStoredSession(f.record(), 12)
        val original = f.store.saved
        f.rejectRestore = true
        val host = f.host()
        host.open(); host.restart()
        assertTrue(host.state.value.restoreFailed)
        assertEquals(1, f.sessions.single().closeCalls)
        assertSame(original, f.store.saved)
        assertTrue(f.store.attempts.isEmpty())
        assertTrue(host.leave())
    }

    @Test fun failedBackupRetiresFixedOwnerAndCannotReplenishReplacedRow() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val host = f.host()
        host.open()
        f.store.failSave = true
        host.apply(HostFakeSession.action)
        val attempts = f.store.attempts.size
        try { f.gate.exclusive(replacing = true) { throw IOException("backup failed") } }
        catch (_: IOException) { }
        advanceUntilIdle()
        host.retirementCompletion.await()
        assertTrue(host.state.value.retired)
        assertEquals(1, f.sessions.single().closeCalls)
        host.retrySave(); host.restart(); host.open()
        assertEquals(attempts, f.store.attempts.size)
        assertEquals(1, f.store.ownerCaptures)
        try {
            f.store.save(f.store.saved!!)
            fail("retired store wrote")
        } catch (_: IOException) { }
    }

    @Test fun acceptedSaveAndLeaveAreSerializedAndReleaseWaitsForAcknowledgement() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val host = f.host()
        host.open()
        val engine = f.sessions.single()
        val saveGate = CompletableDeferred<Unit>()
        f.store.saveGate = saveGate
        val turn = launch { host.apply(HostFakeSession.action) }
        runCurrent()
        val leaving = async { host.leave() }
        runCurrent()
        assertFalse(leaving.isCompleted)
        assertEquals(0, engine.closeCalls)
        saveGate.complete(Unit)
        turn.join()
        assertTrue(leaving.await())
        assertEquals(1, engine.closeCalls)
        assertTrue(host.retirementCompletion.isCompleted)
        assertTrue(host.leave())
        host.apply(HostFakeSession.action)
        assertEquals(1, engine.applied)
    }

    @Test fun cancelledLeaveWaiterDoesNotOrphanNativeRelease() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val host = f.host()
        host.open()
        f.store.failSave = true
        host.apply(HostFakeSession.action)
        f.store.failSave = false
        val gate = CompletableDeferred<Unit>()
        f.store.saveGate = gate
        val leaving = launch { host.leave() }
        runCurrent()
        leaving.cancel()
        gate.complete(Unit)
        leaving.join()
        host.retirementCompletion.await()
        assertEquals(1, f.sessions.single().closeCalls)
    }

    @Test fun failedExitSaveKeepsHandleAndExactPendingRequestForRetry() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val host = f.host()
        host.open()
        f.store.failSave = true
        host.apply(HostFakeSession.action)
        val request = f.store.attempts.last()
        assertFalse(host.leave())
        assertSame(request, f.store.attempts.last())
        assertEquals(0, f.sessions.single().closeCalls)
        f.store.failSave = false
        assertTrue(host.leave())
        assertSame(request, f.store.saved)
    }

    @Test fun restartReleasesOldBeforeOpeningNextAndFailedNewSaveIsExactlyRetryable() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val host = f.host()
        host.open()
        val old = f.store.saved
        f.store.failSave = true
        host.restart()
        assertEquals(1, f.sessions.first().closeCalls)
        assertEquals(2, f.sessions.size)
        assertEquals(0, f.sessions.last().closeCalls)
        assertSame(old, f.store.saved)
        val candidate = f.store.attempts.last()
        assertEquals(19L, candidate.record.seed)
        assertTrue(host.state.value.table!!.needsSave)
        f.store.failSave = false
        host.retrySave()
        assertSame(candidate, f.store.saved)
        assertEquals(1, f.maxLive)
        host.closeAndJoin()
    }

    @Test fun closeFailureIsStableRetirementFailureAndDoesNotOpenReplacementOrDoubleClose() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val host = f.host()
        host.open()
        f.sessions.single().failClose = true
        val readsBefore = f.sessions.single().snapshotCalls
        host.restart()
        assertEquals(readsBefore + 1, f.sessions.single().snapshotCalls)
        assertEquals(1, f.sessions.size)
        val first = host.beginRetirement()
        assertSame(first, host.beginRetirement())
        try { first.await(); fail("release failure hidden") } catch (_: IOException) { }
        assertEquals(1, f.sessions.single().closeCalls)
        assertTrue(first.isCancelled)
    }

    @Test fun retirementBeforeOpenNeverCreatesNative() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val host = f.host()
        host.beginRetirement()
        host.open()
        host.closeAndJoin()
        assertTrue(f.sessions.isEmpty())
        assertEquals(0, f.store.loads)
    }

    @Test fun pageRetirementAfterBotCommitStillSavesAcceptedWorkUnderUnchangedOwner() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val host = f.host()
        host.open()
        val engine = f.sessions.single()
        engine.plans += HostBotPlan(2)
        engine.onAdvance = { host.beginRetirement() }
        host.apply(HostFakeSession.action)
        host.retirementCompletion.await()
        assertEquals(3, LanzhouMahjongNativeHistory.actionCount(f.store.saved!!.record.nativeState))
        assertEquals(9L, f.store.saved!!.record.nextChoiceSeed)
        assertEquals(1, engine.closeCalls)
    }

    @Test fun nextHostWithSameGateWaitsForOldNativeReleaseAcrossSeparateStorageBindings() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val first = f.host()
        first.open()
        val second = f.host()
        val opening = launch { second.open() }
        runCurrent()
        assertEquals(1, f.sessions.size)
        assertFalse(opening.isCompleted)
        assertTrue(first.leave())
        opening.join()
        assertEquals(2, f.sessions.size)
        assertEquals(1, f.maxLive)
        second.closeAndJoin()
    }

    @Test fun retiringHostWaitingForPredecessorNeverCreatesNewNative() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val first = f.host()
        first.open()
        val second = f.host()
        val opening = launch { second.open() }
        runCurrent()
        second.beginRetirement()
        assertTrue(first.leave())
        opening.join()
        second.retirementCompletion.await()
        assertEquals(1, f.sessions.size)
    }

    @Test fun failedPredecessorReleaseCannotBeBypassedByOpeningAnotherHost() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val first = f.host()
        first.open()
        f.sessions.single().failClose = true
        try { first.closeAndJoin(); fail("release failure hidden") } catch (_: IOException) { }
        repeat(2) {
            val next = f.host()
            next.open()
            assertTrue(next.state.value.restoreFailed)
            assertEquals(1, f.sessions.size)
            next.closeAndJoin()
        }
        assertEquals(1, f.sessions.single().closeCalls)
    }

    @Test fun allNativeOpenReadUseAndCloseCallsRunOnWorkerThread() = runBlocking {
        val nativeThread = AtomicReference<Thread>()
        Executors.newSingleThreadExecutor { command ->
            Thread(command, "lzmj-host-test-worker").also(nativeThread::set)
        }
            .asCoroutineDispatcher().use { worker ->
                val f = HostFixture(worker, Difficulty.MEDIUM)
                // Coroutine debugging decorates the name; the actual worker must remain identical.
                f.assertWorker = { assertSame(checkNotNull(nativeThread.get()), Thread.currentThread()) }
                val host = f.host()
                host.open(); host.apply(HostFakeSession.action); host.requestHint(); host.undoDecision()
                host.restart()
                assertTrue(host.leave())
                assertEquals(1, f.maxLive)
                assertTrue(f.sessions.all { it.closeCalls == 1 })
            }
    }

    @Test fun boundedBotPauseUsesActualActionCountAndSeedWrapsWithoutBecomingNegative() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val original = f.record()
        f.store.saved = LanzhouMahjongStoredSession(LanzhouMahjongSessionRecord(original.difficulty,
            0, 7, 0, 1, Long.MAX_VALUE, original.nativeState), 123)
        val host = f.host()
        host.open()
        val engine = f.sessions.single()
        engine.plans += HostBotPlan(2, stop = LanzhouMahjongBotStop.STEP_LIMIT)
        host.apply(HostFakeSession.action)
        assertEquals(1L, f.store.saved!!.record.nextChoiceSeed)
        assertTrue(host.state.value.table!!.needsAdvance)
        engine.plans += HostBotPlan(1)
        host.retryAdvance()
        assertEquals(2L, f.store.saved!!.record.nextChoiceSeed)
        assertEquals(listOf(Long.MAX_VALUE, Long.MAX_VALUE, 1L), engine.seeds)
        host.closeAndJoin()
    }

    @Test fun oldNativeRuleIsPreservedOnSaveAndExplicitRestartUsesNewRule() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val old = HostFakeSession(LanzhouMahjongLaunchConfig(Difficulty.EASY, 7)).apply { nativeVersion = 1 }
        f.store.saved = LanzhouMahjongStoredSession(LanzhouMahjongSessionRecord(Difficulty.EASY,
            0, 7, 0, 1, 7, old.serialize()), 123)
        val host = f.host()
        host.open()
        host.apply(HostFakeSession.action)
        assertEquals(1, f.store.saved!!.record.nativeState[4].toInt())
        host.restart()
        assertEquals(2, f.store.saved!!.record.nativeState[4].toInt())
        assertEquals(1, f.maxLive)
        host.closeAndJoin()
    }

    @Test fun cancelledLoadPropagatesAndReleasedLeaseAllowsNextHost() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val cancelled = CancellationException("private load cancellation")
        f.store.loadError = cancelled
        val host = f.host()
        try { host.open(); fail("load cancellation swallowed") } catch (_: CancellationException) { }
        assertSame(cancelled, host.failures.single())
        assertEquals("对局恢复已中止，原存档已保留", host.state.value.error)
        host.closeAndJoin()
        f.store.loadError = null
        val next = f.host()
        next.open()
        assertEquals(1, f.sessions.size)
        next.closeAndJoin()
    }

    @Test fun cancelledPreviousReleaseDoesNotReplaceItsFailedLeaseSlot() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val first = f.host()
        first.open()
        f.sessions.single().failClose = true
        f.sessions.single().closeFailure = CancellationException("private release cancellation")
        try { first.closeAndJoin(); fail("cancelled release hidden") } catch (_: CancellationException) { }
        repeat(2) {
            val next = f.host()
            try { next.open(); fail("predecessor cancellation swallowed") } catch (_: CancellationException) { }
            assertTrue(next.state.value.restoreFailed)
            assertFalse(next.state.value.toString().contains("private release"))
            next.closeAndJoin()
        }
        assertEquals(1, f.sessions.size)
        assertEquals(1, f.sessions.single().closeCalls)
    }

    @Test fun nativeOpenFailureAndFailedCloseRetainBothOriginalCausesAndSuppressedChain() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        f.store.saved = LanzhouMahjongStoredSession(f.record(), 123)
        val primary = IOException("private Native restore failure")
        val close = IOException("private Native handle release failure")
        val suppressed = IOException("original suppressed diagnostic")
        close.addSuppressed(suppressed)
        val engine = HostFakeSession(LanzhouMahjongLaunchConfig(Difficulty.EASY, 7)).apply {
            restoreFailure = primary
            closeFailure = close
            failClose = true
        }
        val host = LanzhouMahjongGameHost(f.storage(), engine.config, { engine }, f.dispatcher,
            openMode = LanzhouMahjongOpenMode.RESUME)
        host.open()
        try { host.retirementCompletion.await(); fail("cleanup failure hidden") }
        catch (failure: com.masterofchessstrategy.settings.LocalDataWriterFailure) {
            assertSame(primary, failure.failures[0])
            assertSame(close, failure.failures[1])
        }
        assertTrue(host.state.value.retired)
        assertFalse(host.state.value.toString().contains("private Native"))
        assertEquals(listOf(primary, close), host.failures)
        assertEquals(0, primary.suppressed.size)
        assertArrayEquals(arrayOf(suppressed), close.suppressed)
        assertEquals(1, engine.closeCalls)
    }

    @Test fun botCancellationSavesMeasuredPrefixAndPropagatesAfterAcknowledgement() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val host = f.host()
        host.open()
        val engine = f.sessions.single()
        engine.plans += HostBotPlan(2, cancellation = true)
        try { host.apply(HostFakeSession.action); fail("batch cancellation swallowed") }
        catch (_: CancellationException) { }
        assertEquals(9L, f.store.saved!!.record.nextChoiceSeed)
        assertEquals(3, LanzhouMahjongNativeHistory.actionCount(f.store.saved!!.record.nativeState))
        assertTrue(host.state.value.table!!.needsAdvance)
        assertFalse(host.state.value.table!!.blocked)
        assertFalse(host.state.value.toString().contains("private batch"))
        host.retryAdvance()
        assertEquals(listOf(7L, 7L, 9L), engine.seeds)
        host.closeAndJoin()
    }

    @Test fun cancelledSaveRetainsExactRetryAndDoesNotRunBotsAgain() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val host = f.host()
        host.open()
        val cancelled = CancellationException("private save cancellation")
        f.store.saveError = cancelled
        try { host.apply(HostFakeSession.action); fail("save cancellation swallowed") }
        catch (_: CancellationException) { }
        val pending = f.store.attempts.last()
        assertTrue(host.state.value.table!!.needsSave)
        assertFalse(host.state.value.table!!.blocked)
        assertTrue(host.failures.any { it === cancelled })
        assertFalse(host.state.value.toString().contains("private save"))
        f.store.saveError = null
        host.retrySave()
        assertSame(pending, f.store.saved)
        assertEquals(2, f.sessions.single().seeds.size)
        host.closeAndJoin()
    }

    @Test fun epochChangeDuringLoadKeepsRetiredFlagAndNeverOpensNative() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val storage = LanzhouMahjongHostStorage(f.gate) { _ ->
            object : LanzhouMahjongAdmittedSessionStore {
                override suspend fun load(): LanzhouMahjongStoredSession? {
                    f.gate.exclusive(replacing = true) { }
                    return null
                }
                override suspend fun save(snapshot: LanzhouMahjongStoredSession) { fail("retired load wrote") }
            }
        }
        val host = LanzhouMahjongGameHost(storage, LanzhouMahjongLaunchConfig(Difficulty.EASY, 7),
            sessionFactory = { fail("retired load opened Native"); error("unreachable") }, dispatcher = f.dispatcher)
        host.open()
        host.retirementCompletion.await()
        assertTrue(host.state.value.retired)
        assertTrue(host.state.value.leaving)
    }

    @Test fun operationCauseStaysInDiagnosticsAndStableChineseFeedbackReplacesRawNativeText() = runTest {
        val f = HostFixture(StandardTestDispatcher(testScheduler))
        val host = f.host()
        host.open()
        val cause = IllegalStateException("Native SQL private failure")
        f.sessions.single().applyFailure = cause
        host.apply(HostFakeSession.action)
        assertSame(cause, host.failures.single())
        assertTrue(host.state.value.table!!.blocked)
        assertEquals("对局操作失败，原存档已保留，请返回后重试", host.state.value.table!!.feedback)
        assertFalse(host.state.value.toString().contains("private failure"))
        host.closeAndJoin()
    }
}

internal data class HostBotPlan(val steps: Int, val failure: Boolean = false,
    val breakSerialization: Boolean = false, val wrongCount: Boolean = false, val rewritePrefix: Boolean = false,
    val stop: LanzhouMahjongBotStop = LanzhouMahjongBotStop.VIEWER_ACTION, val cancellation: Boolean = false)

internal class HostFixture(val dispatcher: CoroutineDispatcher, val difficulty: Difficulty = Difficulty.EASY) {
    val gate = LanzhouMahjongStorageEpochGate()
    val store = HostMemoryStore(gate)
    val sessions = mutableListOf<HostFakeSession>()
    var clockCalls = 0
    var live = 0
    var maxLive = 0
    var rejectRestore = false
    var assertWorker: () -> Unit = {}
    fun storage() = LanzhouMahjongHostStorage(gate) { owner -> store.bind(owner) }
    fun host() = LanzhouMahjongGameHost(storage(), LanzhouMahjongLaunchConfig(difficulty, 7),
        sessionFactory = { config ->
            assertWorker()
            assertEquals(0, live)
            live++
            maxLive = maxOf(maxLive, live)
            HostFakeSession(config, assertWorker) { live-- }.also {
                it.rejectRestore = rejectRestore
                sessions += it
            }
        }, dispatcher = dispatcher, now = { clockCalls++; 1000L + clockCalls }, newSeed = { 19 },
        openMode = if (store.saved == null) LanzhouMahjongOpenMode.NEW else LanzhouMahjongOpenMode.RESUME)
    fun record() = LanzhouMahjongSessionRecord(difficulty, 0, 7, 0, 1, 7,
        HostFakeSession(LanzhouMahjongLaunchConfig(difficulty, 7)).serialize())
}

internal class HostMemoryStore(private val gate: LanzhouMahjongStorageEpochGate) : LanzhouMahjongAdmittedSessionStore {
    private lateinit var owner: LanzhouMahjongStorageEpochGate.Owner
    var ownerCaptures = 0
    fun bind(value: LanzhouMahjongStorageEpochGate.Owner): LanzhouMahjongAdmittedSessionStore {
        owner = value
        ownerCaptures++
        // Each returned writer retains its own owner even when a later page opens.
        return object : LanzhouMahjongAdmittedSessionStore {
            override suspend fun load() = gate.access(value) { loadBody() }
            override suspend fun save(snapshot: LanzhouMahjongStoredSession) = gate.access(value) { saveBody(snapshot) }
            override suspend fun authorizeNew(difficulty: Difficulty, expectedCurrent: LanzhouMahjongStoredSession?) =
                gate.access(value) { authorizeBody(expectedCurrent) }
        }
    }
    var saved: LanzhouMahjongStoredSession? = null
    val attempts = mutableListOf<LanzhouMahjongStoredSession>()
    var failSave = false
    var saveGate: CompletableDeferred<Unit>? = null
    var loadError: Exception? = null
    var saveError: Exception? = null
    var loads = 0
    var authorizeError: Exception? = null
    var authorizations = 0
    private fun authorizeBody(expected: LanzhouMahjongStoredSession?) {
        authorizations++
        authorizeError?.let { throw it }
        check(saved === expected) { "QA checkpoint changed" }
    }
    private fun loadBody(): LanzhouMahjongStoredSession? { loads++; loadError?.let { throw it }; return saved }
    private suspend fun saveBody(snapshot: LanzhouMahjongStoredSession) {
        attempts += snapshot
        saveGate?.await()
        saveError?.let { throw it }
        if (failSave) throw IOException("disk full")
        saved = snapshot
    }
    override suspend fun load() = gate.access(owner) { loadBody() }
    override suspend fun save(snapshot: LanzhouMahjongStoredSession) = gate.access(owner) { saveBody(snapshot) }
}

internal class HostFakeSession(val config: LanzhouMahjongLaunchConfig,
    private val checkWorker: () -> Unit = {}, private val onClose: () -> Unit = {}) : LanzhouMahjongBotSession {
    companion object { val action = LanzhouMahjongAction(LanzhouMahjongActionKind.DISCARD, 0) }
    override val viewerSeat: Int get() { checkWorker(); return config.viewerSeat }
    val actions = mutableListOf<Triple<Int, Int, Int>>()
    val plans = ArrayDeque<HostBotPlan>()
    val seeds = mutableListOf<Long>()
    var applied = 0
    var closeCalls = 0
    var rejectRestore = false
    var failClose = false
    var breakSerialization = false
    var onAdvance: () -> Unit = {}
    var nativeVersion = 2
    var closeFailure: Throwable = IOException("close failed")
    var restoreFailure: Throwable? = null
    var applyFailure: Throwable? = null
    var snapshotCalls = 0
    override fun advanceOtherSeats(difficulty: Difficulty, choiceSeed: Long, stepLimit: Int): LanzhouMahjongBotAdvance {
        checkWorker()
        seeds += choiceSeed
        val plan = plans.removeFirstOrNull() ?: HostBotPlan(0)
        repeat(plan.steps) { actions += Triple(0, (config.viewerSeat + 1) % 4, 255) }
        if (plan.rewritePrefix) actions[0] = Triple(1, config.viewerSeat, 6)
        breakSerialization = plan.breakSerialization
        onAdvance()
        if (plan.cancellation) throw CancellationException("private batch cancellation")
        if (plan.failure) throw IOException("injected Native batch failure")
        return LanzhouMahjongBotAdvance(plan.stop,
            plan.steps + if (plan.wrongCount) 1 else 0)
    }
    override fun snapshot(): LanzhouMahjongPublicView {
        checkWorker()
        snapshotCalls++
        return LanzhouMahjongPublicView(LanzhouMahjongPhase.DISCARD, config.viewerSeat,
            config.dealerSeat, 55, null, null,
            LanzhouMahjongOutcomeView(null, null, config.dealerSeat, false, false, false, false, false),
            (0..3).map { seat -> LanzhouMahjongSeatView(seat, if (seat == config.viewerSeat) 14 else 13,
                false, false, emptyList(), emptyList(), if (seat == config.viewerSeat)
                    listOf(0, 0, 0, 8, 8, 8, 9, 9, 9, 17, 17, 17, 4, 6) else emptyList()) }, List(4) { 0L })
    }
    override fun legalActions(): List<LanzhouMahjongAction> {
        checkWorker(); return listOf(action, LanzhouMahjongAction(LanzhouMahjongActionKind.DISCARD, 6))
    }
    override fun apply(action: LanzhouMahjongAction): ActionResult {
        checkWorker(); applyFailure?.let { throw it }; applied++; actions += Triple(action.kind.code, config.viewerSeat, action.tile)
        return ActionResult.Accepted
    }
    override fun undo() = false
    override fun serialize(): ByteArray {
        checkWorker()
        if (breakSerialization) throw IOException("cannot measure complete prefix")
        val bytes = ByteArray(130 + actions.size * 3)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf(76, 90, 77, 74, nativeVersion.toByte(), config.dealerSeat.toByte()))
        buffer.putLong(config.baseScore)
        // Distinguish game identities for restart without copying any private real data into logs.
        repeat(108) { buffer.put(((it + config.seed.toInt()) % 27).toByte()) }
        buffer.putInt(actions.size)
        actions.forEach { (kind, seat, tile) -> buffer.put(kind.toByte()); buffer.put(seat.toByte()); buffer.put(tile.toByte()) }
        var hash = 0x811c9dc5.toInt()
        for (index in 0 until bytes.size - 4) hash = (hash xor (bytes[index].toInt() and 255)) * 16777619
        buffer.putInt(hash)
        return bytes
    }
    override fun restore(data: ByteArray): RestoreResult {
        checkWorker()
        restoreFailure?.let { throw it }
        val count = LanzhouMahjongNativeHistory.actionCount(data)
        if (rejectRestore || count == null) return RestoreResult.Rejected(EngineError.CORRUPTED_DATA)
        nativeVersion = data[4].toInt()
        actions.clear()
        repeat(count) { index ->
            val offset = 126 + index * 3
            actions += Triple(data[offset].toInt() and 255, data[offset + 1].toInt() and 255, data[offset + 2].toInt() and 255)
        }
        return RestoreResult.Restored
    }
    override fun close() { checkWorker(); closeCalls++; if (failClose) throw closeFailure; onClose() }
}
