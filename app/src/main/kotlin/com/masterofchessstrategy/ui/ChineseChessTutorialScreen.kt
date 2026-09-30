package com.masterofchessstrategy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.R
import com.masterofchessstrategy.data.TutorialProgress
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessBoard
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.game.ChineseChessGameUiState
import com.masterofchessstrategy.tutorial.ChineseChessTutorialUiState
import com.masterofchessstrategy.tutorial.ChineseChessTutorialViewModel
import com.masterofchessstrategy.tutorial.StalemateAnswer
import com.masterofchessstrategy.tutorial.TutorialFeedback
import com.masterofchessstrategy.tutorial.TutorialStage

internal const val TUTORIAL_SCREEN_TAG = "tutorial_screen"
internal const val TUTORIAL_CONTINUE_TAG = "tutorial_continue"
internal const val TUTORIAL_CORRECT_ANSWER_TAG = "tutorial_correct_answer"
internal const val TUTORIAL_ENDGAME_TAG = "tutorial_endgame"
internal const val TUTORIAL_HORSE_DIAGRAM_TAG = "tutorial_horse_diagram"
internal const val TUTORIAL_CANNON_DIAGRAM_TAG = "tutorial_cannon_diagram"

internal enum class TutorialMoveDiagram { HORSE, CANNON }

@Composable
internal fun ChineseChessTutorialScreen(
    state: ChineseChessTutorialUiState,
    onBack: () -> Unit,
    onContinueReading: () -> Unit,
    onPracticeSquareTap: (BoardPosition) -> Unit,
    onCompletePractice: () -> Unit,
    onAnswerQuiz: (StalemateAnswer) -> Unit,
    onCompleteQuiz: () -> Unit,
    modifier: Modifier = Modifier,
    practiceTitle: String? = null,
    onOpenEndgamePractice: () -> Unit = {},
) {
    BackHandler(enabled = state.isSaving) {
        // Keep the destination alive until a tutorial checkpoint is committed.
    }
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(TUTORIAL_SCREEN_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PageHeader(
                title = stringResource(R.string.tutorial_title),
                subtitle = stringResource(R.string.tutorial_subtitle),
                onBack = {
                    if (!state.isSaving) onBack()
                },
            )
            LinearProgressIndicator(
                progress = {
                    state.progress.completedStepCount.toFloat() /
                        TutorialProgress.TOTAL_STEP_COUNT
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(
                    R.string.tutorial_progress,
                    state.progress.completedStepCount,
                    TutorialProgress.TOTAL_STEP_COUNT,
                ),
                style = MaterialTheme.typography.labelLarge,
            )
            if (state.isLoading) {
                Text(stringResource(R.string.tutorial_loading))
            } else {
                when (state.stage) {
                    TutorialStage.RULES -> RulesLesson(
                        enabled = state.isInteractionEnabled,
                        onContinue = onContinueReading,
                    )

                    TutorialStage.PIECES -> PiecesLesson(
                        enabled = state.isInteractionEnabled,
                        onContinue = onContinueReading,
                    )

                    TutorialStage.PRACTICE -> PracticeLesson(
                        state = state,
                        onSquareTap = onPracticeSquareTap,
                        onComplete = onCompletePractice,
                    )

                    TutorialStage.QUIZ -> QuizLesson(
                        state = state,
                        onAnswer = onAnswerQuiz,
                        onComplete = onCompleteQuiz,
                    )

                    TutorialStage.COMPLETE -> CompleteLesson(
                        practiceTitle = practiceTitle,
                        enabled = state.isInteractionEnabled && practiceTitle != null,
                        onOpenPractice = onOpenEndgamePractice,
                    )
                }
            }
            TutorialStatus(state)
        }
    }
}

@Composable
private fun RulesLesson(
    enabled: Boolean,
    onContinue: () -> Unit,
) {
    LessonCard(
        title = stringResource(R.string.tutorial_rules_title),
        paragraphs = listOf(
            stringResource(R.string.tutorial_rules_goal),
            stringResource(R.string.tutorial_rules_turn),
            stringResource(R.string.tutorial_rules_loss),
        ),
    )
    Button(
        onClick = onContinue,
        enabled = enabled,
        modifier = Modifier.testTag(TUTORIAL_CONTINUE_TAG),
    ) {
        Text(stringResource(R.string.tutorial_next))
    }
}

@Composable
private fun PiecesLesson(
    enabled: Boolean,
    onContinue: () -> Unit,
) {
    var diagram by remember { mutableStateOf(TutorialMoveDiagram.HORSE) }
    LessonCard(
        title = stringResource(R.string.tutorial_pieces_title),
        paragraphs = listOf(
            stringResource(R.string.tutorial_pieces_line_one),
            stringResource(R.string.tutorial_pieces_line_two),
            stringResource(R.string.tutorial_pieces_line_three),
        ),
    )
    Text(
        text = stringResource(R.string.tutorial_piece_diagram_intro),
        style = MaterialTheme.typography.bodyLarge,
    )
    OutlinedButton(
        onClick = { diagram = TutorialMoveDiagram.HORSE },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(TUTORIAL_HORSE_DIAGRAM_TAG),
    ) { Text(stringResource(R.string.tutorial_horse_diagram_title)) }
    OutlinedButton(
        onClick = { diagram = TutorialMoveDiagram.CANNON },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(TUTORIAL_CANNON_DIAGRAM_TAG),
    ) { Text(stringResource(R.string.tutorial_cannon_diagram_title)) }
    ChineseChessBoard(
        state = tutorialMoveDiagramState(diagram),
        onSquareTap = {},
        modifier = Modifier.fillMaxWidth().height(300.dp),
    )
    Text(
        text = stringResource(
            if (diagram == TutorialMoveDiagram.HORSE) {
                R.string.tutorial_horse_diagram_explanation
            } else {
                R.string.tutorial_cannon_diagram_explanation
            },
        ),
        style = MaterialTheme.typography.bodyLarge,
    )
    Button(
        onClick = onContinue,
        enabled = enabled,
        modifier = Modifier.testTag(TUTORIAL_CONTINUE_TAG),
    ) {
        Text(stringResource(R.string.tutorial_start_practice))
    }
}

@Composable
private fun PracticeLesson(
    state: ChineseChessTutorialUiState,
    onSquareTap: (BoardPosition) -> Unit,
    onComplete: () -> Unit,
) {
    LessonCard(
        title = stringResource(R.string.tutorial_practice_title),
        paragraphs = listOf(stringResource(R.string.tutorial_practice_instruction)),
    )
    ChineseChessBoard(
        state = practiceBoardState(state),
        onSquareTap = onSquareTap,
        modifier = Modifier
            .fillMaxWidth()
            .requiredHeightIn(min = 360.dp, max = 560.dp),
    )
    Button(
        onClick = onComplete,
        enabled = state.isInteractionEnabled && state.isPracticeSolved,
        modifier = Modifier.testTag(TUTORIAL_CONTINUE_TAG),
    ) {
        Text(stringResource(R.string.tutorial_enter_quiz))
    }
}

@Composable
private fun QuizLesson(
    state: ChineseChessTutorialUiState,
    onAnswer: (StalemateAnswer) -> Unit,
    onComplete: () -> Unit,
) {
    LessonCard(
        title = stringResource(R.string.tutorial_quiz_title),
        paragraphs = listOf(stringResource(R.string.tutorial_quiz_question)),
    )
    QuizAnswerButton(
        label = stringResource(R.string.tutorial_quiz_lose),
        answer = StalemateAnswer.SIDE_TO_MOVE_LOSES,
        enabled = state.isInteractionEnabled,
        onAnswer = onAnswer,
        modifier = Modifier.testTag(TUTORIAL_CORRECT_ANSWER_TAG),
    )
    QuizAnswerButton(
        label = stringResource(R.string.tutorial_quiz_draw),
        answer = StalemateAnswer.DRAW,
        enabled = state.isInteractionEnabled,
        onAnswer = onAnswer,
    )
    QuizAnswerButton(
        label = stringResource(R.string.tutorial_quiz_skip),
        answer = StalemateAnswer.SKIP_TURN,
        enabled = state.isInteractionEnabled,
        onAnswer = onAnswer,
    )
    Button(
        onClick = onComplete,
        enabled = state.isInteractionEnabled && state.isQuizCorrect,
        modifier = Modifier.testTag(TUTORIAL_CONTINUE_TAG),
    ) {
        Text(stringResource(R.string.tutorial_finish))
    }
}

@Composable
private fun QuizAnswerButton(
    label: String,
    answer: StalemateAnswer,
    enabled: Boolean,
    onAnswer: (StalemateAnswer) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = { onAnswer(answer) },
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(label)
    }
}

@Composable
private fun CompleteLesson(
    practiceTitle: String?,
    enabled: Boolean,
    onOpenPractice: () -> Unit,
) {
    LessonCard(
        title = stringResource(R.string.tutorial_complete_title),
        paragraphs = listOf(
            stringResource(R.string.tutorial_complete_summary),
            stringResource(R.string.tutorial_complete_boundary),
        ),
    )
    Text(
        stringResource(R.string.tutorial_endgame_summary),
        style = MaterialTheme.typography.bodyLarge,
    )
    Button(
        onClick = onOpenPractice,
        enabled = enabled,
        modifier = Modifier.testTag(TUTORIAL_ENDGAME_TAG),
    ) {
        Text(
            if (practiceTitle != null) {
                stringResource(R.string.tutorial_endgame_open, practiceTitle)
            } else {
                stringResource(R.string.tutorial_endgame_unavailable)
            },
        )
    }
}

@Composable
private fun LessonCard(
    title: String,
    paragraphs: List<String>,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            paragraphs.forEach { paragraph ->
                Text(text = paragraph, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun TutorialStatus(state: ChineseChessTutorialUiState) {
    val status = when {
        state.isSaving -> R.string.tutorial_saving
        state.feedback == TutorialFeedback.SELECT_SOLDIER -> R.string.tutorial_select_soldier
        state.feedback == TutorialFeedback.TRY_FORWARD -> R.string.tutorial_try_forward
        state.feedback == TutorialFeedback.PRACTICE_SOLVED -> R.string.tutorial_practice_solved
        state.feedback == TutorialFeedback.QUIZ_CORRECT -> R.string.tutorial_quiz_correct
        state.feedback == TutorialFeedback.QUIZ_INCORRECT -> R.string.tutorial_quiz_incorrect
        state.feedback == TutorialFeedback.LOAD_RECOVERED -> R.string.tutorial_load_recovered
        state.feedback == TutorialFeedback.SAVE_FAILED -> R.string.tutorial_save_failed
        else -> null
    }
    status?.let {
        Text(
            text = stringResource(it),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun practiceBoardState(
    state: ChineseChessTutorialUiState,
): ChineseChessGameUiState {
    val board = MutableList<ChineseChessPiece?>(
        ChineseChessBoard.WIDTH * ChineseChessBoard.HEIGHT,
    ) { null }
    board[indexOf(BoardPosition(4, 0))] =
        ChineseChessPiece(ChineseChessPieceType.GENERAL, ChineseChessSide.BLACK)
    board[indexOf(BoardPosition(5, 9))] =
        ChineseChessPiece(ChineseChessPieceType.GENERAL, ChineseChessSide.RED)
    val soldierPosition = if (state.isPracticeSolved) {
        ChineseChessTutorialViewModel.PRACTICE_SOLDIER_TARGET
    } else {
        ChineseChessTutorialViewModel.PRACTICE_SOLDIER_START
    }
    board[indexOf(soldierPosition)] =
        ChineseChessPiece(ChineseChessPieceType.SOLDIER, ChineseChessSide.RED)
    return ChineseChessGameUiState(
        board = board,
        selectedPosition = state.selectedPracticePosition,
        legalDestinations = if (
            state.selectedPracticePosition ==
            ChineseChessTutorialViewModel.PRACTICE_SOLDIER_START
        ) {
            setOf(ChineseChessTutorialViewModel.PRACTICE_SOLDIER_TARGET)
        } else {
            emptySet()
        },
        isEngineAvailable = state.isInteractionEnabled,
    )
}

/** Static rule illustrations reuse the formal board renderer but accept no taps. */
internal fun tutorialMoveDiagramState(diagram: TutorialMoveDiagram): ChineseChessGameUiState {
    val board = MutableList<ChineseChessPiece?>(
        ChineseChessBoard.WIDTH * ChineseChessBoard.HEIGHT,
    ) { null }
    fun place(x: Int, y: Int, type: ChineseChessPieceType, side: ChineseChessSide) {
        board[indexOf(BoardPosition(x, y))] = ChineseChessPiece(type, side)
    }
    place(4, 0, ChineseChessPieceType.GENERAL, ChineseChessSide.BLACK)
    place(5, 9, ChineseChessPieceType.GENERAL, ChineseChessSide.RED)
    val selected: BoardPosition
    val destinations: Set<BoardPosition>
    when (diagram) {
        TutorialMoveDiagram.HORSE -> {
            place(4, 5, ChineseChessPieceType.HORSE, ChineseChessSide.RED)
            place(4, 4, ChineseChessPieceType.SOLDIER, ChineseChessSide.RED)
            selected = BoardPosition(4, 5)
            destinations = setOf(
                BoardPosition(2, 4), BoardPosition(2, 6),
                BoardPosition(6, 4), BoardPosition(6, 6),
                BoardPosition(3, 7), BoardPosition(5, 7),
            )
        }
        TutorialMoveDiagram.CANNON -> {
            place(1, 7, ChineseChessPieceType.CANNON, ChineseChessSide.RED)
            place(1, 5, ChineseChessPieceType.SOLDIER, ChineseChessSide.RED)
            place(1, 3, ChineseChessPieceType.CHARIOT, ChineseChessSide.BLACK)
            selected = BoardPosition(1, 7)
            destinations = setOf(BoardPosition(1, 3))
        }
    }
    return ChineseChessGameUiState(
        board = board,
        selectedPosition = selected,
        legalDestinations = destinations,
        isEngineAvailable = false,
    )
}

private fun indexOf(position: BoardPosition): Int =
    position.y * ChineseChessBoard.WIDTH + position.x
