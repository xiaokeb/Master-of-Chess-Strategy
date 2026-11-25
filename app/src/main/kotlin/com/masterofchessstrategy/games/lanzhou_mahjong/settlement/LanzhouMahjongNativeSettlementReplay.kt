package com.masterofchessstrategy.games.lanzhou_mahjong.settlement

import com.masterofchessstrategy.engine.LanzhouMahjongPublicView
import com.masterofchessstrategy.engine.LanzhouMahjongRuleSession
import com.masterofchessstrategy.engine.NativeLanzhouMahjongSession
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecord
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongSessionAdmission
import java.io.IOException

/** Reuses production admission and the existing JNI rules; contains no Mahjong rule engine. */
internal class LanzhouMahjongNativeSettlementReplay(
    private val factory: (LanzhouMahjongSessionRecord) -> LanzhouMahjongRuleSession = {
        NativeLanzhouMahjongSession(it.seed, it.dealerSeat, it.baseScore, it.viewerSeat)
    },
) : LanzhouMahjongSettlementReplay {
    override fun sessionId(record: LanzhouMahjongSessionRecord): String =
        LanzhouMahjongSessionAdmission.sessionId(record)

    override fun replay(record: LanzhouMahjongSessionRecord): LanzhouMahjongSettlementReplayResult {
        var initial: ByteArray? = null
        var view: LanzhouMahjongPublicView? = null
        val admission = LanzhouMahjongSessionAdmission { owned ->
            factory(owned).use { session ->
                if (session.viewerSeat != owned.viewerSeat) throw IOException("兰州麻将回放席位不一致")
                initial = session.serialize().copyOf()
                if (session.restore(owned.nativeState) != RestoreResult.Restored) {
                    throw IOException("兰州麻将结算历史无法重放")
                }
                val canonical = session.serialize()
                view = session.snapshot()
                canonical
            }
        }
        val admitted = admission.admit(record)
        return LanzhouMahjongSettlementReplayResult(
            checkNotNull(initial), admitted.nativeState, checkNotNull(view),
        )
    }
}
