package com.masterofchessstrategy.games.go

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.MainActivity
import com.masterofchessstrategy.ui.GO_LEARNING_ENTRY_TAG
import com.masterofchessstrategy.ui.GO_MODES_BACK_TAG
import com.masterofchessstrategy.ui.GO_MODES_TAG
import com.masterofchessstrategy.ui.HOME_GO_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Read-only tutorial navigation in the production landscape Activity. */
class GoLearningLandscapeNavigationTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun productionLandscapeTutorialAndCatalogPreserveProgress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("go-learning-v1", Context.MODE_PRIVATE)
        val before = preferences.all.toMap()
        val directory = File(context.getExternalFilesDir(null), "go-learning-landscape-qa/${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        composeRule.onNodeWithTag(HOME_GO_TAG).performScrollTo().performClick()
        composeRule.onNodeWithTag(GO_LEARNING_ENTRY_TAG).performScrollTo()
        capture(directory, "modes")
        composeRule.onNodeWithTag(GO_LEARNING_ENTRY_TAG).performClick()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodes(hasTestTag("go_lesson_title")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("go_lesson_title").performScrollTo()
        capture(directory, "tutorial")
        composeRule.onNodeWithText("知识点目录").performScrollTo().performClick()
        composeRule.onNodeWithText(GoLearningLibrary.lessons.first().title).performScrollTo()
        capture(directory, "catalog-first")
        composeRule.onNodeWithText(GoLearningLibrary.lessons.last().title).performScrollTo()
        capture(directory, "catalog-last")
        composeRule.onNodeWithText("关闭").performClick()
        composeRule.onNodeWithText("返回").performScrollTo().performClick()
        composeRule.onNodeWithTag(GO_MODES_TAG).assertExists()
        composeRule.onNodeWithTag(GO_MODES_BACK_TAG).performScrollTo().performClick()
        composeRule.onNodeWithTag(HOME_GO_TAG).assertExists()
        assertEquals(before, preferences.all)
    }

    private fun capture(directory: File, name: String) {
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        try {
            assertTrue("Production Activity must render landscape", bitmap.width > bitmap.height)
            File(directory, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
