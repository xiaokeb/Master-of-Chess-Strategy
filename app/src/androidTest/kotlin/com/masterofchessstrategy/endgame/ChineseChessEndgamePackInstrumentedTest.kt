package com.masterofchessstrategy.endgame

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChineseChessEndgamePackInstrumentedTest {
    @Test
    fun everyBundledPrincipalVariationWinsWithinItsLimit() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val pack = ChineseChessEndgamePackParser.loadBundled(assets)

        pack.levels.forEach { level ->
            NativeChineseChessEngine().use { engine ->
                assertTrue(
                    level.id,
                    engine.restore(level.initialEngineState) is RestoreResult.Restored,
                )
                assertEquals(level.id, GameResult.ONGOING, engine.gameResult())
                level.principalVariation.forEachIndexed { index, move ->
                    assertTrue(
                        "${level.id} step ${index + 1} was rejected",
                        engine.apply(move) is ActionResult.Accepted,
                    )
                }
                assertEquals(level.id, GameResult.FIRST_PLAYER_WIN, engine.gameResult())
                assertTrue(
                    level.id,
                    (level.principalVariation.size + 1) / 2 <= level.maxPlayerMoves,
                )
            }
        }
    }
}
