package com.masterofchessstrategy.games.competition_mahjong.auto

import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchLedger
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchSeating
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongUnlockProgress
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

internal fun autoConfig(viewer: Int = 0, levels: List<Int> = List(4) { 0 }, id: String = "81000000-0000-4000-8000-000000000001") =
    CompetitionMahjongAutoRunConfig(id, 73, viewer, levels, 109)

internal fun autoPolicy(full: Boolean = true) = CompetitionMahjongPlayPolicy.fromProgress(
    CompetitionMahjongUnlockProgress(true, if (full) 10 else 0, if (full) 15 else 0, if (full) 20 else 0),
    Difficulty.entries.associateWith { it.code },
)

/** Mechanical envelope only: deliberately synthetic wall/events, never claimed as Native legal replay. */
internal fun autoRecord(config: CompetitionMahjongAutoConfig, count: Int = 0, origin: Int = config.seed.toInt(), eventByte: Int = 7): ByteArray {
    val out = ByteBuffer.allocate(187 + 4 * count).order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(0x414d434d).put(1).put(config.viewerSeat.toByte())
    config.botLevels.forEach { out.put(it.toByte()) }
    out.putShort(0).putLong(config.decisionSeed).putInt(out.capacity() - 28)
    out.putInt(0x4d434f4d).put(1).put(config.dealerSeat.toByte()).put(config.roundWind.toByte())
    repeat(144) { out.put((origin + it).toByte()) }
    out.putInt(count)
    repeat(count * 4) { out.put((eventByte + it).toByte()) }
    fixAutoRecordChecksums(out.array())
    return out.array()
}

internal fun fixAutoRecordChecksums(bytes: ByteArray) {
    var hash = 2166136261u
    for (index in 24 until bytes.size - 8) hash = (hash xor bytes[index].toUByte().toUInt()) * 16777619u
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size - 8, hash.toInt())
        .putInt(bytes.size - 4, CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt())
}

internal fun fixAutoCheckpointCrc(bytes: ByteArray) {
    ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(bytes.size - 4,
        CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt())
}

internal fun autoFrame(config: CompetitionMahjongAutoConfig, terminal: Boolean = false, winner: Int? = null): CompetitionMahjongPublicView {
    val scores = MutableList(4) { 0 }
    winner?.let { seat -> repeat(4) { scores[it] = if (it == seat) 48 else -16 } }
    return CompetitionMahjongPublicView(
        config.viewerSeat, if (!terminal) CompetitionMahjongRoundPhase.DISCARD else if (winner == null)
            CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW else CompetitionMahjongRoundPhase.WON,
        config.dealerSeat, config.roundWind, config.dealerSeat, if (terminal) 0 else 90,
        null, null, winner, null, (config.dealerSeat + 1) % 4, scores,
        CompetitionMahjongFanView(0, winner != null, if (winner == null) 0 else 8, if (winner == null) 0 else 8,
            winner != null, if (winner == null) emptyList() else listOf(CompetitionMahjongFanEntry(48, "双暗杠", 8, 1))),
        (0..3).map { CompetitionMahjongSeatView(it, 13, false, emptyList(), emptyList(), emptyList(), emptyList()) },
        publicFrameVersion = 2,
    )
}

internal fun autoCheckpoint(config: CompetitionMahjongAutoRunConfig = autoConfig(), settings: CompetitionMahjongAutoSettings = CompetitionMahjongAutoSettings(),
    series: Int = 0, hand: Int = 0, events: Int = 0, completed: Boolean = false, opening: ByteArray? = null,
    winnerPlayer: (Int) -> Int? = { null }, elapsed: Long = 0): CompetitionMahjongAutoCheckpoint {
    val history = (0 until hand + if (completed) 1 else 0).map { index ->
        val native = config.handConfig(series, index, opening != null)
        val first = if (index == 0 && opening != null) opening else autoRecord(native)
        val winner = winnerPlayer(index)?.let { CompetitionMahjongMatchSeating.forHand(index).seatOfPlayer(it) }
        val view = autoFrame(native, true, winner)
        CompetitionMahjongAutoHandRecord(CompetitionMahjongMatchLedger.captureTerminal(config.seriesConfig(series, opening != null), index, view),
            first, autoRecord(native, 3))
    }
    val native = config.handConfig(series, hand, opening != null)
    val first = if (hand == 0 && opening != null) opening else autoRecord(native)
    return CompetitionMahjongAutoCheckpoint(config, settings, series, hand, history, first,
        if (completed) history.last().terminal else autoRecord(native, events), opening, elapsed)
}

internal class AutoMemoryStore : CompetitionMahjongAutoStore {
    var bytes: ByteArray? = null
    var failNext = false
    var failAfterCommit = false
    var writes = 0
    val archives = linkedMapOf<String, CompetitionMahjongAutoArchive>()
    override suspend fun load(viewerPlayerId: Int): CompetitionMahjongAutoCheckpoint? =
        bytes?.let { CompetitionMahjongAutoCheckpointCodec.decode(it, viewerPlayerId) ?: throw IOException("活动损坏或归属不一致") }
    override suspend fun save(checkpoint: CompetitionMahjongAutoCheckpoint, archive: CompetitionMahjongAutoArchive?) {
        if (failNext) { failNext = false; throw IOException("保存失败") }
        archive?.let { incoming -> archives[incoming.key]?.let { require(it.sameContent(incoming)) } }
        val encoded = CompetitionMahjongAutoCheckpointCodec.encode(checkpoint)
        writes++
        bytes = encoded
        archive?.let { archives.putIfAbsent(it.key, it) }
        if (failAfterCommit) { failAfterCommit = false; throw IOException("提交回执失败") }
    }
}

/** Pure transaction double. Terminal after three synthetic events; rules are tested by real Native tests. */
internal class AutoSessionDouble(override val config: CompetitionMahjongAutoConfig) : CompetitionMahjongAutoSession {
    var count = 0
    var advanceCalls = 0
    var closed = false
    var failSnapshot = false
    var rejectRestore: (Int) -> Boolean = { false }
    private var record = autoRecord(config)
    override fun snapshot(): CompetitionMahjongPublicView {
        check(!failSnapshot) { "公开帧读取失败" }
        return autoFrame(config, count == 3)
    }
    override fun advance(maxActions: Int): CompetitionMahjongAutoAdvance {
        check(!closed && maxActions == 1 && count < 3)
        advanceCalls++
        count++
        record = autoRecord(config, count)
        return CompetitionMahjongAutoAdvance(1, count == 3)
    }
    override fun undo(): Boolean {
        check(!closed)
        if (count == 0) return false
        count--
        record = autoRecord(config, count)
        return true
    }
    override fun serialize() = record.copyOf()
    override fun restore(data: ByteArray): RestoreResult {
        val restored = CompetitionMahjongAutoRecord.eventCount(data, config)
        if (restored > 3 || rejectRestore(restored)) return RestoreResult.Rejected(EngineError.CORRUPTED_DATA)
        count = restored
        record = data.copyOf()
        return RestoreResult.Restored
    }
    override fun close() { closed = true }
}

internal class AutoHarness(
    val config: CompetitionMahjongAutoRunConfig = autoConfig(),
    val store: AutoMemoryStore = AutoMemoryStore(),
    settings: CompetitionMahjongAutoSettings = CompetitionMahjongAutoSettings(),
    policy: CompetitionMahjongPlayPolicy? = autoPolicy(),
    opening: ByteArray? = null,
) {
    val sessions = mutableListOf<AutoSessionDouble>()
    var rejectRestore: (Int) -> Boolean = { false }
    var failNextSnapshot = false
    var time = 0L
    val controller = CompetitionMahjongAutoController(config, store, policy, settings, opening,
        sessionFactory = { AutoSessionDouble(it).also { session ->
            session.rejectRestore = rejectRestore
            session.failSnapshot = failNextSnapshot
            failNextSnapshot = false
            sessions += session
        } },
        dispatcher = Dispatchers.Unconfined, clockMillis = { time })
    val active: AutoSessionDouble get() = sessions.last { !it.closed }
    suspend fun step() = controller.state.value.let { controller.step(it.seriesIndex, it.handIndex) }
}
