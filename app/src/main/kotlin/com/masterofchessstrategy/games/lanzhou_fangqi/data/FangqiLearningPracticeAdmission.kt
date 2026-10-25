package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSession
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSide
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.NativeLanzhouFangqiSession
import com.masterofchessstrategy.games.lanzhou_fangqi.LanzhouFangqiLearningContent
import java.io.IOException

internal fun interface FangqiLearningCompletionVerifier {
    /** Rejects invalid evidence. A UI snapshot or caller-supplied outcome is never sufficient. */
    fun verify(practiceId: String, completeHistory: ByteArray)
}

/** Binds the authored initial board and red turn before actual Native replays the full history. */
internal class FangqiLearningPracticeAdmission(
    private val createSession: (List<Int>, FangqiSide) -> FangqiSession =
        NativeLanzhouFangqiSession::fromPosition,
) : FangqiLearningCompletionVerifier {
    override fun verify(practiceId: String, completeHistory: ByteArray) {
        FangqiLearningCatalog.practiceKey(practiceId)
        if (completeHistory.isEmpty() || completeHistory.size > MAX_BYTES) {
            throw IOException("方棋练习完整历史超限或编码无效")
        }
        val bytes = completeHistory.copyOf()
        if (bytes.any { it.toInt() !in 32..126 }) {
            throw IOException("方棋练习完整历史超限或编码无效")
        }
        val parts = bytes.toString(Charsets.US_ASCII).split('|', limit = 6)
        val practice = LanzhouFangqiLearningContent.practices.single { it.id == practiceId }
        val seedBoard = practice.board.toList()
        val seed = seedBoard.joinToString("")
        val count = parts.getOrNull(4)?.toIntOrNull()
        if (parts.size != 6 || parts[0] != "MOCS-LFQ" || parts[1] != "1" || parts[2] != "1" ||
            parts[3] != seed || count == null || count !in 1..MAX_ACTIONS || count.toString() != parts[4]
        ) throw IOException("方棋练习历史与指定原始题目不匹配")
        createSession(seedBoard, FangqiSide.RED).use { session ->
            val expectedSeed = "MOCS-LFQ|1|1|$seed|0|".toByteArray(Charsets.US_ASCII)
            if (!session.serialize().contentEquals(expectedSeed) || !session.restore(bytes.copyOf()) ||
                !session.serialize().contentEquals(bytes)
            ) throw IOException("方棋练习完整历史未通过原生重放校验")
            val result = session.snapshot()
            if (result.result != GameResult.FIRST_PLAYER_WIN || result.pendingCaptures != 0) {
                throw IOException("方棋练习尚未达到红方获胜条件")
            }
        }
    }

    companion object {
        private const val MAX_BYTES = 1_048_576
        private const val MAX_ACTIONS = 16_384
    }
}
