package com.masterofchessstrategy.games.lanzhou_fangqi

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.*
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLocalStorage
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiStorageEpochGate
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Explicit rule/store doubles: these tests verify ownership and saves, not Native legality or SQLite. */
@OptIn(ExperimentalCoroutinesApi::class)
class FangqiGameViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun main() { Dispatchers.setMain(dispatcher) }
    @After fun reset() { Dispatchers.resetMain() }

    private fun storage(store: FangqiOwnerTestStore) = FangqiLocalStorage(FangqiStorageEpochGate(), { store }, dispatcher)
    private fun model(storage: FangqiLocalStorage, session: FangqiOwnerTestSession,
        requested: Difficulty? = null) = FangqiGameViewModel(storage, requested, { session }, dispatcher, { 123L }, { "new-id" })

    @Test fun newStandardGameIsNotPlayableUntilItsActualInitialSaveCompletes() = runTest(dispatcher) {
        val store = FangqiOwnerTestStore().apply { saveRelease = CompletableDeferred() }
        val session = FangqiOwnerTestSession()
        val vm = model(storage(store), session)
        runCurrent()
        assertTrue(vm.state.value.loading)
        assertNull(vm.state.value.interaction)
        store.saveRelease!!.complete(Unit)
        runCurrent()
        assertNotNull(vm.state.value.interaction)
        assertEquals(FangqiStoredMode.LOCAL_TWO_PLAYER, vm.state.value.mode)
        assertEquals("new-id", store.saved!!.sessionId)
        assertTrue(vm.leave())
    }

    @Test fun existingModeDifficultyIdAndAuxiliaryDataOverrideRequestedParameters() = runTest(dispatcher) {
        val store = FangqiOwnerTestStore().apply {
            saved = FangqiOwnerTestSession.saved(FangqiStoredMode.HUMAN_VS_AI, Difficulty.MEDIUM, count = 2, undo = 2, hint = 3)
        }
        val session = FangqiOwnerTestSession()
        val vm = model(storage(store), session, Difficulty.MASTER)
        runCurrent()
        assertEquals(Difficulty.MEDIUM, vm.state.value.difficulty)
        assertEquals(FangqiStoredMode.HUMAN_VS_AI, vm.state.value.mode)
        assertEquals(0, store.attempts.size)
        assertTrue(vm.leave())
        assertEquals("retained-id", store.saved!!.sessionId)
        assertEquals(2, store.saved!!.undoUseCount)
        assertEquals(3, store.saved!!.hintUseCount)
    }

    @Test fun corruptLoadIsRetainedAndRetryDoesNotCreateAReplacementGame() = runTest(dispatcher) {
        val store = FangqiOwnerTestStore().apply { loadFailure = IOException("bad original row") }
        val vm = model(storage(store), FangqiOwnerTestSession())
        runCurrent()
        assertTrue(vm.state.value.retryLoad)
        assertNull(vm.state.value.interaction)
        assertTrue(store.attempts.isEmpty())
        store.loadFailure = null
        store.saved = FangqiOwnerTestSession.saved()
        vm.retryLoad(); runCurrent()
        assertNotNull(vm.state.value.interaction)
        assertTrue(store.attempts.isEmpty())
        assertTrue(vm.leave())
    }

    @Test fun failedInitialSaveRetriesSameSnapshotIdTimeAndRevision() = runTest(dispatcher) {
        val store = FangqiOwnerTestStore().apply { saveFailure = IOException("disk unavailable") }
        val vm = model(storage(store), FangqiOwnerTestSession())
        runCurrent()
        assertTrue(vm.state.value.retrySave)
        assertNull(vm.state.value.interaction)
        val first = store.attempts.single()
        store.saveFailure = null
        vm.retrySave(); runCurrent()
        assertEquals(2, store.attempts.size)
        val retried = store.attempts.last()
        assertSame(first, retried)
        assertEquals(123L, retried.updatedAtEpochMillis)
        assertEquals("new-id", retried.sessionId)
        assertNotNull(vm.state.value.interaction)
        assertTrue(vm.leave())
    }

    @Test fun leavingAnUnreadableRetainedRowDoesNotRequireOverwritingOrRereadingIt() = runTest(dispatcher) {
        val store = FangqiOwnerTestStore().apply { loadFailure = IOException("corrupt original") }
        val vm = model(storage(store), FangqiOwnerTestSession())
        runCurrent()
        assertTrue(vm.state.value.retryLoad)
        assertTrue(vm.leave())
        assertTrue(store.attempts.isEmpty())
    }

    @Test fun retirementDuringNativeCreationClosesTheLateCandidateBeforeGrantingNextLease() = runTest(dispatcher) {
        val owner = storage(FangqiOwnerTestStore().apply { saved = FangqiOwnerTestSession.saved() })
        val session = FangqiOwnerTestSession()
        lateinit var vm: FangqiGameViewModel
        vm = FangqiGameViewModel(owner, sessionFactory = { vm.beginRetirement(); session }, worker = dispatcher)
        runCurrent()
        vm.retirementCompletion.await()
        assertEquals(1, session.closeCalls)
        assertNull(vm.state.value.interaction)
        val next = model(owner, FangqiOwnerTestSession())
        runCurrent()
        assertNotNull(next.state.value.interaction)
        assertTrue(next.leave())
    }

    @Test fun restartUsesANewIdentityAndResetsAllowancesOnlyOnceAcrossSaveRetry() = runTest(dispatcher) {
        val store = FangqiOwnerTestStore().apply {
            saved = FangqiOwnerTestSession.saved(FangqiStoredMode.HUMAN_VS_AI, Difficulty.MEDIUM, 2, 3, 3)
        }
        val vm = model(storage(store), FangqiOwnerTestSession())
        runCurrent()
        store.saveFailure = IOException("write failed")
        val interaction = vm.state.value.interaction!!
        interaction.reset(); runCurrent()
        assertTrue(vm.state.value.undoAllowed)
        val first = store.attempts.single()
        assertEquals("new-id", first.sessionId)
        assertEquals(0, first.undoUseCount)
        assertEquals(0, first.hintUseCount)
        store.saveFailure = null
        interaction.retrySave(); runCurrent()
        assertSame(first, store.attempts.last())
        assertTrue(vm.leave())
    }

    @Test fun restoredMasterCannotUndoAndMediumFourthRequestIsRejectedBeforeNative() = runTest(dispatcher) {
        for ((difficulty, used) in listOf(Difficulty.MASTER to 0, Difficulty.MEDIUM to 3)) {
            val store = FangqiOwnerTestStore().apply {
                saved = FangqiOwnerTestSession.saved(FangqiStoredMode.HUMAN_VS_AI, difficulty, 2, used)
            }
            val session = FangqiOwnerTestSession()
            val vm = model(storage(store), session)
            runCurrent()
            assertFalse(vm.state.value.undoAllowed)
            vm.state.value.interaction!!.undo()
            runCurrent()
            assertEquals(0, session.undoCalls)
            assertTrue(store.attempts.isEmpty())
            assertTrue(vm.leave())
        }
    }

    @Test fun wholeHumanTurnUndoUsesOneAllowanceAndSaveRetryDoesNotChargeAgain() = runTest(dispatcher) {
        val store = FangqiOwnerTestStore().apply {
            saved = FangqiOwnerTestSession.saved(FangqiStoredMode.HUMAN_VS_AI, Difficulty.MEDIUM, 2, 2, 3)
        }
        val session = FangqiOwnerTestSession()
        val vm = model(storage(store), session)
        runCurrent()
        store.saveFailure = IOException("late readback failure")
        val interaction = vm.state.value.interaction!!
        interaction.undo(); runCurrent()
        assertEquals(2, session.undoCalls)
        assertFalse(vm.state.value.undoAllowed)
        assertEquals(3, store.attempts.single().undoUseCount)
        assertEquals(3, store.attempts.single().hintUseCount)
        store.saveFailure = null
        interaction.retrySave(); runCurrent()
        assertEquals(2, store.attempts.size)
        assertSame(store.attempts[0], store.attempts[1])
        assertEquals(3, store.saved!!.undoUseCount)
        assertEquals(2, session.undoCalls)
        assertTrue(vm.leave())
    }

    @Test fun failedLeaveNeverReleasesNativeOrPopsAndSuccessfulRetryCanReenter() = runTest(dispatcher) {
        val store = FangqiOwnerTestStore().apply { saveFailure = IOException("no space") }
        val owner = storage(store)
        val session = FangqiOwnerTestSession()
        val vm = model(owner, session)
        runCurrent()
        assertFalse(vm.leave())
        val failedAttempts = store.attempts.size
        assertFalse(vm.leave())
        assertEquals(failedAttempts, store.attempts.size)
        assertEquals(0, session.closeCalls)
        assertFalse(vm.retirementCompletion.isCompleted)
        store.saveFailure = null
        assertTrue(vm.leave(requestId = 1))
        assertEquals(1, session.closeCalls)
        val next = model(owner, FangqiOwnerTestSession(), Difficulty.MASTER)
        runCurrent()
        assertEquals(FangqiStoredMode.LOCAL_TWO_PLAYER, next.state.value.mode)
        assertTrue(next.leave())
    }

    @Test fun recreatedRouteCanRejoinTheSameDelayedExitAfterItsOldWaiterWasCancelled() = runTest(dispatcher) {
        val store = FangqiOwnerTestStore().apply { saved = FangqiOwnerTestSession.saved() }
        val session = FangqiOwnerTestSession()
        val vm = model(storage(store), session)
        runCurrent()
        store.saveRelease = CompletableDeferred()
        val interaction = vm.state.value.interaction!!
        interaction.tap(FangqiPoint(0, 0)); interaction.tap(FangqiPoint(1, 0))
        runCurrent()
        val oldComposition = async { vm.leave(requestId = 7) }
        runCurrent()
        assertFalse(oldComposition.isCompleted)
        oldComposition.cancelAndJoin()
        assertEquals(0, session.closeCalls)
        val recreatedComposition = async { vm.leave(requestId = 7) }
        runCurrent()
        assertFalse(recreatedComposition.isCompleted)
        store.saveRelease!!.complete(Unit)
        assertTrue(recreatedComposition.await())
        assertEquals(1, session.closeCalls)
        assertTrue(vm.leave(requestId = 7))
        assertEquals(1, session.closeCalls)
        assertEquals(1, store.attempts.size)
    }

    @Test fun retirementDiscardsLateAiBeforeApplyAndClosesOnlyAfterChooseReturns() = runTest(dispatcher) {
        val store = FangqiOwnerTestStore().apply {
            saved = FangqiOwnerTestSession.saved(FangqiStoredMode.HUMAN_VS_AI, Difficulty.EASY, 1)
        }
        lateinit var vm: FangqiGameViewModel
        val session = FangqiOwnerTestSession().apply { onChoose = { vm.beginRetirement() } }
        vm = model(storage(store), session)
        runCurrent()
        vm.retirementCompletion.await()
        assertEquals(1, session.chooseCalls)
        assertEquals(0, session.applyCalls)
        assertEquals(1, session.closeCalls)
        assertFalse(session.closedWhileChoosing)
        assertTrue(store.attempts.isEmpty())
    }

    @Test fun restoreRetirementWaitsForNoncancellableWriterBeforeClosingAndNewOwner() = runTest(dispatcher) {
        val store = FangqiOwnerTestStore().apply { saveRelease = CompletableDeferred(); noncancellableSave = true }
        val owner = storage(store)
        val firstSession = FangqiOwnerTestSession()
        val first = model(owner, firstSession)
        runCurrent()
        val completed = first.beginRetirement()
        val second = model(owner, FangqiOwnerTestSession())
        runCurrent()
        assertFalse(completed.isCompleted)
        assertEquals(0, firstSession.closeCalls)
        assertTrue(second.state.value.loading)
        store.saveRelease!!.complete(Unit)
        runCurrent()
        completed.await()
        assertEquals(1, firstSession.closeCalls)
        assertNotNull(second.state.value.interaction)
        assertTrue(second.leave())
    }

    @Test fun restoredAutomaticModeOwnsBothAiTurnsAndPersistsEachOne() = runTest(dispatcher) {
        val store = FangqiOwnerTestStore().apply {
            saved = FangqiOwnerTestSession.saved(FangqiStoredMode.AI_AUTO_PLAY, Difficulty.EASY)
        }
        val session = FangqiOwnerTestSession().apply { finishAfter = 2 }
        val vm = model(storage(store), session)
        runCurrent()
        assertEquals(FangqiStoredMode.AI_AUTO_PLAY, vm.state.value.mode)
        assertEquals(2, session.chooseCalls)
        assertEquals(2, session.applyCalls)
        assertEquals(2, store.attempts.size)
        assertEquals(2, store.saved!!.acceptedActionCount)
        assertTrue(vm.leave())
    }

    @Test fun resourceLimitStopsHumanAndAutomaticAiWithoutRetryResultOrSave() = runTest(dispatcher) {
        for (mode in listOf(FangqiStoredMode.HUMAN_VS_AI, FangqiStoredMode.AI_AUTO_PLAY)) {
            val count = if (mode == FangqiStoredMode.HUMAN_VS_AI) 1 else 0
            val original = FangqiOwnerTestSession.saved(mode, Difficulty.EASY, count)
            val store = FangqiOwnerTestStore().apply { saved = original }
            val session = FangqiOwnerTestSession().apply { applyRejection = FangqiRejection.RESOURCE_LIMIT }
            val vm = model(storage(store), session)
            runCurrent()
            val interaction = vm.state.value.interaction!!
            assertEquals(FangqiFeedback.HISTORY_LIMIT, interaction.state.value.feedback)
            assertEquals(GameResult.ONGOING, interaction.state.value.snapshot.result)
            assertTrue(vm.state.value.historyLimited)
            assertFalse(vm.state.value.retryAi)
            assertEquals(1, session.chooseCalls)
            assertEquals(1, session.applyAttempts)
            assertEquals(0, session.applyCalls)
            assertSame(original, store.saved)
            assertTrue(store.attempts.isEmpty())
            assertArrayEquals(original.engineState, session.serialize())
            // Selection feedback is transient; it must not reopen the VM's resource fence.
            interaction.tap(FangqiPoint(6, 6))
            vm.retryAi(); runCurrent()
            assertEquals(1, session.chooseCalls)
            assertEquals(1, session.applyAttempts)
            assertTrue(vm.state.value.historyLimited)
            assertTrue(vm.leave())
        }
    }

    @Test fun restartingAfterResourceLimitClearsTheFenceAndSavesANewGameBeforeAiContinues() = runTest(dispatcher) {
        val store = FangqiOwnerTestStore().apply {
            saved = FangqiOwnerTestSession.saved(FangqiStoredMode.AI_AUTO_PLAY, Difficulty.EASY)
        }
        val session = FangqiOwnerTestSession().apply { applyRejection = FangqiRejection.RESOURCE_LIMIT }
        val vm = model(storage(store), session)
        runCurrent()
        assertTrue(vm.state.value.historyLimited)
        session.applyRejection = null
        session.finishAfter = 1
        store.saveRelease = CompletableDeferred()
        vm.state.value.interaction!!.reset()
        runCurrent()
        assertFalse(vm.state.value.historyLimited)
        assertEquals(1, session.chooseCalls)
        assertEquals("new-id", store.attempts.single().sessionId)
        assertEquals(0, store.attempts.single().acceptedActionCount)
        store.saveRelease!!.complete(Unit)
        runCurrent()
        assertEquals(2, session.chooseCalls)
        assertEquals(1, session.applyCalls)
        assertEquals(GameResult.FIRST_PLAYER_WIN, vm.state.value.interaction!!.state.value.snapshot.result)
        assertEquals(1, store.saved!!.acceptedActionCount)
        assertTrue(vm.leave())
    }

    @Test fun ongoingAiWithoutAnActionKeepsLegalPositionAndDoesNotGuessAResourceFailure() = runTest(dispatcher) {
        val original = FangqiOwnerTestSession.saved(FangqiStoredMode.HUMAN_VS_AI, Difficulty.EASY, 1)
        val store = FangqiOwnerTestStore().apply { saved = original }
        val session = FangqiOwnerTestSession().apply { chooseReturnsNull = true }
        val vm = model(storage(store), session)
        runCurrent()
        assertTrue(session.legalActions().isNotEmpty())
        assertEquals(GameResult.ONGOING, session.snapshot().result)
        assertTrue(vm.state.value.aiBlocked)
        assertFalse(vm.state.value.historyLimited)
        assertFalse(vm.state.value.retryAi)
        assertEquals("AI暂不能继续走子，当前对局已保留，可返回备份或重开", vm.state.value.error)
        assertSame(original, store.saved)
        assertTrue(store.attempts.isEmpty())
        vm.state.value.interaction!!.tap(FangqiPoint(6, 6))
        vm.retryAi(); runCurrent()
        assertEquals(1, session.chooseCalls)
        assertEquals(0, session.applyAttempts)
        assertArrayEquals(original.engineState, session.serialize())
        session.chooseReturnsNull = false
        vm.state.value.interaction!!.undo()
        runCurrent()
        assertFalse(vm.state.value.aiBlocked)
        assertEquals(0, store.saved!!.acceptedActionCount)
        assertEquals(1, store.saved!!.undoUseCount)
        assertTrue(vm.leave())
    }

    @Test fun failedNativeCloseRemainsAnExceptionalRetirementAndBlocksNextOwner() = runTest(dispatcher) {
        val owner = storage(FangqiOwnerTestStore().apply { saved = FangqiOwnerTestSession.saved() })
        val session = FangqiOwnerTestSession().apply { closeFailure = IOException("Native close failed") }
        val vm = model(owner, session)
        runCurrent()
        try { vm.beginRetirement().await(); fail() } catch (_: IOException) { }
        assertTrue(vm.retirementCompletion.isCancelled)
        val next = model(owner, FangqiOwnerTestSession())
        runCurrent()
        assertTrue(next.state.value.retryLoad)
        next.beginRetirement().await()
    }
}

internal class FangqiOwnerTestStore : FangqiSessionStore {
    var saved: FangqiStoredSession? = null
    var loadFailure: Exception? = null
    var saveFailure: Exception? = null
    var saveRelease: CompletableDeferred<Unit>? = null
    var noncancellableSave = false
    val attempts = mutableListOf<FangqiStoredSession>()
    override suspend fun load(): FangqiStoredSession? { loadFailure?.let { throw it }; return saved }
    override suspend fun save(snapshot: FangqiStoredSession) {
        attempts += snapshot
        suspend fun write() { saveRelease?.await(); saveFailure?.let { throw it }; saved = snapshot }
        if (noncancellableSave) withContext(NonCancellable) { write() } else write()
    }
}

internal class FangqiOwnerTestSession : FangqiSession {
    private var count = 0
    var finishAfter = Int.MAX_VALUE
    var onChoose: (() -> Unit)? = null
    var closeFailure: Exception? = null
    var undoCalls = 0
    var chooseCalls = 0
    var applyCalls = 0
    var applyAttempts = 0
    var applyRejection: FangqiRejection? = null
    var chooseReturnsNull = false
    var closeCalls = 0
    private var choosing = false
    var closedWhileChoosing = false
    private val side get() = if (count % 2 == 0) FangqiSide.RED else FangqiSide.BLACK
    override fun snapshot() = FangqiSnapshot(board(count), side, 0,
        if (count >= finishAfter) GameResult.FIRST_PLAYER_WIN else GameResult.ONGOING, count > 0)
    override fun legalActions(): List<FangqiAction> = if (count >= finishAfter) emptyList() else listOf(nextAction(count))
    override fun apply(action: FangqiAction): FangqiMoveResult {
        applyAttempts++
        if (action !in legalActions()) return FangqiMoveResult(false, FangqiRejection.INVALID_ACTION)
        applyRejection?.let { return FangqiMoveResult(false, it) }
        applyCalls++; count++; return FangqiMoveResult(true, FangqiRejection.NONE)
    }
    override fun undo(): Boolean { if (count == 0) return false; undoCalls++; count--; return true }
    override fun reset() { count = 0 }
    override fun serialize() = bytes(count)
    override fun restore(data: ByteArray): Boolean {
        count = data.toString(Charsets.US_ASCII).split('|')[4].toInt()
        return true
    }
    override fun chooseAction(difficulty: Difficulty): FangqiAction? {
        chooseCalls++; choosing = true
        try { onChoose?.invoke(); return if (chooseReturnsNull) null else legalActions().firstOrNull() } finally { choosing = false }
    }
    override fun close() { closeCalls++; closedWhileChoosing = choosing; closeFailure?.let { throw it } }
    companion object {
        private fun nextAction(count: Int): FangqiAction {
            val red = count % 2 == 0
            val from = if (red) (count / 2) % 2 else 48 - (count / 2) % 2
            val to = if (red) 1 - from else 95 - from
            return FangqiAction.Move(FangqiPoint(from % 7, from / 7), FangqiPoint(to % 7, to / 7))
        }
        private fun board(count: Int) = MutableList(49) { 0 }.apply {
            this[((count + 1) / 2) % 2] = 1
            this[48 - (count / 2) % 2] = 2
        }
        private fun bytes(count: Int) = ("MOCS-LFQ|1|1|" + board(0).joinToString("") + "|$count|" +
            (0 until count).joinToString(";") { index ->
                val move = nextAction(index) as FangqiAction.Move
                "m,${if (index % 2 == 0) 1 else 2},${move.from.index},${move.to.index}"
            }).toByteArray(Charsets.US_ASCII)
        fun saved(mode: FangqiStoredMode = FangqiStoredMode.LOCAL_TWO_PLAYER, difficulty: Difficulty? = null,
            count: Int = 0, undo: Int = 0, hint: Int = 0) = FangqiStoredSession(mode, difficulty, bytes(count),
            "retained-id", 10L, count, undo, hint)
    }
}
