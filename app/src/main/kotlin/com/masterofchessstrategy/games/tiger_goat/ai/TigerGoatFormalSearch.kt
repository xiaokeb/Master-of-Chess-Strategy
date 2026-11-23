package com.masterofchessstrategy.games.tiger_goat.ai

import com.masterofchessstrategy.games.tiger_goat.TigerGoatAction

/** Owner consumes on its serialized control thread, immediately before Native apply. */
internal interface TigerGoatFormalSearch {
    val failed: Boolean
    fun replace(checkpoint: TigerGoatFormalCheckpoint)
    fun start(): Boolean
    fun consume(checkpoint: TigerGoatFormalCheckpoint): TigerGoatAction?
    fun cancel()
    fun seal()
    fun closeAndJoinBlocking()
}
