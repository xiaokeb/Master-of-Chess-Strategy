package com.masterofchessstrategy.game

import java.io.Closeable

/** Main-thread state for live cues; enabling never replays discarded sounds. */
internal class ChineseChessSoundPlayback(
    private val playLoaded: (ChineseChessSoundCue) -> Boolean,
    private val stopPlayback: () -> Unit,
    private val release: () -> Unit,
) : Closeable {
    private val loaded = mutableSetOf<ChineseChessSoundCue>()
    private var pending: ChineseChessSoundCue? = null
    private var enabled = true
    private var closed = false

    fun setEnabled(value: Boolean) {
        if (closed || enabled == value) return
        enabled = value
        if (!value) {
            pending = null
            stopPlayback()
        }
    }

    fun onLoadCompleted(cue: ChineseChessSoundCue, successful: Boolean) {
        if (closed || !successful) return
        loaded += cue
        if (pending == cue) {
            pending = null
            if (enabled) playLoaded(cue)
        }
    }

    fun isLoaded(cue: ChineseChessSoundCue): Boolean = !closed && cue in loaded

    fun play(cue: ChineseChessSoundCue): Boolean {
        if (closed || !enabled) return false
        pending = null
        if (cue in loaded) return playLoaded(cue)
        pending = cue
        return false
    }

    override fun close() {
        if (closed) return
        closed = true
        pending = null
        release()
    }
}
