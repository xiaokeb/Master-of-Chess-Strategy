package com.masterofchessstrategy.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.masterofchessstrategy.R
import java.io.Closeable

/**
 * Owns the short game-effect samples for one visible game destination.
 *
 * SoundPool performs decoding off the UI thread. If the first event arrives
 * while its sample is loading, only the newest pending cue is retained.
 */
internal class ChineseChessSoundPlayer(context: Context) : Closeable {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val soundIds = mutableMapOf<ChineseChessSoundCue, Int>()
    private val loadedSoundIds = mutableSetOf<Int>()
    private var pendingCue: ChineseChessSoundCue? = null
    private var isClosed = false

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (isClosed || status != LOAD_SUCCESS) return@setOnLoadCompleteListener
            loadedSoundIds += sampleId
            val cue = pendingCue
            if (cue != null && soundIds[cue] == sampleId) {
                pendingCue = null
                playLoaded(sampleId)
            }
        }
        soundIds[ChineseChessSoundCue.MOVE] =
            soundPool.load(context, R.raw.chess_move, EFFECT_PRIORITY)
        soundIds[ChineseChessSoundCue.CAPTURE] =
            soundPool.load(context, R.raw.chess_capture, EFFECT_PRIORITY)
        soundIds[ChineseChessSoundCue.VICTORY] =
            soundPool.load(context, R.raw.game_victory, EFFECT_PRIORITY)
        soundIds[ChineseChessSoundCue.DEFEAT] =
            soundPool.load(context, R.raw.game_defeat, EFFECT_PRIORITY)
        soundIds[ChineseChessSoundCue.DRAW] =
            soundPool.load(context, R.raw.game_draw, EFFECT_PRIORITY)
    }

    fun play(cue: ChineseChessSoundCue) {
        if (isClosed) return
        val soundId = soundIds.getValue(cue)
        if (soundId in loadedSoundIds) {
            pendingCue = null
            playLoaded(soundId)
        } else {
            pendingCue = cue
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        pendingCue = null
        soundPool.setOnLoadCompleteListener(null)
        soundPool.release()
    }

    private fun playLoaded(soundId: Int) {
        soundPool.play(
            soundId,
            EFFECT_VOLUME,
            EFFECT_VOLUME,
            EFFECT_PRIORITY,
            NO_LOOP,
            NORMAL_RATE,
        )
    }

    private companion object {
        const val LOAD_SUCCESS = 0
        const val EFFECT_VOLUME = 0.72f
        const val EFFECT_PRIORITY = 1
        const val NO_LOOP = 0
        const val NORMAL_RATE = 1f
    }
}
