package com.masterofchessstrategy.games.competition_mahjong.auto

import com.masterofchessstrategy.engine.CompetitionMahjongPublicView
import com.masterofchessstrategy.engine.CompetitionMahjongRoundPhase
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongHandResult
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchLedger
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.zip.CRC32

class CompetitionMahjongAutoHandRecord internal constructor(
    val result: CompetitionMahjongHandResult,
    baseline: ByteArray,
    terminal: ByteArray,
) {
    private val initial = baseline.copyOf()
    private val last = terminal.copyOf()
    val baseline: ByteArray get() = initial.copyOf()
    val terminal: ByteArray get() = last.copyOf()
    override fun toString(): String = "CompetitionMahjongAutoHandRecord(hand=${result.handIndex})"
}

/** Private complete series history, current hand and playback settings form one activity record. */
class CompetitionMahjongAutoCheckpoint internal constructor(
    val config: CompetitionMahjongAutoRunConfig,
    val settings: CompetitionMahjongAutoSettings,
    val seriesIndex: Int,
    val activeHandIndex: Int,
    completedHands: List<CompetitionMahjongAutoHandRecord>,
    baseline: ByteArray,
    engineRecord: ByteArray,
    openingRecord: ByteArray? = null,
    val elapsedMillis: Long = 0,
) {
    val completedHands: List<CompetitionMahjongAutoHandRecord> = Collections.unmodifiableList(completedHands.toList())
    private val first = baseline.copyOf()
    private val current = engineRecord.copyOf()
    private val opening = openingRecord?.copyOf()
    val baseline: ByteArray get() = first.copyOf()
    val engineRecord: ByteArray get() = current.copyOf()
    val openingRecord: ByteArray? get() = opening?.copyOf()
    val ledger: CompetitionMahjongMatchLedger = CompetitionMahjongMatchLedger.restored(this.completedHands.map { it.result })
    val eventsAfterOpening: Int

    init {
        require(seriesIndex in 0 until settings.maxSeries && activeHandIndex in 0..15 && elapsedMillis >= 0)
        require(ledger.completedHandCount in activeHandIndex..activeHandIndex + 1)
        val hand = config.handConfig(seriesIndex, activeHandIndex, opening != null)
        eventsAfterOpening = CompetitionMahjongAutoRecord.eventsAfter(first, current, hand)
        require(CompetitionMahjongAutoRecord.eventCount(first, hand) < CompetitionMahjongAutoRecord.MAX_EVENTS)
        opening?.let { CompetitionMahjongAutoRecord.eventCount(it, config.handConfig(0, 0, true)) }
        this.completedHands.forEachIndexed { index, record ->
            require(record.result.handIndex == index)
            require(CompetitionMahjongAutoRecord.eventsAfter(record.baseline, record.terminal,
                config.handConfig(seriesIndex, index, opening != null)) > 0)
        }
        opening?.let {
            val firstHand = this.completedHands.firstOrNull()?.baseline ?: first
            require(it.contentEquals(firstHand)) { "指定开局与赛事第一盘起点不一致" }
        }
        if (ledger.completedHandCount == activeHandIndex + 1) {
            val terminal = this.completedHands.last()
            require(terminal.baseline.contentEquals(first) && terminal.terminal.contentEquals(current))
        }
    }

    internal fun validateFrame(frame: CompetitionMahjongPublicView) {
        require(frame.publicFrameVersion == 2)
        val series = config.seriesConfig(seriesIndex, opening != null)
        CompetitionMahjongMatchLedger.validateFrame(series, activeHandIndex, frame)
        require(frame.isAutoTerminal() == (ledger.completedHandCount == activeHandIndex + 1))
        if (frame.isAutoTerminal()) require(ledger.results.last() ==
            CompetitionMahjongMatchLedger.captureTerminal(series, activeHandIndex, frame))
    }
    override fun toString(): String = "CompetitionMahjongAutoCheckpoint(runId=${config.runId}, series=$seriesIndex, hand=$activeHandIndex)"
}

/** Coordinator implements atomic activity + optional idempotent private-series archive storage. */
interface CompetitionMahjongAutoStore {
    /** Missing only returns null; wrong ownership, corruption and unsupported versions preserve data and throw. */
    suspend fun load(viewerPlayerId: Int): CompetitionMahjongAutoCheckpoint?
    /** Archive key is (runId, seriesIndex). Equal repeats do not duplicate; conflicting repeats fail atomically. */
    suspend fun save(checkpoint: CompetitionMahjongAutoCheckpoint, archive: CompetitionMahjongAutoArchive? = null)
}

class CompetitionMahjongAutoArchive internal constructor(val checkpoint: CompetitionMahjongAutoCheckpoint) {
    init { require(checkpoint.ledger.isComplete) }
    val key: String get() = "${checkpoint.config.runId}/${checkpoint.seriesIndex}"
    internal fun selected(policy: CompetitionMahjongPlayPolicy): Boolean {
        val settings = checkpoint.settings
        if (settings.archiveAll) return true
        if (settings.archiveFanThreshold?.let { limit -> checkpoint.ledger.results.any { it.totalFan >= limit } } == true) return true
        if (settings.archiveLongHandEvents?.let { limit -> checkpoint.completedHands.any { hand ->
            CompetitionMahjongAutoRecord.eventsAfter(hand.baseline, hand.terminal,
                checkpoint.config.handConfig(checkpoint.seriesIndex, hand.result.handIndex, checkpoint.openingRecord != null)) >= limit
        } } == true) return true
        if (settings.archiveMaster && checkpoint.config.playerBotLevels.any { level ->
            policy.options.any { it.botLevel == level && it.difficulty == Difficulty.MASTER }
        }) return true
        if (settings.archiveComeback) {
            val winners = checkpoint.ledger.finalStandings().filter { it.rank == 1 }.map { it.playerId }
            val running = LongArray(4)
            checkpoint.ledger.results.dropLast(1).forEach { result ->
                repeat(4) { running[it] += result.scores[it] }
                if (winners.any { player -> running[player] < running.maxOrNull()!! }) return true
            }
        }
        return false
    }
    /** Settings changes do not rewrite the already completed private record. */
    internal fun sameContent(other: CompetitionMahjongAutoArchive): Boolean =
        checkpoint.config == other.checkpoint.config && checkpoint.seriesIndex == other.checkpoint.seriesIndex &&
        checkpoint.elapsedMillis == other.checkpoint.elapsedMillis && checkpoint.ledger.results == other.checkpoint.ledger.results &&
        checkpoint.completedHands.zip(other.checkpoint.completedHands).all { (a, b) ->
            a.baseline.contentEquals(b.baseline) && a.terminal.contentEquals(b.terminal)
        } && checkpoint.openingRecord.contentEqualsNullable(other.checkpoint.openingRecord)
}

/** MCAU v1, BE; opaque MCMA/MOCM remain their original LE v1 formats. */
internal object CompetitionMahjongAutoCheckpointCodec {
    private const val MAGIC = 0x4d434155
    const val MAX_BYTES = 1_100_000
    fun encode(checkpoint: CompetitionMahjongAutoCheckpoint): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { out ->
            val config = checkpoint.config
            out.write(config.runId.toByteArray(Charsets.US_ASCII))
            out.writeLong(config.seed)
            out.writeLong(config.decisionSeed)
            out.writeByte(config.viewerPlayerId)
            config.playerBotLevels.forEach(out::writeByte)
            out.writeShort(checkpoint.seriesIndex)
            out.writeByte(checkpoint.activeHandIndex)
            out.writeLong(checkpoint.elapsedMillis)
            val settings = checkpoint.settings
            out.writeBoolean(settings.paused)
            out.writeFloat(settings.speed)
            out.writeLong(settings.stepDelayMillis)
            out.writeLong(settings.handBreakMillis)
            out.writeBoolean(settings.autoContinue)
            out.writeShort(settings.maxSeries)
            out.writeBoolean(settings.backgroundEnabled)
            out.writeLong(settings.backgroundDelayMillis)
            out.writeBoolean(settings.archiveAll)
            out.writeInt(settings.archiveFanThreshold ?: 0)
            out.writeInt(settings.archiveLongHandEvents ?: 0)
            out.writeBoolean(settings.archiveComeback)
            out.writeBoolean(settings.archiveMaster)
            fun record(bytes: ByteArray?) {
                out.writeInt(bytes?.size ?: 0)
                if (bytes != null) out.write(bytes)
            }
            record(checkpoint.openingRecord)
            record(checkpoint.baseline)
            record(checkpoint.engineRecord)
            out.writeByte(checkpoint.completedHands.size)
            checkpoint.completedHands.forEach { hand ->
                val result = hand.result
                out.writeByte(result.handIndex)
                out.writeByte(result.phase.code)
                out.writeByte(result.winnerPlayerId ?: 255)
                out.writeByte(result.payerPlayerId ?: 255)
                repeat(4) { out.writeInt(result.scores[it].toInt()) }
                out.writeInt(result.fanWithoutFlowers)
                out.writeInt(result.totalFan)
                record(hand.baseline)
                record(hand.terminal)
            }
        }
        val payload = body.toByteArray()
        require(payload.size + 16 <= MAX_BYTES)
        return ByteBuffer.allocate(payload.size + 16).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(MAGIC).put(1).put(0).putShort(0).putInt(payload.size).put(payload)
            putInt(CRC32().apply { update(array(), 0, position()) }.value.toInt())
        }.array()
    }

    fun decode(bytes: ByteArray, viewerPlayerId: Int): CompetitionMahjongAutoCheckpoint? {
        require(viewerPlayerId in 0..3)
        if (bytes.size !in 16..MAX_BYTES) return null
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (input.int != MAGIC || input.get().toInt() != 1 || input.get().toInt() != 0 || input.short.toInt() != 0 ||
            input.int != bytes.size - 16 || input.getInt(bytes.size - 4) != CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt()) return null
        input.limit(bytes.size - 4)
        return try {
            fun byte(): Int = input.get().toInt() and 255
            fun bool(): Boolean = byte().let { require(it in 0..1); it == 1 }
            fun record(nullable: Boolean = false): ByteArray? {
                val size = input.int
                if (nullable && size == 0) return null
                require(size in 187..CompetitionMahjongAutoRecord.MAX_BYTES && size <= input.remaining())
                return ByteArray(size).also(input::get)
            }
            val id = ByteArray(36).also(input::get).toString(Charsets.US_ASCII)
            val seed = input.long
            val decision = input.long
            val viewer = byte()
            require(viewer == viewerPlayerId)
            val config = CompetitionMahjongAutoRunConfig(id, seed, viewer, List(4) { byte() }, decision)
            val series = input.short.toInt() and 65535
            val hand = byte()
            val elapsed = input.long
            val settings = CompetitionMahjongAutoSettings(bool(), input.float, input.long, input.long, bool(),
                input.short.toInt() and 65535, bool(), input.long, bool(), input.int.takeUnless { it == 0 },
                input.int.takeUnless { it == 0 }, bool(), bool())
            val opening = record(true)
            val baseline = record()!!
            val current = record()!!
            val count = byte()
            require(count in 0..16)
            val history = List(count) {
                val index = byte()
                val phase = when (byte()) {
                    CompetitionMahjongRoundPhase.WON.code -> CompetitionMahjongRoundPhase.WON
                    CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW.code -> CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW
                    else -> error("自动记分阶段无效")
                }
                val winner = byte().takeUnless { it == 255 }
                val payer = byte().takeUnless { it == 255 }
                val scores = com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongPlayerScores(
                    input.int.toLong(), input.int.toLong(), input.int.toLong(), input.int.toLong())
                val result = CompetitionMahjongHandResult(index, phase, scores, winner, payer, input.int, input.int)
                CompetitionMahjongAutoHandRecord(result, record()!!, record()!!)
            }
            require(!input.hasRemaining())
            CompetitionMahjongAutoCheckpoint(config, settings, series, hand, history, baseline, current, opening, elapsed)
        } catch (_: Exception) { null }
    }
}

internal fun CompetitionMahjongPublicView.isAutoTerminal(): Boolean =
    phase == CompetitionMahjongRoundPhase.WON || phase == CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW
private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    if (this == null || other == null) this == null && other == null else contentEquals(other)
