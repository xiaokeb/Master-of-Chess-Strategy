package com.masterofchessstrategy.games.lanzhou_mahjong.data

import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongHostStorage

/** Bind the DAO, fixed writer owner and backup epoch to the exact same database. */
internal fun MocsDatabase.createLanzhouMahjongHostStorage(): LanzhouMahjongHostStorage {
    val database = this
    val gate = LanzhouMahjongStorageEpochGate.forDatabase(database)
    return LanzhouMahjongHostStorage(gate) { owner ->
        RoomLanzhouMahjongAdmittedSessionStore(database, database.activeGameDao(), gate, owner)
    }
}
