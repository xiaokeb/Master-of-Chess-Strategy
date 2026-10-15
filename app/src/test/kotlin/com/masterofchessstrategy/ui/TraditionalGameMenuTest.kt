package com.masterofchessstrategy.ui

import org.junit.Assert.*
import org.junit.Test

class TraditionalGameMenuTest {
    private fun entry(id: String, tag: String = id, click: () -> Unit = {}) =
        TraditionalGameMenuEntry(id, "模式 $id", tag, click)

    @Test fun preservesRegistrationOrderWithoutCallingCallbacks() {
        var calls = 0
        val menu = TraditionalGameMenu(listOf(entry("tiger", click = { calls++ }), entry("fangqi")))
        assertEquals(listOf("tiger", "fangqi"), menu.entries.map { it.id })
        assertEquals(0, calls)
    }

    @Test fun clicksOnlyTheChosenRegistration() {
        val calls = mutableListOf<String>()
        val menu = TraditionalGameMenu(listOf(entry("tiger", click = { calls += "tiger" }),
            entry("fangqi", click = { calls += "fangqi" })))
        menu.entries[1].onClick()
        assertEquals(listOf("fangqi"), calls)
    }

    @Test fun ownsAnImmutableSnapshotOfTheRegistrations() {
        val source = mutableListOf(entry("tiger"))
        val menu = TraditionalGameMenu(source)
        source.clear()
        assertEquals(listOf("tiger"), menu.entries.map { it.id })
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (menu.entries as MutableList<TraditionalGameMenuEntry>).clear()
        }
    }

    @Test fun duplicateIdsAreRejectedBeforeRendering() {
        assertThrows(IllegalArgumentException::class.java) {
            TraditionalGameMenu(listOf(entry("tiger", "first"), entry("tiger", "second")))
        }
    }

    @Test fun duplicateTagsAreRejectedBeforeRendering() {
        assertThrows(IllegalArgumentException::class.java) {
            TraditionalGameMenu(listOf(entry("tiger", "same"), entry("fangqi", "same")))
        }
    }

    @Test fun invalidIdentityAndBlankLabelsAreRejected() {
        for (value in listOf("", " ", "invalid tag")) {
            assertThrows(IllegalArgumentException::class.java) { entry(value) }
            assertThrows(IllegalArgumentException::class.java) { entry("tiger", value) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TraditionalGameMenuEntry("tiger", " ", "tiger", {})
        }
    }

    @Test fun anEmptyRegistryDoesNotInventPlayableEntries() {
        assertTrue(TraditionalGameMenu(emptyList()).entries.isEmpty())
    }
}
