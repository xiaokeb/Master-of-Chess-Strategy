package com.masterofchessstrategy.games.lanzhou_fangqi

import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiRankedRoomFixtures
import com.masterofchessstrategy.games.lanzhou_mahjong.data.withOwnedLanzhouDatabase
import com.masterofchessstrategy.ui.HOME_RECORDS_TAG
import com.masterofchessstrategy.ui.MasterOfChessStrategyApp
import java.io.IOException
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule

class FangqiRecordedRoundInstrumentedTest {
    private val compose = createComposeRule()

    @get:Rule
    val uiRule = TestRule { base, description ->
        // Native replay tests can run without starting an activity on the device.
        if (description.methodName == "sharedRecordsFilterFavoritesReplayAndDeleteWhileKeepingTheSettlement") {
            compose.apply(base, description)
        } else {
            base
        }
    }

    @Test fun sharedRecordsFilterFavoritesReplayAndDeleteWhileKeepingTheSettlement() = withOwnedLanzhouDatabase { fixture ->
        val id = "fangqi/record+1"
        val (repository, initial) = FangqiRankedRoomFixtures.start(fixture.db, id)
        val terminal = FangqiRankedRoomFixtures.terminal(initial, redWin = false)
        repository.checkpoint(initial, terminal)
        val original = repository.firstTerminal(terminal, 30)
        compose.setContent { MasterOfChessStrategyApp(fixture.db) }
        compose.onNodeWithTag(HOME_RECORDS_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("record_game_4").performClick()
        awaitText("收藏")
        compose.onNodeWithText("收藏").performClick()
        awaitText("取消收藏")
        compose.onNodeWithText("回放").performClick()
        awaitText("红方 行棋")
        compose.onNodeWithText("下一步").performScrollTo().performClick()
        awaitText("1 / 53 步")
        compose.onNodeWithText("终局").performScrollTo().performClick()
        awaitText("终局：黑方获胜")
        capture()
        compose.onNodeWithTag("fangqi_record_delete").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("fangqi_record_delete").performScrollTo().performClick()
        compose.onNodeWithText("删除").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("fangqi_record_detail").fetchSemanticsNodes().isEmpty() }
        assertNull(fixture.db.gameRecordDao().find(id))
        assertEquals(original, repository.load().settlements.getValue(id).receipt)
    }

    private fun awaitText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun capture() {
        val token = InstrumentationRegistry.getArguments().getString("mocsVisualToken") ?: return
        require(token.matches(Regex("qa-[a-f0-9]{32}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(requireNotNull(context.getExternalFilesDir(null)), "mocs-visual/$token")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            File(folder, "fangqi-record.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun verifiedHistoryKeepsCaptureStepsAndOriginalResult() = runBlocking {
        val round = FangqiRecordedRound.open(record())
        assertEquals(53, round.stepCount)
        val initial = round.frame(0)
        assertEquals(28, initial.board.count { it != 0 })
        val captureStep = (1 until round.stepCount).first { round.frame(it).pendingCaptures > 0 }
        val before = round.frame(captureStep)
        val after = round.frame(captureStep + 1)
        assertEquals(before.board.count { it != 0 } - 1, after.board.count { it != 0 })
        assertEquals(GameResult.SECOND_PLAYER_WIN, round.frame(round.stepCount).result)
        assertEquals(initial, round.frame(0))
    }

    @Test fun declaredResultCannotOverrideNativeReplay() = runBlocking {
        val failure = runCatching { FangqiRecordedRound.open(record().copy(result = GameResult.FIRST_PLAYER_WIN)) }
        assertTrue(failure.exceptionOrNull() is IOException)
    }

    private fun record(): GameRecord {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bytes = assets.open("games/lanzhou_fangqi/ranked/standard-black-win.mocs-lfq").use { it.readBytes() }
        return GameRecord(
            "verified-replay-fixture", GameType.LANZHOU_SQUARE_CHESS, StoredGameMode.HUMAN_VS_AI, Difficulty.EASY,
            GameResult.SECOND_PLAYER_WIN, bytes, 53, completedAtEpochMillis = 1,
        )
    }
}
