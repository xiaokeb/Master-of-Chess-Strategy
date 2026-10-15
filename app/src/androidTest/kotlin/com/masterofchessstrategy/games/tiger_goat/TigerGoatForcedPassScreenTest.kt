package com.masterofchessstrategy.games.tiger_goat

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.games.tiger_goat.NativeTigerGoatSession
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TigerGoatForcedPassScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun useLandscapeHost() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(10_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    @Test fun nativeBlockedGoatsShowPassAndUndoRestoresIt() {
        NativeTigerGoatSession().use { native ->
            assertTrue(native.restore(tigerGoatBlockedGoatsSave()))
            val controller = TigerGoatGameController(NativeTigerGoatRulesSession(native))
            compose.setContent {
                val state by controller.uiState.collectAsState()
                MaterialTheme {
                    TigerGoatGameScreen(controller.boardSpec, state, controller::onPointTap,
                        controller::endChain, controller::undo, controller::restart,
                        controller::resign, {}, controller::passGoat)
                }
            }
            compose.onNodeWithText("羊已全部摆入且无合法走步，请停着交由老虎行动").assertIsDisplayed()
            compose.onNodeWithTag("tiger_goat_pass").assertIsDisplayed().assertIsEnabled()
            saveImage("blocked")
            compose.onNodeWithText("认输").performScrollTo().assertIsDisplayed()
            saveImage("controls")
            compose.onNodeWithTag("tiger_goat_pass").performScrollTo().performClick()
            compose.onNodeWithTag("tiger_goat_pass").assertDoesNotExist()
            compose.runOnIdle { assertEquals(1, native.snapshot().actionCount); assertEquals(1, native.snapshot().side) }
            saveImage("passed")
            compose.onNodeWithText("悔棋").performScrollTo().performClick()
            compose.onNodeWithTag("tiger_goat_pass").assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, native.snapshot().actionCount) }
        }
    }

    @Test fun openingDoesNotOfferVoluntaryPass() {
        NativeTigerGoatSession().use { native ->
            val controller = TigerGoatGameController(NativeTigerGoatRulesSession(native))
            compose.setContent {
                val state by controller.uiState.collectAsState()
                MaterialTheme {
                    TigerGoatGameScreen(controller.boardSpec, state,
                        controller::onPointTap, controller::endChain, controller::undo,
                        controller::restart, controller::resign, {}, controller::passGoat)
                }
            }
            compose.onNodeWithTag("tiger_goat_pass").assertDoesNotExist()
            compose.onNodeWithText("老虎先从中央环移去一只羊").assertIsDisplayed()
        }
    }

    private fun saveImage(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "tiger-goat-pass-visual")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create screenshot directory: $directory" }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        assertTrue("Screenshot must be landscape: ${bitmap.width}x${bitmap.height}", bitmap.width > bitmap.height)
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
