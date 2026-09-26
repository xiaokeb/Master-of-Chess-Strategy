package com.masterofchessstrategy.records

import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRecordExportCodecTest {
    @Test
    fun exportContainsStableVersionStateAndChecksum() {
        val record = GameRecord(
            recordId = "quoted-\"id",
            gameType = GameType.CHINESE_CHESS,
            mode = StoredGameMode.LOCAL_TWO_PLAYER,
            difficulty = null,
            result = GameResult.DRAW,
            engineState = byteArrayOf(1, 2, 3),
            moveCount = 9,
            completedAtEpochMillis = 1_000L,
        )

        val text = GameRecordExportCodec.encode(record).toString(Charsets.UTF_8)

        assertTrue(text.contains("\"version\": 2"))
        assertTrue(text.contains("\"playerIndex\": 0"))
        assertTrue(text.contains("\"recordId\": \"quoted-\\\"id\""))
        assertTrue(text.contains("\"engineStateHex\": \"010203\""))
        assertTrue(
            text.contains(
                "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
            ),
        )
    }
}
