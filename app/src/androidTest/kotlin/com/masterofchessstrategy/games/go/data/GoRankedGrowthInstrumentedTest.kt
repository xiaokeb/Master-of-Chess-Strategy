package com.masterofchessstrategy.games.go.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real Native and UUID disk Room; no model search, installation DB, app reset or injected wins. */
class GoRankedGrowthInstrumentedTest {
    @Test fun nineLessonsBlockCreationAndAllTenPermitEasyWithoutWins() = runBlocking {
        owned { f ->
            assertEquals(10, GoLearningLibrary.lessons.size)
            f.learning().save(GoLearningProgress(GoLearningLibrary.lessons.take(9).map { it.id }.toSet()))
            denied { f.creator().create(initial()) }
            assertNull(f.checkpoints().load())
            assertTrue(f.db.goRankedDao().starts().isEmpty())
            f.completeLessons()
            val start = f.creator().create(initial())
            assertNotNull(f.db.goRankedDao().start(start.recordId))
            assertTrue(f.db.matchOutcomeDao().listAll().isEmpty())
        }
    }

    @Test fun creationLateFailureAndCancellationRollBackCheckpointAndSourceTogether() = runBlocking {
        owned { f ->
            f.completeLessons()
            val candidate = initial()
            for (cancel in listOf(false, true)) {
                val creator = f.creator {
                    if (cancel) throw CancellationException("owned create edge") else throw IOException("owned create edge")
                }
                try { creator.create(candidate); fail("create edge hidden") }
                catch (error: Exception) { assertTrue(if (cancel) error is CancellationException else error is IOException) }
                assertNull(f.db.goAiCheckpointDao().findActive(GameType.GO.code))
                assertTrue(f.db.goRankedDao().starts().isEmpty())
                assertTrue(f.db.goAiCheckpointDao().listArchives().isEmpty())
            }
            f.creator().create(candidate)
            assertEquals(1, f.db.goRankedDao().starts().size)
        }
    }

    @Test fun actualCloseReopenRetainsCanonicalCreationAndActiveBytes() = runBlocking {
        owned { f ->
            f.completeLessons()
            val match = initial()
            val start = f.creator().create(match)
            f.reopen()
            val row = f.db.goRankedDao().start(start.recordId)!!
            assertEquals(1, row.formatVersion)
            assertEquals(100L, row.createdAtEpochMillis)
            assertArrayEquals(start.copyBytes(), row.initialCheckpoint)
            assertArrayEquals(start.copyBytes(), GoAiMatchFileCodec.encode(f.checkpoints().load()!!))
            assertTrue(f.db.goRankedDao().settlements().isEmpty())
        }
    }

    @Test fun reservedCompletionIsIdempotentFavoriteSafeAndRealWhiteWinCountsAfterReopen() = runBlocking {
        owned { f ->
            f.completeLessons()
            val match = initial(human = GoPlayer.WHITE)
            val start = f.creator().create(match)
            val done = f.persistFinished(match)
            val sink = RoomGoAiCompletionRepository(f.db)
            sink.storeCompleted(done)
            assertEquals(1, f.db.gameRecordDao().setFavorite(start.recordId, true))
            sink.storeCompleted(done)
            f.reopen()
            RoomGoAiCompletionRepository(f.db).storeCompleted(done)
            assertEquals(1, f.db.goRankedDao().starts().size)
            assertEquals(1, f.db.goRankedDao().settlements().size)
            assertEquals(1, f.db.goAiProjectionDao().listAll().size)
            assertEquals(1, f.db.matchOutcomeDao().listAll().size)
            val outcome = f.db.matchOutcomeDao().listAll().single().toMatchOutcome()!!
            assertEquals(start.recordId, outcome.matchId)
            assertEquals(GameResult.SECOND_PLAYER_WIN, outcome.result)
            assertEquals(1, outcome.playerIndex)
            assertEquals(200L, outcome.settledAtEpochMillis)
            assertTrue(outcome.isWin)
            assertTrue(f.db.gameRecordDao().listAll().single().isFavorite)
            val stats = f.statistics()
            assertEquals(1, stats.completedMatches)
            assertEquals(1, stats.totalWins)
            assertEquals(10, stats.score)
            assertEquals(1, stats.winsAt(GameType.GO, Difficulty.EASY))
        }
    }

    @Test fun lateCompletionFailureRollsBackAllDerivedRowsAndRetryKeepsRawSource() = runBlocking {
        owned { f -> f.assertCompletionRollback(cancel = false) }
    }

    @Test fun cancelledCompletionRollsBackAllDerivedRowsAndSameConnectionRetries() = runBlocking {
        owned { f -> f.assertCompletionRollback(cancel = true) }
    }

    @Test fun ordinaryOldGameAndBackfillNeverMintCreationOrGrowth() = runBlocking {
        owned { f ->
            val old = initial()
            f.checkpoints().save(old) // Deliberately bypass formal creation; not a trusted origin.
            val done = f.persistFinished(old)
            RoomGoAiCompletionRepository(f.db).projectExistingArchives()
            RoomGoAiCompletionRepository(f.db).storeCompleted(done)
            assertEquals(1, f.db.gameRecordDao().listAll().size)
            assertTrue(f.db.goRankedDao().starts().isEmpty())
            assertTrue(f.db.goRankedDao().settlements().isEmpty())
            assertTrue(f.db.matchOutcomeDao().listAll().isEmpty())
            assertEquals(0, f.statistics().completedMatches)
        }
    }

    @Test fun automaticParticipationStillSavesOrdinaryRecordWithoutGrowth() = runBlocking {
        owned { f ->
            f.completeLessons()
            val match = initial()
            f.creator().create(match)
            val mixed = GoAiSavedMatch(match.matchId, match.config, match.copyBytes(), automaticUsed = true)
            f.checkpoints().save(mixed)
            val done = f.persistFinished(mixed)
            RoomGoAiCompletionRepository(f.db).storeCompleted(done)
            assertEquals(1, f.db.gameRecordDao().listAll().size)
            assertEquals(1, f.db.goRankedDao().starts().size)
            assertTrue(f.db.goRankedDao().settlements().isEmpty())
            assertTrue(f.db.matchOutcomeDao().listAll().isEmpty())
            assertEquals(0, f.statistics().completedMatches)
        }
    }

    @Test fun backfillWithFormalCreationStillCannotIssueSettlement() = runBlocking {
        owned { f ->
            f.completeLessons()
            val match = initial(human = GoPlayer.WHITE)
            f.creator().create(match)
            f.persistFinished(match)
            assertEquals(1, RoomGoAiCompletionRepository(f.db).projectExistingArchives())
            assertEquals(1, f.db.gameRecordDao().listAll().size)
            assertEquals(1, f.db.goRankedDao().starts().size)
            assertTrue(f.db.goRankedDao().settlements().isEmpty())
            assertTrue(f.db.matchOutcomeDao().listAll().isEmpty())
        }
    }

    @Test fun rollbackClockKeepsCompletedRecordButDoesNotIssueSettlement() = runBlocking {
        owned { f ->
            f.completeLessons()
            val match = initial(human = GoPlayer.WHITE)
            RoomGoRankedMatchCreator(f.db, f.checkpoints(), f.learning(), clock = { 300L }).create(match)
            RoomGoAiCompletionRepository(f.db).storeCompleted(f.persistFinished(match)) // Raw clock is 200.
            assertEquals(1, f.db.gameRecordDao().listAll().size)
            assertEquals(200L, f.db.gameRecordDao().listAll().single().completedAtEpochMillis)
            assertEquals(1, f.db.goRankedDao().starts().size)
            assertTrue(f.db.goRankedDao().settlements().isEmpty())
            assertTrue(f.db.matchOutcomeDao().listAll().isEmpty())
        }
    }

    @Test fun oldGoLedgerDoesNotCountOrUnlockAndDatabaseAwareRecordCannotForgeNewGoWins() = runBlocking {
        owned { f ->
            f.completeLessons()
            for (level in Difficulty.entries) repeat(25) {
                val old = MatchOutcome(UUID.randomUUID().toString(), GameType.GO, StoredGameMode.HUMAN_VS_AI,
                    level, 1, GameResult.SECOND_PLAYER_WIN, 1)
                f.db.matchOutcomeDao().insert(old.toMatchOutcomeEntity())
            }
            assertEquals(0, f.statistics().completedMatches)
            for (level in listOf(GoAiDifficulty.MEDIUM, GoAiDifficulty.HARD, GoAiDifficulty.MASTER)) {
                denied { f.creator().create(initial(level = level)) }
            }
            val forged = MatchOutcome(UUID.randomUUID().toString(), GameType.GO, StoredGameMode.HUMAN_VS_AI,
                Difficulty.EASY, 1, GameResult.SECOND_PLAYER_WIN, 2)
            denied { RoomMatchStatisticsRepository(f.db).record(forged) }
            assertNull(f.db.matchOutcomeDao().find(forged.matchId))
            assertTrue(f.db.goRankedDao().starts().isEmpty())
        }
    }

    @Test fun staleCreatorCannotMintNewSourceAfterEpochChange() = runBlocking {
        owned { f ->
            f.completeLessons()
            val stale = f.creator()
            GoStorageEpochGate.forDatabase(f.db).backup(replacing = true) { }
            try { stale.create(initial()); fail("stale creator wrote") } catch (_: IOException) { }
            assertTrue(f.db.goRankedDao().starts().isEmpty())
            assertNull(f.db.goAiCheckpointDao().findActive(GameType.GO.code))
            f.creator().create(initial())
            assertEquals(1, f.db.goRankedDao().starts().size)
        }
    }

    @Test fun ongoingLegacyAndExistingIdentityCannotBeReplacedOrUpgraded() = runBlocking {
        owned { f ->
            f.completeLessons()
            val old = initial()
            f.checkpoints().save(old)
            val before = f.db.goAiCheckpointDao().findActive(GameType.GO.code)!!.checkpoint.copyOf()
            denied { f.creator().create(old) }
            denied { f.creator().create(initial()) }
            assertArrayEquals(before, f.db.goAiCheckpointDao().findActive(GameType.GO.code)!!.checkpoint)
            assertTrue(f.db.goRankedDao().starts().isEmpty())
        }
    }

    @Test fun oldTerminalMustBeReservedAndProjectedBeforeNextFormalStart() = runBlocking {
        owned { f ->
            f.completeLessons()
            val old = initial()
            f.checkpoints().save(old)
            val terminal = finished(old)
            f.checkpoints().save(terminal)
            denied { f.creator().create(initial()) }
            val reserved = reserved(terminal)
            f.checkpoints().save(reserved)
            denied { f.creator().create(initial()) }
            RoomGoAiCompletionRepository(f.db).storeCompleted(reserved)
            val next = initial()
            val start = f.creator().create(next)
            assertEquals(next.matchId, f.checkpoints().load()!!.matchId)
            assertEquals(1, f.db.goRankedDao().starts().size)
            assertNotNull(f.db.goRankedDao().start(start.recordId))
            assertTrue(f.db.goRankedDao().settlements().isEmpty())
        }
    }

    @Test fun trustedWinsUnlockExactlyTenEasyFifteenMediumAndTwentyHard() = runBlocking {
        owned { f ->
            f.completeLessons()
            val tiers = listOf(GoAiDifficulty.SIMPLE to (10 to GoAiDifficulty.MEDIUM),
                GoAiDifficulty.MEDIUM to (15 to GoAiDifficulty.HARD),
                GoAiDifficulty.HARD to (20 to GoAiDifficulty.MASTER))
            for ((level, boundary) in tiers) {
                val (required, next) = boundary
                repeat(required) { index ->
                    denied { f.creator().create(initial(level = next)) }
                    val game = initial(level = level, human = GoPlayer.WHITE)
                    f.creator().create(game)
                    RoomGoAiCompletionRepository(f.db).storeCompleted(f.persistFinished(game))
                    assertEquals(index + 1, f.statistics().winsAt(GameType.GO, Difficulty.entries[level.ordinal]))
                }
            }
            f.creator().create(initial(level = GoAiDifficulty.MASTER))
            assertEquals(45, f.db.goRankedDao().settlements().size)
            assertEquals(45, f.statistics().totalWins)
        }
    }

    private fun initial(level: GoAiDifficulty = GoAiDifficulty.SIMPLE, human: GoPlayer = GoPlayer.BLACK) =
        NativeGoEngine(9).use { GoAiSavedMatch(UUID.randomUUID().toString(), GoAiMatchConfig(9,
            humanPlayer = human, difficulty = level), it.serialize()) }

    private fun finished(match: GoAiSavedMatch): GoAiSavedMatch = NativeGoEngine(9).use { engine ->
        assertEquals(RestoreResult.Restored, engine.restore(match.copyBytes()))
        repeat(2) { assertEquals(ActionResult.Accepted, engine.apply(GoAction.Pass)) }
        val revision = engine.scoringState().proposalRevision
        assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.BLACK, revision)))
        assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.WHITE, revision)))
        assertEquals(GameResult.SECOND_PLAYER_WIN, engine.gameResult()) // Empty board, white komi.
        GoAiSavedMatch(match.matchId, match.config, engine.serialize(), match.undoUsed, match.hintUsed,
            automaticUsed = match.automaticUsed)
    }

    private fun reserved(match: GoAiSavedMatch) = GoAiSavedMatch(match.matchId, match.config, match.copyBytes(),
        match.undoUsed, match.hintUsed, true, match.rememberedHuman, match.automaticUsed,
        match.copyOpeningBytes(), match.autoContinueEnabled, match.autoContinueMaxGames, match.autoContinueGameNumber)

    private suspend fun denied(block: suspend () -> Unit) {
        try { block(); fail("unqualified operation accepted") } catch (_: IllegalArgumentException) { }
    }

    private inner class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val name = "go-ranked-growth-${UUID.randomUUID()}.db"
        var db: MocsDatabase = open()
            private set
        private fun open() = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()
        fun reopen() { db.close(); db = open() }
        fun close() { db.close(); check(context.deleteDatabase(name)) }
        fun checkpoints() = GoAiCheckpointRepository(RoomGoAiCheckpointStore(db), clock = { 200L })
        fun learning() = RoomGoLearningProgressRepository(db)
        fun creator(beforeCommit: suspend () -> Unit = {}) = RoomGoRankedMatchCreator(db, checkpoints(), learning(),
            clock = { 100L }, beforeCommit = beforeCommit)
        suspend fun completeLessons() = learning().save(GoLearningProgress(GoLearningLibrary.lessons.map { it.id }.toSet()))
        suspend fun statistics() = (RoomMatchStatisticsRepository(db).load() as LoadMatchStatisticsResult.Loaded).statistics
        suspend fun persistFinished(match: GoAiSavedMatch): GoAiSavedMatch {
            val terminal = finished(match)
            checkpoints().save(terminal)
            return GoAiSavedMatch(terminal.matchId, terminal.config, terminal.copyBytes(), terminal.undoUsed,
                terminal.hintUsed, true, terminal.rememberedHuman, terminal.automaticUsed).also { checkpoints().save(it) }
        }
        suspend fun assertCompletionRollback(cancel: Boolean) {
            completeLessons()
            val match = initial(human = GoPlayer.WHITE)
            val start = creator().create(match)
            val done = persistFinished(match)
            val raw = db.goAiCheckpointDao().listArchives().single().checkpoint.copyOf()
            val failing = RoomGoAiCompletionRepository(db, beforeCommit = {
                if (cancel) throw CancellationException("owned completion edge") else throw IOException("owned completion edge")
            })
            try { failing.storeCompleted(done); fail("completion edge hidden") }
            catch (error: Exception) { assertTrue(if (cancel) error is CancellationException else error is IOException) }
            assertTrue(db.gameRecordDao().listAll().isEmpty())
            assertTrue(db.goAiProjectionDao().listAll().isEmpty())
            assertTrue(db.matchOutcomeDao().listAll().isEmpty())
            assertTrue(db.goRankedDao().settlements().isEmpty())
            assertNotNull(db.goRankedDao().start(start.recordId))
            assertArrayEquals(raw, db.goAiCheckpointDao().listArchives().single().checkpoint)
            assertTrue(checkpoints().load()!!.completionReserved)
            RoomGoAiCompletionRepository(db).storeCompleted(done)
            assertEquals(1, db.goRankedDao().settlements().size)
            assertEquals(1, statistics().totalWins)
        }
    }

    private suspend fun owned(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }
}
