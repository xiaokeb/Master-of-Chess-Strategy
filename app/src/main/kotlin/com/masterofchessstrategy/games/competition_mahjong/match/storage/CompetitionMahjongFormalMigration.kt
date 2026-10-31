package com.masterofchessstrategy.games.competition_mahjong.match.storage

import com.masterofchessstrategy.games.competition_mahjong.history.*
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpointCodec
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.games.competition_mahjong.statistics.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

/** Explicit source snapshot supplied under the coordinator's legacy-writer barrier. No source is modified. */
class CompetitionMahjongFormalMigrationSource(
    val viewerPlayerId: Int,
    activeMcmt: ByteArray?,
    statisticsMcst: ByteArray?,
    historyMchr: ByteArray?,
    val materializeActiveCompletion: Boolean,
    val activeSettledAtEpochMillis: Long,
    confirmedDeletedMatchIds: Set<String> = emptySet(),
) {
    private val active = activeMcmt?.also { require(it.size in 1..1_100_000) }?.copyOf()
    private val statistics = statisticsMcst?.also {
        require(it.size in 1..CompetitionMahjongStatisticsCodec.MAX_BYTES)
    }?.copyOf()
    private val history = historyMchr?.also {
        require(it.size in 1..CompetitionMahjongMatchHistoryCodec.MAX_BYTES)
    }?.copyOf()
    val confirmedDeletedMatchIds: Set<String> = Collections.unmodifiableSet(confirmedDeletedMatchIds.toSortedSet())

    init {
        require(viewerPlayerId in 0..3 && activeSettledAtEpochMillis >= 0)
        require(this.confirmedDeletedMatchIds.size <= CompetitionMahjongFormalSnapshot.MAX_DELETIONS)
        require(this.confirmedDeletedMatchIds.all { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) })
    }

    internal val activeBytes: ByteArray? get() = active?.copyOf()
    internal val statisticsBytes: ByteArray? get() = statistics?.copyOf()
    internal val historyBytes: ByteArray? get() = history?.copyOf()

    /** Identity of the exact source and explicit choices, not authentication of their provenance. */
    val sha256: String get() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { data ->
            data.writeInt(1)
            data.writeByte(viewerPlayerId)
            data.writeBoolean(materializeActiveCompletion)
            data.writeLong(activeSettledAtEpochMillis)
            for (section in listOf(active, statistics, history)) {
                data.writeInt(section?.size ?: -1)
                section?.let(data::write)
            }
            data.writeInt(confirmedDeletedMatchIds.size)
            confirmedDeletedMatchIds.forEach { data.write(it.toByteArray(Charsets.US_ASCII)) }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
    }
}

data class CompetitionMahjongFormalMigrationPreview(
    val sourceSha256: String,
    val activeMatchId: String?,
    val completedHandCount: Int?,
    val statisticsCount: Int,
    val visibleHistoryCount: Int,
    val confirmedDeletionCount: Int,
    val materializedActiveCompletion: Boolean,
    val retractedActiveResult: Boolean,
)

data class CompetitionMahjongFormalMigrationResult(
    val preview: CompetitionMahjongFormalMigrationPreview,
    val wasInstalled: Boolean,
)

/** Preview and commit are separate. Commit revalidates the source identity and target under its lock. */
class CompetitionMahjongFormalMigration internal constructor(
    private val storage: CompetitionMahjongFormalStorage,
    private val viewerPlayerId: Int,
    private val playPolicy: CompetitionMahjongPlayPolicy,
    private val admitCheckpoint: suspend (CompetitionMahjongMatchCheckpoint) -> Unit,
    private val dispatcher: CoroutineDispatcher,
) {
    suspend fun preview(readSource: suspend () -> CompetitionMahjongFormalMigrationSource):
        CompetitionMahjongFormalMigrationPreview = withContext(dispatcher) { prepare(readSource()).preview }

    /** The caller must keep legacy writers stopped until routing has switched to the new repository. */
    suspend fun migrate(expectedSourceSha256: String,
                        readSource: suspend () -> CompetitionMahjongFormalMigrationSource):
        CompetitionMahjongFormalMigrationResult = withContext(dispatcher) {
        storage.mutex.withLock {
            val source = readSource()
            require(source.sha256 == expectedSourceSha256) { "迁移来源或选择已变化，请重新预览" }
            val plan = prepare(source)
            val previous = storage.read()
            val encoded = CompetitionMahjongFormalSnapshotCodec.encode(plan.snapshot)
            if (previous != null) require(previous.contentEquals(encoded)) { "统一仓库已有不同数据，未执行迁移覆盖" }
            require(readSource().sha256 == expectedSourceSha256) { "迁移来源在准入期间变化，原数据已保留" }
            if (previous == null) storage.replace(encoded)
            CompetitionMahjongFormalMigrationResult(plan.preview, wasInstalled = previous == null)
        }
    }

    private data class Plan(val snapshot: CompetitionMahjongFormalSnapshot,
                            val preview: CompetitionMahjongFormalMigrationPreview)

    private suspend fun prepare(source: CompetitionMahjongFormalMigrationSource): Plan {
        require(source.viewerPlayerId == viewerPlayerId) { "迁移观察玩家归属不一致" }
        val active = source.activeBytes?.let {
            requireNotNull(CompetitionMahjongMatchCheckpointCodec.decode(it, viewerPlayerId)) { "旧MCMT损坏或版本不兼容" }
        }
        var statistics = source.statisticsBytes?.let {
            requireNotNull(CompetitionMahjongStatisticsCodec.decode(it, viewerPlayerId)) { "旧统计损坏或版本不兼容" }
        } ?: CompetitionMahjongStatistics.empty()
        var history = source.historyBytes?.let {
            requireNotNull(CompetitionMahjongMatchHistoryCodec.decode(it, viewerPlayerId)) { "旧成绩历史损坏或版本不兼容" }
        } ?: CompetitionMahjongMatchHistory.empty()

        // Admit the actual active Native record before any repair or derivation.
        active?.let {
            playPolicy.requireAuthorized(it.config.botLevel, it.assistance)
            admitCheckpoint(it)
        }
        require(history.entries.none { it.matchId in source.confirmedDeletedMatchIds }) { "删除证据与可见历史冲突" }
        var materialized = false
        var retracted = false
        active?.let { checkpoint ->
            val id = checkpoint.config.matchId
            if (!checkpoint.ledger.isComplete) {
                retracted = statistics.outcomes.any { it.matchId == id } || history.entries.any { it.matchId == id }
                if (retracted) {
                    require(checkpoint.activeHandIndex == 15 && checkpoint.ledger.completedHandCount == 15) {
                        "旧成绩只能随当前赛事末盘撤回，不可用早期档清除已结算结果"
                    }
                    history.entries.firstOrNull { it.matchId == id }?.let {
                        require(it.hands.take(15) == checkpoint.ledger.results) { "末盘撤回前十五盘与旧成绩单不一致" }
                    }
                }
                statistics = CompetitionMahjongStatistics.from(statistics.outcomes.filterNot { it.matchId == id })
                history = CompetitionMahjongMatchHistory.from(history.entries.filterNot { it.matchId == id })
            } else if (source.materializeActiveCompletion) {
                val existing = statistics.outcomes.firstOrNull { it.matchId == id }
                val existingSheet = history.entries.firstOrNull { it.matchId == id }
                val time = existing?.settledAtEpochMillis ?: existingSheet?.settledAtEpochMillis ?: source.activeSettledAtEpochMillis
                val outcome = CompetitionMahjongSeriesOutcome.from(checkpoint, playPolicy, time)
                val sheet = CompetitionMahjongMatchHistoryEntry.from(checkpoint, playPolicy, time)
                existing?.let { require(it == outcome) { "当前赛事与旧统计冲突" } }
                existingSheet?.let {
                    require(it.sameResult(sheet) && it.settledAtEpochMillis == time) { "当前赛事与旧成绩单冲突" }
                }
                // A recorded result with no sheet has unknown deletion status; explicit evidence is required.
                require(existing == null || existingSheet != null || id in source.confirmedDeletedMatchIds) {
                    "当前赛事已有统计但历史缺失，不能推定删除或补建"
                }
                materialized = existing == null || (existingSheet == null && id !in source.confirmedDeletedMatchIds)
                if (existing == null) statistics = CompetitionMahjongStatistics.from(statistics.outcomes + outcome)
                if (existingSheet == null && id !in source.confirmedDeletedMatchIds)
                    history = CompetitionMahjongMatchHistory.from(history.entries + sheet)
            }
        }

        val sheetIds = history.entries.map { it.matchId }.toSet()
        require(statistics.outcomes.all { it.matchId in sheetIds || it.matchId in source.confirmedDeletedMatchIds }) {
            "旧统计存在缺少逐盘历史且无删除证据的赛事，未执行迁移"
        }
        val snapshot = CompetitionMahjongFormalSnapshot(viewerPlayerId, active, statistics, history,
            source.confirmedDeletedMatchIds)
        // Check all capacities and canonical inner formats before a preview is offered.
        CompetitionMahjongFormalSnapshotCodec.encode(snapshot)
        return Plan(snapshot, CompetitionMahjongFormalMigrationPreview(source.sha256, active?.config?.matchId,
            active?.ledger?.completedHandCount, statistics.seriesCount, history.entries.size,
            source.confirmedDeletedMatchIds.size, materialized, retracted))
    }
}
