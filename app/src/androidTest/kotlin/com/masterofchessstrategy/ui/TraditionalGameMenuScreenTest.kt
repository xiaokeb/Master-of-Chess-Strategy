package com.masterofchessstrategy.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Menu-only host: no game engine, Room database or user's progress is touched. */
class TraditionalGameMenuScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun useLandscape() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(10_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    @Test fun tigerOnlyRegistrationDoesNotShowUnregisteredFangqiModes() {
        var tigerClicks = 0
        var backClicks = 0
        val menu = TraditionalGameMenu(listOf(
            TraditionalGameMenuEntry("tiger_local", "老虎吃羊棋", TIGER_GOAT_LOCAL_TAG, { tigerClicks++ }),
        ))
        compose.setContent { MaterialTheme { LanzhouTraditionalModesScreen(menu) { backClicks++ } } }
        compose.onAllNodes(hasText("兰州方棋", substring = true)).assertCountEquals(0)
        compose.onNodeWithTag(TIGER_GOAT_LOCAL_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithText("返回").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, tigerClicks); assertEquals(1, backClicks) }
        saveImage("only-tiger")
    }

    @Test fun rendersInjectedModesAndKeepsEachCallbackIndependent() {
        val calls = mutableListOf<String>()
        val menu = TraditionalGameMenu(listOf(
            TraditionalGameMenuEntry("mode_a", "模式 A", "mode_a", { calls += "A" }),
            TraditionalGameMenuEntry("mode_b", "模式 B", "mode_b", { calls += "B" },
                TraditionalMenuButtonStyle.OUTLINED),
        ))
        compose.setContent { MaterialTheme { LanzhouTraditionalModesScreen(menu) {} } }
        compose.onNodeWithTag("mode_b").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("mode_a").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("B", "A"), calls) }
        saveImage("injected-pair")
    }

    private fun saveImage(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        assertTrue("The shared menu must be tested in landscape", image.width > image.height)
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "traditional-game-menu-visual")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create the visual test cache" }
        File(directory, "$name.png").outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Cannot save the menu screenshot" }
        }
    }
}
