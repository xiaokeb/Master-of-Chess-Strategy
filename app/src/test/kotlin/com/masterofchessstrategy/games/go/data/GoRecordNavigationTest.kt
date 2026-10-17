package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.navigation.AppDestination
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class GoRecordNavigationTest {
    @Test fun hashAndLegacyUnicodeIdsHaveTheirOwnEncodedGoRoute() {
        val hash = "a".repeat(64)
        assertEquals("go/records/$hash", AppDestination.goRecord(hash))
        val legacy = "旧谱/a b?#%"
        val route = AppDestination.goRecord(legacy)
        assertEquals(legacy, URLDecoder.decode(route.removePrefix("go/records/"), "UTF-8"))
        assertFalse(route.removePrefix("go/records/").contains('/'))
        assertFalse(route.contains(' '))
        assertEquals("records/chess", AppDestination.chineseChessRecord("chess"))
    }

    @Test fun emptyAndOversizeIdsAreRejectedBeforeNavigation() {
        for (id in listOf("", " ", "a".repeat(65))) {
            assertThrows(IllegalArgumentException::class.java) { AppDestination.goRecord(id) }
        }
    }
}
