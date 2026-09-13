package com.masterofchessstrategy.game

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PikafishMasterAiInstrumentedTest {
    @Test
    fun bundledNetworkReturnsAnAuthoritativeLegalMove() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val networkProvider = PikafishNetworkProvider(context)

        NativeChineseChessEngine(
            networkProvider::requireNetworkPath,
        ).use { engine ->
            val legalMoves = engine.legalActions()
            val selected = engine.chooseMove(Difficulty.MASTER)

            assertNotNull(selected)
            assertTrue(selected in legalMoves)
        }
    }
}
