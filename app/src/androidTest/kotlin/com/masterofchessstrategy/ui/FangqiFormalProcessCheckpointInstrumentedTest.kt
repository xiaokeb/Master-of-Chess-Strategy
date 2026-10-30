package com.masterofchessstrategy.ui

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.games.lanzhou_fangqi.*
import com.masterofchessstrategy.games.lanzhou_fangqi.data.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Host controls exact separate QA PID. This inspector never opens the default application DB. */
class FangqiFormalProcessCheckpointInstrumentedTest {
    @Test fun seedInspectOrCleanTheDedicatedFormalSource() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = checkNotNull(args.getString(FangqiFormalProcessCheckActivity.DATABASE_ARGUMENT))
        require(FangqiFormalProcessCheckActivity.isOwnedDatabase(name))
        val phase = checkNotNull(args.getString("phase"))
        require(phase in listOf("seed-ongoing", "seed-terminal", "after-kill", "after-resume", "after-second-resume", "cleanup"))
        if (phase == "cleanup") {
            assertTrue(context.deleteDatabase(name))
            val legacy = File(context.cacheDir, name.removeSuffix(".db") + "-legacy")
            if (legacy.exists()) { assertTrue(legacy.listFiles()!!.isEmpty()); assertTrue(legacy.delete()) }
            return@runBlocking
        }
        val seed = phase.startsWith("seed-")
        assertEquals("Only seed may create the exact owned DB", !seed, context.getDatabasePath(name).exists())
        val database = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()
        val id = name.removeSuffix(".db")
        try {
            val repository = RoomFangqiRankedRepository(database)
            if (seed) {
                for (key in FangqiLearningCatalog.keys) database.fangqiLearningProgressDao()
                    .insert(FangqiLearningProgressEntity(key, 1, 1))
                val initial = repository.create(FangqiRankedRoomFixtures.creation(id), null)
                if (phase == "seed-terminal") {
                    val terminal = FangqiRankedRoomFixtures.terminal(initial)
                    val parts = terminal.engineState.toString(Charsets.US_ASCII).split('|', limit = 6)
                    val bytes = (parts.take(4).joinToString("|") + "|49|" + parts[5].split(';').take(49).joinToString(";"))
                        .toByteArray(Charsets.US_ASCII)
                    repository.checkpoint(initial, FangqiStoredSession(initial.mode, initial.difficulty, bytes, id, 19, 49))
                    database.openHelper.writableDatabase.execSQL("CREATE TRIGGER qa_fq_process_settle BEFORE INSERT ON fangqi_ranked_settlements BEGIN SELECT RAISE(ABORT, 'owned process QA fault'); END")
                }
            }
            val verified = repository.load()
            val active = requireNotNull(verified.active)
            assertEquals(id, active.sessionId)
            assertEquals(1, verified.creations.size)
            val proof = database.withTransaction {
                val row = requireNotNull(database.activeGameDao().find(4))
                val creation = requireNotNull(database.fangqiRankedDao().findStart(id))
                val receipt = database.fangqiRankedDao().findSettlement(id)
                val outcome = database.matchOutcomeDao().find(id)
                val record = database.gameRecordDao().find(id)
                val replay = NativeFangqiRankedReplay().replay(FangqiRankedHistory(row.engineState))
                val hash = sha(row.engineState)
                args.getString("expected_state_sha")?.let { assertEquals(it, hash) }
                args.getString("expected_creation_sha")?.let { assertEquals(it, creation.creationSha256) }
                val kind = args.getString("kind")
                if (!seed && kind == "ongoing") {
                    assertTrue(row.acceptedMoveCount >= 2)
                    assertEquals(GameResult.ONGOING, replay.result)
                    assertNull(receipt); assertNull(outcome); assertNull(record)
                } else if (!seed && kind == "terminal") {
                    assertEquals(50, row.acceptedMoveCount)
                    assertEquals(GameResult.FIRST_PLAYER_WIN, replay.result)
                    if (phase == "after-kill") {
                        assertNull(receipt); assertNull(outcome); assertNull(record)
                    } else {
                        assertNotNull(receipt); assertNotNull(outcome); assertNotNull(record)
                        assertArrayEquals(row.engineState, record!!.engineState)
                        assertEquals(50, record.moveCount)
                        args.getString("expected_settled_at")?.let { assertEquals(it.toLong(), receipt!!.settledAtEpochMillis) }
                        assertEquals(1, verified.wins.winsByDifficulty[Difficulty.EASY])
                    }
                }
                JSONObject().put("database", name).put("phase", phase).put("session_id", id)
                    .put("actions", row.acceptedMoveCount).put("state_sha256", hash)
                    .put("updated_at", row.updatedAtEpochMillis).put("undo_used", row.undoUseCount).put("hint_used", row.hintUseCount)
                    .put("native_sha256", replay.canonicalHistory.sha256).put("result", replay.result.name)
                    .put("creation_sha256", creation.creationSha256).put("starts", verified.creations.size)
                    .put("settlements", verified.settlements.size).put("settled_at", receipt?.settledAtEpochMillis ?: -1)
                    .put("record_sha256", record?.let { sha(it.engineState) } ?: JSONObject.NULL)
                    .put("outcome_present", outcome != null).put("record_present", record != null)
            }
            if (phase == "after-kill" && args.getString("kind") == "terminal") {
                // The host has killed only the QA UI PID. Remove only this owned fault;
                // the recreated UI must settle the still-unsettled durable terminal itself.
                database.openHelper.writableDatabase.execSQL("DROP TRIGGER qa_fq_process_settle")
            }
            val directory = File(context.filesDir, "fangqi-formal-process-qa").apply { check(isDirectory || mkdir()) }
            File(directory, name.removeSuffix(".db") + "-$phase.json").writeText(proof.toString())
        } finally { database.close() }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
