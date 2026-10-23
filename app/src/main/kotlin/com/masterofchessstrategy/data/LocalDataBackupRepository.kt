package com.masterofchessstrategy.data

import androidx.room.withTransaction
import com.masterofchessstrategy.endgame.ChineseChessEndgameLevel
import com.masterofchessstrategy.endgame.ChineseChessEndgamePack
import com.masterofchessstrategy.custom.CustomPositionStateCodec
import com.masterofchessstrategy.custom.ChineseChessHandicapConfig
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.go.data.requireGoAiArchiveParents
import com.masterofchessstrategy.games.go.data.requireGoAiProjectionParents
import com.masterofchessstrategy.games.go.data.requireGoRankedParents

internal data class LocalDataRestoreSummary(
    val activeSessionCount: Int,
    val matchOutcomeCount: Int,
    val gameRecordCount: Int,
    val completedEndgameCount: Int,
    val competitionMatchCount: Int = 0,
)

internal interface LocalDataBackupRepository {
    suspend fun export(createdAtEpochMillis: Long): ByteArray

    suspend fun restore(bytes: ByteArray): LocalDataRestoreSummary
}

/** Reads and replaces all user data under one Room transaction. */
internal class RoomLocalDataBackupRepository(
    private val database: MocsDatabase,
    private val endgamePack: ChineseChessEndgamePack,
    private val validateEngineState: (ByteArray) -> Boolean,
    private val gameBackups: GameBackupRegistry,
) : LocalDataBackupRepository {
    private val sessionRepository = RoomGameSessionRepository(database.activeGameDao())
    private val selectionRepository = RoomLastSelectionRepository(database.lastSelectionDao())
    private val settingsRepository = RoomAppSettingsRepository(database.appSettingsDao())
    private val tutorialRepository = RoomTutorialProgressRepository(database.tutorialProgressDao())
    private val gameRecordRepository = RoomGameRecordRepository(database.gameRecordDao())
    private val endgameRepository = RoomEndgameProgressRepository(
        database.endgameProgressDao(),
        endgamePack,
    )
    private val levelsById = endgamePack.levels.associateBy(ChineseChessEndgameLevel::id)

    override suspend fun export(createdAtEpochMillis: Long): ByteArray {
        require(createdAtEpochMillis >= 0L)
        val snapshot = database.withTransaction {
            gameBackups.requireSupportedSlots(BackupStorageInventory.read(database))
            LocalDataSnapshot(
                createdAtEpochMillis = createdAtEpochMillis,
                gameExtensions = gameBackups.readRecords(database),
                // Every other active row belongs to an explicitly registered game adapter.
                activeSessions = listOf(GameType.CHINESE_CHESS).mapNotNull { gameType ->
                    when (val result = sessionRepository.load(gameType)) {
                        LoadGameSessionResult.NotFound -> null
                        LoadGameSessionResult.Incompatible -> error("Active session is incompatible")
                        is LoadGameSessionResult.Loaded -> result.snapshot
                    }
                },
                lastSelections = GameType.entries.mapNotNull { gameType ->
                    when (val result = selectionRepository.load(gameType)) {
                        LoadLastSelectionResult.NotFound -> null
                        LoadLastSelectionResult.Incompatible -> error("Last selection is incompatible")
                        is LoadLastSelectionResult.Loaded -> result.selection
                    }
                },
                settings = when (val result = settingsRepository.load()) {
                    LoadAppSettingsResult.NotFound -> null
                    LoadAppSettingsResult.Incompatible -> error("Settings are incompatible")
                    is LoadAppSettingsResult.Loaded -> result.settings
                },
                tutorialProgress = GameType.entries.mapNotNull { gameType ->
                    when (val result = tutorialRepository.load(gameType)) {
                        LoadTutorialProgressResult.NotFound -> null
                        LoadTutorialProgressResult.Incompatible -> error("Tutorial data is incompatible")
                        is LoadTutorialProgressResult.Loaded -> result.progress
                    }
                },
                matchOutcomes = database.matchOutcomeDao().listAll().map {
                    it.toMatchOutcome() ?: error("Match outcome ledger is incompatible")
                },
                gameRecords = when (
                    val result = gameRecordRepository.list(GameRecordCategory.ALL)
                ) {
                    LoadGameRecordsResult.Incompatible -> error("Game records are incompatible")
                    // Go payload/provenance is owned by its registered extension, not Xiangqi RECORD.
                    is LoadGameRecordsResult.Loaded -> result.records.filter { it.gameType != GameType.GO }
                },
                endgameProgress = when (val result = endgameRepository.load()) {
                    LoadEndgameProgressResult.Incompatible -> error("Endgame progress is incompatible")
                    is LoadEndgameProgressResult.Loaded -> result.progress.completedById.values.toList()
                },
            )
        }
        validateEngineStates(snapshot)
        // Export is subject to the same real engine admission as restore, without writes.
        gameBackups.prepareRestore(snapshot.gameExtensions, gameBackups.containerVersion(snapshot.gameExtensions),
            snapshot.gameRecords.map { it.recordId }.toSet(), snapshot.matchOutcomes)
        return LocalDataBackupCodec.encode(snapshot, gameBackups)
    }

    override suspend fun restore(bytes: ByteArray): LocalDataRestoreSummary {
        val snapshot = LocalDataBackupCodec.decode(bytes, gameBackups)
        validateEngineStates(snapshot)
        val preparedGames = gameBackups.prepareRestore(
            snapshot.gameExtensions,
            gameBackups.containerVersion(snapshot.gameExtensions),
            snapshot.gameRecords.map { it.recordId }.toSet(),
            snapshot.matchOutcomes,
        )
        val endgameLevels = snapshot.endgameProgress.associateWith { progress ->
            val level = levelsById[progress.levelId]
                ?: error("Backup references an unknown endgame level")
            require(progress.difficulty == level.difficulty)
            require(progress.bestPlayerMoves in 1..level.maxPlayerMoves)
            require(progress.starsAwarded == level.starReward)
            require(progress.scoreAwarded == level.scoreReward)
            level
        }
        database.withTransaction {
            // Recheck actual stored slots under the write transaction before clearing anything.
            gameBackups.requireSupportedSlots(BackupStorageInventory.read(database))
            clearAll()
            // A pre-migration/Go-free backup must not resurrect data from any old disk source.
            // These tombstones share this transaction and survive only a successful replacement.
            database.localDataMigrationDao().sealGoLegacySourcesForRestore()
            preparedGames.forEach { it.write(database) }
            snapshot.activeSessions.forEach { sessionRepository.save(it) }
            snapshot.lastSelections.forEach { selectionRepository.save(it) }
            snapshot.settings?.let { settingsRepository.save(it) }
            snapshot.tutorialProgress.forEach { tutorialRepository.save(it) }
            database.matchOutcomeDao().insertAll(
                snapshot.matchOutcomes.map(MatchOutcome::toMatchOutcomeEntity),
            )
            snapshot.gameRecords.forEach {
                check(gameRecordRepository.saveCompleted(it))
            }
            endgameLevels.forEach { (progress, level) ->
                val result = endgameRepository.complete(
                    level = level,
                    playerMoves = progress.bestPlayerMoves,
                    completedAtEpochMillis = progress.completedAtEpochMillis,
                )
                check(result.firstCompletion)
            }
            database.requireGoAiArchiveParents()
            database.requireGoAiProjectionParents()
            database.requireGoRankedParents()
        }
        return LocalDataRestoreSummary(
            activeSessionCount = snapshot.activeSessions.size +
                preparedGames.count { it.storageSlot.table in setOf(BackupStorageTable.ACTIVE_GAME,
                    BackupStorageTable.GO_LOCAL_SESSION, BackupStorageTable.GO_AI_SESSION) },
            matchOutcomeCount = snapshot.matchOutcomes.size,
            gameRecordCount = snapshot.gameRecords.size +
                preparedGames.sumOf { it.completedRecordIds.size },
            completedEndgameCount = snapshot.endgameProgress.size,
            competitionMatchCount = preparedGames.count { it.storageSlot.table == BackupStorageTable.COMPETITION_MATCH },
        )
    }

    private fun validateEngineStates(snapshot: LocalDataSnapshot) {
        val customInitialStates = snapshot.activeSessions
            .filter {
                it.mode == StoredGameMode.CUSTOM_POSITION ||
                    it.mode == StoredGameMode.OPENING_AUTO_PLAY
            }
            .map { CustomPositionStateCodec.decodeSessionVariant(it.sessionVariantId) }
        val handicapInitialStates = snapshot.activeSessions
            .filter { it.mode == StoredGameMode.HANDICAP }
            .map { ChineseChessHandicapConfig.initialState(it.sessionVariantId) }
        (snapshot.activeSessions.map(GameSessionSnapshot::engineState) +
            snapshot.gameRecords.map(GameRecord::engineState) +
            customInitialStates + handicapInitialStates).forEach { state ->
            require(validateEngineState(state.copyOf())) {
                "Backup contains an engine state that cannot be restored"
            }
        }
    }

    private suspend fun clearAll() {
        // Both tables form one deferred parent graph; clear inside this same
        // replacement transaction, never orphaning a child in a separate commit.
        database.goRankedDao().deleteSettlements()
        database.goRankedDao().deleteStarts()
        database.goAiProjectionDao().deleteAll()
        database.goAiCheckpointDao().deleteAllActive()
        database.goAiCheckpointDao().deleteAllArchives()
        database.goLocalSessionDao().deleteAll()
        database.goRecordSourceDao().deleteAll()
        database.goProgressDao().deleteAll()
        database.competitionMahjongMatchDao().deleteAll()
        database.activeGameDao().deleteAll()
        database.lastSelectionDao().deleteAll()
        database.appSettingsDao().deleteAll()
        database.tutorialProgressDao().deleteAll()
        database.matchOutcomeDao().deleteAll()
        database.gameRecordDao().deleteAll()
        database.endgameProgressDao().deleteAll()
    }
}
