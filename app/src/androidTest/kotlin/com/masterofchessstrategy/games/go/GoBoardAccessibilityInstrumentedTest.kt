package com.masterofchessstrategy.games.go

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class GoBoardAccessibilityInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun platformFocusBoundsMatchEveryIntersectionOnAllBoardSizes() {
        val size = mutableIntStateOf(9)
        var tapped: Pair<Int, Int>? = null
        composeRule.setContent {
            Box(Modifier.size(300.dp)) {
                GoBoard(size.intValue, List(size.intValue * size.intValue) { 0 },
                    { x, y -> tapped = x to y })
            }
        }
        for (boardSize in listOf(9, 13, 19)) {
            composeRule.runOnIdle { size.intValue = boardSize }
            val nodes = awaitBoard(boardSize)
            val board = nodes.single { it.contentDescription?.toString() == "${boardSize}路围棋棋盘" }
            val bounds = Rect().also(board::getBoundsInScreen)
            for (y in 0 until boardSize) for (x in 0 until boardSize) {
                val description = goBoardPointDescription(x, y, 0, false, false, false)
                val node = nodes.single { it.contentDescription?.toString() == description }
                val cell = Rect().also(node::getBoundsInScreen)
                val expectedX = bounds.left + bounds.width() * (x + 0.5f) / boardSize
                val expectedY = bounds.top + bounds.height() * (y + 0.5f) / boardSize
                assertTrue("$boardSize:$x,$y focus=$cell board=$bounds", abs(cell.exactCenterX() - expectedX) <= 2f)
                assertTrue("$boardSize:$x,$y focus=$cell board=$bounds", abs(cell.exactCenterY() - expectedY) <= 2f)
                assertTrue(node.isClickable)
            }
            // Pointer input goes through the canvas rather than a semantic onClick callback.
            composeRule.onNodeWithTag("go_intersection_${boardSize - 1}_${boardSize - 1}")
                .performTouchInput { click(center) }
            assertEquals(boardSize - 1 to boardSize - 1, tapped)
        }
    }

    @Test fun disabledBoardExposesCoordinatesWithoutAnAction() {
        val enabled = mutableStateOf(true)
        var taps = 0
        composeRule.setContent {
            Box(Modifier.size(300.dp)) {
                GoBoard(9, List(81) { 0 }, { _, _ -> taps++ }, interactionEnabled = enabled.value)
            }
        }
        awaitBoard(9)
        composeRule.runOnIdle { enabled.value = false }
        val nodes = awaitBoard(9, enabled = false)
        val intersections = nodes.filter { it.contentDescription?.toString()?.startsWith("第") == true }
        assertEquals(81, intersections.size)
        assertTrue(intersections.all { !it.isClickable })
        assertFalse(intersections.any { it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK } })
        composeRule.onNodeWithTag("go_intersection_0_0").performTouchInput { click(center) }
        assertEquals(0, taps)
    }

    private fun awaitBoard(size: Int, enabled: Boolean = true): List<AccessibilityNodeInfo> {
        var snapshot = emptyList<AccessibilityNodeInfo>()
        composeRule.waitUntil(10_000) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            // The same virtual IDs are reused when changing size; read fresh platform bounds.
            if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
            val root = automation.rootInActiveWindow
            snapshot = if (root == null) emptyList() else flatten(root)
            snapshot.count { it.contentDescription?.toString()?.startsWith("第") == true } == size * size &&
                snapshot.any { it.contentDescription?.toString() == "${size}路围棋棋盘" } &&
                snapshot.filter { it.contentDescription?.toString()?.startsWith("第") == true }
                    .all { it.isClickable == enabled }
        }
        return snapshot
    }

    private fun flatten(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        if (Build.VERSION.SDK_INT < 34) root.refresh()
        add(root)
        for (index in 0 until root.childCount) root.getChild(index)?.let { addAll(flatten(it)) }
    }
}
