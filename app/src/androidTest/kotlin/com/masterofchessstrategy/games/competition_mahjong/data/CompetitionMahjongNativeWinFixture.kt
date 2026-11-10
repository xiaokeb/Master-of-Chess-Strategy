package com.masterofchessstrategy.games.competition_mahjong.data

import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.data.GameBackupRegistry
import com.masterofchessstrategy.data.LocalDataBackupCodec
import com.masterofchessstrategy.data.LocalDataSnapshot
import com.masterofchessstrategy.engine.NativeCompetitionMahjongSession
import com.masterofchessstrategy.games.competition_mahjong.history.CompetitionMahjongMatchHistory
import com.masterofchessstrategy.games.competition_mahjong.history.CompetitionMahjongMatchHistoryEntry
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningBackupAdapter
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningProof
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningProofCatalog
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningProofCodec
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningProofRequest
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongBotStrategy
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongCompetitionConfig
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchLedger
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongAssistanceUsage
import com.masterofchessstrategy.games.competition_mahjong.match.policy.competitionMahjongProductionStrategies
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongArchivedSeries
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshot
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshotCodec
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongPrivateSeriesReplay
import com.masterofchessstrategy.games.competition_mahjong.match.storage.toOutcome
import com.masterofchessstrategy.games.competition_mahjong.statistics.CompetitionMahjongStatistics
import java.util.UUID

/** Generated from actual Native rounds for test APKs. Production admission still replays every proof. */
internal object CompetitionMahjongNativeWinFixture {
    private val matches by lazy {
        val inputs = InstrumentationRegistry.getInstrumentation().context.assets
            .open("competition-mahjong/native-wins-v1.tsv").bufferedReader().use { reader ->
                reader.readLines().map { it.split('\t') }
            }
        check(inputs.all { it.size == 22 })
        check(inputs.map { it[0].toInt() } == List(10) { 0 } + List(15) { 1 } + List(20) { 2 })
        check(inputs.map { it[1].toLong() }.distinct().size == 45)
        inputs.mapIndexed { index, fields -> lazy { replayFixture(index, fields) } }
    }

    fun records(wins: Int, activeLevel: Int? = null): List<GameBackupRecord> {
        require(wins in 0..45)
        return records(matches.take(wins).map { it.value }, activeLevel)
    }

    /** Reuse replayable seeds to exercise storage volume without creating extra qualified wins. */
    fun volumeBackup(series: Int): ByteArray {
        require(series in matches.size..500)
        val originals = matches.map { it.value }
        val selected = List(series) { index ->
            val original = originals[index % originals.size]
            if (index < originals.size) original else {
                val (proof, entry) = original
                val config = proof.config.copy(matchId = UUID(3, index.toLong()).toString())
                CompetitionMahjongArchivedSeries(config, List(16, proof::decisions)) to
                    CompetitionMahjongMatchHistoryEntry(config.matchId, entry.viewerPlayerId, entry.difficulty,
                        entry.botLevel, index.toLong() + 1, false, entry.hands)
            }
        }
        return encodeBackup(records(selected, activeLevel = 3))
    }

    private fun records(
        selected: List<Pair<CompetitionMahjongArchivedSeries, CompetitionMahjongMatchHistoryEntry>>,
        activeLevel: Int?,
    ): List<GameBackupRecord> {
        val history = selected.map { it.second }
        val active = activeLevel?.let { initial(it) }
        val snapshot = CompetitionMahjongFormalSnapshot(0, active,
            CompetitionMahjongStatistics.from(history.map { it.toOutcome() }),
            CompetitionMahjongMatchHistory.from(history), settledHandRecords = active?.let { emptyList() },
            archivedSeries = selected.map { it.first })
        val formal = CompetitionMahjongFormalBackupWire.encode(0, CompetitionMahjongFormalSnapshotCodec.encode(snapshot))
        val learning = CompetitionMahjongLearningProofCatalog.chapterIds.foldIndexed(CompetitionMahjongLearningProof()) {
            index, proof, chapter ->
            proof.confirm(CompetitionMahjongLearningProofRequest(UUID(1, index.toLong()).toString(), chapter,
                CompetitionMahjongLearningProofCatalog.cardIds(chapter), index.toLong()))
        }
        return listOf(formal, CompetitionMahjongLearningBackupAdapter().encode(CompetitionMahjongLearningProofCodec.encode(learning)))
    }

    fun backup(wins: Int, activeLevel: Int? = null): ByteArray =
        encodeBackup(records(wins, activeLevel))

    private fun encodeBackup(records: List<GameBackupRecord>): ByteArray =
        LocalDataBackupCodec.encode(LocalDataSnapshot(1, gameExtensions = records),
            GameBackupRegistry(listOf(CompetitionMahjongFormalBackupAdapter(competitionMahjongProductionStrategies),
                CompetitionMahjongLearningBackupAdapter())))

    private fun replayFixture(index: Int, fields: List<String>): Pair<CompetitionMahjongArchivedSeries, CompetitionMahjongMatchHistoryEntry> {
        val level = fields[0].toInt()
        val seed = fields[1].toLong()
        val config = CompetitionMahjongCompetitionConfig(UUID(0, seed).toString(), seed, 0, level,
            CompetitionMahjongBotStrategy.POSITION_SEEDED_V1)
        val proof = CompetitionMahjongArchivedSeries(config, fields.drop(6).map { hex ->
            check(hex.length % 4 == 0)
            ShortArray(hex.length / 4) { position -> hex.substring(position * 4, position * 4 + 4).toInt(16).toShort() }
        })
        val ledger = CompetitionMahjongPrivateSeriesReplay.replayArchive(proof)
        check((0..3).map { ledger.cumulativeScores[it] } == fields.subList(2, 6).map(String::toLong)) {
            "Native fixture score changed for seed $seed"
        }
        val difficulty = competitionMahjongProductionStrategies.entries.single { it.value == level }.key
        val entry = CompetitionMahjongMatchHistoryEntry(config.matchId, 0, difficulty, level,
            index.toLong() + 1, false, ledger.results)
        check(entry.viewerStanding.rank == 1 && entry.viewerStanding.standardPointsDenominator == 1)
        return proof to entry
    }

    private fun initial(level: Int): CompetitionMahjongMatchCheckpoint {
        val seed = 400000L + level
        val config = CompetitionMahjongCompetitionConfig(UUID(2, seed).toString(), seed, 0, level,
            CompetitionMahjongBotStrategy.POSITION_SEEDED_V1)
        val difficulty = competitionMahjongProductionStrategies.entries.single { it.value == level }.key
        return NativeCompetitionMahjongSession(seed, 0, 0, 0).use {
            CompetitionMahjongMatchCheckpoint(config, CompetitionMahjongMatchLedger.empty(), 0, it.serialize(),
                CompetitionMahjongAssistanceUsage(difficulty))
        }
    }
}
