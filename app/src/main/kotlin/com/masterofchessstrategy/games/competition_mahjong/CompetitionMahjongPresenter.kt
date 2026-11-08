package com.masterofchessstrategy.games.competition_mahjong

import com.masterofchessstrategy.engine.CompetitionMahjongAction
import com.masterofchessstrategy.engine.CompetitionMahjongApplyResult
import com.masterofchessstrategy.engine.CompetitionMahjongMeldKind
import com.masterofchessstrategy.engine.CompetitionMahjongPublicMeld
import com.masterofchessstrategy.engine.CompetitionMahjongPublicView
import com.masterofchessstrategy.engine.CompetitionMahjongRoundActionKind
import com.masterofchessstrategy.engine.CompetitionMahjongRoundError
import com.masterofchessstrategy.engine.CompetitionMahjongRoundPhase

private val winds = listOf("东", "南", "西", "北")

internal fun competitionMahjongActionId(action: CompetitionMahjongAction): String =
    "${action.kind.code}-${action.tile}-${action.sequenceStart}"

/** Maps the public session frame only; a private replay record is never read here. */
internal fun competitionMahjongTableState(
    view: CompetitionMahjongPublicView,
    actions: List<CompetitionMahjongAction>,
    feedback: String? = null,
    isBusy: Boolean = false,
    canRestart: Boolean = true,
    canUndo: Boolean = true,
    autoPlay: CompetitionMahjongAutoPlayUi = CompetitionMahjongAutoPlayUi(),
): CompetitionMahjongTableUiState {
    require(view.viewerSeat in 0..3 && view.dealerSeat in 0..3 && view.roundWind in 0..3)
    require(view.netScores.size == 4)
    val terminal = view.phase == CompetitionMahjongRoundPhase.WON ||
        view.phase == CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW
    val reveal = terminal && view.publicFrameVersion >= 2
    fun wind(seat: Int) = winds[(seat - view.dealerSeat + 4) % 4]
    fun player(seat: Int) = if (seat == view.viewerSeat) "你" else "${wind(seat)}家"
    val seats = view.seats.map { seat ->
        CompetitionMahjongSeatUi(
            seat = seat.seat,
            name = if (seat.seat == view.viewerSeat) "我" else "${wind(seat.seat)}家",
            windLabel = wind(seat.seat),
            concealedCount = seat.concealedCount,
            visibleHand = seat.concealedTiles.takeIf { seat.seat == view.viewerSeat },
            flowers = seat.flowers,
            melds = seat.melds.map { meld -> meldUi(meld, seat.seat, view.viewerSeat, reveal) },
            discards = seat.discards,
            netScore = view.netScores[seat.seat],
            canWin = !seat.winDisabled,
        )
    }
    val claim = if (view.phase == CompetitionMahjongRoundPhase.CLAIM ||
        view.phase == CompetitionMahjongRoundPhase.ROB_KONG) {
        val pendingSeat = requireNotNull(view.pendingSeat)
        val pendingTile = requireNotNull(view.pendingTile)
        val robbing = view.phase == CompetitionMahjongRoundPhase.ROB_KONG
        CompetitionMahjongClaimUi(pendingSeat, pendingTile,
            if (robbing) "加杠，待抢杠和" else "打出，待认领", robbing)
    } else null
    val result = when (view.phase) {
        CompetitionMahjongRoundPhase.WON -> {
            val winner = requireNotNull(view.winnerSeat)
            val selfDraw = view.payerSeat == null
            CompetitionMahjongResultUi(
                title = "${player(winner)}${if (selfDraw) "自摸" else "和牌"}",
                detail = (if (selfDraw) "三家各付底分与番分" else
                    "${player(requireNotNull(view.payerSeat))}点和，三家付底分") +
                    " · 不含花 ${view.winningFan.fanWithoutFlowers} 分 · " +
                    "下盘${wind(view.nextDealer)}家坐庄",
                netScores = view.netScores,
                fans = view.winningFan.entries.map {
                    CompetitionMahjongFanUi(it.name, it.points, it.count)
                },
                totalFan = view.winningFan.totalFan,
                seatLabels = List(4) { wind(it) },
                winningTile = view.winningTile.takeIf { reveal },
                winningTiles = if (reveal) view.winningTiles else emptyList(),
            )
        }
        CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW -> CompetitionMahjongResultUi(
            title = "荒牌",
            detail = "本盘结束，保留本盘已发生的罚分 · 下盘${wind(view.nextDealer)}家坐庄",
            netScores = view.netScores,
            seatLabels = List(4) { wind(it) },
        )
        else -> null
    }
    val status = when (view.phase) {
        CompetitionMahjongRoundPhase.DISCARD -> if (view.currentSeat == view.viewerSeat)
            "请选择手牌出牌" else "${player(view.currentSeat)}正在出牌"
        CompetitionMahjongRoundPhase.DRAW -> if (view.currentSeat == view.viewerSeat)
            "轮到你摸牌" else "等待${player(view.currentSeat)}摸牌"
        CompetitionMahjongRoundPhase.CLAIM -> if (actions.isNotEmpty())
            "请选择认领或过牌" else "等待其他席确认认领"
        CompetitionMahjongRoundPhase.ROB_KONG -> if (actions.isNotEmpty())
            "请选择抢杠和或过牌" else "等待其他席确认抢杠"
        CompetitionMahjongRoundPhase.WON -> "本盘和牌已结算"
        CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW -> "牌墙已尽，本盘荒牌"
    }
    return CompetitionMahjongTableUiState(
        seats = seats,
        localSeat = view.viewerSeat,
        dealerSeat = view.dealerSeat,
        currentSeat = view.currentSeat.takeUnless { terminal },
        roundLabel = "${winds[view.roundWind]}风圈",
        wallRemaining = view.wallRemaining,
        status = status,
        availableActions = if (terminal) emptyList() else actions.distinctBy(::competitionMahjongActionId)
            .map(::actionUi),
        claim = claim,
        result = result,
        feedback = feedback,
        isBusy = isBusy,
        canRestart = canRestart,
        canUndo = canUndo,
        autoPlay = autoPlay,
        drawnTile = view.drawnTile.takeIf { view.publicFrameVersion >= 2 &&
            view.phase == CompetitionMahjongRoundPhase.DISCARD && view.currentSeat == view.viewerSeat },
    )
}

internal fun competitionMahjongApplyFeedback(result: CompetitionMahjongApplyResult): String? = when {
    result.accepted && result.falseWin -> "错和已按竞赛规则罚分，本盘停和；可继续摸打和吃碰杠。"
    result.accepted -> null
    else -> when (result.error) {
        CompetitionMahjongRoundError.WRONG_PHASE -> "当前阶段不能执行此操作，请按牌桌提示选择。"
        CompetitionMahjongRoundError.WRONG_PLAYER -> "请等待轮到你操作。"
        CompetitionMahjongRoundError.ILLEGAL_ACTION -> "此操作不符合当前局面，请重新选择。"
        CompetitionMahjongRoundError.ALREADY_RESPONDED -> "你已回应此次认领，请等待其他席。"
        CompetitionMahjongRoundError.CORRUPTED_DATA -> "局面数据校验失败，请恢复已保存对局。"
        CompetitionMahjongRoundError.ACTION_LIMIT -> "本盘操作数已达上限，请重新开局。"
        CompetitionMahjongRoundError.NONE -> "操作未执行，请重试。"
    }
}

private fun meldUi(
    meld: CompetitionMahjongPublicMeld,
    seat: Int,
    viewer: Int,
    reveal: Boolean,
): CompetitionMahjongMeldUi {
    val concealed = meld.kind == CompetitionMahjongMeldKind.KONG && meld.fromSeat == seat
    val tile = meld.tile.takeUnless { concealed && seat != viewer && !reveal }
    val tiles = when (meld.kind) {
        CompetitionMahjongMeldKind.CHOW -> {
            val start = requireNotNull(tile)
            require(start < 27 && start % 9 <= 6)
            listOf(start, start + 1, start + 2)
        }
        CompetitionMahjongMeldKind.PUNG -> List(3) { requireNotNull(tile) }
        CompetitionMahjongMeldKind.KONG -> List(4) { tile }
    }
    val label = when {
        meld.kind == CompetitionMahjongMeldKind.CHOW -> "吃"
        meld.kind == CompetitionMahjongMeldKind.PUNG -> "碰"
        concealed -> "暗杠"
        meld.addedKong -> "加杠"
        else -> "明杠"
    }
    return CompetitionMahjongMeldUi(label, tiles, concealed, isRevealed = concealed && reveal && tile != null)
}

/** Eligibility comes from frame v2; phase alone cannot distinguish a draw from Chow/Pung. */
internal fun competitionMahjongDeclarationTiles(view: CompetitionMahjongPublicView, hasChoice: Boolean): List<Int> {
    if (view.publicFrameVersion < 2 || !view.canDeclareWin || !hasChoice ||
        view.seats[view.viewerSeat].winDisabled) return emptyList()
    return when (view.phase) {
        CompetitionMahjongRoundPhase.DISCARD -> if (view.currentSeat == view.viewerSeat && view.drawnTile != null)
            view.seats[view.viewerSeat].concealedTiles.distinct() else emptyList()
        CompetitionMahjongRoundPhase.CLAIM, CompetitionMahjongRoundPhase.ROB_KONG -> listOfNotNull(view.pendingTile)
        else -> emptyList()
    }
}

private fun actionUi(action: CompetitionMahjongAction): CompetitionMahjongActionUi {
    val tileName = competitionMahjongTileName(action.tile)
    val (kind, label) = when (action.kind) {
        CompetitionMahjongRoundActionKind.DRAW -> CompetitionMahjongActionKind.DRAW to "摸牌"
        CompetitionMahjongRoundActionKind.DISCARD -> CompetitionMahjongActionKind.DISCARD to "出 $tileName"
        CompetitionMahjongRoundActionKind.PASS -> CompetitionMahjongActionKind.PASS to "过"
        CompetitionMahjongRoundActionKind.CHOW -> CompetitionMahjongActionKind.CHOW to
            "吃 ${(action.sequenceStart..action.sequenceStart + 2).joinToString("·", transform = ::competitionMahjongTileName)}"
        CompetitionMahjongRoundActionKind.PUNG -> CompetitionMahjongActionKind.PUNG to "碰 $tileName"
        CompetitionMahjongRoundActionKind.EXPOSED_KONG -> CompetitionMahjongActionKind.KONG to "明杠 $tileName"
        CompetitionMahjongRoundActionKind.CONCEALED_KONG -> CompetitionMahjongActionKind.CONCEALED_KONG to "暗杠 $tileName"
        CompetitionMahjongRoundActionKind.ADDED_KONG -> CompetitionMahjongActionKind.ADDED_KONG to "加杠 $tileName"
        CompetitionMahjongRoundActionKind.WIN -> CompetitionMahjongActionKind.WIN to "和 $tileName"
    }
    val tiles = when (action.kind) {
        CompetitionMahjongRoundActionKind.CHOW -> (action.sequenceStart..action.sequenceStart + 2).toList()
        CompetitionMahjongRoundActionKind.PUNG -> List(3) { action.tile }
        CompetitionMahjongRoundActionKind.EXPOSED_KONG,
        CompetitionMahjongRoundActionKind.CONCEALED_KONG,
        CompetitionMahjongRoundActionKind.ADDED_KONG -> List(4) { action.tile }
        else -> emptyList()
    }
    return CompetitionMahjongActionUi(competitionMahjongActionId(action), label, kind,
        action.tile.takeUnless { action.kind == CompetitionMahjongRoundActionKind.DRAW ||
            action.kind == CompetitionMahjongRoundActionKind.PASS }, tiles)
}

/** Public context only: do not seed another seat's decision with private records or the viewer's hand. */
internal fun competitionMahjongPublicBotSeed(view: CompetitionMahjongPublicView): Long {
    var hash = 1125899906842597L
    fun add(value: Int) { hash = hash * 31 + value }
    add(view.phase.code)
    add(view.dealerSeat)
    add(view.roundWind)
    add(view.currentSeat)
    add(view.wallRemaining)
    add(view.pendingTile ?: -1)
    add(view.pendingSeat ?: -1)
    view.netScores.forEach(::add)
    view.seats.forEach { seat ->
        add(seat.seat)
        add(seat.concealedCount)
        add(if (seat.winDisabled) 1 else 0)
        add(seat.flowers.size)
        seat.flowers.forEach(::add)
        add(seat.discards.size)
        seat.discards.forEach(::add)
        add(seat.melds.size)
        seat.melds.forEach { meld ->
            add(meld.kind.code)
            // A concealed Kong is private even when it belongs to the UI viewer.
            add(if (meld.fromSeat == seat.seat) -1 else meld.tile ?: -1)
            add(meld.fromSeat)
            add(meld.claimedPosition)
            add(if (meld.addedKong) 1 else 0)
        }
    }
    return hash and Long.MAX_VALUE
}
