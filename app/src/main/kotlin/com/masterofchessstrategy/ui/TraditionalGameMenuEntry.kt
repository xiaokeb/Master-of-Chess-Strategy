package com.masterofchessstrategy.ui

import java.util.Collections

internal const val LANZHOU_FANGQI_LOCAL_TAG = "lanzhou_fangqi_local"
internal const val LANZHOU_FANGQI_LEARNING_TAG = "lanzhou_fangqi_learning"
internal const val TIGER_GOAT_LOCAL_TAG = "tiger_goat_local"

internal enum class TraditionalMenuButtonStyle { FILLED, OUTLINED }

/** The host registers only modes whose routes it actually installs. No engine dependency. */
internal data class TraditionalGameMenuEntry(
    val id: String,
    val label: String,
    val testTag: String,
    val onClick: () -> Unit,
    val style: TraditionalMenuButtonStyle = TraditionalMenuButtonStyle.FILLED,
) {
    init {
        require(id.matches(IDENTITY)) { "Invalid traditional mode id" }
        require(testTag.matches(IDENTITY)) { "Invalid traditional mode test tag" }
        require(label.isNotBlank()) { "Traditional mode label cannot be blank" }
    }

    private companion object {
        val IDENTITY = Regex("[a-z0-9_-]+")
    }
}

/** Own the list so later registration mutations cannot change a displayed menu silently. */
internal class TraditionalGameMenu(entries: List<TraditionalGameMenuEntry>) {
    val entries: List<TraditionalGameMenuEntry> = Collections.unmodifiableList(ArrayList(entries))

    init {
        require(this.entries.map { it.id }.distinct().size == this.entries.size) { "Duplicate traditional mode id" }
        require(this.entries.map { it.testTag }.distinct().size == this.entries.size) { "Duplicate traditional mode tag" }
    }
}
