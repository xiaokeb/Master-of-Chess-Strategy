package com.masterofchessstrategy.games.go.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.MocsDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Called under the shared epoch gate, after all producers/resources have retired. */
internal class GoCompleteBackupPreparation(
    private val database: MocsDatabase,
    private val session: GoLocalLegacySource,
    private val learning: GoLearningLegacySource,
    private val endgame: GoEndgameLegacySource,
    private val ai: GoAiCheckpointRepository,
) : GoBackupPreparation {
    override suspend fun beforeExport() {
        database.withTransaction {
            // Nested repository transactions join this transaction. A bad fourth source must
            // roll back every earlier row and fence, not leave three successful migrations.
            RoomGoSessionRepository(database, session).load()
            RoomGoLearningProgressRepository(database, learning).load()
            RoomGoEndgameProgressRepository(database, endgame).load()
            ai.load()
            database.requireGoAiArchiveParents()
            currentCoroutineContext().ensureActive()
        }
    }

    override suspend fun beforeRestore() {
        // Never inspect old bytes: a valid replacement may intentionally replace damaged data.
        // RoomLocalDataBackupRepository seals all four legacy scopes in its replacement TX.
    }
}
