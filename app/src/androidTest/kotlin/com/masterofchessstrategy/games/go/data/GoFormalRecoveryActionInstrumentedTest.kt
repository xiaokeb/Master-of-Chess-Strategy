package com.masterofchessstrategy.games.go.data

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.ui.GoFormalProcessCheckActivity
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Exercises the production route after a process-restart driver has preserved its isolated database. */
class GoFormalRecoveryActionInstrumentedTest {
    @Test fun actOnRestoredGame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("go_process_case")
        assumeNotNull(mode)
        require(mode in setOf("standard", "opening", "continuation"))
        val token = requireNotNull(args.getString("go_process_token"))
        require(UUID.fromString(token).toString() == token)

        val context = instrumentation.targetContext
        val fixture = "go-formal-process-qa-$token"
        val receipt = File(context.filesDir, "go-formal-process-qa/$fixture.json")
        val previous = JSONObject(receipt.readText())
        val matchId = UUID.nameUUIDFromBytes("$token:$mode".toByteArray(Charsets.UTF_8)).toString()
        assertEquals(matchId, previous.getString("match_id"))

        val automation = instrumentation.uiAutomation
        context.startActivity(Intent(context, GoFormalProcessCheckActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("owned_database", "$fixture.db")
            .putExtra("match_id", matchId))
        val restored = waitForReceipt(receipt) { it.getInt("pid") != previous.getInt("pid") }
        for (key in previous.keys()) {
            if (key != "pid") assertEquals(key, previous.get(key), restored.get(key))
        }

        val updated = when (mode) {
            "standard" -> {
                click(automation, "第5行第5列", byDescription = true)
                waitForReceipt(receipt) { it.getInt("event_count") == 4 }.also {
                    assertEquals(matchId, it.getString("match_id"))
                    assertNotEquals(restored.getString("checkpoint_sha256"), it.getString("checkpoint_sha256"))
                }
            }
            "opening" -> {
                click(automation, "重新开始")
                click(automation, "确认重开")
                waitForReceipt(receipt) { it.getString("match_id") != matchId }.also {
                    assertEquals(2, it.getInt("event_count"))
                    assertEquals(2, it.getInt("opening_event_count"))
                    assertEquals(restored.getString("opening_sha256"), it.getString("opening_sha256"))
                }
            }
            else -> {
                click(automation, "白方确认", scroll = false)
                waitForReceipt(receipt) { it.getInt("series_game") == 3 }.also {
                    assertNotEquals(matchId, it.getString("match_id"))
                    assertEquals(3, it.getInt("series_limit"))
                    assertEquals(1, it.getInt("archive_count"))
                    assertEquals(1, it.getInt("record_count"))
                    assertEquals(restored.getString("opening_sha256"), it.getString("opening_sha256"))
                }
            }
        }
        assertEquals(restored.getInt("pid"), updated.getInt("pid"))
        val evidence = File(requireNotNull(context.getExternalFilesDir(null)), "go-formal-recovery-$token")
        check(evidence.mkdir())
        File(evidence, "restored.json").writeText(restored.toString())
        File(evidence, "updated.json").writeText(updated.toString())
        val screenshot = checkNotNull(automation.takeScreenshot())
        try {
            File(evidence, "$mode.png").outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            screenshot.recycle()
        }
    }

    private fun waitForReceipt(file: File, ready: (JSONObject) -> Boolean): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val value = JSONObject(file.readText())
            if (ready(value)) return value
            SystemClock.sleep(200)
        }
        error("Recovery receipt did not reach the expected state")
    }

    private fun click(automation: UiAutomation, label: String, byDescription: Boolean = false, scroll: Boolean = true) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var visibleLabels = emptyList<String>()
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = automation.rootInActiveWindow
            if (root?.packageName?.toString() == "com.masterofchessstrategy") {
                val visible = descendants(root).filter { it.isVisibleToUser }.toList()
                visibleLabels = visible.mapNotNull { it.text?.toString() }
                val target = visible.firstOrNull {
                    it.isEnabled && if (byDescription) it.contentDescription?.startsWith(label) == true
                    else it.text?.toString() == label
                }
                if (target != null) {
                    val clickable = generateSequence(target) { it.parent }.firstOrNull { it.isClickable }
                    if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
                } else if (scroll) {
                    val scroller = visible.firstOrNull { it.isScrollable }
                    if (scroller?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != true) {
                        scroller?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)
                    }
                }
            }
            SystemClock.sleep(200)
        }
        error("Cannot activate $label on the restored Go board. Visible text: $visibleLabels")
    }

    private fun descendants(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(descendants(it)) }
    }
}
