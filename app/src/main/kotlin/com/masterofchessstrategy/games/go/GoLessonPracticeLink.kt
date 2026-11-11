package com.masterofchessstrategy.games.go

/** Only reviewed lesson/puzzle pairs can become navigation requests; progress is checked separately. */
data class GoLessonPracticeLink(val lessonId: String, val puzzleId: String) {
    init { require(pairs[lessonId] == puzzleId) }

    companion object {
        private val pairs = mapOf(
            "liberties" to "escape-atari",
            "connection" to "bridge-groups",
            "capture" to "corner-capture",
        )

        fun forLesson(lessonId: String): GoLessonPracticeLink? =
            pairs[lessonId]?.let { resolve(lessonId, it) }

        fun resolve(lessonId: String?, puzzleId: String?): GoLessonPracticeLink? {
            if (lessonId == null || puzzleId == null || pairs[lessonId] != puzzleId) return null
            if (GoLearningLibrary.lesson(lessonId) == null || GoEndgameLibrary.puzzle(puzzleId) == null) return null
            return GoLessonPracticeLink(lessonId, puzzleId)
        }
    }
}
