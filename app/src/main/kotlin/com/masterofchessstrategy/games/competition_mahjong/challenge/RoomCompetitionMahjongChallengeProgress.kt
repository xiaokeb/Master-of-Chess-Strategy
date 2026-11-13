package com.masterofchessstrategy.games.competition_mahjong.challenge

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.withTransaction
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongStorageEpochGate
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "competition_mahjong_challenge_progress", primaryKeys = ["singletonId"])
internal data class CompetitionMahjongChallengeProgressRow(val singletonId: Int, val payload: ByteArray)

@Dao
internal interface CompetitionMahjongChallengeProgressDao {
    @Query("SELECT singletonId, length(payload) AS byteCount FROM competition_mahjong_challenge_progress ORDER BY singletonId LIMIT 2")
    suspend fun metadata(): List<Metadata>

    @Query("SELECT payload FROM competition_mahjong_challenge_progress WHERE singletonId = 0 AND length(payload) BETWEEN 1 AND :maxBytes")
    suspend fun payload(maxBytes: Int): ByteArray?

    @Query("SELECT payload FROM competition_mahjong_challenge_progress WHERE singletonId = 0 AND length(payload) BETWEEN 1 AND :maxBytes AND (SELECT count(*) FROM competition_mahjong_challenge_progress) = 1")
    fun observePayload(maxBytes: Int): Flow<ByteArray?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: CompetitionMahjongChallengeProgressRow)

    @Query("DELETE FROM competition_mahjong_challenge_progress")
    suspend fun deleteAll()

    data class Metadata(val singletonId: Int, val byteCount: Long)

    companion object {
        const val CREATE_SQL = "CREATE TABLE IF NOT EXISTS competition_mahjong_challenge_progress (" +
            "singletonId INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(singletonId))"
    }
}

/** One transaction commits unlocks and the completion set used by the reward summary. */
internal class RoomCompetitionMahjongChallengeProgress(
    private val database: MocsDatabase,
    private val gate: CompetitionMahjongStorageEpochGate = CompetitionMahjongStorageEpochGate.forDatabase(database),
) : CompetitionMahjongChallengeProgressRepository {
    private val owner = gate.captureOwner()
    private val dao = database.competitionMahjongChallengeProgressDao()

    override suspend fun load() = access { readCompetitionChallengeProgress(dao) ?: CompetitionMahjongChallengeProgress() }

    override suspend fun select(id: String, mode: CompetitionMahjongChallengeMode) = update { it.select(id, mode) }
    override suspend fun selectDaily(date: LocalDate, id: String) = update { it.selectDaily(date, id) }
    override suspend fun complete(id: String) = update { it.complete(id) }

    private suspend fun update(transform: (CompetitionMahjongChallengeProgress) -> CompetitionMahjongChallengeProgress) = access {
        val previous = readCompetitionChallengeProgress(dao) ?: CompetitionMahjongChallengeProgress()
        val next = transform(previous)
        val payload = CompetitionMahjongChallengeProgressCodec.encode(next)
        if (!payload.contentEquals(CompetitionMahjongChallengeProgressCodec.encode(previous))) {
            dao.save(CompetitionMahjongChallengeProgressRow(0, payload))
        }
        next
    }

    private suspend fun <T> access(block: suspend () -> T): T = gate.access(owner) { database.withTransaction { block() } }
}

internal suspend fun readCompetitionChallengeProgress(dao: CompetitionMahjongChallengeProgressDao): CompetitionMahjongChallengeProgress? {
    val rows = dao.metadata()
    if (rows.isEmpty()) return null
    val maxBytes = CompetitionMahjongChallengeProgressCodec.MAX_BYTES
    val row = rows.singleOrNull()?.takeIf { it.singletonId == 0 && it.byteCount in 1L..maxBytes.toLong() }
        ?: throw IOException("国标麻将牌型训练进度无效，原数据已保留")
    return dao.payload(maxBytes)?.takeIf { it.size.toLong() == row.byteCount }
        ?.let(CompetitionMahjongChallengeProgressCodec::decode)
        ?: throw IOException("国标麻将牌型训练进度不兼容，原数据已保留")
}
