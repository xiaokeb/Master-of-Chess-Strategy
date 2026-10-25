package com.masterofchessstrategy.games.lanzhou_fangqi

import androidx.lifecycle.ViewModelStore
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.*
import com.masterofchessstrategy.games.lanzhou_fangqi.data.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Explicit Store/Native doubles: no SQLite, JNI, Activity recreation or actual navigation is tested here. */
@OptIn(ExperimentalCoroutinesApi::class)
class FangqiLearningViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setMain() { Dispatchers.setMain(dispatcher) }
    @After fun resetMain() { Dispatchers.resetMain() }

    private fun model(store: FakeStore, native: FakeSession = FakeSession(),
        gate: FangqiStorageEpochGate = FangqiStorageEpochGate(),
        worker: CoroutineDispatcher = dispatcher,
    ) = FangqiLearningViewModel(store, gate, gate.epoch.value, { board, side ->
        assertEquals(LanzhouFangqiLearningContent.practices.first().board, board)
        assertEquals(FangqiSide.RED, side)
        native
    }, worker)

    @Test fun loadUsesActualStoreResultAndNeverCreatesEmptyProgressOnFailure() = runTest(dispatcher) {
        val store = FakeStore().apply { loadFailure = IOException("retained bad row") }
        val vm = model(store)
        runCurrent()
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.progress)
        assertEquals("学习进度读取失败，请重试。原记录已保留", vm.state.value.error)
        assertTrue(store.writes.isEmpty())
        store.loadFailure = null
        store.rows += row("lesson:step")
        vm.retryLoad(); runCurrent()
        assertEquals(setOf("step"), vm.state.value.progress!!.completedLessons)
        assertTrue(vm.leave())
    }

    @Test fun lessonCompletionAppearsOnlyAfterStoreCommitReturns() = runTest(dispatcher) {
        val store = FakeStore().apply { saveRelease = CompletableDeferred() }
        val vm = model(store)
        runCurrent()
        vm.acknowledgeLesson("opening"); runCurrent()
        assertTrue(vm.state.value.saving)
        assertEquals("lesson:opening", vm.state.value.pendingContentKey)
        assertTrue(vm.state.value.progress!!.completedLessons.isEmpty())
        vm.acknowledgeLesson("step"); runCurrent()
        assertEquals(1, store.writes.size)
        store.saveRelease!!.complete(Unit); runCurrent()
        assertEquals(setOf("opening"), vm.state.value.progress!!.completedLessons)
        assertNull(vm.state.value.pendingContentKey)
        assertTrue(vm.leave())
    }

    @Test fun failedLessonKeepsSameIntentAndExplicitRetryIsRequired() = runTest(dispatcher) {
        val store = FakeStore().apply { saveFailure = IOException("disk unavailable") }
        val vm = model(store)
        runCurrent(); vm.acknowledgeLesson("opening"); runCurrent()
        assertFalse(vm.state.value.saving)
        assertEquals("学习进度保存失败，请重试。当前进度尚未确认", vm.state.value.error)
        assertTrue(vm.state.value.progress!!.completedLessons.isEmpty())
        assertEquals(1, store.writes.size)
        runCurrent()
        assertEquals(1, store.writes.size)
        store.saveFailure = null
        vm.retrySave(); runCurrent()
        assertEquals(listOf("lesson:opening", "lesson:opening"), store.writes.map { it.first })
        assertEquals(setOf("opening"), vm.state.value.progress!!.completedLessons)
        assertTrue(vm.leave())
    }

    @Test fun practiceAndAcceptedNonterminalMoveStayOnSameRetainedModelWithoutProgressWrite() = runTest(dispatcher) {
        val store = FakeStore(); val session = FakeSession(); val vm = model(store, session)
        runCurrent(); vm.openPractice(PRACTICE); runCurrent()
        val controller = vm.state.value.interaction!!
        val retainedSession = vm.state.value.session
        move(controller)
        assertSame(controller, vm.state.value.interaction)
        assertSame(retainedSession, vm.state.value.session)
        assertEquals(1, controller.state.value.snapshot.pendingCaptures)
        assertTrue(store.writes.isEmpty())
        assertFalse(session.closed)
        assertFalse(controller.state.value.savePending)
        assertTrue(vm.backToLessons())
        assertTrue(session.closed)
        assertNull(vm.state.value.practiceId)
        assertTrue(vm.leave())
    }

    @Test fun terminalHistoryIsPassedToStoreAndConfirmedOnlyAfterCommit() = runTest(dispatcher) {
        val store = FakeStore().apply { saveRelease = CompletableDeferred() }
        val session = FakeSession(); val vm = model(store, session)
        runCurrent(); vm.openPractice(PRACTICE); runCurrent()
        win(vm.state.value.interaction!!); runCurrent()
        assertEquals(GameResult.FIRST_PLAYER_WIN, vm.state.value.interaction!!.state.value.snapshot.result)
        assertTrue(vm.state.value.interaction!!.state.value.savePending)
        assertFalse(PRACTICE in vm.state.value.progress!!.completedPractices)
        assertArrayEquals(session.serialize(), store.writes.single().second)
        store.saveRelease!!.complete(Unit); runCurrent()
        assertTrue(PRACTICE in vm.state.value.progress!!.completedPractices)
        assertFalse(vm.state.value.interaction!!.state.value.savePending)
        assertTrue(vm.leave())
    }

    @Test fun failedTerminalSaveFreezesHistoryAndRetriesSameBytesWithoutAnotherMove() = runTest(dispatcher) {
        val store = FakeStore().apply { saveFailure = IOException("retry required") }
        val session = FakeSession(); val vm = model(store, session)
        runCurrent(); vm.openPractice(PRACTICE); runCurrent()
        val controller = vm.state.value.interaction!!
        win(controller); runCurrent()
        val bytes = session.serialize()
        assertTrue(controller.state.value.saveFailed)
        assertFalse(PRACTICE in vm.state.value.progress!!.completedPractices)
        controller.reset(); controller.undo(); controller.tap(FangqiPoint(0, 0))
        assertArrayEquals(bytes, session.serialize())
        assertEquals(1, store.writes.size)
        store.saveFailure = null
        controller.retrySave(); runCurrent()
        assertEquals(2, store.writes.size)
        assertArrayEquals(store.writes[0].second, store.writes[1].second)
        assertTrue(PRACTICE in vm.state.value.progress!!.completedPractices)
        assertTrue(vm.leave())
    }

    @Test fun restartRestoresPracticeSeedRatherThanCallingStandardReset() = runTest(dispatcher) {
        val session = FakeSession(); val store = FakeStore(); val vm = model(store, session)
        runCurrent(); vm.openPractice(PRACTICE); runCurrent()
        val controller = vm.state.value.interaction!!
        val seed = session.serialize()
        move(controller)
        assertFalse(seed.contentEquals(session.serialize()))
        controller.reset()
        assertArrayEquals(seed, session.serialize())
        assertEquals(0, session.standardResets)
        assertTrue(store.writes.isEmpty())
        assertTrue(vm.leave())
    }

    @Test fun staleCourseClickCannotStartLessonSaveWhilePracticeOwnsThePage() = runTest(dispatcher) {
        val store = FakeStore(); val vm = model(store)
        runCurrent(); vm.openPractice(PRACTICE); runCurrent()
        vm.acknowledgeLesson("opening"); runCurrent()
        assertTrue(store.writes.isEmpty())
        assertTrue(vm.leave())
    }

    @Test fun cancelledComposeWaiterDoesNotCancelVmOwnedBackSaveAndClose() = runTest(dispatcher) {
        val store = FakeStore().apply { saveRelease = CompletableDeferred() }
        val session = FakeSession(); val vm = model(store, session)
        runCurrent(); vm.openPractice(PRACTICE); runCurrent()
        win(vm.state.value.interaction!!); runCurrent()
        val waiter = launch { vm.backToLessons(5) }
        runCurrent()
        assertTrue(vm.state.value.leaving)
        waiter.cancelAndJoin()
        assertFalse(session.closed)
        store.saveRelease!!.complete(Unit); runCurrent()
        assertTrue(vm.backToLessons(5))
        assertTrue(session.closed)
        assertEquals(1, session.closes)
        assertTrue(PRACTICE in vm.state.value.progress!!.completedPractices)
        assertNull(vm.state.value.practiceId)
        assertTrue(vm.leave(6))
    }

    @Test fun failedSavePreventsBackAndSameRequestDoesNotImplicitlyRetry() = runTest(dispatcher) {
        val store = FakeStore().apply { saveFailure = IOException("bad save") }
        val session = FakeSession(); val vm = model(store, session)
        runCurrent(); vm.openPractice(PRACTICE); runCurrent()
        win(vm.state.value.interaction!!); runCurrent()
        assertFalse(vm.backToLessons(1))
        assertFalse(vm.backToLessons(1))
        assertFalse(session.closed)
        assertEquals(1, store.writes.size)
        store.saveFailure = null
        vm.retrySave(); runCurrent()
        assertFalse(vm.backToLessons(1))
        assertTrue(vm.backToLessons(2))
        assertTrue(session.closed)
        assertTrue(vm.leave(3))
    }

    @Test fun repeatedRequestIdInANewPracticeCannotReuseThePreviousCloseResult() = runTest(dispatcher) {
        val gate = FangqiStorageEpochGate()
        val first = FakeSession(); val second = FakeSession()
        val sessions = ArrayDeque(listOf(first, second))
        val vm = FangqiLearningViewModel(FakeStore(), gate, gate.epoch.value, { _, _ -> sessions.removeFirst() }, dispatcher)
        runCurrent(); vm.openPractice(PRACTICE); runCurrent()
        assertTrue(vm.backToLessons())
        assertTrue(first.closed)
        vm.openPractice(PRACTICE); runCurrent()
        assertFalse(second.closed)
        assertTrue(vm.backToLessons())
        assertTrue(second.closed)
        assertEquals(1, second.closes)
        assertTrue(vm.leave())
    }

    @Test fun outsideLeaveWaitsForPracticeBackThenCompletesItsOwnRetirement() = runTest(dispatcher) {
        val store = FakeStore().apply { saveRelease = CompletableDeferred() }
        val session = FakeSession(); val vm = model(store, session)
        runCurrent(); vm.openPractice(PRACTICE); runCurrent()
        win(vm.state.value.interaction!!); runCurrent()
        val practiceBack = async { vm.backToLessons(1) }
        runCurrent()
        val pageBack = async { vm.leave(2) }
        runCurrent()
        assertFalse(pageBack.isCompleted)
        assertFalse(vm.retirementCompletion.isCompleted)
        store.saveRelease!!.complete(Unit); runCurrent()
        assertTrue(practiceBack.await())
        assertTrue(pageBack.await())
        assertTrue(vm.retirementCompletion.isCompleted)
        assertTrue(session.closed)
        assertEquals(1, session.closes)
    }

    @Test fun closeFailureIsObservableAndNeverReportedAsSuccessfulRetirement() = runTest(dispatcher) {
        for (explicitBack in listOf(false, true)) {
            val error = IOException("release failed")
            val session = FakeSession().apply { closeError = error }
            val vm = model(FakeStore(), session)
            runCurrent(); vm.openPractice(PRACTICE); runCurrent()
            if (explicitBack) assertFalse(vm.backToLessons(1))
            val retirement = vm.beginRetirement()
            try { retirement.await(); fail("false successful retirement") } catch (failure: IOException) {
                assertEquals("release failed", failure.message)
            }
            assertTrue(vm.state.value.closeFailed)
            assertEquals("练习未能安全结束，请重启应用后重试", vm.state.value.error)
            assertSame(retirement, vm.beginRetirement())
            assertEquals(1, session.closes)
        }
    }

    @Test fun epochReplacementRetiresPracticeAndLateCompletionDoesNotRefreshOwner() = runTest(dispatcher) {
        val gate = FangqiStorageEpochGate()
        val store = FakeStore().apply { saveRelease = CompletableDeferred() }
        val session = FakeSession(); val vm = model(store, session, gate)
        runCurrent(); vm.openPractice(PRACTICE); runCurrent()
        win(vm.state.value.interaction!!); runCurrent()
        gate.exclusive(replacing = true) {}; runCurrent()
        vm.retirementCompletion.await()
        assertTrue(vm.state.value.retired)
        assertTrue(session.closed)
        assertFalse(PRACTICE in vm.state.value.progress!!.completedPractices)
        val attempts = store.writes.size
        vm.retrySave(); vm.acknowledgeLesson("opening"); vm.openPractice(PRACTICE); runCurrent()
        assertEquals(attempts, store.writes.size)
    }

    @Test fun permanentViewModelClearWaitsForNativeAndRetirementIsStable() = runTest(dispatcher) {
        val session = FakeSession(); val vm = model(FakeStore(), session)
        runCurrent(); vm.openPractice(PRACTICE); runCurrent()
        val owners = ViewModelStore().apply { put("learning", vm) }
        owners.clear(); runCurrent()
        vm.retirementCompletion.await()
        assertTrue(session.closed)
        assertSame(vm.retirementCompletion, vm.beginRetirement())
    }

    @Test fun lateNativeCreationAfterRetirementIsStillClosedBeforeAcknowledgement() = runTest(dispatcher) {
        val workerScheduler = TestCoroutineScheduler()
        val worker = StandardTestDispatcher(workerScheduler)
        val session = FakeSession(); val vm = model(FakeStore(), session, worker = worker)
        runCurrent(); vm.openPractice(PRACTICE); runCurrent()
        assertTrue(vm.state.value.openingPractice)
        val completion = vm.beginRetirement()
        runCurrent()
        assertFalse(completion.isCompleted)
        workerScheduler.runCurrent(); runCurrent()
        workerScheduler.runCurrent(); runCurrent()
        completion.await()
        assertTrue(session.closed)
        assertEquals(1, session.closes)
        assertNull(vm.state.value.interaction)
    }

    @Test fun sharedNativeLeaseWaitsForPriorPracticeCloseWithoutTouchingOrdinaryMatchStorage() = runTest(dispatcher) {
        val gate = FangqiStorageEpochGate()
        val ordinary = OrdinaryStore()
        val storage = FangqiLocalStorage(gate, { ordinary }, dispatcher)
        val order = mutableListOf<String>()
        val first = FakeSession().apply { onClose = { order += "closed-first" } }
        val second = FakeSession()
        val vm1 = FangqiLearningViewModel(FakeStore(), gate, gate.epoch.value,
            { _, _ -> order += "created-first"; first }, dispatcher, storage::acquire)
        val vm2 = FangqiLearningViewModel(FakeStore(), gate, gate.epoch.value,
            { _, _ -> order += "created-second"; second }, dispatcher, storage::acquire)
        runCurrent(); vm1.openPractice(PRACTICE); runCurrent()
        vm2.openPractice(PRACTICE); runCurrent()
        assertNull(vm2.state.value.session)
        assertEquals(listOf("created-first"), order)
        assertTrue(vm1.backToLessons(1)); runCurrent()
        assertEquals(listOf("created-first", "closed-first", "created-second"), order)
        assertNotNull(vm2.state.value.session)
        assertTrue(vm1.leave(2)); assertTrue(vm2.leave(1))
        assertEquals(0, ordinary.loads)
        assertEquals(0, ordinary.saves)
        storage.acquire().also { it.retireWriter(); it.release() }
    }

    @Test fun priorNativeCloseFailureBlocksANewViewModelInsteadOfBeingBypassedOnReentry() = runTest(dispatcher) {
        val gate = FangqiStorageEpochGate()
        val ordinary = OrdinaryStore()
        val storage = FangqiLocalStorage(gate, { ordinary }, dispatcher)
        var firstLease: FangqiStorageLease? = null
        val first = FakeSession().apply { closeError = IOException("prior Native release failed") }
        val vm1 = FangqiLearningViewModel(FakeStore(), gate, gate.epoch.value, { _, _ -> first }, dispatcher,
            { storage.acquire().also { firstLease = it } })
        runCurrent(); vm1.openPractice(PRACTICE); runCurrent()
        assertFalse(vm1.backToLessons(1))
        var laterCreations = 0
        val vm2 = FangqiLearningViewModel(FakeStore(), gate, gate.epoch.value,
            { _, _ -> laterCreations++; FakeSession() }, dispatcher, storage::acquire)
        runCurrent(); vm2.openPractice(PRACTICE); runCurrent()
        assertEquals(0, laterCreations)
        assertNull(vm2.state.value.session)
        assertEquals("练习暂时无法打开，请返回课程后重试", vm2.state.value.error)
        try { vm1.beginRetirement().await(); fail("failed retirement") } catch (_: IOException) {}
        assertTrue(vm2.backToLessons(1)); assertTrue(vm2.leave(2))
        assertEquals(1, first.closes)
        assertEquals(0, ordinary.loads)
        assertEquals(0, ordinary.saves)
        firstLease!!.retireWriter() // Only drains this test's idle queue; its failed Native release stays failed.
    }

    @Test fun epochReplacementWhileWaitingForNativeLeaseRetiresWithoutCreatingOrRefreshingTheOwner() = runTest(dispatcher) {
        val gate = FangqiStorageEpochGate()
        val ordinary = OrdinaryStore()
        val storage = FangqiLocalStorage(gate, { ordinary }, dispatcher)
        val priorLease = storage.acquire()
        var creates = 0
        val vm = FangqiLearningViewModel(FakeStore(), gate, gate.epoch.value,
            { _, _ -> creates++; FakeSession() }, dispatcher, storage::acquire)
        runCurrent(); vm.openPractice(PRACTICE); runCurrent()
        assertTrue(vm.state.value.openingPractice)
        gate.exclusive(replacing = true) {}; runCurrent()
        vm.retirementCompletion.await()
        priorLease.release()
        runCurrent()
        assertTrue(vm.state.value.retired)
        assertEquals(0, creates)
        vm.openPractice(PRACTICE); runCurrent()
        assertEquals(0, creates)
        assertEquals(0, ordinary.loads)
        assertEquals(0, ordinary.saves)
        storage.acquire().also { assertEquals(1L, it.epoch); it.retireWriter(); it.release() }
    }

    private class OrdinaryStore : FangqiSessionStore {
        var loads = 0
        var saves = 0
        override suspend fun load(): FangqiStoredSession? { loads++; error("learning must not read ordinary match") }
        override suspend fun save(snapshot: FangqiStoredSession) { saves++; error("learning must not write ordinary match") }
    }

    private class FakeStore : FangqiLearningStore {
        val rows = mutableListOf<FangqiLearningProgressEntity>()
        val writes = mutableListOf<Pair<String, ByteArray?>>()
        var loadFailure: Throwable? = null
        var saveFailure: Throwable? = null
        var saveRelease: CompletableDeferred<Unit>? = null
        override suspend fun load(): FangqiLearningSnapshot {
            loadFailure?.let { throw it }
            return FangqiLearningSnapshot(rows)
        }
        override suspend fun acknowledgeLesson(id: String) = save("lesson:$id", null)
        override suspend fun completePractice(id: String, completeHistory: ByteArray) = save("practice:$id", completeHistory)
        private suspend fun save(key: String, bytes: ByteArray?): FangqiLearningSnapshot {
            writes += key to bytes?.copyOf()
            saveRelease?.await()
            saveFailure?.let { throw it }
            if (rows.none { it.contentKey == key }) rows += row(key)
            return FangqiLearningSnapshot(rows)
        }
    }

    private class FakeSession : FangqiSession {
        private val seed = LanzhouFangqiLearningContent.practices.first().board.toList()
        private var board = seed.toMutableList()
        private var actions = 0
        var standardResets = 0
        var closed = false
        var closes = 0
        var closeError: Throwable? = null
        var onClose: (() -> Unit)? = null
        override fun snapshot() = FangqiSnapshot(board.toList(), FangqiSide.RED, if (actions == 1) 1 else 0,
            if (actions == 2) GameResult.FIRST_PLAYER_WIN else GameResult.ONGOING, actions != 0)
        override fun serialize(): ByteArray = ("MOCS-LFQ|1|1|${seed.joinToString("")}|$actions|" + when (actions) {
            1 -> "m,1,15,8"
            2 -> "m,1,15,8;c,1,40,40"
            else -> ""
        }).toByteArray()
        override fun apply(action: FangqiAction): FangqiMoveResult {
            when (action) {
                is FangqiAction.Move -> { board[action.from.index] = 0; board[action.to.index] = 1; actions = 1 }
                is FangqiAction.Capture -> { board[action.point.index] = 0; actions = 2 }
            }
            return FangqiMoveResult(true, FangqiRejection.NONE)
        }
        override fun legalActions(): List<FangqiAction> = when (actions) {
            0 -> listOf(FangqiAction.Move(FangqiPoint(1, 2), FangqiPoint(1, 1)))
            1 -> listOf(FangqiAction.Capture(FangqiPoint(5, 5)))
            else -> emptyList()
        }
        override fun undo(): Boolean { if (actions == 0) return false; restoreSeed(); return true }
        override fun reset() { standardResets++; board = MutableList(49) { 0 }; actions = 0 }
        override fun restore(data: ByteArray): Boolean {
            if (data.toString(Charsets.US_ASCII) != "MOCS-LFQ|1|1|${seed.joinToString("")}|0|") return false
            restoreSeed(); return true
        }
        private fun restoreSeed() { board = seed.toMutableList(); actions = 0 }
        override fun chooseAction(difficulty: Difficulty): FangqiAction? = error("learning never searches")
        override fun close() { closes++; closeError?.let { throw it }; closed = true; onClose?.invoke() }
    }

    companion object {
        private const val PRACTICE = "lfq-practice-square-01"
        private fun row(key: String) = FangqiLearningProgressEntity(key, 1, 123L)
        private fun move(controller: LanzhouFangqiInteraction) { controller.tap(FangqiPoint(1, 2)); controller.tap(FangqiPoint(1, 1)) }
        private fun win(controller: LanzhouFangqiInteraction) { move(controller); controller.tap(FangqiPoint(5, 5)) }
    }
}
