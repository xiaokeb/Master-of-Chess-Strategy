package com.masterofchessstrategy.games.go.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.AppSettings
import com.masterofchessstrategy.data.HighlightCondition
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.RoomAppSettingsRepository
import com.masterofchessstrategy.data.toGameRecord
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

    @Test fun standaloneAutomaticGamePublishesOneReplayBeforeRestartAndAwardsNoRankedGrowth() = runBlocking {
        val scope = this
        withOwned { database, bundle ->
            val config = GoAiMatchConfig(9, humanPlayer = null)
            val readyForScoring = NativeGoEngine(9).use { rules ->
                repeat(2) { assertEquals(ActionResult.Accepted, rules.apply(GoAction.Pass)) }
                GoAiSavedMatch("auto-single-replay", config, rules.serialize())
            }
            val repository = bundle.formalAiRepository()
            repository.save(readyForScoring)
            val sink = bundle.completedMatchSink()
            repeat(2) { openCount ->
                val session = GoAiMatchSession(scope, repository, config, noSearch, completedSink = sink,
                    completeAutomaticImmediately = true)
                try {
                    await(session) { it.game.canOperate }
                    if (openCount == 0) {
                        assertTrue(database.gameRecordDao().listAll().isEmpty())
                        confirmBoth(session)
                    }
                    val settled = await(session) { it.completionStored && it.game.canOperate }
                    assertEquals(readyForScoring.matchId, settled.matchId)
                    assertTrue(repository.load()!!.completionReserved)
                    val row = database.gameRecordDao().listAll().single()
                    assertNotNull(GoStoredRecordLoader(database).load(row.recordId).aiSource)
                    assertEquals(1, database.goAiProjectionDao().listAll().size)
                    assertTrue(database.matchOutcomeDao().listAll().isEmpty())
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun nativeComebackUsesLiveCaptureHistoryAndRespectsManualUnfavoriteOnRetry() = runBlocking {
        withOwned { database, bundle ->
            val config = GoAiMatchConfig(9, humanPlayer = null)
            val match = NativeGoEngine(9).use { rules ->
                fun place(x: Int, y: Int) {
                    assertEquals(ActionResult.Accepted, rules.apply(GoAction.Place(BoardPosition(x, y))))
                }
                for (column in 0..4) {
                    place(column, 0)
                    place(column, 1)
                }
                place(8, 8)
                place(5, 0) // White captures all five connected Black stones.
                assertEquals(GoStone.EMPTY, rules.stoneAt(BoardPosition(0, 0)))
                repeat(2) { assertEquals(ActionResult.Accepted, rules.apply(GoAction.Pass)) }
                for (point in listOf(BoardPosition(0, 1), BoardPosition(5, 0))) {
                    assertEquals(ActionResult.Accepted, rules.apply(GoAction.ToggleDeadGroup(point, GoPlayer.BLACK)))
                }
                val revision = rules.scoringState().proposalRevision
                for (player in GoPlayer.entries) {
                    assertEquals(ActionResult.Accepted, rules.apply(GoAction.ConfirmScore(player, revision)))
                }
                assertEquals(GameResult.FIRST_PLAYER_WIN, rules.gameResult())
                GoAiSavedMatch("native-auto-highlight", config, rules.serialize(), completionReserved = true)
            }
            val settings = RoomAppSettingsRepository(database.appSettingsDao())
            val comeback = HighlightCondition.COMEBACK.bit
            settings.save(AppSettings.DEFAULT.copy(highlightConditionsMask = comeback))
            bundle.aiRepository().save(match)
            val sink = bundle.completedMatchSink()
            sink.storeCompleted(match)
            val row = database.gameRecordDao().listAll().single()
            assertTrue(row.isFavorite)
            val record = checkNotNull(row.toGameRecord())
            assertFalse(GoAiHighlightPolicy.matches(record, 9, openingEventCount = 12, conditions = comeback))
            assertFalse(GoAiHighlightPolicy.matches(record.copy(result = GameResult.SECOND_PLAYER_WIN), 9, 0, comeback))
            assertEquals(1, database.gameRecordDao().setFavorite(row.recordId, false))
            sink.storeCompleted(match)
            assertFalse(database.gameRecordDao().listAll().single().isFavorite)
            assertTrue(database.matchOutcomeDao().listAll().isEmpty())
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
