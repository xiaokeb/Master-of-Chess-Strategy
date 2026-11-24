package com.masterofchessstrategy.games.competition_mahjong

import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class CompetitionMahjongTileAccessibilityInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun platformAnnouncesFaceUpTilesOnceAndOnlyAnnouncesTheBackOfAConcealedTile() {
        compose.setContent {
            MaterialTheme {
                Row {
                    MahjongTile(0, faceUp = true)
                    MahjongTile(33, faceUp = false)
                    MahjongTile(null, faceUp = false)
                }
            }
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var nodes = emptyList<AccessibilityNodeInfo>()
        compose.waitUntil(10_000) {
            nodes = automation.rootInActiveWindow?.let(::nodesBelow).orEmpty()
            nodes.count { it.contentDescription?.toString() == "牌背" } == 2
        }
        val announced = nodes.filter { it.isImportantForAccessibility }
        assertEquals(1, announced.count { it.contentDescription?.toString() == "1万" })
        assertEquals(2, announced.count { it.contentDescription?.toString() == "牌背" })
        assertFalse(announced.any { it.text?.toString() in setOf("1万", "纹", "白") ||
            it.contentDescription?.toString() == "白" })
    }

    private fun nodesBelow(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        add(root)
        for (index in 0 until root.childCount) root.getChild(index)?.let { addAll(nodesBelow(it)) }
    }
}
