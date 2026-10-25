package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.engine.games.lanzhou_fangqi.NativeLanzhouFangqiSession
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiStoredSession
import java.io.IOException

/** Native replays the entire history, including intermediate capture and repetition states. */
internal class FangqiSessionAdmission(
    private val canonicalize: (ByteArray) -> ByteArray = { bytes ->
        NativeLanzhouFangqiSession().use { session ->
            if (!session.restore(bytes)) throw IOException("方棋完整历史校验失败，原存档已保留")
            session.serialize()
        }
    },
) {
    fun admit(snapshot: FangqiStoredSession): FangqiStoredSession {
        val bytes = snapshot.engineState
        inspectHistory(bytes)
        val canonical = canonicalize(bytes.copyOf())
        if (!bytes.contentEquals(canonical)) throw IOException("方棋存档不是规范完整历史，原存档已保留")
        return copyOwned(snapshot)
    }

    companion object {
        const val MAX_ACTIONS = 16_384

        /** This only bounds the envelope. Legal play and history are exclusively checked by Native. */
        internal fun inspectHistory(bytes: ByteArray): Int {
            if (bytes.isEmpty() || bytes.size > FangqiStoredSession.MAX_ENGINE_BYTES ||
                bytes.any { it.toInt() !in 32..126 }
            ) throw IOException("方棋存档超限或编码无效")
            val parts = bytes.toString(Charsets.US_ASCII).split('|', limit = 6)
            if (parts.size != 6 || parts[0] != "MOCS-LFQ" || parts[1] != "1" ||
                parts[2] !in listOf("1", "2") || parts[3].length != 49 ||
                parts[3].any { it !in '0'..'2' }
            ) throw IOException("不支持的方棋完整历史格式")
            val count = parts[4].toIntOrNull()
            if (count == null || count !in 0..MAX_ACTIONS || count.toString() != parts[4]) {
                throw IOException("方棋历史动作数无效")
            }
            val actions = parts[5]
            val actualCount = if (actions.isEmpty()) 0 else actions.count { it == ';' } + 1
            if (actualCount != count || actions.startsWith(';') || actions.endsWith(';') || ";;" in actions) {
                throw IOException("方棋历史动作数不匹配")
            }
            return count
        }

        internal fun copyOwned(snapshot: FangqiStoredSession) = FangqiStoredSession(
            mode = snapshot.mode,
            difficulty = snapshot.difficulty,
            engineState = snapshot.engineState,
            sessionId = snapshot.sessionId,
            updatedAtEpochMillis = snapshot.updatedAtEpochMillis,
            acceptedActionCount = snapshot.acceptedActionCount,
            undoUseCount = snapshot.undoUseCount,
            hintUseCount = snapshot.hintUseCount,
        )
    }
}
