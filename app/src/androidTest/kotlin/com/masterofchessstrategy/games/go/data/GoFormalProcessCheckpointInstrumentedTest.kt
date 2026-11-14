package com.masterofchessstrategy.games.go.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.GoAction
import com.masterofchessstrategy.engine.GoPlayer
import com.masterofchessstrategy.engine.NativeGoEngine
import com.masterofchessstrategy.games.go.GoAiMatchConfig
import com.masterofchessstrategy.games.go.GoAiMatchFileCodec
import com.masterofchessstrategy.games.go.GoAiSavedMatch
import com.masterofchessstrategy.games.go.GoEndgameProgress
import com.masterofchessstrategy.games.go.GoLearningLibrary
import com.masterofchessstrategy.games.go.GoLearningProgress
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Persists an owned source for the ADB process driver. The same method can run as a standalone test. */
class GoFormalProcessCheckpointInstrumentedTest {
    @Test fun seed() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val externalToken = args.getString("go_process_token")
        val token = externalToken ?: UUID.randomUUID().toString()
        val mode = args.getString("go_process_case") ?: "standard"
        require(UUID.fromString(token).toString() == token)
        require(mode in setOf("standard", "opening", "continuation", "long19", "resignation"))
        val name = "go-formal-process-qa-$token.db"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(!context.getDatabasePath(name).exists())
        val matchId = UUID.nameUUIDFromBytes("$token:$mode".toByteArray(Charsets.UTF_8)).toString()
        val db = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()
        try {
            val bundle = GoCompleteStorageBundle(db, GoLocalLegacySource { null },
                GoLearningLegacySource { GoLearningProgress() }, GoEndgameLegacySource { GoEndgameProgress() },
                GoAiLegacySource { null })
            val longGame = mode == "long19"
            val automatic = longGame || mode == "continuation"
            val ranked = mode == "standard" || mode == "resignation"
            val config = GoAiMatchConfig(if (longGame) 19 else 9,
                humanPlayer = if (automatic) null else GoPlayer.BLACK)
            val repository = bundle.formalAiRepository()
            val saved = NativeGoEngine(config.boardSize).use { native ->
                if (longGame) return@use GoAiSavedMatch(matchId, config, native.serialize(),
                    autoContinueEnabled = true, autoContinueMaxGames = 2, autoContinueGameNumber = 2)
                if (ranked) {
                    RoomGoLearningProgressRepository(db).save(GoLearningProgress(GoLearningLibrary.lessons.map { it.id }.toSet()))
                    bundle.rankedMatchCreator().create(GoAiSavedMatch(matchId, config, native.serialize()))
                }
                assertEquals(ActionResult.Accepted, native.apply(GoAction.Place(BoardPosition(0, 0))))
                assertEquals(ActionResult.Accepted, native.apply(GoAction.Place(BoardPosition(1, 0))))
                val opening = native.serialize().takeIf { !ranked }
                if (mode == "continuation") {
                    assertEquals(ActionResult.Accepted, native.apply(GoAction.Pass))
                    assertEquals(ActionResult.Accepted, native.apply(GoAction.Pass))
                    assertEquals(ActionResult.Accepted, native.apply(GoAction.ConfirmScore(
                        GoPlayer.BLACK, native.scoringState().proposalRevision)))
                } else if (mode == "opening") {
                    assertEquals(ActionResult.Accepted, native.apply(GoAction.Place(BoardPosition(2, 0))))
                    assertEquals(ActionResult.Accepted, native.apply(GoAction.Place(BoardPosition(3, 0))))
                } else if (mode == "resignation") {
                    assertEquals(ActionResult.Accepted, native.apply(GoAction.Resign(GoPlayer.BLACK)))
                }
                GoAiSavedMatch(matchId, config, native.serialize(), openingBytes = opening,
                    completionReserved = mode == "resignation",
                    autoContinueEnabled = automatic, autoContinueMaxGames = if (automatic) 3 else 1,
                    autoContinueGameNumber = if (automatic) 2 else 1)
            }
            repository.save(saved)
            assertArrayEquals(GoAiMatchFileCodec.encode(saved), GoAiMatchFileCodec.encode(checkNotNull(repository.load())))
            assertEquals(if (ranked) 1 else 0, db.goRankedDao().starts().size)
            if (mode == "resignation") {
                bundle.completedMatchSink().storeCompleted(saved)
                assertEquals(1, db.gameRecordDao().listAll().size)
            }
            println("GO_PROCESS_FIXTURE $name $matchId $mode")
        } finally {
            db.close()
            if (externalToken == null) check(context.deleteDatabase(name))
        }
        Unit
    }
}
