package com.masterofchessstrategy.games.tiger_goat.ai

import com.masterofchessstrategy.games.tiger_goat.TigerGoatAction
import com.masterofchessstrategy.games.tiger_goat.TigerGoatActionKind
import com.masterofchessstrategy.games.tiger_goat.TigerGoatBoardSpec
import com.masterofchessstrategy.games.tiger_goat.TigerGoatEdge
import com.masterofchessstrategy.games.tiger_goat.TigerGoatPhase
import com.masterofchessstrategy.games.tiger_goat.TigerGoatPoint
import com.masterofchessstrategy.games.tiger_goat.TigerGoatRulesSession
import com.masterofchessstrategy.games.tiger_goat.TigerGoatSnapshot
import com.masterofchessstrategy.games.tiger_goat.TigerGoatWinner
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Scripted legal-action fixture checks ownership/persistence, not Native game rules. */
class TigerGoatFormalGameOwnerTest {
    @Test fun everyAiChainActionSavesBeforeTheNextSearchAndOnlyEndChainHandsOver() = runBlocking {
        val fixture = Fixture()
        fixture.owner.load()
        fixture.deliverAndAdvance()
        fixture.owner.onPointTap(3)
        fixture.deliverAndAdvance()
        assertEquals(TigerGoatPhase.CAPTURE_CHAIN, fixture.owner.uiState.value.game!!.phase)
        assertTrue(fixture.owner.uiState.value.thinking)
        fixture.owner.onPointTap(4) // Human input is ignored throughout the AI chain.
        assertEquals(3, fixture.rules.stage)
        fixture.deliverAndAdvance()
        assertTrue(fixture.owner.uiState.value.thinking)
        fixture.deliverAndAdvance()
        assertFalse(fixture.owner.uiState.value.thinking)
        assertEquals(2, fixture.owner.uiState.value.game!!.toMove)
        assertEquals((0..5).toList(), fixture.store.saved.map { it.actionCount })
        assertEquals(listOf(0, 2, 3, 4), fixture.search.startedAt)
        fixture.owner.retire()
    }

    @Test fun saveFailureBlocksBothInputsAndSearchUntilTheSameCheckpointIsRetried() = runBlocking {
        val fixture = Fixture()
        fixture.owner.load()
        fixture.deliverAndAdvance()
        fixture.store.failNextSave = true
        fixture.owner.onPointTap(3)
        val failedRevision = fixture.store.attempts.last().revision
        assertTrue(fixture.owner.uiState.value.canRetrySave)
        fixture.owner.advanceAi()
        fixture.owner.onPointTap(3)
        assertEquals(2, fixture.rules.stage)
        assertEquals(listOf(0), fixture.search.startedAt)
        fixture.owner.retrySave()
        assertEquals(failedRevision, fixture.store.saved.last().revision)
        assertEquals(listOf(0, 2), fixture.search.startedAt)
        assertNull(fixture.owner.uiState.value.error)
        fixture.owner.retire()
    }

    @Test fun undoAndRestartInvalidateQueuedResultsAndChangePositionIdentity() = runBlocking {
        val fixture = Fixture()
        fixture.owner.load()
        fixture.deliverAndAdvance()
        fixture.owner.onPointTap(3)
        fixture.search.queue(fixture.rules.legalActions().first())
        fixture.owner.undo()
        assertEquals(1, fixture.rules.stage)
        fixture.owner.advanceAi()
        assertEquals(1, fixture.rules.stage)
        fixture.owner.onPointTap(3)
        fixture.search.queue(fixture.rules.legalActions().first())
        val priorId = fixture.store.saved.last().sessionId
        fixture.owner.restart()
        fixture.owner.advanceAi()
        assertEquals(0, fixture.rules.stage)
        assertNotEquals(priorId, fixture.store.saved.last().sessionId)
        assertTrue(fixture.search.cancelCalls >= 3)
        fixture.owner.retire()
    }

    @Test fun restoreMidChainResumesOnlyTheAiSideWithTheSavedIdentity() = runBlocking {
        val fixture = Fixture()
        fixture.store.loaded = TigerGoatFormalLoadResult.Loaded(
            TigerGoatFormalCheckpoint("saved-chain", 21, TigerGoatFormalConfig(2), 3, 100, byteArrayOf(3)),
        )
        fixture.owner.load()
        assertTrue(fixture.owner.uiState.value.thinking)
        assertEquals("saved-chain", fixture.search.position!!.sessionId)
        fixture.deliverAndAdvance()
        assertEquals(22L, fixture.store.saved.last().revision)
        assertEquals(4, fixture.rules.stage)
        fixture.owner.retire()
    }

    @Test fun unqualifiedAndLocalCheckpointsArePreservedWithoutStartingAi() = runBlocking {
        val unqualified = Fixture(qualified = false)
        unqualified.owner.load()
        assertTrue(unqualified.owner.uiState.value.recoveryBlocked)
        assertTrue(unqualified.store.saved.isEmpty())
        assertTrue(unqualified.search.startedAt.isEmpty())
        unqualified.owner.retire()

        val local = Fixture()
        local.store.loaded = TigerGoatFormalLoadResult.LocalGame
        local.owner.load()
        assertTrue(local.owner.uiState.value.recoveryBlocked)
        assertTrue(local.store.saved.isEmpty())
        assertTrue(local.search.startedAt.isEmpty())
        local.owner.retire()
    }

    @Test fun replacementRetiresTheWriterAndJoinsSearchBeforeNativeClose() = runBlocking {
        val fixture = Fixture()
        fixture.owner.load()
        fixture.search.queue(fixture.rules.legalActions().first())
        fixture.store.isCurrent = false
        fixture.owner.advanceAi()
        assertTrue(fixture.owner.uiState.value.retired)
        assertEquals(0, fixture.rules.stage)
        fixture.owner.retrySave()
        fixture.owner.restart()
        assertEquals(1, fixture.store.saved.size)
        fixture.owner.retire()
        fixture.owner.retire()
        assertEquals(listOf("seal", "joined", "native-closed"), fixture.events)
    }

    @Test fun leavingDuringSaveDrainsThatOperationWithoutStartingAnotherSearch() = runBlocking {
        val fixture = Fixture()
        val saveEntered = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        fixture.store.beforeSave = { saveEntered.complete(Unit); releaseSave.await() }
        val loading = launch { fixture.owner.load() }
        saveEntered.await()
        fixture.owner.beginRetirement()
        releaseSave.complete(Unit)
        fixture.owner.retire()
        loading.join()
        assertTrue(fixture.search.startedAt.isEmpty())
        assertEquals(listOf("seal", "joined", "native-closed"), fixture.events)
    }

    @Test fun illegalSearchActionLeavesRulesAndSavedHistoryUntouchedAndCanRetry() = runBlocking {
        val fixture = Fixture()
        fixture.owner.load()
        fixture.search.queue(TigerGoatAction(TigerGoatActionKind.CAPTURE, 0, 4))
        fixture.owner.advanceAi()
        assertTrue(fixture.owner.uiState.value.canRetryAi)
        assertEquals(0, fixture.rules.stage)
        assertEquals(1, fixture.store.saved.size)
        fixture.owner.retryAi()
        fixture.deliverAndAdvance()
        assertEquals(1, fixture.rules.stage)
        fixture.owner.retire()
    }

    @Test fun checkpointOwnsSourceAndReturnedHistoryArrays() {
        val bytes = byteArrayOf(3)
        val checkpoint = TigerGoatFormalCheckpoint("owned", 0, TigerGoatFormalConfig(2), 3, 100, bytes)
        bytes[0] = 9
        checkpoint.copyBytes()[0] = 8
        assertArrayEquals(byteArrayOf(3), checkpoint.copyOwned().copyBytes())
    }

    @Test fun humanStepAndAiGoatReplyUndoToTheStartOfTheHumanTurn() = runBlocking {
        val events = mutableListOf<String>()
        val rules = HumanTigerRules(events, chain = false)
        val store = MemoryStore()
        val search = SearchSpy(events)
        val owner = TigerGoatFormalGameOwner(
            store, rules, search, TigerGoatFormalConfig(1), { true }, now = { 100 },
        )
        owner.load()
        owner.onPointTap(0)
        owner.onPointTap(2)
        search.queue(rules.legalActions().first())
        owner.advanceAi()
        assertEquals(2, rules.stage)
        owner.undo()
        assertEquals(0, rules.stage)
        assertEquals(0, store.saved.last().actionCount)
        assertFalse(owner.uiState.value.thinking)
        owner.retire()
    }

    @Test fun completeHumanTigerChainAndAiGoatReplyUndoAllCapturesAndEndChain() = runBlocking {
        val events = mutableListOf<String>()
        val rules = HumanTigerRules(events, chain = true)
        val store = MemoryStore()
        val search = SearchSpy(events)
        val owner = TigerGoatFormalGameOwner(
            store, rules, search, TigerGoatFormalConfig(1), { true }, now = { 100 },
        )
        owner.load()
        owner.onPointTap(0)
        owner.onPointTap(2)
        owner.onPointTap(4)
        owner.endChain()
        search.queue(rules.legalActions().first())
        owner.advanceAi()
        assertEquals(4, rules.stage)
        owner.undo()
        assertEquals(0, rules.stage)
        assertEquals(TigerGoatPhase.PLAY, owner.uiState.value.game!!.phase)
        assertEquals(0, store.saved.last().actionCount)
        owner.retire()
    }

    @Test fun changingWallClockDoesNotInvalidateAnUnchangedSearchPosition() = runBlocking {
        val events = mutableListOf<String>()
        val rules = ScriptedRules(events)
        val store = MemoryStore()
        val search = SearchSpy(events)
        var clock = 100L
        val owner = TigerGoatFormalGameOwner(
            store, rules, search, TigerGoatFormalConfig(2), { true }, now = { clock++ },
        )
        owner.load()
        val searchedAt = search.position!!.updatedAt
        search.queue(rules.legalActions().first())
        owner.advanceAi()
        assertEquals(1, rules.stage)
        assertTrue(store.saved.last().updatedAt > searchedAt)
        owner.retire()
    }

    private class Fixture(qualified: Boolean = true) {
        val events = mutableListOf<String>()
        val rules = ScriptedRules(events)
        val store = MemoryStore()
        val search = SearchSpy(events)
        private var nextId = 0
        val owner = TigerGoatFormalGameOwner(
            store, rules, search, TigerGoatFormalConfig(2), { qualified },
            now = { 100 }, newId = { "test-${nextId++}" },
        )
        suspend fun deliverAndAdvance() {
            search.queue(rules.legalActions().first())
            owner.advanceAi()
        }
    }

    private class MemoryStore : TigerGoatFormalSessionStore {
        override var isCurrent = true
        var loaded: TigerGoatFormalLoadResult = TigerGoatFormalLoadResult.Missing
        val saved = mutableListOf<TigerGoatFormalCheckpoint>()
        val attempts = mutableListOf<TigerGoatFormalCheckpoint>()
        var failNextSave = false
        var beforeSave: suspend () -> Unit = {}
        override suspend fun load() = loaded
        override suspend fun save(checkpoint: TigerGoatFormalCheckpoint) {
            attempts += checkpoint.copyOwned()
            beforeSave()
            if (failNextSave) {
                failNextSave = false
                throw IOException("save fixture failure")
            }
            saved += checkpoint.copyOwned()
        }
    }

    private class SearchSpy(private val events: MutableList<String>) : TigerGoatFormalSearch {
        override var failed = false
        var position: TigerGoatFormalCheckpoint? = null
        var generation = 0
        var cancelCalls = 0
        var sealed = false
        val startedAt = mutableListOf<Int>()
        private var queued: Pair<Int, TigerGoatAction>? = null
        fun queue(action: TigerGoatAction) { queued = generation to action }
        override fun replace(checkpoint: TigerGoatFormalCheckpoint) {
            generation += 1
            position = checkpoint.copyOwned()
        }
        override fun start(): Boolean {
            if (sealed) return false
            startedAt += position!!.actionCount
            return true
        }
        override fun consume(checkpoint: TigerGoatFormalCheckpoint): TigerGoatAction? {
            val result = queued
            queued = null
            return result?.takeIf {
                it.first == generation && position!!.sessionId == checkpoint.sessionId &&
                    position!!.revision == checkpoint.revision &&
                    position!!.copyBytes().contentEquals(checkpoint.copyBytes())
            }?.second
        }
        override fun cancel() { cancelCalls += 1; generation += 1 }
        override fun seal() { sealed = true; generation += 1; events += "seal" }
        override fun closeAndJoinBlocking() { events += "joined" }
    }

    private class ScriptedRules(private val events: MutableList<String>) : TigerGoatRulesSession {
        var stage = 0
        override val actionCount: Int get() = stage
        override val spec = TigerGoatBoardSpec(
            List(5) { TigerGoatPoint(it, it, 0) }, List(4) { TigerGoatEdge(it, it + 1) },
        )
        override fun snapshot() = TigerGoatSnapshot(
            pieces = listOf(1, 2, 0, 0, 0),
            phase = when (stage) {
                0 -> TigerGoatPhase.OPENING_REMOVAL
                3, 4 -> TigerGoatPhase.CAPTURE_CHAIN
                else -> TigerGoatPhase.PLAY
            },
            toMove = if (stage == 1 || stage == 5) 2 else 1,
            reserve = 14,
            captured = stage,
            winner = TigerGoatWinner.NONE,
            chainTiger = if (stage == 3) 2 else if (stage == 4) 4 else -1,
        )
        override fun legalActions() = when (stage) {
            0 -> listOf(TigerGoatAction(TigerGoatActionKind.REMOVE_GOAT, 1))
            1 -> listOf(TigerGoatAction(TigerGoatActionKind.PLACE_GOAT, to = 3))
            2 -> listOf(TigerGoatAction(TigerGoatActionKind.CAPTURE, 0, 2))
            3 -> listOf(TigerGoatAction(TigerGoatActionKind.CAPTURE, 2, 4))
            4 -> listOf(TigerGoatAction(TigerGoatActionKind.END_CHAIN))
            else -> emptyList()
        }
        override fun apply(action: TigerGoatAction): Int {
            if (action !in legalActions()) return 2
            stage += 1
            return 0
        }
        override fun undo(): Boolean = if (stage == 0) false else { stage -= 1; true }
        override fun reset() { stage = 0 }
        override fun serialize() = byteArrayOf(stage.toByte())
        override fun restore(bytes: ByteArray): Boolean {
            if (bytes.size != 1 || bytes[0].toInt() !in 0..5) return false
            stage = bytes[0].toInt()
            return true
        }
        override fun close() { events += "native-closed" }
    }

    private class HumanTigerRules(
        private val events: MutableList<String>,
        private val chain: Boolean,
    ) : TigerGoatRulesSession {
        var stage = 0
        private val goatTurn: Int get() = if (chain) 3 else 1
        override val actionCount: Int get() = stage
        override val spec = TigerGoatBoardSpec(
            List(5) { TigerGoatPoint(it, it, 0) }, List(4) { TigerGoatEdge(it, it + 1) },
        )
        override fun snapshot() = TigerGoatSnapshot(
            pieces = listOf(1, 2, 0, 0, 0),
            phase = if (chain && stage in 1..2) TigerGoatPhase.CAPTURE_CHAIN else TigerGoatPhase.PLAY,
            toMove = if (stage == goatTurn) 2 else 1,
            reserve = 14,
            captured = if (chain) minOf(stage, 2) else 0,
            winner = TigerGoatWinner.NONE,
            chainTiger = if (stage == 1) 2 else if (stage == 2) 4 else -1,
        )
        override fun legalActions() = when {
            stage == 0 -> listOf(TigerGoatAction(
                if (chain) TigerGoatActionKind.CAPTURE else TigerGoatActionKind.STEP, 0, 2,
            ))
            stage == goatTurn -> listOf(TigerGoatAction(TigerGoatActionKind.PLACE_GOAT, to = 3))
            chain && stage == 1 -> listOf(TigerGoatAction(TigerGoatActionKind.CAPTURE, 2, 4))
            chain && stage == 2 -> listOf(TigerGoatAction(TigerGoatActionKind.END_CHAIN))
            else -> emptyList()
        }
        override fun apply(action: TigerGoatAction): Int {
            if (action !in legalActions()) return 2
            stage += 1
            return 0
        }
        override fun undo(): Boolean = if (stage == 0) false else { stage -= 1; true }
        override fun reset() { stage = 0 }
        override fun serialize() = byteArrayOf(stage.toByte())
        override fun restore(bytes: ByteArray): Boolean {
            if (bytes.size != 1 || bytes[0].toInt() !in 0..(goatTurn + 1)) return false
            stage = bytes[0].toInt()
            return true
        }
        override fun close() { events += "native-closed" }
    }
}
