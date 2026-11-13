package com.masterofchessstrategy.games.competition_mahjong.challenge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongStorageEpochGate
import com.masterofchessstrategy.progress.EndgameRewardTotals
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
internal fun CompetitionMahjongChallengeProductionRoute(database: MocsDatabase, writers: LocalDataWriterRegistry,
    onBack: () -> Unit) {
    val gate = remember(database) { CompetitionMahjongStorageEpochGate.forDatabase(database) }
    val epoch by gate.epoch.collectAsState()
    val repository = remember(database, epoch) { RoomCompetitionMahjongChallengeProgress(database, gate) }
    CompetitionMahjongChallengeRoute(onBack, repository = repository, ownerKey = "competition-mahjong-challenge-$epoch",
        onWriterReady = writers::register)
}

internal fun observeCompetitionChallengeRewards(database: MocsDatabase) = database.competitionMahjongChallengeProgressDao()
    .observePayload(CompetitionMahjongChallengeProgressCodec.MAX_BYTES)
    .map { bytes -> bytes?.let(CompetitionMahjongChallengeProgressCodec::decode)?.completedIds?.let(::competitionChallengeRewards)
        ?: EndgameRewardTotals() }
    .distinctUntilChanged()

internal fun competitionChallengeRewards(ids: Set<String>): EndgameRewardTotals {
    val completed = CompetitionMahjongChallengeLibrary.challenges.filter { it.id in ids }
    return EndgameRewardTotals(completed.size, completed.size, completed.sumOf { 10 shl it.level.ordinal })
}
