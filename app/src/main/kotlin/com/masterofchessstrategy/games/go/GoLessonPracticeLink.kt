package com.masterofchessstrategy.games.go

/** Only reviewed lesson/puzzle pairs can become navigation requests; progress is checked separately. */
data class GoLessonPracticeLink(val lessonId: String, val puzzleId: String) {
    init { require(puzzleId in pairs[lessonId].orEmpty()) }

    companion object {
        private val pairs = mapOf(
            "liberties" to listOf("escape-atari"),
            "connection" to listOf("bridge-groups", "join-three-groups", "link-one-eye-groups"),
            "capture" to listOf("corner-capture", "shared-liberty-double-capture"),
            "suicide" to listOf("corner-counter-capture"),
            "ko" to listOf("corner-ko-capture", "corner-snapback"),
        )

        fun forLesson(lessonId: String): List<GoLessonPracticeLink> =
            pairs[lessonId].orEmpty().mapNotNull { resolve(lessonId, it) }

        fun resolve(lessonId: String?, puzzleId: String?): GoLessonPracticeLink? {
            if (lessonId == null || puzzleId == null || puzzleId !in pairs[lessonId].orEmpty()) return null
            if (GoLearningLibrary.lesson(lessonId) == null || GoEndgameLibrary.puzzle(puzzleId) == null) return null
            return GoLessonPracticeLink(lessonId, puzzleId)
        }
    }
}
