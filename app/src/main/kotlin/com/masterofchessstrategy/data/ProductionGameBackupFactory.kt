package com.masterofchessstrategy.data

import com.masterofchessstrategy.endgame.ChineseChessEndgamePack
import com.masterofchessstrategy.games.competition_mahjong.data.CompetitionMahjongBackupAdapter
import com.masterofchessstrategy.games.go.data.GoLocalStorage
import com.masterofchessstrategy.games.go.data.GoStorageEpochGate
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiBackupAdapter
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLearningBackupAdapter
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiCoordinatedBackupRepository
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLocalStorage
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiStorageEpochGate
import com.masterofchessstrategy.games.tiger_goat.data.TigerGoatBackupAdapter
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongBackupAdapter
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongCoordinatedBackupRepository
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongStorageEpochGate

/** The app and integration tests use the same complete adapter/gate composition. */
internal fun createProductionGameBackupRepository(
    database: MocsDatabase,
    endgamePack: ChineseChessEndgamePack,
    goStorage: GoLocalStorage,
    fangqiStorage: FangqiLocalStorage,
    validateEngineState: (ByteArray) -> Boolean,
): LocalDataBackupRepository {
    require(goStorage.gate === GoStorageEpochGate.forDatabase(database)) { "围棋备份门禁与数据库不匹配" }
    require(fangqiStorage.gate === FangqiStorageEpochGate.forDatabase(database)) { "方棋备份门禁与数据库不匹配" }
    return LanzhouMahjongCoordinatedBackupRepository(
        FangqiCoordinatedBackupRepository(
            goStorage.backupRepository { goAdapters ->
                RoomLocalDataBackupRepository(
                    database, endgamePack, validateEngineState,
                    GameBackupRegistry(listOf(TigerGoatBackupAdapter(), CompetitionMahjongBackupAdapter(),
                        FangqiBackupAdapter(), FangqiLearningBackupAdapter(), LanzhouMahjongBackupAdapter()) + goAdapters),
                )
            },
            fangqiStorage.gate,
        ),
        LanzhouMahjongStorageEpochGate.forDatabase(database),
    )
}
