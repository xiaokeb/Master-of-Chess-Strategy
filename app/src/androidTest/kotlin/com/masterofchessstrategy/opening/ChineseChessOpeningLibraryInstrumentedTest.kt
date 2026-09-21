package com.masterofchessstrategy.opening

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChineseChessOpeningLibraryInstrumentedTest {
    @Test
    fun everyBundledOpeningStepIsAcceptedByNativeRules() {
        ChineseChessOpeningLibrary.lines.forEach { line ->
            NativeChineseChessEngine().use { engine ->
                engine.reset()
                line.steps.forEachIndexed { index, step ->
                    assertTrue(
                        "${line.id} step ${index + 1} was rejected",
                        engine.apply(step.move) is ActionResult.Accepted,
                    )
                    assertEquals(GameResult.ONGOING, engine.gameResult())
                }
            }
        }
    }
}
