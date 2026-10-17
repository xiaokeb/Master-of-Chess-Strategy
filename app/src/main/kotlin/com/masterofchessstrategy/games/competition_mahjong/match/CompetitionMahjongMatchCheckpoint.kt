package com.masterofchessstrategy.games.competition_mahjong.match

import com.masterofchessstrategy.engine.CompetitionMahjongPublicView
import com.masterofchessstrategy.engine.CompetitionMahjongRoundPhase
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.CompetitionMahjongAction
import com.masterofchessstrategy.games.competition_mahjong.CompetitionMahjongCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.CompetitionMahjongCheckpointCodec
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongAssistanceUsage
import com.masterofchessstrategy.games.competition_mahjong.match.hint.CompetitionMahjongHintReceipt
import com.masterofchessstrategy.games.competition_mahjong.match.hint.CompetitionMahjongHintReceiptCodec
import com.masterofchessstrategy.games.competition_mahjong.match.hint.competitionMahjongHintPosition
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/** Private persistence object; never pass it to a table renderer or export it as public replay. */
class CompetitionMahjongMatchCheckpoint(
    val config: CompetitionMahjongCompetitionConfig,
    val ledger: CompetitionMahjongMatchLedger,
    val activeHandIndex: Int,
    engineRecord: ByteArray,
    val assistance: CompetitionMahjongAssistanceUsage? = null,
    val playback: CompetitionMahjongMatchPlayback? = null,
    val hintReceipt: CompetitionMahjongHintReceipt? = null,
) {
    private val record = engineRecord.copyOf()
    val engineRecord: ByteArray get() = record.copyOf()

    init {
        require(playback == null || assistance != null) { "播放配置必须随正式难度保存" }
        require(activeHandIndex in 0..15)
        require(ledger.completedHandCount == activeHandIndex || ledger.completedHandCount == activeHandIndex + 1)
        hintReceipt?.let {
            require(assistance != null && assistance.hintUsed > 0 && !assistance.undoInProgress && playback != null)
            require(it.handIndex == activeHandIndex && ledger.completedHandCount == activeHandIndex)
        }
        // Reuse the established MCMS viewer/CRC envelope gate, without changing its frozen format.
        CompetitionMahjongCheckpointCodec.encode(
            CompetitionMahjongCheckpoint(config.handConfig(activeHandIndex), record),
        )
    }

    internal fun validateRestoredFrame(view: CompetitionMahjongPublicView,
                                       legalActions: List<CompetitionMahjongAction>? = null) {
        CompetitionMahjongMatchLedger.validateFrame(config, activeHandIndex, view)
        val terminal = view.phase == CompetitionMahjongRoundPhase.WON ||
            view.phase == CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW
        require(terminal == (ledger.completedHandCount == activeHandIndex + 1)) {
            "牌局终局与赛事记分不一致"
        }
        if (terminal) {
            require(ledger.results.last() == CompetitionMahjongMatchLedger.captureTerminal(config, activeHandIndex, view))
        }
        hintReceipt?.let {
            require(it.positionSha256 == competitionMahjongHintPosition(activeHandIndex, view)) { "提示回执与当前局面不一致" }
            if (legalActions != null) require(it.action in legalActions) { "已付提示不属于当前合法动作" }
        }
    }

    override fun toString(): String =
        "CompetitionMahjongMatchCheckpoint(matchId=${config.matchId}, activeHand=$activeHandIndex, completed=${ledger.completedHandCount})"

    internal fun withAssistance(usage: CompetitionMahjongAssistanceUsage): CompetitionMahjongMatchCheckpoint =
        CompetitionMahjongMatchCheckpoint(config, ledger, activeHandIndex, record, usage, playback, hintReceipt)

    internal fun withPlayback(value: CompetitionMahjongMatchPlayback): CompetitionMahjongMatchCheckpoint =
        CompetitionMahjongMatchCheckpoint(config, ledger, activeHandIndex, record, assistance, value, hintReceipt)

    internal fun withHintReceipt(value: CompetitionMahjongHintReceipt?): CompetitionMahjongMatchCheckpoint =
        CompetitionMahjongMatchCheckpoint(config, ledger, activeHandIndex, record, assistance, playback, value)
}

/** Coordinator supplies a Room-backed implementation; this slice creates no parallel file library. */
interface CompetitionMahjongMatchStore {
    /** A missing row returns null. Corrupt, incompatible or differently owned data must throw. */
    suspend fun load(viewerPlayerId: Int): CompetitionMahjongMatchCheckpoint?
    /** Replace configuration, ledger and current private engine bytes in one transaction. */
    suspend fun save(checkpoint: CompetitionMahjongMatchCheckpoint)
}

/** MCMT v1..v4: assistance, user playback, paid hint receipt; nested MCPK/MCMS stays v1. */
internal object CompetitionMahjongMatchCheckpointCodec {
    private const val MAGIC = 0x4d434d54
    private const val HEADER_BYTES = 12
    private const val FOOTER_BYTES = 4
    private const val RESULT_BYTES = 36
    private const val MAX_FILE_BYTES = 1_100_000

    fun encode(checkpoint: CompetitionMahjongMatchCheckpoint): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            val config = checkpoint.config
            val id = config.matchId.toByteArray(StandardCharsets.US_ASCII)
            data.writeByte(id.size)
            data.write(id)
            data.writeLong(config.seed)
            data.writeByte(config.viewerPlayerId)
            data.writeByte(config.botLevel)
            data.writeByte(checkpoint.activeHandIndex)
            val results = checkpoint.ledger.results
            data.writeByte(results.size)
            results.forEach { result ->
                data.writeByte(result.handIndex)
                data.writeByte(result.phase.code)
                data.writeByte(result.winnerPlayerId ?: 255)
                data.writeByte(result.payerPlayerId ?: 255)
                repeat(4) { data.writeInt(result.scores[it].toInt()) }
                data.writeInt(result.fanWithoutFlowers)
                data.writeInt(result.totalFan)
                data.writeLong(0) // Reserved for a separately versioned rule revision.
            }
            checkpoint.assistance?.let { usage ->
                data.writeByte(1) // Assistance policy revision.
                data.writeByte(usage.difficulty.code)
                data.writeByte(if (usage.undoInProgress) 1 else 0)
                data.writeByte(0)
                data.writeInt(usage.undoUsed)
                data.writeInt(usage.hintUsed)
            }
            checkpoint.playback?.let { playback ->
                data.writeByte(1) // Playback revision.
                data.writeByte(if (playback.paused) 1 else 0)
                data.writeShort(0)
                data.writeInt(playback.speedPermille)
            }
            checkpoint.hintReceipt?.let { receipt ->
                val encoded = CompetitionMahjongHintReceiptCodec.encode(receipt)
                data.writeInt(encoded.size)
                data.write(encoded)
            }
            val singleHand = CompetitionMahjongCheckpointCodec.encode(
                CompetitionMahjongCheckpoint(config.handConfig(checkpoint.activeHandIndex), checkpoint.engineRecord),
            )
            data.writeInt(singleHand.size)
            data.write(singleHand)
        }
        val body = output.toByteArray()
        val frame = ByteBuffer.allocate(HEADER_BYTES + body.size + FOOTER_BYTES).order(ByteOrder.BIG_ENDIAN)
        require(frame.capacity() <= MAX_FILE_BYTES)
        val version = if (checkpoint.hintReceipt != null) 4 else if (checkpoint.playback != null) 3 else if (checkpoint.assistance != null) 2 else 1
        frame.putInt(MAGIC).put(version.toByte())
            .put(0.toByte()).put(0.toByte()).put(0.toByte()).putInt(body.size)
        frame.put(body)
        frame.putInt(crc(frame.array(), frame.position()))
        return frame.array()
    }

    fun decode(bytes: ByteArray, viewerPlayerId: Int): CompetitionMahjongMatchCheckpoint? {
        require(viewerPlayerId in 0..3)
        if (bytes.size !in HEADER_BYTES + FOOTER_BYTES..MAX_FILE_BYTES) return null
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (input.int != MAGIC) return null
        val version = input.get().toInt()
        if (version !in 1..4) return null
        repeat(3) { if (input.get().toInt() != 0) return null }
        if (input.int != bytes.size - HEADER_BYTES - FOOTER_BYTES ||
            crc(bytes, bytes.size - FOOTER_BYTES) != input.getInt(bytes.size - FOOTER_BYTES)) return null
        input.limit(bytes.size - FOOTER_BYTES)
        if (input.remaining() < 1) return null
        val idLength = input.get().toInt() and 255
        if (idLength != 36 || input.remaining() < idLength + 12) return null
        val id = ByteArray(idLength).also(input::get).toString(StandardCharsets.US_ASCII)
        val seed = input.long
        val viewer = input.get().toInt() and 255
        val level = input.get().toInt() and 255
        val active = input.get().toInt() and 255
        val count = input.get().toInt() and 255
        if (seed < 0 || viewer != viewerPlayerId || level !in 0..3 || active !in 0..15 || count !in 0..16 ||
            count !in active..active + 1 || input.remaining() < count * RESULT_BYTES + 4 +
                (if (version >= 2) 12 else 0) + (if (version >= 3) 8 else 0) +
                (if (version == 4) 4 + CompetitionMahjongHintReceiptCodec.MIN_BYTES else 0)) return null
        return try {
            val config = CompetitionMahjongCompetitionConfig(id, seed, viewer, level)
            val results = ArrayList<CompetitionMahjongHandResult>(count)
            repeat(count) { index ->
                if ((input.get().toInt() and 255) != index) return null
                val phase = when (input.get().toInt()) {
                    CompetitionMahjongRoundPhase.WON.code -> CompetitionMahjongRoundPhase.WON
                    CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW.code -> CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW
                    else -> return null
                }
                fun player(): Int? = (input.get().toInt() and 255).let { if (it == 255) null else it }
                val winner = player()
                val payer = player()
                val scores = CompetitionMahjongPlayerScores(
                    input.int.toLong(), input.int.toLong(), input.int.toLong(), input.int.toLong(),
                )
                val fan = input.int
                val total = input.int
                if (input.long != 0L) return null
                results += CompetitionMahjongHandResult(index, phase, scores, winner, payer, fan, total)
            }
            val assistance = if (version >= 2) {
                if (input.get().toInt() != 1) return null
                val difficultyCode = input.get().toInt()
                val difficulty = Difficulty.entries.firstOrNull { it.code == difficultyCode } ?: return null
                val pending = input.get().toInt()
                if (pending !in 0..1 || input.get().toInt() != 0) return null
                CompetitionMahjongAssistanceUsage(difficulty, input.int, input.int, pending == 1)
            } else null
            val playback = if (version >= 3) {
                if (input.get().toInt() != 1) return null
                val paused = input.get().toInt()
                if (paused !in 0..1 || input.short.toInt() != 0) return null
                val speedPermille = input.int
                if (speedPermille !in setOf(500, 1000, 2000, 4000)) return null
                CompetitionMahjongMatchPlayback(paused == 1, speedPermille / 1000f)
            } else null
            val receipt = if (version == 4) {
                val size = input.int
                if (size !in CompetitionMahjongHintReceiptCodec.MIN_BYTES..CompetitionMahjongHintReceiptCodec.MAX_BYTES ||
                    size > input.remaining() - 4) return null
                CompetitionMahjongHintReceiptCodec.decode(ByteArray(size).also(input::get)) ?: return null
            } else null
            val length = input.int
            if (length !in 1..CompetitionMahjongCheckpointCodec.MAX_FILE_BYTES || length != input.remaining()) return null
            val single = CompetitionMahjongCheckpointCodec.decode(
                ByteArray(length).also(input::get), config.handConfig(active).viewerSeat,
            ) ?: return null
            if (single.config != config.handConfig(active)) return null
            CompetitionMahjongMatchCheckpoint(config, CompetitionMahjongMatchLedger.restored(results), active,
                single.engineRecord, assistance, playback, receipt)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun crc(bytes: ByteArray, length: Int): Int = CRC32().apply { update(bytes, 0, length) }.value.toInt()
}
