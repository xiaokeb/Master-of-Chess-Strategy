package com.masterofchessstrategy.games.competition_mahjong.auto

import com.masterofchessstrategy.engine.CompetitionMahjongAutoConfig
import com.masterofchessstrategy.engine.CompetitionMahjongAutoSession
import com.masterofchessstrategy.engine.CompetitionMahjongPublicView
import com.masterofchessstrategy.engine.NativeCompetitionMahjongAutoSession
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchLedger
import java.io.IOException

/** Checks private records before a controller can publish their public frames. */
internal class CompetitionMahjongAutoAdmission(
    private val sessionFactory: (CompetitionMahjongAutoConfig) -> CompetitionMahjongAutoSession =
        ::NativeCompetitionMahjongAutoSession,
) {
    fun create(expected: CompetitionMahjongAutoConfig): CompetitionMahjongAutoSession {
        val active = sessionFactory(expected)
        val actual = active.config
        if (actual.seed != expected.seed || actual.dealerSeat != expected.dealerSeat ||
            actual.roundWind != expected.roundWind || actual.viewerSeat != expected.viewerSeat ||
            actual.decisionSeed != expected.decisionSeed || actual.botLevels != expected.botLevels) {
            active.close()
            error("自动会话工厂配置不一致")
        }
        return active
    }

    fun restore(active: CompetitionMahjongAutoSession, record: ByteArray) {
        CompetitionMahjongAutoRecord.eventCount(record, active.config)
        if (active.restore(record) != RestoreResult.Restored) throw IOException("自动演局记录无法合法恢复，原记录已保留")
    }

    fun requireBaseline(active: CompetitionMahjongAutoSession): CompetitionMahjongPublicView {
        val view = active.snapshot()
        require(view.publicFrameVersion == 2 && !view.isAutoTerminal() &&
            view.netScores.all { it == 0 } && view.seats.none { it.winDisabled })
        require(CompetitionMahjongAutoRecord.eventCount(active.serialize(), active.config) < CompetitionMahjongAutoRecord.MAX_EVENTS)
        return view
    }

    fun restoreBaseline(active: CompetitionMahjongAutoSession, record: ByteArray, specified: Boolean) {
        require(specified || record.contentEquals(active.serialize())) { "自动演局起点与种子生成的开局不一致" }
        restore(active, record)
        requireBaseline(active)
    }

    fun validateHistory(checkpoint: CompetitionMahjongAutoCheckpoint) {
        val hasOpening = checkpoint.openingRecord != null
        val series = checkpoint.config.seriesConfig(checkpoint.seriesIndex, hasOpening)
        for (record in checkpoint.completedHands) {
            val hand = record.result.handIndex
            create(checkpoint.config.handConfig(checkpoint.seriesIndex, hand, hasOpening)).use { active ->
                restoreBaseline(active, record.baseline, hand == 0 && hasOpening)
                restore(active, record.terminal)
                val terminal = active.snapshot()
                require(terminal.publicFrameVersion == 2 && terminal.isAutoTerminal() &&
                    record.result == CompetitionMahjongMatchLedger.captureTerminal(series, hand, terminal))
            }
        }
    }

    fun restoreCurrent(active: CompetitionMahjongAutoSession, checkpoint: CompetitionMahjongAutoCheckpoint): CompetitionMahjongPublicView {
        restoreBaseline(active, checkpoint.baseline, checkpoint.activeHandIndex == 0 && checkpoint.openingRecord != null)
        restore(active, checkpoint.engineRecord)
        return active.snapshot().also(checkpoint::validateFrame)
    }
}
