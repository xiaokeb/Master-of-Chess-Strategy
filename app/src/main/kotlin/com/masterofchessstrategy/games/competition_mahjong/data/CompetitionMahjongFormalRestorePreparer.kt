package com.masterofchessstrategy.games.competition_mahjong.data

import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningBackupAdapter
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningProofCodec
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongUnlockProgress

/**
 * Strict formal-container preparation, before any Room transaction or clearing. The coordinator
 * still owns container-version validation, legacy-source compatibility and production registration.
 */
internal object CompetitionMahjongFormalRestorePreparer {
    fun prepare(
        records: List<GameBackupRecord>,
        strategyBindings: Map<Difficulty, Int>,
    ): CompetitionMahjongFormalRestorePlan {
        val candidateRecords = records.toList()
        val learningRecords = candidateRecords.filter { it.kind == CompetitionMahjongLearningBackupAdapter.KIND }
        require(learningRecords.size <= 1) { "国标麻将独立学习证明重复，未准备正式恢复" }
        val tutorialCompleted = learningRecords.singleOrNull()?.let { record ->
            val bytes = CompetitionMahjongLearningBackupAdapter().decode(record)
            checkNotNull(CompetitionMahjongLearningProofCodec.decode(bytes)).isCompleted
        } ?: false
        // No trusted series-win source exists yet. Candidate MCTX statistics never grant permission.
        val policy = CompetitionMahjongPlayPolicy.fromProgress(
            CompetitionMahjongUnlockProgress(tutorialCompleted, easyWins = 0, mediumWins = 0, hardWins = 0),
            strategyBindings.toMap(),
        )
        // One whole-container call preserves legacy-kind rejection and admits all four seats eagerly.
        return CompetitionMahjongFormalBackupBundle.prepareReplacement(candidateRecords) { policy }
    }
}
