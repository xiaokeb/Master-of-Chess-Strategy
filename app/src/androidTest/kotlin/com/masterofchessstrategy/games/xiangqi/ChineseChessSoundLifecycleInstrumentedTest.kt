package com.masterofchessstrategy.game

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.MainActivity
import com.masterofchessstrategy.ui.ChineseChessSoundEffect
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose settings changes and disposal, without touching the user's settings or games. */
@RunWith(AndroidJUnit4::class)
class ChineseChessSoundLifecycleInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun disabledEventsAreDiscardedAndLeavingTheDestinationReleasesSoundPool() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val events = MutableSharedFlow<ChineseChessSoundCue>(extraBufferCapacity = 8)
        var enabled by mutableStateOf(false)
        var mounted by mutableStateOf(true)
        instrumentation.runOnMainSync {
            compose.activity.setContent {
                if (mounted) ChineseChessSoundEffect(events, enabled)
            }
        }
        try {
            compose.waitUntil(5_000) { events.subscriptionCount.value == 1 }
            val playerId = requireNotNull(APP_PLAYER.findAll(dumpAudio()).lastOrNull()).groupValues[1]
            val started = "player piid:$playerId event:started"
            val before = count(started)
            emit(events, ChineseChessSoundCue.VICTORY)
            SystemClock.sleep(700)
            assertEquals(before, count(started))

            compose.runOnIdle { enabled = true }
            compose.waitForIdle()
            SystemClock.sleep(200)
            assertEquals("Enabling cannot replay the muted event", before, count(started))
            emit(events, ChineseChessSoundCue.MOVE)
            await { count(started) == before + 1 }

            compose.runOnIdle { enabled = false }
            compose.waitForIdle()
            emit(events, ChineseChessSoundCue.DEFEAT)
            SystemClock.sleep(700)
            assertEquals(before + 1, count(started))
            compose.runOnIdle { enabled = true }
            compose.waitForIdle()
            SystemClock.sleep(200)
            assertEquals(before + 1, count(started))
            emit(events, ChineseChessSoundCue.CAPTURE)
            await { count(started) == before + 2 }

            compose.runOnIdle { mounted = false }
            compose.waitForIdle()
            await { dumpAudio().contains("releasing player piid:$playerId") }
            compose.waitUntil(5_000) { events.subscriptionCount.value == 0 }
            emit(events, ChineseChessSoundCue.DRAW)
            SystemClock.sleep(200)
            assertEquals(before + 2, count(started))
        } finally {
            compose.runOnIdle { mounted = false }
            compose.waitForIdle()
        }
    }

    private fun count(token: String): Int = dumpAudio().lineSequence().count { it.contains(token) }

    private fun emit(events: MutableSharedFlow<ChineseChessSoundCue>, cue: ChineseChessSoundCue) {
        assertTrue(events.tryEmit(cue))
        // The Compose v2 rule owns LaunchedEffect's test dispatcher.
        compose.waitForIdle()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!condition()) {
            compose.waitForIdle()
            check(SystemClock.uptimeMillis() < deadline) { "SoundPool lifecycle receipt timed out" }
            SystemClock.sleep(20)
        }
    }

    private fun dumpAudio(): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys audio"),
    ).bufferedReader().use { it.readText() }

    private companion object {
        val APP_PLAYER = Regex(
            "new player piid:(\\d+)[^\\r\\n]*package:com\\.masterofchessstrategy[^\\r\\n]*",
        )
    }
}
