package com.masterofchessstrategy.games.lanzhou_fangqi

import com.masterofchessstrategy.navigation.AppDestination
import com.masterofchessstrategy.ui.decodeFangqiSavedRouteId
import org.junit.Assert.*
import org.junit.Test

class FangqiSavedNavigationTest {
    @Test fun savedRoutesPreserveUnicodeAndSeparatorsWithoutAddingNavigationSegments() {
        for (id in listOf("source-123", "旧棋谱|一/ ?🀄", "汉".repeat(64))) {
            for (formal in listOf(false, true)) {
                val suffix = AppDestination.lanzhouFangqiSavedGame(id, formal).removePrefix("lanzhou-fangqi/saved/")
                val segments = suffix.split('/')
                assertEquals(2, segments.size)
                assertEquals(if (formal) "1" else "0", segments[1])
                assertEquals(id, decodeFangqiSavedRouteId(segments[0]))
            }
        }
    }

    @Test fun malformedIdsAreRejectedInsteadOfBeingReplacedOrTruncated() {
        for (bad in listOf("", " ", "a".repeat(65), "\uD800")) {
            assertThrows(IllegalArgumentException::class.java) { AppDestination.lanzhouFangqiSavedGame(bad, true) }
        }
    }

    @Test fun decoderRejectsNonCanonicalHexInvalidUtf8BlankAndOverlongValues() {
        for (bad in listOf(null, "", "1", "FF", "gg", "ff", "c080", "20", "61".repeat(65), "61".repeat(257))) {
            assertNull(bad, decodeFangqiSavedRouteId(bad))
        }
    }
}
