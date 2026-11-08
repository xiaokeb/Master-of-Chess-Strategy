package com.masterofchessstrategy.games.competition_mahjong

import androidx.compose.runtime.Immutable
import java.util.Collections

private fun <T> frozenList(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

@Immutable
enum class CompetitionMahjongActionKind {
    DRAW, DISCARD, CHOW, PUNG, KONG, CONCEALED_KONG, ADDED_KONG, WIN, PASS,
}

/** The opaque ID is issued by the engine adapter and returned unchanged. */
@Immutable
class CompetitionMahjongActionUi(
    val id: String,
    val label: String,
    val kind: CompetitionMahjongActionKind,
    val tile: Int? = null,
    tiles: List<Int> = emptyList(),
) {
    val tiles: List<Int> = frozenList(tiles)

    init {
        require(id.isNotBlank() && label.isNotBlank())
        require(tile == null || tile in 0..41)
        require(tiles.all { it in 0..41 })
    }
}

@Immutable
class CompetitionMahjongMeldUi(
    val label: String,
    tiles: List<Int?>,
    val isConcealed: Boolean = false,
    val isRevealed: Boolean = false,
) {
    val tiles: List<Int?> = frozenList(tiles)

    init {
        require(label.isNotBlank())
        require(tiles.size in 3..4 && tiles.all { it == null || it in 0..33 })
        require(tiles.none { it == null } ||
            (isConcealed && tiles.size == 4 && tiles.all { it == null }))
        require(!isRevealed || (isConcealed && tiles.none { it == null }))
    }
}

@Immutable
class CompetitionMahjongSeatUi(
    val seat: Int,
    val name: String,
    val windLabel: String,
    val concealedCount: Int,
    visibleHand: List<Int>? = null,
    flowers: List<Int> = emptyList(),
    melds: List<CompetitionMahjongMeldUi> = emptyList(),
    discards: List<Int> = emptyList(),
    val netScore: Int = 0,
    val canWin: Boolean = true,
) {
    val visibleHand: List<Int>? = visibleHand?.let(::frozenList)
    val flowers: List<Int> = frozenList(flowers)
    val melds: List<CompetitionMahjongMeldUi> = frozenList(melds)
    val discards: List<Int> = frozenList(discards)

    init {
        require(seat in 0..3 && name.isNotBlank() && windLabel.isNotBlank())
        require(concealedCount in 0..18)
        require(visibleHand == null ||
            (visibleHand.size == concealedCount && visibleHand.all { it in 0..33 }))
        require(flowers.all { it in 34..41 })
        require(discards.all { it in 0..33 })
    }
}

@Immutable
class CompetitionMahjongClaimUi(
    val fromSeat: Int,
    val tile: Int,
    val description: String,
    val isRobbingKong: Boolean = false,
) {
    init {
        require(fromSeat in 0..3 && tile in 0..33 && description.isNotBlank())
    }
}

@Immutable
data class CompetitionMahjongFanUi(
    val name: String,
    val points: Int,
    val count: Int = 1,
) {
    init {
        require(name.isNotBlank() && points > 0 && count > 0)
    }
}

@Immutable
class CompetitionMahjongResultUi(
    val title: String,
    val detail: String,
    netScores: List<Int>,
    fans: List<CompetitionMahjongFanUi> = emptyList(),
    val totalFan: Int? = null,
    seatLabels: List<String> = listOf("东", "南", "西", "北"),
    val winningTile: Int? = null,
    winningTiles: List<Int> = emptyList(),
) {
    val netScores: List<Int> = frozenList(netScores)
    val fans: List<CompetitionMahjongFanUi> = frozenList(fans)
    val seatLabels: List<String> = frozenList(seatLabels)
    val winningTiles: List<Int> = frozenList(winningTiles)

    init {
        require(title.isNotBlank() && netScores.size == 4)
        require(netScores.sumOf { it.toLong() } == 0L)
        require(totalFan == null || totalFan >= 0)
        require(seatLabels.size == 4 && seatLabels.all { it.isNotBlank() })
        require(winningTiles.size <= 14 && winningTiles.all { it in 0..33 })
        require((winningTile == null && winningTiles.isEmpty()) ||
            (winningTile != null && winningTile in winningTiles))
    }
}

@Immutable
data class CompetitionMahjongAutoPlayUi(
    val available: Boolean = false,
    val running: Boolean = false,
    val speed: Float = 1f,
    val controlsEnabled: Boolean = true,
) {
    init {
        require(speed in COMPETITION_MAHJONG_AUTO_SPEEDS)
    }
}

val COMPETITION_MAHJONG_AUTO_SPEEDS: List<Float> =
    frozenList(listOf(0.5f, 1f, 2f, 4f))

/** A presentation snapshot; rules and action validity belong to the engine. */
@Immutable
class CompetitionMahjongTableUiState(
    seats: List<CompetitionMahjongSeatUi>,
    val localSeat: Int,
    val dealerSeat: Int,
    val currentSeat: Int?,
    val roundLabel: String,
    val wallRemaining: Int,
    val status: String,
    availableActions: List<CompetitionMahjongActionUi> = emptyList(),
    val claim: CompetitionMahjongClaimUi? = null,
    val result: CompetitionMahjongResultUi? = null,
    val feedback: String? = null,
    val isBusy: Boolean = false,
    val canRestart: Boolean = true,
    val autoPlay: CompetitionMahjongAutoPlayUi = CompetitionMahjongAutoPlayUi(),
    val canUndo: Boolean = true,
    val drawnTile: Int? = null,
) {
    val seats: List<CompetitionMahjongSeatUi> = frozenList(seats.sortedBy { it.seat })
    val availableActions: List<CompetitionMahjongActionUi> = frozenList(availableActions)
    val actionsEnabled: Boolean get() = !isBusy && result == null

    init {
        require(seats.size == 4 && seats.map { it.seat }.toSet() == setOf(0, 1, 2, 3))
        require(localSeat in 0..3 && dealerSeat in 0..3)
        require(currentSeat == null || currentSeat in 0..3)
        require(wallRemaining in 0..144)
        require(roundLabel.isNotBlank() && status.isNotBlank())
        require(availableActions.map { it.id }.toSet().size == availableActions.size)
        require(drawnTile == null || (drawnTile in 0..33 && currentSeat == localSeat &&
            result == null && drawnTile in seats.single { it.seat == localSeat }.visibleHand.orEmpty()))
    }
}

/** Updates display controls without reading the native session during an active batch. */
internal fun CompetitionMahjongTableUiState.withControls(
    isBusy: Boolean = this.isBusy,
    autoPlay: CompetitionMahjongAutoPlayUi = this.autoPlay,
): CompetitionMahjongTableUiState = CompetitionMahjongTableUiState(
    seats = seats,
    localSeat = localSeat,
    dealerSeat = dealerSeat,
    currentSeat = currentSeat,
    roundLabel = roundLabel,
    wallRemaining = wallRemaining,
    status = status,
    availableActions = availableActions,
    claim = claim,
    result = result,
    feedback = feedback,
    isBusy = isBusy,
    canRestart = canRestart,
    autoPlay = autoPlay,
    canUndo = canUndo,
    drawnTile = drawnTile,
)
