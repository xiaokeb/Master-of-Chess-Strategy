package com.masterofchessstrategy.data

import androidx.room.withTransaction
import com.masterofchessstrategy.endgame.ChineseChessEndgameLevel
import com.masterofchessstrategy.endgame.ChineseChessEndgamePack
import com.masterofchessstrategy.custom.CustomPositionStateCodec
import com.masterofchessstrategy.engine.GameType

internal data class LocalDataRestoreSummary(
    val activeSessionCount: Int,
    val matchOutcomeCount: Int,
    val gameRecordCount: Int,
    val completedEndgameCount: Int,
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
            LocalDataSnapshot(
                createdAtEpochMillis = createdAtEpochMillis,
                activeSessions = GameType.entries.mapNotNull { gameType ->
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
                    is LoadGameRecordsResult.Loaded -> result.records
                },
                endgameProgress = when (val result = endgameRepository.load()) {
                    LoadEndgameProgressResult.Incompatible -> error("Endgame progress is incompatible")
                    is LoadEndgameProgressResult.Loaded -> result.progress.completedById.values.toList()
                },
            )
        }
        validateEngineStates(snapshot)
        return LocalDataBackupCodec.encode(snapshot)
    }

    override suspend fun restore(bytes: ByteArray): LocalDataRestoreSummary {
        val snapshot = LocalDataBackupCodec.decode(bytes)
        validateEngineStates(snapshot)
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
            clearAll()
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
        }
        return LocalDataRestoreSummary(
            activeSessionCount = snapshot.activeSessions.size,
            matchOutcomeCount = snapshot.matchOutcomes.size,
            gameRecordCount = snapshot.gameRecords.size,
            completedEndgameCount = snapshot.endgameProgress.size,
        )
    }

    private fun validateEngineStates(snapshot: LocalDataSnapshot) {
        val customInitialStates = snapshot.activeSessions
            .filter {
                it.mode == StoredGameMode.CUSTOM_POSITION ||
                    it.mode == StoredGameMode.OPENING_AUTO_PLAY
            }
            .map { CustomPositionStateCodec.decodeSessionVariant(it.sessionVariantId) }
        (snapshot.activeSessions.map(GameSessionSnapshot::engineState) +
            snapshot.gameRecords.map(GameRecord::engineState) +
            customInitialStates).forEach { state ->
            require(validateEngineState(state.copyOf())) {
                "Backup contains an engine state that cannot be restored"
            }
        }
    }

    private suspend fun clearAll() {
        database.activeGameDao().deleteAll()
        database.lastSelectionDao().deleteAll()
        database.appSettingsDao().deleteAll()
        database.tutorialProgressDao().deleteAll()
        database.matchOutcomeDao().deleteAll()
        database.gameRecordDao().deleteAll()
        database.endgameProgressDao().deleteAll()
    }
}
