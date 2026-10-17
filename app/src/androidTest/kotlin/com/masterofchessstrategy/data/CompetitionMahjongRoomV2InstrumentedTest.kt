package com.masterofchessstrategy.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.competition_mahjong.data.CompetitionMahjongBackupAdapter
import com.masterofchessstrategy.games.competition_mahjong.match.*
import com.masterofchessstrategy.games.competition_mahjong.match.policy.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** Actual Native + production v2 store/backup, in this test's UUID disk DB only. */
class CompetitionMahjongRoomV2InstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val name = "mcmt-v2-qa-${UUID.randomUUID()}.db"
    private lateinit var database: MocsDatabase
    private val code = GameType.STANDARD_MAHJONG.code
    private val adapter = CompetitionMahjongBackupAdapter()
    private val registry = GameBackupRegistry(listOf(adapter))
    private val policy = CompetitionMahjongPlayPolicy.fromProgress(
        CompetitionMahjongUnlockProgress(true, 10, 15, 20), Difficulty.entries.associateWith { it.code })

    @Before fun prepare() { database = openDisk() }
    @After fun cleanup() { database.close(); assertTrue(context.deleteDatabase(name)) }

    @Test fun diskAndNativeBackupPreserveTheChargedPendingRequestExactly() = runBlocking {
        val source = pendingUndo().first
        store().save(source)
        val original = row().checkpoint.copyOf()
        database.close(); database = openDisk()
        assertEquals(source.assistance, store().load(0)!!.assistance)
        assertArrayEquals(original, row().checkpoint)
        assertTrue(validateCompetitionMahjongMatchArchive(CompetitionMahjongMatchArchive.fromEntity(row())))
        val bytes = backup().export(99)
        val target = Room.inMemoryDatabaseBuilder(context, MocsDatabase::class.java).build()
        try {
            assertEquals(1, backup(target).restore(bytes).competitionMatchCount)
            val restored = RoomCompetitionMahjongMatchStore(target.competitionMahjongMatchDao()).load(0)!!
            assertEquals(source.assistance, restored.assistance)
            assertArrayEquals(source.engineRecord, restored.engineRecord)
            assertArrayEquals(bytes, backup(target).export(99))
        } finally { target.close() }
    }

    @Test fun sqlReplacementFailureNeverCommitsOnlyTheAllowanceOrOnlyTheHand() = runBlocking {
        val old = fresh()
        val next = pendingUndo().first
        store().save(old)
        val original = row().checkpoint.copyOf()
        rejectMatchWrites()
        assertThrows(RuntimeException::class.java) { runBlocking { store().save(next) } }
        assertArrayEquals(original, row().checkpoint)
        assertEquals(old.assistance, store().load(0)!!.assistance)
        allowMatchWrites()
        store().save(next)
        database.close(); database = openDisk()
        assertEquals(next.assistance, store().load(0)!!.assistance)
        assertArrayEquals(next.engineRecord, store().load(0)!!.engineRecord)
    }

    @Test fun lateBackupFailureRollsBackPendingFlagsCountersAndTutorialTogether() = runBlocking {
        store().save(fresh())
        database.tutorialProgressDao().upsert(TutorialProgressEntity(code, 1, 1, false, 20))
        val original = backup().export(99)
        val candidate = pendingUndo().first
        val bytes = LocalDataBackupCodec.encode(LocalDataSnapshot(99,
            gameExtensions = listOf(adapter.encodeArchive(CompetitionMahjongMatchArchive(0, candidate.config.matchId, 42,
                CompetitionMahjongMatchCheckpointCodec.encode(candidate)))),
            tutorialProgress = listOf(TutorialProgress(GameType.STANDARD_MAHJONG, 1, 4, true, 99))), registry)
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_v2_tutorial BEFORE INSERT ON tutorial_progress " +
            "BEGIN SELECT RAISE(ABORT, 'injected v2 backup failure'); END")
        assertThrows(RuntimeException::class.java) { runBlocking { backup().restore(bytes) } }
        assertArrayEquals(original, backup().export(99))
    }

    private fun openDisk() = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()
    private fun store() = RoomCompetitionMahjongMatchStore(database.competitionMahjongMatchDao()) { 42 }
    private suspend fun row() = checkNotNull(database.competitionMahjongMatchDao().find(code))
    private fun backup(db: MocsDatabase = database) = RoomLocalDataBackupRepository(db,
        ChineseChessEndgamePackParser.loadBundled(context.assets), validateEngineState = { false }, gameBackups = registry)
    private fun config() = CompetitionMahjongCompetitionConfig(UUID.randomUUID().toString(), 925, 0, 2)

    private fun fresh(usage: CompetitionMahjongAssistanceUsage? = CompetitionMahjongAssistanceUsage(Difficulty.HARD)): CompetitionMahjongMatchCheckpoint {
        val config = config()
        return NativeCompetitionMahjongSession(925).use {
            CompetitionMahjongMatchCheckpoint(config, CompetitionMahjongMatchLedger.empty(), 0, it.serialize(), usage)
        }
    }

    /** The saved event is the local discard after an AI reply was already undone. */
    private fun pendingUndo(): Pair<CompetitionMahjongMatchCheckpoint, ByteArray> {
        val config = config()
        return NativeCompetitionMahjongSession(925).use {
            val target = it.serialize()
            assertTrue(it.apply(it.legalActions().first { action -> action.kind == CompetitionMahjongRoundActionKind.DISCARD }).accepted)
            assertEquals(1, it.advanceBots(2, 7, 1))
            assertTrue(it.undo())
            assertTrue(it.legalActions().isEmpty())
            CompetitionMahjongMatchCheckpoint(config, CompetitionMahjongMatchLedger.empty(), 0, it.serialize(),
                CompetitionMahjongAssistanceUsage(Difficulty.HARD, 1, 1, true)) to target
        }
    }

    private fun rejectMatchWrites() = database.openHelper.writableDatabase.execSQL(
        "CREATE TRIGGER reject_v2_match BEFORE INSERT ON competition_mahjong_matches " +
            "BEGIN SELECT RAISE(ABORT, 'injected v2 undo save failure'); END")
    private fun allowMatchWrites() = database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_v2_match")
}
