package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.games.lanzhou_fangqi.LanzhouFangqiLearningContent
import java.io.IOException
import java.util.Collections

/** Version 1 covers the existing six authored lessons and three authored practice seeds. */
internal object FangqiLearningCatalog {
    const val CONTENT_VERSION = 1
    private val expectedLessons = setOf("opening", "step", "square", "capture", "history", "finish")
    private val expectedPractices = setOf(
        "lfq-practice-square-01", "lfq-practice-double-01", "lfq-practice-block-01",
    )
    val lessonIds: Set<String> = Collections.unmodifiableSet(
        LanzhouFangqiLearningContent.lessons.map { it.id }.toSet(),
    )
    val practiceIds: Set<String> = Collections.unmodifiableSet(
        LanzhouFangqiLearningContent.practices.map { it.id }.toSet(),
    )
    val keys: Set<String> = Collections.unmodifiableSet(
        (lessonIds.map { "lesson:$it" } + practiceIds.map { "practice:$it" }).toSet(),
    )

    init {
        check(lessonIds == expectedLessons && LanzhouFangqiLearningContent.lessons.size == 6)
        check(practiceIds == expectedPractices && LanzhouFangqiLearningContent.practices.size == 3)
    }

    fun lessonKey(id: String): String {
        require(id in lessonIds) { "未知方棋课程" }
        return "lesson:$id"
    }

    fun practiceKey(id: String): String {
        require(id in practiceIds) { "未知方棋练习" }
        return "practice:$id"
    }

    fun validate(row: FangqiLearningProgressEntity) {
        if (row.contentKey !in keys || row.contentVersion != CONTENT_VERSION || row.completedAtEpochMillis < 0) {
            throw IOException("方棋学习记录不兼容，原记录已保留")
        }
    }
}

/** A bounded, immutable projection of actual rows, not an implicit completion/reward writer. */
internal class FangqiLearningSnapshot(rows: List<FangqiLearningProgressEntity>) {
    val rows: List<FangqiLearningProgressEntity>
    val completedLessons: Set<String>
    val completedPractices: Set<String>
    val tutorialComplete: Boolean

    init {
        val owned = rows.toList()
        if (owned.size > FangqiLearningCatalog.keys.size || owned.map { it.contentKey }.toSet().size != owned.size) {
            throw IOException("方棋学习记录数量或主键重复，原记录已保留")
        }
        owned.forEach(FangqiLearningCatalog::validate)
        this.rows = Collections.unmodifiableList(owned.sortedBy { it.contentKey })
        completedLessons = Collections.unmodifiableSet(owned.filter { it.contentKey.startsWith("lesson:") }
            .map { it.contentKey.removePrefix("lesson:") }.toSet())
        completedPractices = Collections.unmodifiableSet(owned.filter { it.contentKey.startsWith("practice:") }
            .map { it.contentKey.removePrefix("practice:") }.toSet())
        tutorialComplete = completedLessons == FangqiLearningCatalog.lessonIds
    }
}

internal interface FangqiLearningStore {
    suspend fun load(): FangqiLearningSnapshot
    suspend fun acknowledgeLesson(id: String): FangqiLearningSnapshot
    suspend fun completePractice(id: String, completeHistory: ByteArray): FangqiLearningSnapshot
}
