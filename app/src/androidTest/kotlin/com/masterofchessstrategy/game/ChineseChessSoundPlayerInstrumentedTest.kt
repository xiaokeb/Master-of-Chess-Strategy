package com.masterofchessstrategy.game

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChineseChessSoundPlayerInstrumentedTest {
    @Test
    fun allFiveCuesReachTheGameSpeakerAndReleaseRejectsPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        assertFalse(audio.isStreamMute(AudioManager.STREAM_MUSIC))
        assertTrue(audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0)
        assertTrue(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        })
        lateinit var player: ChineseChessSoundPlayer
        instrumentation.runOnMainSync { player = ChineseChessSoundPlayer(context) }
        try {
            // SoundPool decodes asynchronously; all bundled samples must become schedulable.
            await(5_000) {
                var loaded = false
                instrumentation.runOnMainSync {
                    loaded = ChineseChessSoundCue.entries.all(player::isLoaded)
                }
                loaded
            }
            val playerId = requireNotNull(APP_PLAYER.findAll(dumpAudio()).lastOrNull())
                .groupValues[1]
            val startToken = "player piid:$playerId event:started"
            val startedBefore = dumpAudio().count(startToken)
            ChineseChessSoundCue.entries.forEach { cue ->
                var accepted = false
                instrumentation.runOnMainSync { accepted = player.play(cue) }
                assertTrue("$cue must be accepted by SoundPool", accepted)
                SystemClock.sleep(400)
            }
            var startedAfter = startedBefore
            await(3_000) {
                startedAfter = dumpAudio().count(startToken)
                startedAfter >= startedBefore + 5
            }
            val releaseToken = "releasing player piid:$playerId"
            val releasedBefore = dumpAudio().count(releaseToken)
            instrumentation.runOnMainSync { player.close() }
            var releasedAfter = releasedBefore
            await(3_000) {
                releasedAfter = dumpAudio().count(releaseToken)
                releasedAfter >= releasedBefore + 1
            }
            instrumentation.runOnMainSync {
                assertFalse(player.play(ChineseChessSoundCue.MOVE))
            }
            println(
                "Xiangqi SoundPool receipt: piid=$playerId, " +
                    "starts=${startedAfter - startedBefore}, releases=${releasedAfter - releasedBefore}, " +
                    "musicVolume=${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}",
            )
        } finally {
            instrumentation.runOnMainSync { player.close() }
        }
    }

    private fun await(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Audio condition timed out" }
            SystemClock.sleep(20)
        }
    }

    private fun dumpAudio(): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys audio"),
    ).bufferedReader().use { it.readText() }

    private fun String.count(needle: String): Int {
        var matches = 0
        var offset = 0
        while (true) {
            offset = indexOf(needle, offset)
            if (offset < 0) return matches
            matches += 1
            offset += needle.length
        }
    }

    private companion object {
        val APP_PLAYER = Regex(
            "new player piid:(\\d+)[^\\r\\n]*package:com\\.masterofchessstrategy[^\\r\\n]*",
        )
    }
}
