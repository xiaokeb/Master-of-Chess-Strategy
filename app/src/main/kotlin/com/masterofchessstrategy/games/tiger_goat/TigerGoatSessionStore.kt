package com.masterofchessstrategy.games.tiger_goat

import com.masterofchessstrategy.data.ActiveGameDao
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.games.tiger_goat.NativeTigerGoatSession

internal data class TigerGoatSavedSession(
    val id: String,
    val bytes: ByteArray,
    val actionCount: Int,
    val updatedAt: Long,
) {
    fun copyOwned() = copy(bytes = bytes.copyOf())
}

internal sealed interface TigerGoatLoadResult {
    data object Missing : TigerGoatLoadResult
    data object Incompatible : TigerGoatLoadResult
    data class Loaded(val saved: TigerGoatSavedSession) : TigerGoatLoadResult
}

internal interface TigerGoatSessionStore {
    suspend fun load(): TigerGoatLoadResult
    suspend fun save(saved: TigerGoatSavedSession)
}

/** Uses the existing per-game row; shared schema and other game rows are untouched. */
internal class RoomTigerGoatSessionStore(
    private val dao: ActiveGameDao,
    private val admit: (ByteArray) -> Boolean = { bytes -> NativeTigerGoatSession().use { it.restore(bytes) } },
) : TigerGoatSessionStore {
    override suspend fun load(): TigerGoatLoadResult {
        val row = dao.find(GameType.TIGER_AND_GOAT.code) ?: return TigerGoatLoadResult.Missing
        if (row.gameTypeCode != GameType.TIGER_AND_GOAT.code || row.modeCode != 0 ||
            row.difficultyCode != null || row.envelopeVersion != 5 || row.engineFormatVersion !in 1..2 ||
            row.undoUseCount != 0 || row.hintUseCount != 0 || row.resultOverrideCode != null ||
            row.timeControlMinutes != null || row.redRemainingMillis != null ||
            row.blackRemainingMillis != null || row.turnStartedAtEpochMillis != null ||
            row.pendingDrawOfferSideCode != null || row.autoPlayPaused || row.autoPlaySpeedPermille != 1000 ||
            row.completedAutoGames != 0 || row.sessionVariantId.isNotEmpty() || row.playerIndex != 0
        ) return TigerGoatLoadResult.Incompatible
        val saved = TigerGoatSavedSession(row.sessionId, row.engineState.copyOf(),
            row.acceptedMoveCount, row.updatedAtEpochMillis)
        return if (valid(saved) && row.engineFormatVersion == formatVersion(saved.bytes)) {
            TigerGoatLoadResult.Loaded(saved)
        } else TigerGoatLoadResult.Incompatible
    }

    override suspend fun save(saved: TigerGoatSavedSession) {
        require(valid(saved)) { "Invalid tiger-goat session envelope" }
        dao.upsert(ActiveGameEntity(
            gameTypeCode = GameType.TIGER_AND_GOAT.code,
            modeCode = 0,
            difficultyCode = null,
            envelopeVersion = 5,
            engineFormatVersion = formatVersion(saved.bytes),
            engineState = saved.bytes.copyOf(),
            updatedAtEpochMillis = saved.updatedAt,
            sessionId = saved.id,
            acceptedMoveCount = saved.actionCount,
        ))
    }

    private fun valid(saved: TigerGoatSavedSession): Boolean {
        val bytes = saved.bytes
        return saved.id.isNotBlank() && saved.id.length <= 64 && saved.updatedAt >= 0 &&
            saved.actionCount in 0..65_535 && bytes.size == 53 + saved.actionCount * 3 &&
            bytes.take(4) == listOf(77, 79, 67, 84).map(Int::toByte) &&
            formatVersion(bytes) in 1..2 && bytes[5] == 1.toByte() &&
            (bytes[47].toInt() and 255) + ((bytes[48].toInt() and 255) shl 8) == saved.actionCount &&
            admit(bytes.copyOf())
    }

    private fun formatVersion(bytes: ByteArray) = bytes[4].toInt() and 255
}
