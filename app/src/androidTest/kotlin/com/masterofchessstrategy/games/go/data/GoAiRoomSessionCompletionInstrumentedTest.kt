package com.masterofchessstrategy.games.go.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Real Room checkpoints, completion sink and Native rules; scripted search is not a strength test. */
class GoAiRoomSessionCompletionInstrumentedTest {
    @Test fun failedCompletionStaysReservedAndRestartAfterReopenCommitsExactlyOnce() = runBlocking {
        val scope = this
        withOwned { database, bundle ->
            val old = terminal("room-retry", GoAiMatchConfig(9))
            bundle.aiRepository().save(old)
            val broken = RoomGoAiCompletionRepository(database, beforeCommit = { throw IOException("owned sink failure") })
            val first = GoAiMatchSession(scope, bundle.aiRepository(), old.config, noSearch, completedSink = broken)
            try {
                await(first) { it.game.canOperate }
                assertTrue(first.restart())
                await(first) { it.game.canOperate && it.game.error != null }
                assertTrue(bundle.aiRepository().load()!!.completionReserved)
                assertFalse(first.undo())
                assertTrue(database.gameRecordDao().listAll().isEmpty())
                assertTrue(database.goAiProjectionDao().listAll().isEmpty())
            } finally { first.closeAndJoin() }
            val next = GoAiMatchSession(scope, bundle.aiRepository(), old.config, noSearch,
                completedSink = bundle.completedMatchSink())
            try {
                await(next) { it.game.canOperate }
                assertTrue(next.restart())
                await(next) { it.game.canOperate && it.matchId != old.matchId && it.game.phase == GoPhase.PLAYING }
                assertEquals(1, database.gameRecordDao().listAll().size)
                assertEquals(1, database.goAiProjectionDao().listAll().size)
                assertEquals(0, next.state.value.eventCount)
                assertTrue(database.matchOutcomeDao().listAll().isEmpty())
            } finally { next.closeAndJoin() }
        }
    }

    @Test fun specifiedOpeningReplacementArchivesOldWhiteGameBeforeSavingNewBoard() = runBlocking {
        val scope = this
        withOwned { database, bundle ->
            val old = terminal("room-opening", GoAiMatchConfig(9, humanPlayer = GoPlayer.WHITE))
            bundle.aiRepository().save(old)
            val opening = NativeGoEngine(13).use {
                assertEquals(ActionResult.Accepted, it.apply(GoAction.Place(BoardPosition(0, 0))))
                GoAiOpening.fromSnapshot(it.serialize())
            }
            val session = GoAiMatchSession(scope, bundle.aiRepository(), old.config, noSearch)
            try {
                await(session) { it.game.canOperate }
                assertTrue(session.replaceOpening(opening, bundle.completedMatchSink()))
                await(session) { it.game.canOperate && it.config.boardSize == 13 && it.matchId != old.matchId }
                val current = bundle.aiRepository().load()!!
                assertArrayEquals(opening.copyBytes(), current.copyOpeningBytes())
                assertEquals(GoPlayer.WHITE, current.config.humanPlayer)
                assertEquals(1, session.state.value.eventCount)
                val record = database.gameRecordDao().listAll().single()
                assertEquals(1, record.playerIndex)
                assertArrayEquals(old.copyBytes(), record.engineState)
                assertNotNull(GoStoredRecordLoader(database).load(record.recordId).aiSource)
                assertTrue(database.matchOutcomeDao().listAll().isEmpty())
            } finally { session.closeAndJoin() }
        }
    }

    @Test fun twoGameAutomaticSeriesUsesRealCompletionTransactionsWithoutCreatingThirdGame() = runBlocking {
        val scope = this
        withOwned { database, bundle ->
            val search = GoAiSearchEngine { GoAiSearchOutcome.Move(GoAiSuggestion(GoAction.Pass, 1, 0.0, 0.5, 0.0)) }
            val session = GoAiMatchSession(scope, bundle.aiRepository(), GoAiMatchConfig(9, humanPlayer = null), search,
                completedSink = bundle.completedMatchSink())
            try {
                await(session) { it.game.canOperate }
                val firstId = session.state.value.matchId
                assertTrue(session.configureAutoContinuation(true, 2))
                await(session) { it.game.canOperate && it.autoContinueEnabled }
                session.resume()
                await(session) { it.game.phase == GoPhase.SCORING && it.game.canOperate }
                confirmBoth(session)
                await(session) { it.autoContinueGameNumber == 2 && it.game.canOperate }
                val secondId = session.state.value.matchId
                assertNotEquals(firstId, secondId)
                assertEquals(1, database.gameRecordDao().listAll().size)
                await(session) { it.game.phase == GoPhase.SCORING && it.game.canOperate }
                confirmBoth(session)
                await(session) { it.game.canOperate && it.completionReserved && !it.autoContinuePending }
                assertEquals(secondId, bundle.aiRepository().load()!!.matchId)
                assertEquals(2, session.state.value.autoContinueGameNumber)
                assertEquals(2, session.state.value.autoContinueMaxGames)
                assertEquals(GoPhase.FINISHED, session.state.value.game.phase)
                assertFalse(session.continueAutomatically())
                assertEquals(2, database.gameRecordDao().listAll().size)
                assertEquals(2, database.goAiProjectionDao().listAll().size)
                assertTrue(database.matchOutcomeDao().listAll().isEmpty())
            } finally { session.closeAndJoin() }
        }
    }

    private suspend fun confirmBoth(session: GoAiMatchSession) {
        val revision = session.state.value.game.proposalRevision
        assertTrue(session.humanAction(GoAction.ConfirmScore(GoPlayer.BLACK, revision)))
        await(session) { it.game.blackConfirmed && it.game.canOperate }
        assertTrue(session.humanAction(GoAction.ConfirmScore(GoPlayer.WHITE, revision)))
    }

    private fun terminal(id: String, config: GoAiMatchConfig): GoAiSavedMatch = NativeGoEngine(config.boardSize).use {
        repeat(2) { _ -> assertEquals(ActionResult.Accepted, it.apply(GoAction.Pass)) }
        val revision = it.scoringState().proposalRevision
        GoPlayer.entries.forEach { player -> assertEquals(ActionResult.Accepted, it.apply(GoAction.ConfirmScore(player, revision))) }
        GoAiSavedMatch(id, config, it.serialize())
    }

    private val noSearch = GoAiSearchEngine { error("This transition must stay paused") }
    private suspend fun await(session: GoAiMatchSession, predicate: (GoAiMatchUiState) -> Boolean): GoAiMatchUiState =
        try { withTimeout(20_000) { session.state.first(predicate) } }
        catch (timeout: TimeoutCancellationException) {
            throw AssertionError("Session did not reach expected state: ${session.state.value}", timeout)
        }

    private suspend fun withOwned(block: suspend (MocsDatabase, GoCompleteStorageBundle) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "go-session-completion-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()
        val bundle = GoCompleteStorageBundle(database, GoLocalLegacySource { null },
            GoLearningLegacySource { GoLearningProgress() }, GoEndgameLegacySource { GoEndgameProgress() }, GoAiLegacySource { null })
        try { block(database, bundle) } finally { database.close(); check(context.deleteDatabase(name)) }
    }
}
