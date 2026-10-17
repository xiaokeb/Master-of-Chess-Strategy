package com.masterofchessstrategy.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenSourceLicensesKataGoTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun kataGoTermsExposeTheReviewedDependencySourceAction() {
        var exportRequests = 0
        composeRule.setContent {
            OpenSourceLicensesContent(
                documents = LegalDocuments(
                    notices = "notices",
                    gpl = "gpl",
                    network = "pikafish network",
                    mahjongMit = "mahjong mit",
                    kataGo = "KataGo v1.18.1\nKataGo Neural Network License",
                ),
                onBack = {},
                onSaveKataGoSource = { exportRequests++ },
            )
        }

        openKataGoTab()
        composeRule.onNodeWithText("KataGo Neural Network License", substring = true).assertExists()
        composeRule.onNodeWithTag(KATAGO_SOURCE_EXPORT_TAG).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, exportRequests) }
    }

    @Test
    fun actualBundledKataGoPageIsReadableAndCaptured() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { OpenSourceLicensesScreen(onBack = {}) }

        openKataGoTab()
        composeRule.onNodeWithTag(KATAGO_SOURCE_EXPORT_TAG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("KataGo v1.18.1", substring = true).assertExists()
        composeRule.waitForIdle()

        val directory = File(
            context.getExternalFilesDir(null),
            "katago-legal-qa/${UUID.randomUUID()}",
        )
        assertTrue(directory.isDirectory || directory.mkdirs())
        val scale = context.resources.configuration.fontScale.toString().replace('.', '_')
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        try {
            File(directory, "katago-legal-font-$scale.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
    private fun openKataGoTab() {
        val tab = composeRule.onNodeWithText("KataGo 与围棋模型")
        // First reveal the horizontal strip in its vertical page, then reveal this chip.
        tab.onParent().performScrollTo()
        tab.performScrollTo().performClick()
    }
}
