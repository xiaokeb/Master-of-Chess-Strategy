package com.masterofchessstrategy.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.game.ChineseChessFeedback
import com.masterofchessstrategy.game.ChineseChessGameUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChineseChessBoardInteractionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun pinchPanAndStationaryTwoFingerTouchNeverBecomeMoves() {
        val taps = mutableListOf<BoardPosition>()
        composeRule.setContent {
            ChineseChessBoard(ChineseChessGameUiState(), taps::add, Modifier.size(350.dp))
        }
        val board = composeRule.onNodeWithTag(CHINESE_CHESS_BOARD_TAG)
        board.performTouchInput {
            val middle = center
            down(0, middle - Offset(60f, 0f)); down(1, middle + Offset(60f, 0f))
            moveTo(0, middle - Offset(110f, 0f)); moveTo(1, middle + Offset(110f, 0f))
            moveTo(0, middle + Offset(-90f, 25f)); moveTo(1, middle + Offset(130f, 25f))
            up(0); up(1)
        }
        assertTrue(taps.isEmpty())
        val node = board.fetchSemanticsNode()
        val viewport = node.config[BoardViewportKey]
        assertTrue(viewport.scale > 1.5f)
        val size = Size(node.boundsInRoot.width, node.boundsInRoot.height)
        val position = BoardPosition(4, 5)
        val target = viewport.screenPoint(calculateBoardGeometry(size.width, size.height).center(position), size)
        board.performTouchInput { click(target) }
        assertEquals(listOf(position), taps)
        board.performTouchInput {
            down(0, center - Offset(30f, 0f)); down(1, center + Offset(30f, 0f))
            up(1); up(0)
        }
        assertEquals(1, taps.size)
        composeRule.onNodeWithTag(BOARD_RESET_VIEW_TAG).performClick()
        assertEquals(ChineseChessBoardViewport(), board.fetchSemanticsNode().config[BoardViewportKey])
        assertEquals(1, taps.size)
    }

    @Test
    fun readOnlyBoardStillSupportsAccessibleZoomWithoutTapping() {
        var taps = 0
        composeRule.setContent {
            ChineseChessBoard(ChineseChessGameUiState(isEngineAvailable = false), { taps++ }, Modifier.size(350.dp))
        }
        val board = composeRule.onNodeWithTag(CHINESE_CHESS_BOARD_TAG)
        val actions = board.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        composeRule.runOnIdle { assertTrue(actions.single { it.label == "放大棋盘" }.action()) }
        assertEquals(1.25f, board.fetchSemanticsNode().config[BoardViewportKey].scale, 0f)
        board.performTouchInput {
            click(calculateBoardGeometry(width.toFloat(), height.toFloat()).center(BoardPosition(4, 5)))
        }
        assertEquals(0, taps)
        composeRule.runOnIdle { assertTrue(actions.single { it.label == "重置视图" }.action()) }
        assertEquals(1f, board.fetchSemanticsNode().config[BoardViewportKey].scale, 0f)
    }

    @Test
    fun aTapStartedWhileLockedCannotFireAfterAiOrSaveFinishes() {
        val state = mutableStateOf(ChineseChessGameUiState(isAiThinking = true))
        var taps = 0
        composeRule.setContent { ChineseChessBoard(state.value, { taps++ }, Modifier.size(350.dp)) }
        val board = composeRule.onNodeWithTag(CHINESE_CHESS_BOARD_TAG)
        board.performTouchInput {
            down(calculateBoardGeometry(width.toFloat(), height.toFloat()).center(BoardPosition(4, 5)))
        }
        composeRule.runOnIdle { state.value = state.value.copy(isAiThinking = false) }
        board.performTouchInput { up() }
        assertEquals(0, taps)
        board.performTouchInput {
            click(calculateBoardGeometry(width.toFloat(), height.toFloat()).center(BoardPosition(4, 5)))
        }
        assertEquals(1, taps)
    }

    @Test
    fun singleFingerDragRemainsAvailableToTutorialPageScrolling() {
        lateinit var scroll: ScrollState
        var taps = 0
        composeRule.setContent {
            scroll = rememberScrollState()
            Box(Modifier.size(350.dp)) {
                Column(Modifier.verticalScroll(scroll)) {
                    ChineseChessBoard(ChineseChessGameUiState(), { taps++ }, Modifier.height(600.dp))
                }
            }
        }
        composeRule.onNodeWithTag(CHINESE_CHESS_BOARD_TAG).performTouchInput {
            down(Offset(100f, 250f))
            moveTo(Offset(100f, 220f)); moveTo(Offset(100f, 170f)); moveTo(Offset(100f, 80f))
            up()
        }
        composeRule.runOnIdle { assertTrue(scroll.value > 0); assertEquals(0, taps) }
    }

    @Test
    fun movePulseFinishesAndDoesNotReplayForSelectionRestorationOrBlindChess() {
        val piece = ChineseChessPiece(ChineseChessPieceType.SOLDIER, ChineseChessSide.RED)
        val initial = MutableList<ChineseChessPiece?>(90) { null }.apply { this[54] = piece }
        val state = mutableStateOf(ChineseChessGameUiState(board = initial))
        composeRule.setContent { ChineseChessBoard(state.value, {}, Modifier.size(350.dp)) }
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle {
            state.value = state.value.copy(board = initial.toMutableList().apply { this[54] = null; this[45] = piece })
        }
        composeRule.mainClock.advanceTimeBy(64)
        val pulse = composeRule.onNode(SemanticsMatcher.expectValue(BoardMovePulseKey, true), useUnmergedTree = true)
            .fetchSemanticsNode().config[BoardMovePulseProgressKey]
        assertTrue(pulse > 0f && pulse < 1f)
        composeRule.mainClock.advanceTimeBy(240)
        composeRule.onNode(SemanticsMatcher.expectValue(BoardMovePulseKey, false), useUnmergedTree = true).assertExists()
        composeRule.runOnIdle { state.value = state.value.copy(selectedPosition = BoardPosition(0, 5)) }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.onNode(SemanticsMatcher.expectValue(BoardMovePulseKey, true), useUnmergedTree = true).assertDoesNotExist()
        composeRule.runOnIdle {
            state.value = state.value.copy(board = initial, feedback = ChineseChessFeedback.GAME_RESTORED)
        }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.onNode(SemanticsMatcher.expectValue(BoardMovePulseKey, true), useUnmergedTree = true).assertDoesNotExist()
        composeRule.runOnIdle {
            state.value = state.value.copy(
                board = initial.toMutableList().apply { this[54] = null; this[45] = piece },
                isBlindChess = true, feedback = null,
            )
        }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.onNode(SemanticsMatcher.expectValue(BoardMovePulseKey, true), useUnmergedTree = true).assertDoesNotExist()
    }
}
