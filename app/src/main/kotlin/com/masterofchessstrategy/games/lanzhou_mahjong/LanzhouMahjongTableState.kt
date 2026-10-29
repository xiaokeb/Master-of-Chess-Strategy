package com.masterofchessstrategy.games.lanzhou_mahjong

import com.masterofchessstrategy.engine.LanzhouMahjongAction
import com.masterofchessstrategy.engine.LanzhouMahjongPublicView

/** Only controlled public views enter UI state; complete native history stays in the host. */
internal data class LanzhouMahjongTableState(
    val view: LanzhouMahjongPublicView,
    val viewerSeat: Int,
    val actions: List<LanzhouMahjongAction>,
    val feedback: String? = null,
    val isBusy: Boolean = false,
    val needsSave: Boolean = false,
    val needsAdvance: Boolean = false,
    val assistance: LanzhouMahjongAssistanceUsage? = null,
    val canUndo: Boolean = false,
    val hint: LanzhouMahjongHint? = null,
    val blocked: Boolean = false,
)
