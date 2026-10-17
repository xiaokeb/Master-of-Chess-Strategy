package com.masterofchessstrategy.games.go

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.UUID

class GoLearningProgressInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("go-learning-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    private val repository = GoLearningPreferencesRepository(preferences)

    @After fun clearTestPreferences() { assertTrue(preferences.edit().clear().commit()) }

    @Test fun partialAndCompleteProgressRoundTripWithoutPrematureUnlock() = runBlocking {
        val ids = GoLearningLibrary.lessons.map { it.id }.toSet()
        val partial = GoLearningProgress(ids - GoLearningLibrary.lessons.last().id, GoLearningLibrary.lessons.last().id)
        repository.save(partial)
        assertEquals(partial, repository.load())
        assertFalse(repository.load().isCompleted)
        val all = partial.copy(completedLessonIds = ids)
        repository.save(all)
        assertEquals(all, repository.load())
        assertTrue(repository.load().isCompleted)
    }

    @Test fun incompatibleVersionIsPreservedAndReported() = runBlocking {
        assertTrue(preferences.edit().putInt("version", 99).commit())
        var rejected = false
        try { repository.load() } catch (_: IOException) { rejected = true }
        assertTrue(rejected)
        assertEquals(99, preferences.getInt("version", 0))
    }

    @Test fun unknownCompletedLessonIsPreservedAndReported() = runBlocking {
        assertTrue(preferences.edit().putStringSet("completed", setOf("unknown")).commit())
        var rejected = false
        try { repository.load() } catch (_: IOException) { rejected = true }
        assertTrue(rejected)
        assertEquals(setOf("unknown"), preferences.getStringSet("completed", emptySet()))
    }
}
