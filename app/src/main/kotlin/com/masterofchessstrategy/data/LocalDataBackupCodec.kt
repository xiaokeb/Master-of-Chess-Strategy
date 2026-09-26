package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import java.security.MessageDigest

internal data class LocalDataSnapshot(
    val createdAtEpochMillis: Long,
    val activeSessions: List<GameSessionSnapshot> = emptyList(),
    val lastSelections: List<LastGameSelection> = emptyList(),
    val settings: AppSettings? = null,
    val tutorialProgress: List<TutorialProgress> = emptyList(),
    val matchOutcomes: List<MatchOutcome> = emptyList(),
    val gameRecords: List<GameRecord> = emptyList(),
    val endgameProgress: List<CompletedEndgameLevel> = emptyList(),
)

/** Versioned, checksummed codec for user-triggered offline backup files. */
internal object LocalDataBackupCodec {
    const val MAX_BACKUP_BYTES = 128 * 1024 * 1024

    fun encode(snapshot: LocalDataSnapshot): ByteArray {
        validateSnapshot(snapshot)
        val lines = buildList {
            add(HEADER)
            add("CREATED|${snapshot.createdAtEpochMillis}")
            snapshot.activeSessions.sortedBy { it.gameType.code }.forEach { value ->
                add(
                    listOf(
                        "ACTIVE",
                        value.gameType.code,
                        value.mode.code,
                        value.difficulty.encodeNullableCode(),
                        value.engineState.toHex(),
                        value.updatedAtEpochMillis,
                        value.sessionId.encodeText(),
                        value.acceptedMoveCount,
                        value.undoUseCount,
                        value.hintUseCount,
                        value.resultOverride.encodeNullableResult(),
                        value.timeControlMinutes.encodeNullable(),
                        value.redRemainingMillis.encodeNullable(),
                        value.blackRemainingMillis.encodeNullable(),
                        value.turnStartedAtEpochMillis.encodeNullable(),
                        value.pendingDrawOfferSide.encodeNullableSide(),
                        value.autoPlayPaused.encodeBoolean(),
                        value.autoPlaySpeedPermille,
                        value.completedAutoGames,
                        value.sessionVariantId.encodeText(),
                        value.playerIndex,
                    ).joinToString("|"),
                )
            }
            snapshot.lastSelections.sortedBy { it.gameType.code }.forEach { value ->
                add(
                    listOf(
                        "SELECTION",
                        value.gameType.code,
                        value.mode.code,
                        value.difficulty.encodeNullableCode(),
                        value.updatedAtEpochMillis,
                    ).joinToString("|"),
                )
            }
            snapshot.settings?.let { value ->
                add(
                    listOf(
                        "SETTINGS",
                        value.defaultDifficulty.code,
                        value.autoContinueEnabled.encodeBoolean(),
                        value.autoContinueGameLimit,
                        value.soundEnabled.encodeBoolean(),
                        value.gameDurationMinutes.encodeNullable(),
                        value.selectedAppearanceCode,
                        value.highlightConditionsMask,
                        value.aiFirstEnabled.encodeBoolean(),
                        value.updatedAtEpochMillis,
                    ).joinToString("|"),
                )
            }
            snapshot.tutorialProgress.sortedBy { it.gameType.code }.forEach { value ->
                add(
                    listOf(
                        "TUTORIAL",
                        value.gameType.code,
                        value.contentVersion,
                        value.completedStepCount,
                        value.isCompleted.encodeBoolean(),
                        value.updatedAtEpochMillis,
                    ).joinToString("|"),
                )
            }
            snapshot.matchOutcomes.sortedWith(
                compareBy(MatchOutcome::settledAtEpochMillis, MatchOutcome::matchId),
            ).forEach { value ->
                add(
                    listOf(
                        "OUTCOME",
                        value.matchId.encodeText(),
                        value.gameType.code,
                        value.mode.code,
                        value.difficulty.code,
                        value.playerIndex,
                        value.result.encodeResult(),
                        value.settledAtEpochMillis,
                    ).joinToString("|"),
                )
            }
            snapshot.gameRecords.sortedWith(
                compareBy(GameRecord::completedAtEpochMillis, GameRecord::recordId),
            ).forEach { value ->
                add(
                    listOf(
                        "RECORD",
                        value.recordId.encodeText(),
                        value.gameType.code,
                        value.mode.code,
                        value.difficulty.encodeNullableCode(),
                        value.result.encodeResult(),
                        value.engineState.toHex(),
                        value.moveCount,
                        value.isFavorite.encodeBoolean(),
                        value.isEndgame.encodeBoolean(),
                        value.completedAtEpochMillis,
                        value.playerIndex,
                    ).joinToString("|"),
                )
            }
            snapshot.endgameProgress.sortedBy(CompletedEndgameLevel::levelId).forEach { value ->
                add(
                    listOf(
                        "ENDGAME",
                        value.levelId.encodeText(),
                        value.difficulty.code,
                        value.bestPlayerMoves,
                        value.starsAwarded,
                        value.scoreAwarded,
                        value.completedAtEpochMillis,
                    ).joinToString("|"),
                )
            }
        }
        val body = lines.joinToString(separator = "\n", postfix = "\n")
        val checksum = sha256(body.toByteArray(Charsets.UTF_8))
        return (body + "SHA256|$checksum\n").toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): LocalDataSnapshot {
        require(bytes.size in 1..MAX_BACKUP_BYTES) { "Backup size is outside the limit" }
        val content = bytes.toString(Charsets.UTF_8)
        require(content.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
            "Backup is not valid UTF-8"
        }
        require(content.endsWith('\n') && '\r' !in content) {
            "Backup must use canonical LF line endings"
        }
        val checksumMarker = content.lastIndexOf("\nSHA256|")
        require(checksumMarker >= 0)
        val body = content.substring(0, checksumMarker + 1)
        val checksumLine = content.substring(checksumMarker + 1).removeSuffix("\n")
        val expectedChecksum = checksumLine.substringAfter("SHA256|", missingDelimiterValue = "")
        require(expectedChecksum.matches(Regex("[0-9a-f]{64}")))
        require(sha256(body.toByteArray(Charsets.UTF_8)) == expectedChecksum) {
            "Backup checksum does not match"
        }
        val lines = body.lineSequence().filter(String::isNotEmpty).toList()
        require(lines.size in 2..MAX_LINE_COUNT)
        val backupVersion = when (lines.first()) {
            HEADER -> 3
            "MOCS-BACKUP|2" -> 2
            LEGACY_HEADER -> 1
            else -> error("Unsupported backup format")
        }
        val created = lines[1].split('|').also {
            require(it.size == 2 && it[0] == "CREATED")
        }[1].nonNegativeLong()
        val active = mutableListOf<GameSessionSnapshot>()
        val selections = mutableListOf<LastGameSelection>()
        var settings: AppSettings? = null
        val tutorials = mutableListOf<TutorialProgress>()
        val outcomes = mutableListOf<MatchOutcome>()
        val records = mutableListOf<GameRecord>()
        val endgames = mutableListOf<CompletedEndgameLevel>()
        lines.drop(2).forEach { line ->
            val fields = line.split('|')
            when (fields.firstOrNull()) {
                "ACTIVE" -> active += fields.decodeActive(backupVersion)
                "SELECTION" -> selections += fields.decodeSelection()
                "SETTINGS" -> {
                    require(settings == null)
                    settings = fields.decodeSettings(backupVersion)
                }
                "TUTORIAL" -> tutorials += fields.decodeTutorial()
                "OUTCOME" -> outcomes += fields.decodeOutcome()
                "RECORD" -> records += fields.decodeRecord(backupVersion)
                "ENDGAME" -> endgames += fields.decodeEndgame()
                else -> error("Unknown backup record")
            }
        }
        return LocalDataSnapshot(
            createdAtEpochMillis = created,
            activeSessions = active,
            lastSelections = selections,
            settings = settings,
            tutorialProgress = tutorials,
            matchOutcomes = outcomes,
            gameRecords = records,
            endgameProgress = endgames,
        ).also(::validateSnapshot)
    }

    private fun List<String>.decodeActive(version: Int): GameSessionSnapshot {
        require(size == if (version >= 3) 21 else 20)
        val gameType = this[1].decodeGameType()
        val mode = this[2].decodeMode()
        val difficulty = this[3].decodeNullableDifficulty()
        require(gameType == GameType.CHINESE_CHESS)
        require(mode in ACTIVE_GAME_MODES)
        require(
            if (mode == StoredGameMode.LOCAL_TWO_PLAYER) {
                difficulty == null
            } else {
                difficulty != null
            },
        )
        val variantId = this[19].decodeText(MAX_TEXT_BYTES)
        require(mode.acceptsSessionVariant(variantId))
        return GameSessionSnapshot(
            gameType = gameType,
            mode = mode,
            difficulty = difficulty,
            engineState = this[4].decodeHex(GameRecord.MAX_ENGINE_STATE_BYTES),
            updatedAtEpochMillis = this[5].nonNegativeLong(),
            sessionId = this[6].decodeText(MAX_ID_BYTES),
            acceptedMoveCount = this[7].boundedInt(0, MAX_ACTION_COUNT),
            undoUseCount = this[8].boundedInt(0, MAX_ACTION_COUNT),
            hintUseCount = this[9].boundedInt(0, MAX_ACTION_COUNT),
            resultOverride = this[10].decodeNullableResult(),
            timeControlMinutes = this[11].decodeNullableInt(),
            redRemainingMillis = this[12].decodeNullableLong(),
            blackRemainingMillis = this[13].decodeNullableLong(),
            turnStartedAtEpochMillis = this[14].decodeNullableLong(),
            pendingDrawOfferSide = this[15].decodeNullableSide(),
            autoPlayPaused = this[16].decodeBoolean(),
            autoPlaySpeedPermille = this[17].boundedInt(500, 4_000),
            completedAutoGames = this[18].boundedInt(0, MAX_ACTION_COUNT),
            sessionVariantId = variantId,
            playerIndex = if (version >= 3) this[20].boundedInt(0, 1) else 0,
        )
    }

    private fun List<String>.decodeSelection(): LastGameSelection {
        require(size == 5)
        val mode = this[2].decodeMode()
        val difficulty = this[3].decodeNullableDifficulty()
        require(
            if (
                mode == StoredGameMode.HUMAN_VS_AI ||
                mode == StoredGameMode.AI_AUTO_PLAY ||
                mode == StoredGameMode.CUSTOM_POSITION ||
                mode == StoredGameMode.TIMED_CHALLENGE ||
                mode == StoredGameMode.STREAK_CHALLENGE ||
                mode == StoredGameMode.BLIND_CHALLENGE ||
                mode == StoredGameMode.ASSESSMENT_CHALLENGE ||
                mode == StoredGameMode.OPENING_AUTO_PLAY
            ) {
                true
            } else {
                difficulty == null
            },
        )
        return LastGameSelection(
            gameType = this[1].decodeGameType(),
            mode = mode,
            difficulty = difficulty,
            updatedAtEpochMillis = this[4].nonNegativeLong(),
        )
    }

    private fun List<String>.decodeSettings(version: Int): AppSettings {
        require(when (version) {
            3 -> size == 10
            2 -> size == 9
            else -> size == 7 || size == 8
        })
        val hasAppearance = size >= 8
        return AppSettings(
            defaultDifficulty = this[1].decodeDifficulty(),
            autoContinueEnabled = this[2].decodeBoolean(),
            autoContinueGameLimit = this[3].boundedInt(
                AppSettings.AUTO_CONTINUE_LIMIT_RANGE.first,
                AppSettings.AUTO_CONTINUE_LIMIT_RANGE.last,
            ),
            soundEnabled = this[4].decodeBoolean(),
            gameDurationMinutes = this[5].decodeNullableInt(),
            selectedAppearanceCode = if (hasAppearance) {
                this[6].boundedInt(
                    AppSettings.APPEARANCE_CODE_RANGE.first,
                    AppSettings.APPEARANCE_CODE_RANGE.last,
                )
            } else {
                0
            },
            highlightConditionsMask = if (version >= 2) {
                this[7].boundedInt(0, AppSettings.ALL_HIGHLIGHT_CONDITIONS)
            } else {
                0
            },
            updatedAtEpochMillis = last().nonNegativeLong(),
            aiFirstEnabled = version >= 3 && this[8].decodeBoolean(),
        )
    }

    private fun List<String>.decodeTutorial(): TutorialProgress {
        require(size == 6)
        return TutorialProgress(
            gameType = this[1].decodeGameType(),
            contentVersion = this[2].boundedInt(1, Int.MAX_VALUE),
            completedStepCount = this[3].boundedInt(0, TutorialProgress.TOTAL_STEP_COUNT),
            isCompleted = this[4].decodeBoolean(),
            updatedAtEpochMillis = this[5].nonNegativeLong(),
        )
    }

    private fun List<String>.decodeOutcome(): MatchOutcome {
        require(size == 8)
        return MatchOutcome(
            matchId = this[1].decodeText(MAX_ID_BYTES),
            gameType = this[2].decodeGameType(),
            mode = this[3].decodeMode(),
            difficulty = this[4].decodeDifficulty(),
            playerIndex = this[5].boundedInt(0, 1),
            result = this[6].decodeResult(),
            settledAtEpochMillis = this[7].nonNegativeLong(),
        )
    }

    private fun List<String>.decodeRecord(version: Int): GameRecord {
        require(size == if (version >= 3) 12 else 11)
        val gameType = this[2].decodeGameType()
        require(gameType == GameType.CHINESE_CHESS)
        return GameRecord(
            recordId = this[1].decodeText(MAX_ID_BYTES),
            gameType = gameType,
            mode = this[3].decodeMode(),
            difficulty = this[4].decodeNullableDifficulty(),
            result = this[5].decodeResult(),
            engineState = this[6].decodeHex(GameRecord.MAX_ENGINE_STATE_BYTES),
            moveCount = this[7].boundedInt(0, GameRecord.MAX_MOVE_COUNT),
            isFavorite = this[8].decodeBoolean(),
            isEndgame = this[9].decodeBoolean(),
            completedAtEpochMillis = this[10].nonNegativeLong(),
            playerIndex = if (version >= 3) this[11].boundedInt(0, 1) else 0,
        )
    }

    private fun List<String>.decodeEndgame(): CompletedEndgameLevel {
        require(size == 7)
        return CompletedEndgameLevel(
            levelId = this[1].decodeText(MAX_TEXT_BYTES),
            difficulty = this[2].decodeDifficulty(),
            bestPlayerMoves = this[3].boundedInt(1, MAX_ACTION_COUNT),
            starsAwarded = this[4].boundedInt(1, MAX_REWARD),
            scoreAwarded = this[5].boundedInt(1, MAX_SCORE_REWARD),
            completedAtEpochMillis = this[6].nonNegativeLong(),
        )
    }

    private fun validateSnapshot(snapshot: LocalDataSnapshot) {
        require(snapshot.createdAtEpochMillis >= 0L)
        require(snapshot.activeSessions.size <= GameType.entries.size)
        require(snapshot.lastSelections.size <= GameType.entries.size)
        require(snapshot.tutorialProgress.size <= GameType.entries.size)
        require(snapshot.matchOutcomes.size <= MAX_LEDGER_ROWS)
        require(snapshot.gameRecords.size <= MAX_LEDGER_ROWS)
        require(snapshot.endgameProgress.size <= MAX_LEDGER_ROWS)
        require(snapshot.activeSessions.map { it.gameType }.distinct().size == snapshot.activeSessions.size)
        require(snapshot.lastSelections.map { it.gameType }.distinct().size == snapshot.lastSelections.size)
        require(snapshot.tutorialProgress.map { it.gameType }.distinct().size == snapshot.tutorialProgress.size)
        require(snapshot.matchOutcomes.map { it.matchId }.distinct().size == snapshot.matchOutcomes.size)
        require(snapshot.gameRecords.map { it.recordId }.distinct().size == snapshot.gameRecords.size)
        require(snapshot.endgameProgress.map { it.levelId }.distinct().size == snapshot.endgameProgress.size)
        snapshot.activeSessions.forEach {
            require(it.gameType == GameType.CHINESE_CHESS)
            require(it.mode in ACTIVE_GAME_MODES)
            require(it.updatedAtEpochMillis >= 0L)
            require(it.sessionId.isNotBlank() && it.sessionId.length <= MatchOutcome.MAX_MATCH_ID_LENGTH)
            require(it.engineState.size in 1..GameRecord.MAX_ENGINE_STATE_BYTES)
            require(it.mode.acceptsSessionDifficulty(it.difficulty))
            require(it.mode.acceptsSessionVariant(it.sessionVariantId))
            require(it.mode.acceptsPlayerIndex(it.playerIndex))
            require(it.hasValidPersistedClock())
        }
        snapshot.lastSelections.forEach { require(it.updatedAtEpochMillis >= 0L) }
        snapshot.settings?.let { require(it.updatedAtEpochMillis >= 0L) }
        snapshot.tutorialProgress.forEach { require(it.updatedAtEpochMillis >= 0L) }
        snapshot.matchOutcomes.forEach { require(it.settledAtEpochMillis >= 0L) }
        snapshot.gameRecords.forEach { require(it.gameType == GameType.CHINESE_CHESS) }
        snapshot.endgameProgress.forEach {
            require(it.levelId.isNotBlank() && it.levelId.length <= 80)
            require(it.bestPlayerMoves in 1..MAX_ACTION_COUNT)
            require(it.starsAwarded in 1..MAX_REWARD)
            require(it.scoreAwarded in 1..MAX_SCORE_REWARD)
            require(it.completedAtEpochMillis >= 0L)
        }
    }

    private fun String.decodeGameType(): GameType =
        toIntOrNull()?.let { code -> GameType.entries.firstOrNull { it.code == code } }
            ?: error("Unsupported game type")

    private fun String.decodeMode(): StoredGameMode =
        toIntOrNull()?.let { code -> StoredGameMode.entries.firstOrNull { it.code == code } }
            ?: error("Unsupported game mode")

    private fun String.decodeDifficulty(): Difficulty =
        toIntOrNull()?.let { code -> Difficulty.entries.firstOrNull { it.code == code } }
            ?: error("Unsupported difficulty")

    private fun String.decodeNullableDifficulty(): Difficulty? =
        if (this == NULL) null else decodeDifficulty()

    private fun GameResult.encodeResult(): Int = when (this) {
        GameResult.FIRST_PLAYER_WIN -> 1
        GameResult.SECOND_PLAYER_WIN -> 2
        GameResult.DRAW -> 3
        GameResult.ONGOING -> error("Ongoing result cannot be backed up as terminal")
    }

    private fun String.decodeResult(): GameResult = when (toIntOrNull()) {
        1 -> GameResult.FIRST_PLAYER_WIN
        2 -> GameResult.SECOND_PLAYER_WIN
        3 -> GameResult.DRAW
        else -> error("Unsupported terminal result")
    }

    private fun GameResult?.encodeNullableResult(): String =
        this?.encodeResult()?.toString() ?: NULL

    private fun String.decodeNullableResult(): GameResult? =
        if (this == NULL) null else decodeResult()

    private fun ChineseChessSide?.encodeNullableSide(): String = this?.code?.toString() ?: NULL

    private fun String.decodeNullableSide(): ChineseChessSide? =
        if (this == NULL) {
            null
        } else {
            toIntOrNull()?.let { code -> ChineseChessSide.entries.firstOrNull { it.code == code } }
                ?: error("Unsupported Chinese chess side")
        }

    private fun Difficulty?.encodeNullableCode(): String = this?.code?.toString() ?: NULL

    private fun Int?.encodeNullable(): String = this?.toString() ?: NULL

    private fun Long?.encodeNullable(): String = this?.toString() ?: NULL

    private fun String.decodeNullableInt(): Int? =
        if (this == NULL) null else (toIntOrNull() ?: error("Invalid nullable integer"))

    private fun String.decodeNullableLong(): Long? =
        if (this == NULL) null else (toLongOrNull() ?: error("Invalid nullable long"))

    private fun Boolean.encodeBoolean(): Int = if (this) 1 else 0

    private fun String.decodeBoolean(): Boolean = when (this) {
        "0" -> false
        "1" -> true
        else -> error("Invalid boolean")
    }

    private fun String.nonNegativeLong(): Long =
        toLongOrNull()?.also { require(it >= 0L) } ?: error("Invalid timestamp")

    private fun String.boundedInt(minimum: Int, maximum: Int): Int =
        toIntOrNull()?.also { require(it in minimum..maximum) } ?: error("Invalid integer")

    private fun String.encodeText(): String =
        if (isEmpty()) NULL else toByteArray(Charsets.UTF_8).toHex()

    private fun String.decodeText(maximumBytes: Int): String =
        if (this == NULL) {
            ""
        } else {
            decodeHex(maximumBytes).toString(Charsets.UTF_8).also { decoded ->
                require(decoded.toByteArray(Charsets.UTF_8).toHex() == lowercase())
            }
        }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        HEX[(byte.toInt() ushr 4) and 0x0f].toString() + HEX[byte.toInt() and 0x0f]
    }

    private fun String.decodeHex(maximumBytes: Int): ByteArray {
        require(length % 2 == 0 && length / 2 in 1..maximumBytes)
        return ByteArray(length / 2) { index ->
            val high = this[index * 2].digitToIntOrNull(16) ?: error("Invalid hex")
            val low = this[index * 2 + 1].digitToIntOrNull(16) ?: error("Invalid hex")
            ((high shl 4) or low).toByte()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private const val HEADER = "MOCS-BACKUP|3"
    private const val LEGACY_HEADER = "MOCS-BACKUP|1"
    private const val NULL = "-"
    private const val MAX_LINE_COUNT = 200_000
    private const val MAX_LEDGER_ROWS = 100_000
    private const val MAX_ACTION_COUNT = 10_000
    private const val MAX_ID_BYTES = 256
    private const val MAX_TEXT_BYTES = 320
    private const val MAX_REWARD = 5
    private const val MAX_SCORE_REWARD = 1_000
    private const val HEX = "0123456789abcdef"
    private val ACTIVE_GAME_MODES = setOf(
        StoredGameMode.LOCAL_TWO_PLAYER,
        StoredGameMode.HUMAN_VS_AI,
        StoredGameMode.AI_AUTO_PLAY,
        StoredGameMode.ENDGAME,
        StoredGameMode.CUSTOM_POSITION,
        StoredGameMode.TIMED_CHALLENGE,
        StoredGameMode.STREAK_CHALLENGE,
        StoredGameMode.BLIND_CHALLENGE,
        StoredGameMode.ASSESSMENT_CHALLENGE,
        StoredGameMode.OPENING_AUTO_PLAY,
    )
}
