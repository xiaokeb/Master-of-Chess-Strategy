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
    private val playback = ChineseChessSoundPlayback(
        playLoaded = { cue -> playLoaded(soundIds.getValue(cue)) },
        // Never autoResume: turning sound back on must not resume an old cue.
        stopPlayback = soundPool::autoPause,
        release = {
            soundPool.setOnLoadCompleteListener(null)
            soundPool.release()
        },
    )

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            soundIds.entries.firstOrNull { it.value == sampleId }?.key?.let { cue ->
                playback.onLoadCompleted(cue, status == LOAD_SUCCESS)
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

    /** Returns whether Android accepted the cue for immediate playback. */
    fun play(cue: ChineseChessSoundCue): Boolean = playback.play(cue)

    internal fun isLoaded(cue: ChineseChessSoundCue): Boolean = playback.isLoaded(cue)

    fun setEnabled(enabled: Boolean) = playback.setEnabled(enabled)

    override fun close() = playback.close()

    private fun playLoaded(soundId: Int): Boolean =
        soundPool.play(
            soundId,
            EFFECT_VOLUME,
            EFFECT_VOLUME,
            EFFECT_PRIORITY,
            NO_LOOP,
            NORMAL_RATE,
        ) != PLAYBACK_REJECTED

    private companion object {
        const val LOAD_SUCCESS = 0
        const val EFFECT_VOLUME = 0.72f
        const val EFFECT_PRIORITY = 1
        const val NO_LOOP = 0
        const val NORMAL_RATE = 1f
        const val PLAYBACK_REJECTED = 0
    }
}
