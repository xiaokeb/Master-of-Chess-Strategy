package com.masterofchessstrategy.games.xiangqi

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.engine.ChineseChessRuleRevision
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.game.ChineseChessGameUiState
import com.masterofchessstrategy.game.ChineseChessGameViewModel
import com.masterofchessstrategy.ui.ChineseChessGameScreen
import com.masterofchessstrategy.ui.theme.MocsAppWindow
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the formal controls; no application database or settings writes. */
@RunWith(AndroidJUnit4::class)
class XiangqiRuleRevisionScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun unknownMetadataDoesNotInventARevisionAndKnownProfilesHaveDistinctNotices() {
        var state by mutableStateOf(ChineseChessGameUiState())
        composeRule.setContent {
            MocsAppWindow {
                ChineseChessGameScreen(
                    state = state,
                    onSquareTap = {}, onUndo = {}, onHint = {},
                    onResign = {}, onRestart = {}, onBack = {},
                )
            }
        }
        composeRule.onNodeWithText(LEGACY_TITLE).assertDoesNotExist()
        composeRule.onNodeWithText(CURRENT_TITLE).assertDoesNotExist()
        composeRule.runOnIdle { state = state.copy(ruleRevision = ChineseChessRuleRevision.LEGACY_COMPATIBILITY) }
        composeRule.onNodeWithText(LEGACY_TITLE).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("本局沿用保存时的循环裁定，不追溯改判。新开对局使用连续杀证明修订。")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(CURRENT_TITLE).assertDoesNotExist()
        composeRule.runOnIdle { state = state.copy(ruleRevision = ChineseChessRuleRevision.CONTINUOUS_CHECKING_MATE) }
        composeRule.onNodeWithText(CURRENT_TITLE).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("循环分析纳入已证明的连续将军成杀；搜索未完成不作为新增判负依据。")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(LEGACY_TITLE).assertDoesNotExist()
    }

    @Test
    fun restartingRealLegacyEngineChangesTheFormalNoticeToCurrentRules() {
        NativeChineseChessEngine().use { engine ->
            val current = engine.serialize()
            val legacy = current.copyOf(current.size - 1).also { it[4] = 3 }
            val crc = CRC32().apply { update(legacy, 0, legacy.size - 4) }.value
            repeat(4) { byte -> legacy[legacy.size - 4 + byte] = (crc ushr (8 * byte)).toByte() }
            assertEquals(RestoreResult.Restored, engine.restore(legacy))
            lateinit var game: ChineseChessGameViewModel
            composeRule.runOnUiThread {
                game = ChineseChessGameViewModel(clockTickIntervalMillis = null, engineFactory = { engine })
            }
            composeRule.setContent {
                MocsAppWindow {
                    ChineseChessGameScreen(
                        state = game.uiState,
                        onSquareTap = game::onSquareTap,
                        onUndo = game::undo,
                        onHint = game::requestHint,
                        onResign = game::resign,
                        onRestart = { game.restartWithAiFirst(false) },
                        onBack = {},
                    )
                }
            }
            composeRule.onNodeWithText(LEGACY_TITLE).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("重开").performScrollTo().performClick()
            composeRule.runOnIdle {
                assertEquals(ChineseChessRuleRevision.CONTINUOUS_CHECKING_MATE, game.uiState.ruleRevision)
                assertEquals(GameResult.ONGOING, game.uiState.result)
                val fresh = engine.serialize()
                assertEquals(4, fresh[4].toInt())
                assertEquals(1, fresh[fresh.size - 5].toInt())
            }
            composeRule.onNodeWithText(CURRENT_TITLE).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText(LEGACY_TITLE).assertDoesNotExist()
        }
    }

    private companion object {
        const val LEGACY_TITLE = "旧局兼容裁定"
        const val CURRENT_TITLE = "连续杀证明修订"
    }
}
