package com.masterofchessstrategy.games.lanzhou_fangqi

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.RoomGameRecordRepository
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSide
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiStorageEpochGate
import com.masterofchessstrategy.games.lanzhou_fangqi.data.RoomFangqiRankedRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class FangqiRecordDetail(val round: FangqiRecordedRound, val settledAt: Long?)

@Composable
internal fun FangqiStoredRecordRoute(recordId: String, database: MocsDatabase, onBack: () -> Unit) {
    val gate = remember(database) { FangqiStorageEpochGate.forDatabase(database) }
    val owner = remember(gate, recordId) { gate.captureOwner() }
    val epoch by gate.epoch.collectAsState()
    val scope = rememberCoroutineScope()
    var retry by rememberSaveable(recordId) { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf(false) }
    var confirm by rememberSaveable(recordId) { mutableStateOf(false) }
    var deleteTime by rememberSaveable(recordId) { mutableStateOf<Long?>(null) }
    var deleteFailed by remember { mutableStateOf(false) }
    val back: () -> Unit = { if (!deleting) onBack() }
    BackHandler(onBack = back)
    Surface(Modifier.fillMaxSize().testTag("fangqi_record_detail")) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(back, enabled = !deleting) { Text("返回") }
            Text("兰州方棋复盘", style = MaterialTheme.typography.titleLarge)
            if (epoch != owner.epoch) {
                Text("本地数据已更换，请返回后重新打开棋谱")
            } else {
                val result = fangqiRecordRequest(recordId to retry) {
                    gate.access(owner) {
                        val record = RoomGameRecordRepository(database.gameRecordDao()).load(recordId)
                        record?.let {
                            val round = FangqiRecordedRound.open(it)
                            val settledAt = database.fangqiRankedDao().findSettlement(recordId)?.settledAtEpochMillis
                            FangqiRecordDetail(round, settledAt)
                        }
                    }
                }
                when {
                    result == null -> Text("正在读取棋谱…")
                    result.isFailure -> {
                        Text("棋谱无法读取，原记录已保留")
                        OutlinedButton({ retry++ }) { Text("重新读取") }
                    }
                    else -> {
                        val detail = result.getOrThrow()
                        if (detail == null) {
                            Text("这份棋谱已不存在")
                        } else {
                            FangqiRecordSteps(detail.round)
                            if (deleteFailed) Text("删除失败，请重试")
                            if (detail.settledAt != null) {
                                OutlinedButton({ confirm = true }, enabled = !deleting,
                                    modifier = Modifier.testTag("fangqi_record_delete")) { Text("删除棋谱") }
                                if (confirm) {
                                    AlertDialog(
                                        onDismissRequest = { confirm = false },
                                        title = { Text("删除这份棋谱？") },
                                        text = { Text("棋谱将从列表移除，首次结算和成长进度仍会保留。") },
                                        confirmButton = {
                                            OutlinedButton({
                                                confirm = false
                                                deleting = true
                                                deleteFailed = false
                                                val requestedAt = deleteTime ?: maxOf(System.currentTimeMillis(), detail.settledAt)
                                                    .also { deleteTime = it }
                                                scope.launch {
                                                    try {
                                                        RoomFangqiRankedRepository(database, gate, owner).deleteRecord(
                                                            recordId, requestedAt,
                                                        )
                                                        onBack()
                                                    } catch (cancelled: CancellationException) {
                                                        throw cancelled
                                                    } catch (_: Exception) {
                                                        deleteFailed = true
                                                    } finally {
                                                        deleting = false
                                                    }
                                                }
                                            }) { Text("删除") }
                                        },
                                        dismissButton = { OutlinedButton({ confirm = false }) { Text("取消") } },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FangqiRecordSteps(round: FangqiRecordedRound) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val current = step.coerceIn(0, round.stepCount)
    Text("$current / ${round.stepCount} 步", Modifier.testTag("fangqi_record_step"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ step = current - 1 }, enabled = current > 0) { Text("上一步") }
        OutlinedButton({ step = current + 1 }, enabled = current < round.stepCount) { Text("下一步") }
        OutlinedButton({ step = round.stepCount }, enabled = current < round.stepCount) { Text("终局") }
    }
    val state = fangqiRecordRequest(round to current) { round.frame(current) }
    when {
        state == null -> Text("正在恢复棋谱步骤…")
        state.isFailure -> Text("这一棋谱步骤无法恢复")
        else -> {
            val snapshot = state.getOrThrow()
            val side = if (snapshot.currentPlayer == FangqiSide.RED) "红方" else "黑方"
            Text(when (snapshot.result) {
                GameResult.FIRST_PLAYER_WIN -> "终局：红方获胜"
                GameResult.SECOND_PLAYER_WIN -> "终局：黑方获胜"
                else -> if (snapshot.pendingCaptures > 0) "$side 待吃 ${snapshot.pendingCaptures} 子" else "$side 行棋"
            })
            FangqiBoard(FangqiUiState(snapshot), onPoint = null, modifier = Modifier.size(304.dp))
        }
    }
}

@Composable
private fun <T> fangqiRecordRequest(request: Any, block: suspend () -> T): Result<T>? = key(request) {
    produceState<Result<T>?>(null) {
        value = try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        } catch (failure: LinkageError) {
            Result.failure(failure)
        }
    }.value
}
