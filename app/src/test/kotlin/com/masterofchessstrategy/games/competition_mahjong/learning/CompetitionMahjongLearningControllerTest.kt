package com.masterofchessstrategy.games.competition_mahjong.learning

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CompetitionMahjongLearningControllerTest {
    @Test fun lockedChaptersAndUnansweredStepsCannotBeSkipped() = runBlocking {
        val store = MemoryStore()
        val learning = controller(store)
        learning.load()
        learning.selectChapter(3)
        learning.advance()
        assertEquals(0, learning.state.value.chapterIndex)
        assertEquals(0, learning.state.value.cardIndex)
        assertTrue(store.saves.isEmpty())
    }

    @Test fun physicalTileCopiesMustBeSelectedExactlyAndExtraTilesFail() = runBlocking {
        val learning = controller(MemoryStore())
        learning.load()
        learning.acknowledgeReading()
        learning.advance()
        learning.toggleTile(0)
        learning.toggleTile(1)
        learning.submit()
        learning.advance()
        assertEquals(1, learning.state.value.cardIndex)
        assertFalse(learning.state.value.solved)
        learning.toggleTile(1)
        learning.toggleTile(2)
        learning.toggleTile(3)
        learning.submit()
        assertFalse(learning.state.value.solved)
        learning.toggleTile(3)
        learning.submit()
        assertTrue(learning.state.value.solved)
        learning.advance()
        assertEquals(2, learning.state.value.cardIndex)
        assertTrue(learning.state.value.selectedTiles.isEmpty())
    }

    @Test fun wrongKnowledgeAnswerDoesNotPassOrPersist() = runBlocking {
        val store = MemoryStore(proof(2))
        val learning = controller(store)
        learning.load()
        solve(learning)
        learning.advance()
        // Seven fan plus two flowers still fails the eight-fan threshold.
        learning.selectOption(0)
        learning.submit()
        learning.advance()
        assertEquals("flowers-not-threshold", learning.state.value.card.id)
        assertFalse(learning.state.value.solved)
        assertTrue(store.saves.isEmpty())
        learning.selectOption(2)
        learning.submit()
        assertTrue(learning.state.value.solved)
    }

    @Test fun allTwentyNineStepsMustPassAndFourCommitsPrecedeCompletion() = runBlocking {
        val store = MemoryStore()
        val learning = controller(store)
        learning.load()
        var steps = 0
        while (!learning.state.value.tutorialCompleted) {
            assertFalse(learning.state.value.tutorialCompleted)
            solve(learning)
            steps++
            learning.advance()
        }
        assertEquals(29, steps)
        assertEquals(listOf(1, 2, 3, 4), store.saves.map { it.completedChapterCount })
        assertTrue(store.saved!!.isCompleted)
        assertFalse(learning.state.value.canAdvance)
        learning.advance()
        assertEquals(4, store.saves.size)
    }

    @Test fun reopeningStartsFirstUnfinishedChapterAndReviewDoesNotEraseCompletion() = runBlocking {
        val store = MemoryStore(proof(2))
        val learning = controller(store)
        learning.load()
        assertEquals(2, learning.state.value.chapterIndex)
        assertEquals(0, learning.state.value.cardIndex)
        learning.selectChapter(0)
        completeChapter(learning)
        assertEquals(2, learning.state.value.completedChapterCount)
        assertTrue(store.saves.isEmpty())
        val reopened = controller(store)
        reopened.load()
        assertEquals(2, reopened.state.value.chapterIndex)
    }

    @Test fun readFailurePreservesOriginalProgressAndCanBeRetried() = runBlocking {
        val store = MemoryStore(proof(3))
        store.failLoad = true
        val learning = controller(store)
        learning.load()
        assertFalse(learning.state.value.loaded)
        assertTrue(learning.state.value.error != null)
        learning.acknowledgeReading()
        learning.advance()
        assertTrue(store.saves.isEmpty())
        store.failLoad = false
        learning.load()
        assertEquals(3, learning.state.value.completedChapterCount)
        assertTrue(learning.state.value.error == null)
    }

    @Test fun failedFinalCommitBlocksUnlockAndRetryPreservesAllAnswers() = runBlocking {
        val store = MemoryStore(proof(3))
        store.failSave = true
        val learning = controller(store)
        learning.load()
        completeChapter(learning)
        val failed = learning.state.value
        assertTrue(failed.solved)
        assertTrue(failed.needsSave)
        assertFalse(failed.tutorialCompleted)
        assertEquals(3, store.saved!!.completedChapterCount)
        learning.selectChapter(0)
        learning.advance()
        learning.load()
        assertEquals(failed.card.id, learning.state.value.card.id)
        store.failSave = false
        learning.retrySave()
        assertTrue(learning.state.value.tutorialCompleted)
        assertEquals(listOf(4), store.saves.map { it.completedChapterCount })
        learning.retrySave()
        assertEquals(1, store.saves.size)
    }

    @Test fun overlappingActionsCannotDuplicateOrBypassAnInFlightSave() = runBlocking {
        val store = MemoryStore()
        store.saveEntered = CompletableDeferred()
        store.releaseSave = CompletableDeferred()
        val learning = controller(store)
        learning.load()
        prepareChapterEnd(learning)
        val commit = launch(Dispatchers.Unconfined) { learning.advance() }
        store.saveEntered!!.await()
        assertTrue(learning.state.value.busy)
        assertEquals(0, learning.state.value.completedChapterCount)
        learning.advance()
        learning.selectChapter(1)
        assertEquals(0, learning.state.value.chapterIndex)
        store.releaseSave!!.complete(Unit)
        commit.join()
        assertEquals(1, learning.state.value.completedChapterCount)
        assertEquals(1, store.saves.size)
    }

    @Test fun cancellingOwnerDuringAcceptedCommitStillSavesBeforeUnlock() = runBlocking {
        val store = MemoryStore(proof(3))
        store.saveEntered = CompletableDeferred()
        store.releaseSave = CompletableDeferred()
        val learning = controller(store)
        learning.load()
        prepareChapterEnd(learning)
        val commit = launch(Dispatchers.Unconfined) { learning.advance() }
        store.saveEntered!!.await()
        commit.cancel()
        assertFalse(learning.state.value.tutorialCompleted)
        store.releaseSave!!.complete(Unit)
        commit.join()
        assertTrue(store.saved!!.isCompleted)
        assertTrue(learning.state.value.tutorialCompleted)
        val reopened = controller(store)
        reopened.load()
        assertTrue(reopened.state.value.tutorialCompleted)
    }

    @Test fun invalidIndicesAndWrongTaskActionsDoNotMutateExercise() = runBlocking {
        val store = MemoryStore()
        val learning = controller(store)
        learning.load()
        val before = learning.state.value
        learning.selectChapter(-1)
        learning.selectChapter(4)
        learning.selectOption(99)
        learning.toggleTile(-1)
        learning.submit()
        assertEquals(before, learning.state.value)
        assertTrue(store.saves.isEmpty())
    }

    @Test fun tileExercisesArePhysicalAndTeachTheirClaimedStructures() {
        val cards = CompetitionMahjongLearningLibrary.chapters.flatMap { it.cards }
        val tasks = cards.filter { it.task is MahjongLessonTask.Tiles }.associateBy { it.id }
        assertEquals(6, tasks.size)
        fun selected(id: String): List<Int> {
            val task = tasks.getValue(id).task as MahjongLessonTask.Tiles
            return task.acceptedSelections.single().sorted().map(task.tiles::get)
        }
        assertEquals(listOf(22, 22), selected("choose-pair"))
        assertEquals(listOf(1, 2, 3), selected("choose-chow"))
        assertEquals(listOf(31, 31, 31), selected("choose-pung"))
        assertEquals(listOf(0, 1, 2), (selected("claim-chow") + 2).sorted())
        assertEquals(listOf(33, 33, 33), selected("claim-pung") + 33)
        assertEquals((0..8).toList(), selected("pure-straight"))
        assertEquals(29, cards.size)
        assertEquals(cards.size, cards.map { it.id }.toSet().size)
    }

    @Test fun scoringAndFalseWinExamplesUseCompetitionValues() {
        val cards = CompetitionMahjongLearningLibrary.chapters.flatMap { it.cards }.associateBy { it.id }
        fun answer(id: String): String {
            val task = cards.getValue(id).task as MahjongLessonTask.Choice
            return task.options[task.correctIndex]
        }
        assertEquals("26分", answer("self-draw-score"))
        assertEquals("26、8、8", answer("discard-score"))
        assertEquals("10分", answer("below-eight-penalty"))
        assertEquals("20分", answer("unready-penalty"))
        assertEquals("南家", answer("two-winners"))
        assertEquals("原南家接庄", answer("dealer-rotation"))
        assertEquals("自摸，不算杠上开花", answer("kong-flower-win"))
    }

    @Test fun requestIsCreatedOnlyOnFinalExplicitAdvanceAndRetriesKeepItsIdentityAndTime() = runBlocking {
        val store = MemoryStore().apply { failSave = true }
        var clockCalls = 0
        var identityCalls = 0
        val learning = CompetitionMahjongLearningController(store, Dispatchers.Unconfined,
            now = { clockCalls++; 41L }, newRequestId = { identityCalls++; "00000000-0000-0000-0000-000000000099" })
        learning.load(); prepareChapterEnd(learning)
        assertEquals(0, clockCalls)
        assertEquals(0, identityCalls)
        assertTrue(store.attempts.isEmpty())
        learning.advance()
        val original = store.attempts.single()
        assertEquals("tiles", original.chapterId)
        assertEquals(CompetitionMahjongLearningLibrary.chapters.first().cards.map { it.id }, original.passedCardIds)
        learning.retrySave()
        assertSame(original, store.attempts.last())
        store.failSave = false
        learning.retrySave()
        assertSame(original, store.attempts.last())
        assertEquals(1, clockCalls)
        assertEquals(1, identityCalls)
        assertEquals(41L, store.saved!!.receipts.single().request.confirmedAtEpochMillis)
    }

    @Test fun acknowledgementLossDoesNotAdvanceUntilExactRetryConfirmsTheSavedChapter() = runBlocking {
        val store = MemoryStore().apply { loseAcknowledgement = true }
        val learning = controller(store)
        learning.load(); completeChapter(learning)
        assertEquals(0, learning.state.value.completedChapterCount)
        assertTrue(learning.state.value.needsSave)
        assertEquals(1, store.saved!!.completedChapterCount)
        val original = store.attempts.single()
        learning.retrySave()
        assertSame(original, store.attempts.last())
        assertEquals(1, store.saves.size)
        assertEquals(1, learning.state.value.completedChapterCount)
    }

    @Test fun incompatibleProofIsNotTreatedAsEmptyOrCompleted() = runBlocking {
        val store = MemoryStore(proof(4)).apply { incompatibleLoad = true }
        val learning = controller(store)
        learning.load(); learning.acknowledgeReading(); learning.advance()
        assertFalse(learning.state.value.loaded)
        assertFalse(learning.state.value.tutorialCompleted)
        assertTrue(store.attempts.isEmpty())
        assertEquals(4, store.saved!!.completedChapterCount)
        store.incompatibleLoad = false
        learning.load()
        assertTrue(learning.state.value.tutorialCompleted)
    }

    @Test fun unexpectedStoreReplyCannotPublishAnUnconfirmedChapter() = runBlocking {
        val store = MemoryStore().apply { returnWrongProof = true }
        val learning = controller(store)
        learning.load(); completeChapter(learning)
        assertEquals(0, learning.state.value.completedChapterCount)
        assertTrue(learning.state.value.needsSave)
        store.returnWrongProof = false
        learning.retrySave()
        assertEquals(1, learning.state.value.completedChapterCount)
        assertEquals(1, store.saves.size)
    }

    @Test fun retirementStopsAdmissionAndWaitsForAcceptedSaveWithoutPublishingLateProgress() = runBlocking {
        val store = MemoryStore().apply { saveEntered = CompletableDeferred(); releaseSave = CompletableDeferred() }
        val learning = controller(store)
        learning.load(); prepareChapterEnd(learning)
        val commit = launch(Dispatchers.Unconfined) { learning.advance() }
        store.saveEntered!!.await()
        val completion = learning.beginRetirement()
        assertSame(completion, learning.beginRetirement())
        assertFalse(completion.isCompleted)
        assertTrue(learning.state.value.retired)
        learning.retrySave(); learning.advance(); learning.selectChapter(0)
        assertEquals(1, store.attempts.size)
        store.releaseSave!!.complete(Unit)
        commit.join(); completion.await()
        assertEquals(1, store.saved!!.completedChapterCount)
        assertEquals(0, learning.state.value.completedChapterCount)
        assertFalse(learning.state.value.tutorialCompleted)
        assertFalse(learning.state.value.busy)
    }

    @Test fun staleOwnerIsCheckedAfterLoadBeforePublishingOrAcceptingInput() = runBlocking {
        val store = MemoryStore(proof(4)).apply { loadEntered = CompletableDeferred(); releaseLoad = CompletableDeferred() }
        var current = true
        val learning = CompetitionMahjongLearningController(store, Dispatchers.Unconfined, ownerIsCurrent = { current })
        val loading = launch(Dispatchers.Unconfined) { learning.load() }
        store.loadEntered!!.await()
        current = false
        store.releaseLoad!!.complete(Unit)
        loading.join()
        assertTrue(learning.state.value.retired)
        assertFalse(learning.state.value.loaded)
        assertFalse(learning.state.value.tutorialCompleted)
        learning.load(); learning.advance()
        assertTrue(store.attempts.isEmpty())
        learning.retirementCompletion.await()
    }

    @Test fun staleOwnerIsCheckedAfterConfirmationEvenWithoutAnEpochWatcher() = runBlocking {
        val store = MemoryStore().apply { saveEntered = CompletableDeferred(); releaseSave = CompletableDeferred() }
        var current = true
        val learning = CompetitionMahjongLearningController(store, Dispatchers.Unconfined, ownerIsCurrent = { current })
        learning.load(); prepareChapterEnd(learning)
        val commit = launch(Dispatchers.Unconfined) { learning.advance() }
        store.saveEntered!!.await()
        current = false
        store.releaseSave!!.complete(Unit)
        commit.join()
        assertEquals(1, store.saved!!.completedChapterCount)
        assertTrue(learning.state.value.retired)
        assertEquals(0, learning.state.value.completedChapterCount)
    }

    private fun controller(store: MemoryStore) = CompetitionMahjongLearningController(store, Dispatchers.Unconfined)

    private suspend fun solve(learning: CompetitionMahjongLearningController) {
        when (val task = learning.state.value.card.task) {
            MahjongLessonTask.Reading -> learning.acknowledgeReading()
            is MahjongLessonTask.Choice -> { learning.selectOption(task.correctIndex); learning.submit() }
            is MahjongLessonTask.Tiles -> {
                task.acceptedSelections.first().forEach { learning.toggleTile(it) }
                learning.submit()
            }
        }
        assertTrue(learning.state.value.solved)
    }

    private suspend fun prepareChapterEnd(learning: CompetitionMahjongLearningController) {
        while (learning.state.value.cardIndex < learning.state.value.chapter.cards.lastIndex) {
            solve(learning)
            learning.advance()
        }
        solve(learning)
    }

    private suspend fun completeChapter(learning: CompetitionMahjongLearningController) {
        prepareChapterEnd(learning)
        learning.advance()
    }

    private class MemoryStore(var saved: CompetitionMahjongLearningProof? = null) : CompetitionMahjongLearningProofStore {
        var failLoad = false
        var incompatibleLoad = false
        var failSave = false
        var loseAcknowledgement = false
        var returnWrongProof = false
        var loadEntered: CompletableDeferred<Unit>? = null
        var releaseLoad: CompletableDeferred<Unit>? = null
        var saveEntered: CompletableDeferred<Unit>? = null
        var releaseSave: CompletableDeferred<Unit>? = null
        val saves = mutableListOf<CompetitionMahjongLearningProof>()
        val attempts = mutableListOf<CompetitionMahjongLearningProofRequest>()
        override suspend fun load(): CompetitionMahjongLearningProofRead {
            if (failLoad) throw IOException("injected load failure")
            loadEntered?.complete(Unit)
            releaseLoad?.await()
            return if (incompatibleLoad) CompetitionMahjongLearningProofRead.Incompatible
                else CompetitionMahjongLearningProofRead.Ready(saved ?: CompetitionMahjongLearningProof())
        }
        override suspend fun confirm(request: CompetitionMahjongLearningProofRequest): CompetitionMahjongLearningProof {
            attempts += request
            saveEntered?.complete(Unit)
            releaseSave?.await()
            if (failSave) throw IOException("injected save failure")
            val before = saved ?: CompetitionMahjongLearningProof()
            val after = before.confirm(request)
            if (after !== before) saves += after
            saved = after
            if (loseAcknowledgement) {
                loseAcknowledgement = false
                throw IOException("injected acknowledgement loss")
            }
            return if (returnWrongProof) CompetitionMahjongLearningProof() else after
        }
    }

    private fun proof(count: Int): CompetitionMahjongLearningProof =
        (0 until count).fold(CompetitionMahjongLearningProof()) { saved, index ->
            val chapter = CompetitionMahjongLearningLibrary.chapters[index]
            saved.confirm(CompetitionMahjongLearningProofRequest(
                "00000000-0000-0000-0000-${(index + 1).toString().padStart(12, '0')}",
                chapter.id, chapter.cards.map { it.id }, index.toLong()))
        }
}
