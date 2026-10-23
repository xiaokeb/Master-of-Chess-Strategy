package com.masterofchessstrategy.games.go.data

import android.content.Context
import com.masterofchessstrategy.data.GameBackupAdapter
import com.masterofchessstrategy.data.LocalDataBackupRepository
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.go.GoAiMatchRepository

/** One construction owns the Room AI source, paired adapters and four-source preparation.
 * No caller may switch just the AI factory while retaining the old disk-guard backup path.
 */
internal class GoCompleteStorageBundle(
    private val database: MocsDatabase,
    session: GoLocalLegacySource,
    learning: GoLearningLegacySource,
    endgame: GoEndgameLegacySource,
    ai: GoAiLegacySource,
) {
    constructor(database: MocsDatabase, context: Context) : this(database,
        GoLocalSessionDiskSource(context), GoLearningPreferencesDiskSource(context),
        GoEndgameDiskSource(context), GoAiLegacyDiskSource(context))

    val gate = GoStorageEpochGate.forDatabase(database)
    private val rankedLearning = RoomGoLearningProgressRepository(database, learning)
    private val checkpoints = GoAiCheckpointRepository(RoomGoAiCheckpointStore(database), ai)
    private val preparation = GoCompleteBackupPreparation(database, session, learning, endgame, checkpoints)
    private val adapters: List<GameBackupAdapter> = java.util.Collections.unmodifiableList(
        listOf(GoLocalSessionBackupAdapter(), GoLocalRecordBackupAdapter(), GoProgressBackupAdapter()) +
            createGoAiBackupAdapters() + listOf(GoAiProjectionBackupAdapter(),
                GoRankedStartBackupAdapter(), GoRankedSettlementBackupAdapter()))

    // Deliberately not cached: a new page after restore must capture the new epoch.
    fun aiRepository(): GoAiMatchRepository = GatedGoAiMatchRepository(checkpoints, gate)
    fun completedMatchSink() = RoomGoAiCompletionRepository(database, gate)
    fun rankedMatchCreator() = RoomGoRankedMatchCreator(database, checkpoints, rankedLearning, gate)
    fun formalAiRepository() = RoomGoFormalAiMatchRepository(database, checkpoints, rankedLearning, gate)

    /** Supplies all raw, shared and projection-metadata slots as one registration bundle. */
    fun backupRepository(createDelegate: (List<GameBackupAdapter>) -> LocalDataBackupRepository): LocalDataBackupRepository =
        GatedGoBackupRepository(createDelegate(adapters), gate, preparation)
}
