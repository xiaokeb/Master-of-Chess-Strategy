package com.masterofchessstrategy.games.competition_mahjong.match.storage

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.competition_mahjong.match.*
import com.masterofchessstrategy.games.competition_mahjong.match.policy.*
import kotlinx.coroutines.sync.Mutex
import java.io.IOException

internal fun formalCheckpoint(id: Int = 1, count: Int = 16, viewer: Int = 0,
                              drawLast: Boolean = false): CompetitionMahjongMatchCheckpoint {
    val config = matchConfig(viewer).copy(matchId = "97000000-0000-4000-8000-${id.toString().padStart(12, '0')}")
    val ledger = if (count == 16 && drawLast)
        matchLedger(config, 15).recordTerminal(config, 15, matchFrame(config, 15, draw = true))
    else matchLedger(config, count)
    val active = minOf(count, 15)
    return CompetitionMahjongMatchCheckpoint(config, ledger, active,
        matchRecord(config.handConfig(active).viewerSeat, count == 16),
        CompetitionMahjongAssistanceUsage(Difficulty.HARD))
}

internal fun formalPolicy() = CompetitionMahjongPlayPolicy.fromProgress(
    CompetitionMahjongUnlockProgress(true, 10, 15, 20), Difficulty.entries.associateWith { it.code })

internal class FormalMemoryStorage : CompetitionMahjongFormalStorage {
    override val mutex = Mutex()
    var bytes: ByteArray? = null
    var writes = 0
    var failBeforeCommit = false
    var failAfterCommit = false
    override fun read(): ByteArray? = bytes?.copyOf()
    override fun replace(bytes: ByteArray) {
        writes++
        if (failBeforeCommit) throw IOException("before atomic commit")
        this.bytes = bytes.copyOf()
        if (failAfterCommit) throw IOException("acknowledgement after commit lost")
    }
}
