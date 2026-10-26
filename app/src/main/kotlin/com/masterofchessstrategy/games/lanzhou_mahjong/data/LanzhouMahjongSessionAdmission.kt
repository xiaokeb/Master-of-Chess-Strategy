package com.masterofchessstrategy.games.lanzhou_mahjong.data

import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeLanzhouMahjongSession
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecord
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecordCodec
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Bounds/metadata are checked here; only Native can admit the complete legal action history. */
internal class LanzhouMahjongSessionAdmission(
    private val canonicalize: (LanzhouMahjongSessionRecord) -> ByteArray = { record ->
        NativeLanzhouMahjongSession(record.seed, record.dealerSeat, record.baseScore,
            record.viewerSeat).use { session ->
            if (session.restore(record.nativeState) != RestoreResult.Restored) {
                throw IOException("兰州麻将完整历史校验失败，原存档已保留")
            }
            session.serialize()
        }
    },
) {
    /** Own a fresh record; LZMJ v1/v2 must serialize to exactly their original native bytes. */
    fun admit(record: LanzhouMahjongSessionRecord): LanzhouMahjongSessionRecord {
        val owned = copyRecord(record)
        val expected = owned.nativeState
        val canonical = try {
            canonicalize(copyRecord(owned))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw IOException("兰州麻将完整历史校验失败，原存档已保留", failure)
        }
        if (!expected.contentEquals(canonical)) {
            throw IOException("兰州麻将存档不是规范完整历史，原存档已保留")
        }
        return owned
    }

    /** Accept both LZSR envelopes without comparing a legacy v1 row to a newly encoded v2 envelope. */
    fun admitRow(row: ActiveGameEntity): LanzhouMahjongSessionRecord {
        if (row.engineState.size > LanzhouMahjongSessionRecordCodec.MAX_BYTES) {
            throw IOException("兰州麻将存档超限，原存档已保留")
        }
        val bytes = row.engineState.copyOf()
        val record = LanzhouMahjongSessionRecordCodec.decode(bytes)
            ?: throw IOException("兰州麻将存档损坏或版本不兼容，原存档已保留")
        if (row.gameTypeCode != GameType.LANZHOU_MAHJONG.code || row.modeCode != HUMAN_VS_AI_MODE ||
            row.envelopeVersion != ROW_ENVELOPE_VERSION || row.engineFormatVersion != (bytes[4].toInt() and 255) ||
            row.difficultyCode != record.difficulty.code || row.playerIndex != record.viewerSeat ||
            row.sessionVariantId != VARIANT || row.sessionId != sessionId(record) ||
            row.acceptedMoveCount != actionCount(record) || row.updatedAtEpochMillis < 0 ||
            // Existing rows intentionally leave these columns zero. Usage lives in LZSR,
            // including nonzero v2 usage; never refill it from these legacy defaults.
            row.undoUseCount != 0 || row.hintUseCount != 0 ||
            row.resultOverrideCode != null || row.timeControlMinutes != null ||
            row.redRemainingMillis != null || row.blackRemainingMillis != null ||
            row.turnStartedAtEpochMillis != null || row.pendingDrawOfferSideCode != null ||
            row.autoPlayPaused || row.autoPlaySpeedPermille != 1_000 || row.completedAutoGames != 0
        ) throw IOException("兰州麻将存档元数据不一致，原存档已保留")
        return admit(record)
    }

    /** New writes use LZSR v2; native rule identity, initial-wall ID and assistance remain unchanged. */
    fun createRow(record: LanzhouMahjongSessionRecord, updatedAtEpochMillis: Long): ActiveGameEntity {
        if (updatedAtEpochMillis < 0) throw IOException("兰州麻将保存时间无效")
        val admitted = admit(record)
        return ActiveGameEntity(
            gameTypeCode = GameType.LANZHOU_MAHJONG.code,
            modeCode = HUMAN_VS_AI_MODE,
            difficultyCode = admitted.difficulty.code,
            envelopeVersion = ROW_ENVELOPE_VERSION,
            engineFormatVersion = LanzhouMahjongSessionRecordCodec.VERSION,
            engineState = LanzhouMahjongSessionRecordCodec.encode(admitted),
            updatedAtEpochMillis = updatedAtEpochMillis,
            sessionId = sessionId(admitted),
            acceptedMoveCount = actionCount(admitted),
            sessionVariantId = VARIANT,
            playerIndex = admitted.viewerSeat,
        )
    }

    companion object {
        const val VARIANT = "lzmj-project-108-v1"
        const val ROW_ENVELOPE_VERSION = 1
        private const val HUMAN_VS_AI_MODE = 1

        /** Preserve the old store's 69-character ID, including its native rule byte. */
        internal fun sessionId(record: LanzhouMahjongSessionRecord): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(record.nativeState, 0, 122)
            digest.update(record.viewerSeat.toByte())
            digest.update(record.difficulty.code.toByte())
            return "lzmj-" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        internal fun actionCount(record: LanzhouMahjongSessionRecord): Int =
            (record.nativeState.size - 130) / 3

        private fun copyRecord(record: LanzhouMahjongSessionRecord): LanzhouMahjongSessionRecord =
            LanzhouMahjongSessionRecordCodec.decode(LanzhouMahjongSessionRecordCodec.encode(record))
                ?: throw IOException("兰州麻将存档结构无效")
    }
}
