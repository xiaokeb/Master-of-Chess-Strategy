package com.masterofchessstrategy.games.lanzhou_fangqi

import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSnapshot
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.NativeLanzhouFangqiSession
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiSessionAdmission
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Verified MOCS-LFQ history. A capture is a step even when the side to move has not changed. */
internal class FangqiRecordedRound private constructor(
    private val root: String,
    private val actions: List<String>,
    private val terminal: FangqiSnapshot,
) {
    val stepCount: Int get() = actions.size

    suspend fun frame(step: Int): FangqiSnapshot = withContext(Dispatchers.Default) {
        require(step in 0..stepCount)
        if (step == stepCount) return@withContext terminal
        val prefix = "$root|$step|${actions.take(step).joinToString(";")}".toByteArray(Charsets.US_ASCII)
        restore(prefix)
    }

    companion object {
        suspend fun open(record: GameRecord): FangqiRecordedRound {
            require(record.gameType == GameType.LANZHOU_SQUARE_CHESS && record.engineFormatVersion == 1)
            val bytes = record.engineState.copyOf()
            return withContext(Dispatchers.Default) {
                val count = FangqiSessionAdmission.inspectHistory(bytes)
                val terminal = restore(bytes)
                if (count != record.moveCount || terminal.result != record.result || terminal.pendingCaptures != 0) {
                    throw IOException("方棋棋谱与正式结果不一致")
                }
                val fields = bytes.toString(Charsets.US_ASCII).split('|', limit = 6)
                val actions = if (count == 0) emptyList() else fields[5].split(';')
                FangqiRecordedRound(fields.take(4).joinToString("|"), actions, terminal)
            }
        }

        private fun restore(bytes: ByteArray): FangqiSnapshot = NativeLanzhouFangqiSession().use { session ->
            if (!session.restore(bytes) || !session.serialize().contentEquals(bytes)) {
                throw IOException("方棋棋谱无法按原生规则恢复")
            }
            session.snapshot()
        }
    }
}
