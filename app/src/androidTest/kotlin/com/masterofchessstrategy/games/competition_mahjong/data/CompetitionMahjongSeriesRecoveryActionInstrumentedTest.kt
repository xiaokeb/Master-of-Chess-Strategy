package com.masterofchessstrategy.games.competition_mahjong.data

import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Opens a seeded production table and checks either side of a real QA process restart. */
class CompetitionMahjongSeriesRecoveryActionInstrumentedTest {
    @Test fun openSeriesAtSettlementBoundary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("mahjong_process_token")
        assumeNotNull(token)
        require(UUID.fromString(token).toString() == token)
        val hands = requireNotNull(args.getString("mahjong_settled_hands")).toInt()
        require(hands == 15 || hands == 16)
        val reopening = args.getString("mahjong_process_reopen") == "true"
        val context = instrumentation.targetContext
        val database = "competition-mahjong-process-qa-$token.db"
        val receipt = File(context.filesDir, "competition-mahjong-process-qa/$token.json")
        val previous = if (reopening) JSONObject(receipt.readText()) else null
        val evidence = File(requireNotNull(context.getExternalFilesDir(null)), "mahjong-series-recovery-$token")
        check(evidence.isDirectory || evidence.mkdir())
        val automation = instrumentation.uiAutomation
        val launch = automation.executeShellCommand(
            "am start -W -n com.masterofchessstrategy/.games.competition_mahjong.CompetitionMahjongFormalProcessCheckActivity " +
                "--es owned_database $database",
        )
        val status = ParcelFileDescriptor.AutoCloseInputStream(launch).bufferedReader().use { it.readText() }
        check(status.lineSequence().any { it.trim() == "Status: ok" }) { status }
        click(automation, "开始或继续赛事")

        val boundary = waitForReceipt(receipt) {
            it.getString("database") == database && it.getInt("settled_hands") == hands &&
                (previous == null || it.getInt("pid") != previous.getInt("pid"))
        }
        assertEquals(hands - 1, boundary.getInt("hand_index"))
        assertEquals(if (hands == 16) 1 else 0, boundary.getInt("archives"))
        assertEquals("POSITION_SEEDED_WMO_KONGS", boundary.getString("strategy"))
        assertEquals(3, boundary.getInt("session_version"))
        assertEquals(3, boundary.getInt("round_version"))
        check(boundary.getString("phase") in setOf("WON", "EXHAUSTIVE_DRAW"))
        if (previous != null) {
            assertNotEquals(previous.getInt("pid"), boundary.getInt("pid"))
            for (key in previous.keys()) {
                if (key != "pid") assertEquals(key, previous.get(key), boundary.get(key))
            }
        }
        save(automation, evidence, if (reopening) "restored" else "before", boundary)
        if (reopening && hands == 15) {
            click(automation, "下一盘")
            val next = waitForReceipt(receipt) { it.getInt("hand_index") == 15 }
            for (key in listOf("pid", "match_id", "settled_hands", "scores", "archives", "strategy", "session_version", "round_version")) {
                assertEquals(key, boundary.get(key), next.get(key))
            }
            assertNotEquals(boundary.getString("checkpoint_sha256"), next.getString("checkpoint_sha256"))
            save(automation, evidence, "next", next)
        }
    }

    private fun waitForReceipt(file: File, ready: (JSONObject) -> Boolean): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (file.isFile) {
                val value = JSONObject(file.readText())
                if (ready(value)) return value
            }
            SystemClock.sleep(200)
        }
        error("No committed Mahjong settlement receipt")
    }

    private fun click(automation: UiAutomation, label: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var labels = emptyList<String>()
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = automation.rootInActiveWindow
            if (root?.packageName?.toString() == "com.masterofchessstrategy") {
                val visible = descendants(root).filter { it.isVisibleToUser }.toList()
                labels = visible.mapNotNull { it.text?.toString() }
                val node = visible.firstOrNull { it.isEnabled && it.text?.toString() == label }
                val control = generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
                if (control?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
                if (node == null) {
                    visible.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
            }
            SystemClock.sleep(200)
        }
        error("Cannot activate $label. Visible text: $labels")
    }

    private fun save(automation: UiAutomation, directory: File, name: String, receipt: JSONObject) {
        File(directory, "$name.json").writeText(receipt.toString())
        val image = checkNotNull(automation.takeScreenshot())
        try {
            File(directory, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            image.recycle()
        }
    }

    private fun descendants(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(descendants(it)) }
    }
}
