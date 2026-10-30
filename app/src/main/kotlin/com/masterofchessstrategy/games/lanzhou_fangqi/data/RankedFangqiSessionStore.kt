package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.games.lanzhou_fangqi.*
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Serialized by the owning autosave queue. This store can resume a source, never create one. */
internal class RankedFangqiSessionStore internal constructor(
    private val expectedSessionId: String,
    private val read: suspend () -> FangqiRankedVerifiedSnapshot,
    private val checkpoint: suspend (FangqiStoredSession, FangqiStoredSession) -> FangqiStoredSession,
    private val settle: suspend (FangqiStoredSession, Long) -> FangqiRankedTerminalReceipt,
    private val replay: FangqiRankedNativeReplay,
    private val now: () -> Long,
) : FangqiSessionStore {
    constructor(repository: RoomFangqiRankedRepository, expectedSessionId: String,
        now: () -> Long = System::currentTimeMillis,
    ) : this(expectedSessionId, repository::load, repository::checkpoint, repository::firstTerminal,
        NativeFangqiRankedReplay(), now)

    private class Pending(val snapshot: FangqiStoredSession, val settledAt: Long)
    private var acknowledged: FangqiStoredSession? = null
    private var pending: Pending? = null

    override suspend fun load(): FangqiStoredSession = withContext(Dispatchers.IO) {
        val verified = read()
        val active = boundActive(verified)
        if (pending != null && !same(pending!!.snapshot, active)) incompatible("正式方棋待确认局面已改变")
        // A process may stop between durable terminal checkpoint and its first settlement.
        if (verified.settlements[expectedSessionId] == null &&
            replay.replay(FangqiRankedHistory(active.engineState)).result != GameResult.ONGOING) {
            val request = request(active)
            settle(active, request.settledAt)
        }
        acknowledged = active
        pending = null
        active
    }

    override suspend fun save(snapshot: FangqiStoredSession) = withContext(Dispatchers.IO) {
        val before = acknowledged ?: incompatible("请先读取已创建的正式方棋对局")
        if (snapshot.sessionId != expectedSessionId) incompatible("不能替换正式方棋的会话身份")
        val request = request(snapshot)
        val verified = read()
        boundActive(verified)
        if (verified.settlements[expectedSessionId] != null) {
            // The previous settlement may have committed before its acknowledgement was lost.
            // Its exact retry also preserves a record-deletion tombstone.
            settle(snapshot, request.settledAt)
        } else {
            val actual = replay.replay(FangqiRankedHistory(snapshot.engineState))
            checkpoint(before, snapshot)
            if (actual.result != GameResult.ONGOING) settle(snapshot, request.settledAt)
        }
        acknowledged = snapshot
        pending = null
    }

    private fun request(snapshot: FangqiStoredSession): Pending {
        pending?.let {
            if (!same(it.snapshot, snapshot)) incompatible("正式方棋保存尚未确认，请重试同一局面")
            return it
        }
        return Pending(snapshot, maxOf(now(), snapshot.updatedAtEpochMillis)).also { pending = it }
    }

    private fun boundActive(snapshot: FangqiRankedVerifiedSnapshot): FangqiStoredSession {
        val active = snapshot.active ?: incompatible("指定的正式方棋对局已不存在")
        if (active.sessionId != expectedSessionId || expectedSessionId !in snapshot.creations ||
            active.mode != FangqiStoredMode.HUMAN_VS_AI) incompatible("指定活动局不是此正式方棋来源")
        return active
    }

    private fun same(a: FangqiStoredSession, b: FangqiStoredSession) = a.sessionId == b.sessionId &&
        a.mode == b.mode && a.difficulty == b.difficulty && a.updatedAtEpochMillis == b.updatedAtEpochMillis &&
        a.acceptedActionCount == b.acceptedActionCount && a.undoUseCount == b.undoUseCount &&
        a.hintUseCount == b.hintUseCount && a.engineState.contentEquals(b.engineState)
    private fun incompatible(message: String): Nothing = throw IOException(message)
}
