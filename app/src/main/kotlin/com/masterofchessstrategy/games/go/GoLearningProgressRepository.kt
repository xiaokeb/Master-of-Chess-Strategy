package com.masterofchessstrategy.games.go

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

data class GoLearningProgress(
    val completedLessonIds: Set<String> = emptySet(),
    val lastLessonId: String = GoLearningLibrary.lessons.first().id,
) {
    init {
        require(completedLessonIds.all { GoLearningLibrary.lesson(it) != null })
        require(GoLearningLibrary.lesson(lastLessonId) != null)
    }
    val isCompleted: Boolean get() = GoLearningLibrary.lessons.all { it.id in completedLessonIds }
}

interface GoLearningProgressRepository {
    suspend fun load(): GoLearningProgress
    suspend fun save(progress: GoLearningProgress)
}

/** Dedicated atomic preferences until the shared per-game tutorial contract is integrated. */
class GoLearningPreferencesRepository internal constructor(
    private val preferences: SharedPreferences,
) : GoLearningProgressRepository {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("go-learning-v1", Context.MODE_PRIVATE),
    )

    override suspend fun load(): GoLearningProgress = withContext(Dispatchers.IO) {
        try {
            if (preferences.getInt("version", GoLearningLibrary.CONTENT_VERSION) != GoLearningLibrary.CONTENT_VERSION) {
                throw IOException("围棋教程进度版本不兼容")
            }
            GoLearningProgress(
                preferences.getStringSet("completed", emptySet())!!.toSet(),
                preferences.getString("last", GoLearningLibrary.lessons.first().id)!!,
            )
        } catch (error: IllegalArgumentException) {
            throw IOException("围棋教程进度无效", error)
        } catch (error: ClassCastException) {
            throw IOException("围棋教程进度无效", error)
        }
    }

    override suspend fun save(progress: GoLearningProgress) = withContext(Dispatchers.IO) {
        if (!preferences.edit().putInt("version", GoLearningLibrary.CONTENT_VERSION)
                .putStringSet("completed", progress.completedLessonIds.toSet())
                .putString("last", progress.lastLessonId).commit()) {
            throw IOException("围棋教程进度保存失败")
        }
    }
}
