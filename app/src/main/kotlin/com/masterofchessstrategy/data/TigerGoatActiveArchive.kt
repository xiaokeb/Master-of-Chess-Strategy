package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.games.tiger_goat.NativeTigerGoatSession

/** Owns a local two-player MOCT checkpoint, not a Xiangqi session or a public game record. */
internal class TigerGoatActiveArchive(
    val sessionId: String,
    engineState: ByteArray,
    val actionCount: Int,
    val updatedAtEpochMillis: Long,
) {
    private val ownedState = engineState.also { require(it.size in 53..MAX_STATE_BYTES) }.copyOf()
    val engineState: ByteArray get() = ownedState.copyOf()
    val engineFormatVersion: Int get() = ownedState[4].toInt() and 255

    init {
        require(sessionId.isNotBlank() && sessionId.length <= 64)
        require(sessionId.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == sessionId)
        require(updatedAtEpochMillis >= 0 && actionCount in 0..65_535)
        require(ownedState.size == 53 + actionCount * 3)
        require(ownedState.take(4) == listOf(77, 79, 67, 84).map(Int::toByte))
        require(engineFormatVersion in 1..2 && ownedState[5] == 1.toByte())
        require((ownedState[47].toInt() and 255) + ((ownedState[48].toInt() and 255) shl 8) == actionCount)
        var checksum = 0x811c9dc5.toInt()
        for (index in 0 until ownedState.size - 4) {
            checksum = (checksum xor (ownedState[index].toInt() and 255)) * 16777619
        }
        var expected = 0
        repeat(4) { expected = expected or ((ownedState[ownedState.size - 4 + it].toInt() and 255) shl (8 * it)) }
        require(checksum == expected) { "Invalid MOCT checksum" }
    }

    fun toEntity() = ActiveGameEntity(
        gameTypeCode = GameType.TIGER_AND_GOAT.code,
        modeCode = StoredGameMode.LOCAL_TWO_PLAYER.code,
        difficultyCode = null,
        envelopeVersion = 5,
        engineFormatVersion = engineFormatVersion,
        engineState = engineState,
        updatedAtEpochMillis = updatedAtEpochMillis,
        sessionId = sessionId,
        acceptedMoveCount = actionCount,
    )

    override fun equals(other: Any?) = other is TigerGoatActiveArchive &&
        sessionId == other.sessionId && actionCount == other.actionCount &&
        updatedAtEpochMillis == other.updatedAtEpochMillis && ownedState.contentEquals(other.ownedState)

    override fun hashCode(): Int = 31 * (31 * (31 * sessionId.hashCode() + actionCount) +
        updatedAtEpochMillis.hashCode()) + ownedState.contentHashCode()

    companion object {
        const val FORMAT_VERSION = 1
        const val MAX_STATE_BYTES = 196_658

        fun fromEntity(row: ActiveGameEntity): TigerGoatActiveArchive {
            require(row.gameTypeCode == GameType.TIGER_AND_GOAT.code && row.modeCode == 0 &&
                row.difficultyCode == null && row.envelopeVersion == 5 &&
                row.undoUseCount == 0 && row.hintUseCount == 0 && row.resultOverrideCode == null &&
                row.timeControlMinutes == null && row.redRemainingMillis == null &&
                row.blackRemainingMillis == null && row.turnStartedAtEpochMillis == null &&
                row.pendingDrawOfferSideCode == null && !row.autoPlayPaused &&
                row.autoPlaySpeedPermille == 1000 && row.completedAutoGames == 0 &&
                row.sessionVariantId.isEmpty() && row.playerIndex == 0)
            return TigerGoatActiveArchive(row.sessionId, row.engineState, row.acceptedMoveCount,
                row.updatedAtEpochMillis).also { require(row.engineFormatVersion == it.engineFormatVersion) }
        }
    }
}

/** Envelope checks are insufficient: the actual Native engine must replay every action. */
internal fun validateTigerGoatActiveArchive(archive: TigerGoatActiveArchive): Boolean =
    NativeTigerGoatSession().use { it.restore(archive.engineState) }
