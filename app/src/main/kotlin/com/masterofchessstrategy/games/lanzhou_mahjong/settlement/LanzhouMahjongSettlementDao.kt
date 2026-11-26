package com.masterofchessstrategy.games.lanzhou_mahjong.settlement

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "lanzhou_mahjong_formal_creations")
internal data class LanzhouMahjongFormalCreationRow(
    @PrimaryKey val sessionId: String,
    val createdAt: Long,
    val initialRecord: ByteArray,
    val creationSha256: String,
)

@Entity(
    tableName = "lanzhou_mahjong_terminal_receipts",
    foreignKeys = [ForeignKey(
        entity = LanzhouMahjongFormalCreationRow::class,
        parentColumns = ["sessionId"],
        childColumns = ["sessionId"],
    )],
)
internal data class LanzhouMahjongTerminalReceiptRow(
    @PrimaryKey val sessionId: String,
    val capturedAt: Long,
    val terminalRecord: ByteArray,
    val outcome: String,
    val winnerSeat: Int?,
    val payerSeat: Int?,
    val seat0Score: Long,
    val seat1Score: Long,
    val seat2Score: Long,
    val seat3Score: Long,
    @ColumnInfo(defaultValue = "0") val archiveMark: Int = 0,
)

internal data class LanzhouMahjongWinSource(
    val sessionId: String,
    val initialRecord: ByteArray,
    val winnerSeat: Int,
)

internal data class LanzhouMahjongSettlementSummaryRow(
    val outcome: String,
    val winnerSeat: Int?,
    val payerSeat: Int?,
    val seat0Score: Long,
    val seat1Score: Long,
    val seat2Score: Long,
    val seat3Score: Long,
)

internal data class LanzhouMahjongArchiveRow(
    val sessionId: String,
    val capturedAt: Long,
    val outcome: String,
    val initialRecord: ByteArray,
    val archiveMark: Int,
)

@Dao
internal interface LanzhouMahjongSettlementDao {
    @Query("SELECT * FROM lanzhou_mahjong_formal_creations WHERE sessionId = :sessionId")
    suspend fun creation(sessionId: String): LanzhouMahjongFormalCreationRow?

    @Query("SELECT * FROM lanzhou_mahjong_terminal_receipts WHERE sessionId = :sessionId")
    suspend fun receipt(sessionId: String): LanzhouMahjongTerminalReceiptRow?

    @Query("SELECT * FROM lanzhou_mahjong_formal_creations ORDER BY sessionId")
    suspend fun readCreations(): List<LanzhouMahjongFormalCreationRow>

    @Query("SELECT * FROM lanzhou_mahjong_terminal_receipts ORDER BY sessionId")
    suspend fun readReceipts(): List<LanzhouMahjongTerminalReceiptRow>

    @Query(
        "SELECT c.sessionId, c.initialRecord, r.winnerSeat FROM lanzhou_mahjong_formal_creations c " +
            "JOIN lanzhou_mahjong_terminal_receipts r ON r.sessionId = c.sessionId " +
            "WHERE r.outcome = 'WIN' AND r.winnerSeat BETWEEN 0 AND 3",
    )
    suspend fun qualifiedWinSources(): List<LanzhouMahjongWinSource>

    @Query(
        "SELECT r.outcome, r.winnerSeat, r.payerSeat, r.seat0Score, r.seat1Score, r.seat2Score, r.seat3Score " +
            "FROM lanzhou_mahjong_terminal_receipts r JOIN lanzhou_mahjong_formal_creations c ON c.sessionId = r.sessionId",
    )
    suspend fun settlementSummaries(): List<LanzhouMahjongSettlementSummaryRow>

    @Query(
        "SELECT r.sessionId, r.capturedAt, r.outcome, c.initialRecord, r.archiveMark " +
            "FROM lanzhou_mahjong_terminal_receipts r JOIN lanzhou_mahjong_formal_creations c ON c.sessionId = r.sessionId " +
            "WHERE r.archiveMark != 2 AND (:favoritesOnly = 0 OR r.archiveMark = 1) " +
            "ORDER BY r.capturedAt DESC, r.sessionId ASC LIMIT :limit OFFSET :offset",
    )
    suspend fun archivePage(limit: Int, offset: Long, favoritesOnly: Boolean): List<LanzhouMahjongArchiveRow>

    @Query("UPDATE lanzhou_mahjong_terminal_receipts SET archiveMark = :mark WHERE sessionId = :id AND archiveMark != 2")
    suspend fun setArchiveMark(id: String, mark: Int): Int

    @Query("DELETE FROM lanzhou_mahjong_terminal_receipts")
    suspend fun deleteReceipts()

    @Query("DELETE FROM lanzhou_mahjong_formal_creations")
    suspend fun deleteCreations()

    @Insert
    suspend fun insertCreation(row: LanzhouMahjongFormalCreationRow)

    @Insert
    suspend fun insertReceipt(row: LanzhouMahjongTerminalReceiptRow)
}
