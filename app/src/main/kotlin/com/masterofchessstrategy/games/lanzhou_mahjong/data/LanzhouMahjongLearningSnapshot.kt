package com.masterofchessstrategy.games.lanzhou_mahjong.data

import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

/** Independent ten-lesson progress; legacy generic four-step rows are not this content. */
internal data class LanzhouMahjongLearningSnapshot(
    val contentVersion: Int,
    val completedCount: Int,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(contentVersion == CONTENT_VERSION)
        require(completedCount in 0..LESSON_COUNT && updatedAtEpochMillis >= 0)
    }

    val isCompleted: Boolean get() = completedCount == LESSON_COUNT

    companion object {
        const val CONTENT_VERSION = 2
        const val LESSON_COUNT = 10
    }
}

/** Capture once after a correct answer. Retrying reuses these exact values. */
internal data class LanzhouMahjongLearningAdvance(
    val before: LanzhouMahjongLearningSnapshot?,
    val after: LanzhouMahjongLearningSnapshot,
) {
    init {
        require(after.completedCount == (before?.completedCount ?: 0) + 1)
        require(after.updatedAtEpochMillis >= (before?.updatedAtEpochMillis ?: 0))
    }
}

internal interface LanzhouMahjongLearningRepository {
    val ownerEpoch: Long
    val epoch: StateFlow<Long>
    suspend fun load(): LanzhouMahjongLearningSnapshot?
    suspend fun advance(request: LanzhouMahjongLearningAdvance): LanzhouMahjongLearningSnapshot
}

internal class LanzhouMahjongLearningConflictException : IOException("教程进度已变化，请重新读取")
