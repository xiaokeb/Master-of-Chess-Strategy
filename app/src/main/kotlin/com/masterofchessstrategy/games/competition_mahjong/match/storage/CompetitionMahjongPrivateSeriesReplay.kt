package com.masterofchessstrategy.games.competition_mahjong.match.storage

import com.masterofchessstrategy.engine.CompetitionMahjongAction
import com.masterofchessstrategy.engine.CompetitionMahjongPublicView
import com.masterofchessstrategy.engine.CompetitionMahjongRoundActionKind
import com.masterofchessstrategy.engine.CompetitionMahjongRoundPhase
import com.masterofchessstrategy.engine.NativeCompetitionMahjongSession
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongBotStrategy
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongCompetitionConfig
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongHandResult
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchLedger
import com.masterofchessstrategy.games.competition_mahjong.match.advancePositionSeededBot

/** Validates private MCMS v1 records for the configured series. Keep this off the UI thread. */
internal object CompetitionMahjongPrivateSeriesReplay {
    private const val SESSION_PAYLOAD_OFFSET = 12
    private const val SESSION_IDENTITY_BYTES = 8
    private const val ROUND_VERSION_OFFSET = SESSION_PAYLOAD_OFFSET + 4
    private const val ROUND_SETUP_BYTES = 7 + 144 // MOCM identity, dealer, wind and initial wall.
    private const val FIRST_ACTION_OFFSET = SESSION_PAYLOAD_OFFSET + ROUND_SETUP_BYTES + 4
    private const val CHECKSUM_BYTES = 8 // One CRC for each envelope.
    private const val RECORD_MIN_BYTES = FIRST_ACTION_OFFSET + CHECKSUM_BYTES
    const val MAX_HAND_BYTES = RECORD_MIN_BYTES + 4 * 4096 // Four bytes per Native journal action.
    val HAND_BYTES = RECORD_MIN_BYTES..MAX_HAND_BYTES

    fun archive(checkpoint: CompetitionMahjongMatchCheckpoint, prefix: List<ByteArray>): CompetitionMahjongArchivedSeries {
        require(checkpoint.ledger.isComplete && prefix.size == 15)
        val records = prefix + listOf(checkpoint.engineRecord)
        val proof = CompetitionMahjongArchivedSeries(checkpoint.config, records.mapIndexed { index, record ->
            require(record.size in HAND_BYTES && (record.size - RECORD_MIN_BYTES) % 4 == 0)
            val viewer = checkpoint.config.handConfig(index).viewerSeat
            (FIRST_ACTION_OFFSET until record.size - CHECKSUM_BYTES step 4).mapNotNull { offset ->
                if (record[offset + 1].toInt() != viewer) null else {
                    val kind = record[offset].toInt()
                    val tile = record[offset + 2].toInt()
                    val sequence = record[offset + 3].toInt()
                    require(kind in 0..8 && tile in 0..33 && sequence in 0..33)
                    (kind or (tile shl 4) or (sequence shl 10)).toShort()
                }
            }.toShortArray()
        })
        require(replayArchive(proof, records).results == checkpoint.ledger.results) { "跨场证据与结算不一致" }
        return proof
    }

    fun replayArchive(
        proof: CompetitionMahjongArchivedSeries,
        originalRecords: List<ByteArray>? = null,
    ): CompetitionMahjongMatchLedger = CompetitionMahjongMatchLedger.restored(List(16) { index ->
        val config = proof.config
        val hand = config.handConfig(index)
        val decisions = proof.decisions(index)
        NativeCompetitionMahjongSession(hand.seed, hand.dealerSeat, hand.roundWind, hand.viewerSeat).use { native ->
            var read = 0
            for (step in 0 until CompetitionMahjongArchivedSeries.MAX_ACTIONS) {
                if (native.advancePositionSeededBot(config.botLevel) == 1) continue
                val frame = native.snapshot()
                if (frame.isTerminal()) break
                require(read < decisions.size) { "跨场证据缺少玩家动作" }
                val bits = decisions[read++].toInt() and 0xffff
                val kind = CompetitionMahjongRoundActionKind.entries.first { it.code == (bits and 15) }
                require(native.apply(CompetitionMahjongAction(kind, (bits ushr 4) and 63, bits ushr 10)).accepted) {
                    "跨场证据玩家动作不合法"
                }
            }
            val terminal = native.snapshot()
            require(read == decisions.size && terminal.isTerminal()) { "跨场证据未完整重放" }
            if (originalRecords != null) require(native.serialize().contentEquals(originalRecords[index])) {
                "跨场证据无法重建原赛事日志"
            }
            CompetitionMahjongMatchLedger.captureTerminal(config, index, terminal)
        }
    })

    private fun CompetitionMahjongPublicView.isTerminal(): Boolean =
        phase == CompetitionMahjongRoundPhase.WON || phase == CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW

    fun verifyCheckpoint(checkpoint: CompetitionMahjongMatchCheckpoint, prefix: List<ByteArray>) {
        val records = if (checkpoint.ledger.completedHandCount > checkpoint.activeHandIndex)
            prefix + listOf(checkpoint.engineRecord) else prefix
        require(records.size == checkpoint.ledger.completedHandCount)
        records.forEachIndexed { index, record ->
            verifyResult(checkpoint, index, record)
        }
        if (checkpoint.ledger.completedHandCount == checkpoint.activeHandIndex &&
            checkpoint.config.botStrategy == CompetitionMahjongBotStrategy.POSITION_SEEDED_V1) {
            replayFrame(checkpoint.config, checkpoint.activeHandIndex, checkpoint.engineRecord)
        }
    }

    fun verifyResult(checkpoint: CompetitionMahjongMatchCheckpoint, handIndex: Int, record: ByteArray) {
        require(verifyHand(checkpoint.config, handIndex, record) == checkpoint.ledger.results[handIndex]) {
            "逐盘重放结果与赛事成绩不一致"
        }
    }

    fun verifySeries(
        config: CompetitionMahjongCompetitionConfig,
        terminalRecords: List<ByteArray>,
        expected: CompetitionMahjongMatchLedger,
    ): CompetitionMahjongMatchLedger {
        require(expected.isComplete && terminalRecords.size == expected.completedHandCount) { "赛事缺少完整逐盘证据" }
        val replayed = terminalRecords.mapIndexed { index, record -> verifyHand(config, index, record) }
        require(replayed == expected.results) { "逐盘重放结果与赛事成绩不一致" }
        return CompetitionMahjongMatchLedger.restored(replayed)
    }

    fun verifyHand(
        config: CompetitionMahjongCompetitionConfig,
        handIndex: Int,
        terminalRecord: ByteArray,
    ): CompetitionMahjongHandResult {
        val frame = replayFrame(config, handIndex, terminalRecord)
        require(frame.isTerminal()) { "逐盘证据尚未结算" }
        return CompetitionMahjongMatchLedger.captureTerminal(config, handIndex, frame)
    }

    private fun replayFrame(
        config: CompetitionMahjongCompetitionConfig,
        handIndex: Int,
        bytes: ByteArray,
    ): CompetitionMahjongPublicView {
        require(bytes.size in HAND_BYTES && (bytes.size - RECORD_MIN_BYTES) % 4 == 0) { "逐盘证据大小无效" }
        val record = bytes.copyOf()
        val hand = config.handConfig(handIndex)
        return NativeCompetitionMahjongSession(hand.seed, hand.dealerSeat, hand.roundWind, hand.viewerSeat).use { native ->
            val initial = native.serialize()
            // Native restore checks legality using the wall in the record. Compare the seeded setup first.
            require(initial.size == RECORD_MIN_BYTES && initial[4].toInt() == 1 &&
                initial[ROUND_VERSION_OFFSET].toInt() == 1 &&
                (0 until SESSION_IDENTITY_BYTES).all { record[it] == initial[it] } &&
                (SESSION_PAYLOAD_OFFSET until SESSION_PAYLOAD_OFFSET + ROUND_SETUP_BYTES).all { record[it] == initial[it] }) {
                "逐盘证据与赛事种子、座位或圈风不一致"
            }
            if (config.botStrategy == CompetitionMahjongBotStrategy.POSITION_SEEDED_V1) {
                replayPositionSeeded(native, config.botLevel, record)
            } else {
                require(native.restore(record) == RestoreResult.Restored) { "逐盘证据未通过 Native 重放" }
            }
            native.snapshot()
        }
    }

    private fun replayPositionSeeded(native: NativeCompetitionMahjongSession, level: Int, record: ByteArray) {
        for (offset in FIRST_ACTION_OFFSET until record.size - CHECKSUM_BYTES step 4) {
            val seat = record[offset + 1].toInt()
            val accepted = if (seat == native.viewerSeat) {
                val kind = CompetitionMahjongRoundActionKind.entries.firstOrNull { it.code == record[offset].toInt() }
                    ?: throw IllegalArgumentException("逐盘证据操作无效")
                native.apply(CompetitionMahjongAction(kind, record[offset + 2].toInt(), record[offset + 3].toInt())).accepted
            } else {
                native.advancePositionSeededBot(level) == 1
            }
            require(accepted) { "逐盘证据动作与 AI 策略不一致" }
        }
        // Canonical serialization compares every generated action, seat and checksum.
        require(native.serialize().contentEquals(record)) { "逐盘证据日志与 AI 策略不一致" }
    }
}
