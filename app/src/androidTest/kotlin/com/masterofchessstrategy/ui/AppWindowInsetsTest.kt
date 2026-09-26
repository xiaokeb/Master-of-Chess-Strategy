package com.masterofchessstrategy.ui

import android.content.res.Configuration
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.MainActivity
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.game.ChineseChessBackgroundController
import com.masterofchessstrategy.ui.theme.APP_SAFE_CONTENT_TAG
import com.masterofchessstrategy.ui.theme.NightInk
import com.masterofchessstrategy.ui.theme.Parchment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Real window insets, not fabricated padding; emulator settings are restored after the Activity closes. */
class AppWindowInsetsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val composeRule = createAndroidComposeRule<MainActivity>()
    private var originalNight: String? = null
    private var originalNavigation: String? = null
    private val environment = object : ExternalResource() {
        override fun before() {
            assumeTrue("System appearance changes are emulator-only", Build.HARDWARE in setOf("ranchu", "goldfish"))
            originalNight = shell("cmd uimode night").trim().substringAfter("Night mode: ")
            require(originalNight in setOf("no", "yes", "auto")) { "Unknown night mode; do not alter it" }
            originalNavigation = shell("cmd overlay list").lineSequence().map(String::trim)
                .first { it.startsWith("[x] com.android.internal.systemui.navbar.") }.removePrefix("[x] ")
            runBlocking { MocsDatabase.getInstance(context).activeGameDao().deleteAll() }
        }

        override fun after() {
            originalNavigation?.let { shell("cmd overlay enable-exclusive --category $it") }
            originalNight?.let { shell("cmd uimode night $it") }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(environment).around(composeRule)

    @Test fun landscapeCatalogCanRevealItsLastRowWithoutShrinkingText() {
        shell("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural")
        shell("cmd uimode night no")
        composeRule.waitUntil(15_000L) {
            composeRule.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_NO
        }
        composeRule.onAllNodesWithText("后续切片开放").onLast().performScrollTo().assertIsDisplayed()
        captureSettledWindow(false, "home")
        composeRule.onNodeWithTag(HOME_CHINESE_CHESS_TAG).performScrollTo().performClick()
        composeRule.onNodeWithTag(MODE_LOCAL_GAME_TAG).performScrollTo().performClick()
        val controller = ChineseChessBackgroundController.get(context)
        composeRule.waitUntil(15_000L) { controller.game?.uiState?.let { !it.isRestoring && !it.isPersisting } == true }
        assertSafeWindow(false, GAME_BACK_BUTTON_TAG, CHINESE_CHESS_BOARD_TAG)
        captureSettledWindow(false, "game-light")
        shell("cmd uimode night yes")
        composeRule.waitUntil(15_000L) {
            composeRule.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        assertSafeWindow(true, GAME_BACK_BUTTON_TAG, CHINESE_CHESS_BOARD_TAG)
        captureSettledWindow(true, "game-dark")
    }

    @Test fun dayNightAndBothNavigationModesKeepEveryDestinationInsideSafeBounds() {
        for (navigation in listOf("gestural", "threebutton")) {
            shell("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.$navigation")
            for (dark in listOf(false, true)) {
                shell("cmd uimode night ${if (dark) "yes" else "no"}")
                composeRule.waitUntil(15_000L) {
                    (composeRule.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                        Configuration.UI_MODE_NIGHT_YES) == dark
                }
                composeRule.waitForIdle()
                assertSafeWindow(dark, HOME_CHINESE_CHESS_TAG)
                composeRule.onNodeWithTag(HOME_CHINESE_CHESS_TAG).performClick()
                composeRule.onNodeWithTag(MODE_LOCAL_GAME_TAG).performScrollTo().performClick()
                val controller = ChineseChessBackgroundController.get(context)
                composeRule.waitUntil(15_000L) { controller.game?.uiState?.let { !it.isRestoring && !it.isPersisting } == true }
                assertSafeWindow(dark, GAME_BACK_BUTTON_TAG, GAME_SETTINGS_BUTTON_TAG, CHINESE_CHESS_BOARD_TAG)
                val game = requireNotNull(controller.game)
                val before = runBlocking { MocsDatabase.getInstance(context).activeGameDao().find(GameType.CHINESE_CHESS.code) }
                composeRule.activityRule.scenario.recreate()
                composeRule.waitForIdle()
                assertSafeWindow(dark, GAME_BACK_BUTTON_TAG, CHINESE_CHESS_BOARD_TAG)
                assertSame("Window recreation must not replace the chess engine", game, controller.game)
                assertEquals(before, runBlocking { MocsDatabase.getInstance(context).activeGameDao().find(GameType.CHINESE_CHESS.code) })
                composeRule.onNodeWithTag(GAME_SETTINGS_BUTTON_TAG).performClick()
                assertSafeWindow(dark, SETTINGS_SCREEN_TAG)
                composeRule.onNodeWithText("返回").performClick()
                assertSafeWindow(dark, GAME_BACK_BUTTON_TAG, CHINESE_CHESS_BOARD_TAG)
                composeRule.onNodeWithTag(GAME_BACK_BUTTON_TAG).performClick()
                composeRule.onNodeWithText("返回").performClick()
                assertSafeWindow(dark, HOME_CHINESE_CHESS_TAG)
            }
        }
    }

    private fun assertSafeWindow(dark: Boolean, vararg tags: String) {
        composeRule.waitForIdle()
        var expected = Rect.Zero
        composeRule.runOnIdle {
            val activity = composeRule.activity
            val view = activity.window.decorView
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(view)).getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            assertTrue("Expected visible system bars", insets.top > 0 && (insets.bottom > 0 || insets.right > 0 || insets.left > 0))
            val origin = IntArray(2).also(view::getLocationOnScreen)
            expected = Rect((origin[0] + insets.left).toFloat(), (origin[1] + insets.top).toFloat(),
                (origin[0] + view.width - insets.right).toFloat(), (origin[1] + view.height - insets.bottom).toFloat())
            val bars = WindowCompat.getInsetsController(activity.window, view)
            assertEquals(!dark, bars.isAppearanceLightStatusBars)
            assertEquals(!dark, bars.isAppearanceLightNavigationBars)
        }
        val actual = composeRule.onNodeWithTag(APP_SAFE_CONTENT_TAG).fetchSemanticsNode().boundsInWindow
        assertEquals(expected.left, actual.left, 1f)
        assertEquals(expected.top, actual.top, 1f)
        assertEquals(expected.right, actual.right, 1f)
        assertEquals(expected.bottom, actual.bottom, 1f)
        for (tag in tags) {
            val node = composeRule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInWindow
            assertTrue("$tag overlaps system UI: $node vs $expected", node.left >= expected.left - 1f &&
                node.top >= expected.top - 1f && node.right <= expected.right + 1f && node.bottom <= expected.bottom + 1f)
        }
    }

    private fun captureSettledWindow(dark: Boolean, name: String) {
        // Configuration/semantics may already be new while the compositor still shows the old
        // Activity's transition snapshot. Check a real status-bar background pixel before capture.
        val expected = (if (dark) NightInk else Parchment).toArgb()
        var navigationBottom = 0
        composeRule.runOnIdle {
            navigationBottom = requireNotNull(ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView))
                .getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        }
        assertTrue("Gesture bar must be present", navigationBottom > 0)
        composeRule.waitUntil(10_000L) {
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val handle = bitmap.getPixel(bitmap.width / 2, bitmap.height - navigationBottom / 2)
                val brightness = android.graphics.Color.red(handle) + android.graphics.Color.green(handle) + android.graphics.Color.blue(handle)
                // SystemUI's handle tint can settle after the Activity background has changed.
                bitmap.getPixel(bitmap.width / 2, 20) == expected && (if (dark) brightness > 540 else brightness < 450)
            } finally { bitmap.recycle() }
        }
        shell("screencap -p /data/local/tmp/mocs-window-$name.png")
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8) }
}
