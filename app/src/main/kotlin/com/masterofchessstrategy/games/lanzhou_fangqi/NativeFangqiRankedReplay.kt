package com.masterofchessstrategy.games.lanzhou_fangqi

import android.os.Looper
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSession
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.NativeLanzhouFangqiSession
import java.io.IOException

/**
 * Synchronous native admission: the caller must dispatch the entire ledger operation off main.
 * Each call owns a fresh session; restore, canonical serialization and result share that session.
 * A legal authored history can replay here, but only FangqiRankedLedger admits a standard ranked root.
 */
internal class NativeFangqiRankedReplay internal constructor(
    private val createSession: () -> FangqiSession,
    private val requireBackgroundThread: () -> Unit,
) : FangqiRankedNativeReplay {
    /** Production always uses real JNI. Native/cleanup failures propagate; there is no fallback. */
    constructor() : this(
        { NativeLanzhouFangqiSession() },
        { check(Looper.myLooper() != Looper.getMainLooper()) { "方棋完整历史重放必须在后台执行" } },
    )

    override fun replay(history: FangqiRankedHistory): FangqiRankedReplayResult {
        requireBackgroundThread()
        val expected = history.bytes
        return createSession().use { session ->
            if (!session.restore(expected.copyOf())) throw IOException("方棋完整历史未通过真实原生重放")
            val canonical = FangqiRankedHistory(session.serialize())
            if (!canonical.bytes.contentEquals(expected)) throw IOException("方棋原生重放返回非规范完整历史")
            val snapshot = session.snapshot()
            FangqiRankedReplayResult(canonical, snapshot.result, snapshot.pendingCaptures)
        }
    }
}
