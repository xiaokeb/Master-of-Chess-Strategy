package com.masterofchessstrategy.games.go

import java.time.LocalDate
import kotlin.random.Random

enum class GoEndgameChallengeMode(val label: String) {
    MAINLINE("主线"), THEME("主题练习"), DAILY("每日残局"), RANDOM("随机挑战"),
}

/** Selects only unlocked built-in content. Daily selections are fixed by the progress repository. */
object GoEndgameChallenges {
    fun catalog(theme: GoEndgameTheme? = null, level: GoEndgameLevel? = null): List<GoEndgamePuzzle> =
        GoEndgameLibrary.puzzles.filter { (theme == null || it.theme == theme) && (level == null || it.level == level) }

    fun available(progress: GoEndgameProgress, theme: GoEndgameTheme? = null,
        level: GoEndgameLevel? = null,
    ): List<GoEndgamePuzzle> = catalog(theme, level).filter { progress.isUnlocked(it.id) }

    fun daily(progress: GoEndgameProgress, date: LocalDate): GoEndgamePuzzle {
        if (progress.dailyDate == date) return checkNotNull(GoEndgameLibrary.puzzle(checkNotNull(progress.dailyPuzzleId)))
        val pool = available(progress)
        val index = Math.floorMod(date.toEpochDay() + GoEndgameLibrary.CONTENT_VERSION, pool.size.toLong()).toInt()
        return pool[index]
    }

    fun random(progress: GoEndgameProgress, source: Random = Random.Default,
        theme: GoEndgameTheme? = null, level: GoEndgameLevel? = null,
    ): GoEndgamePuzzle? {
        val pool = available(progress, theme, level)
        return if (pool.isEmpty()) null else pool[source.nextInt(pool.size)]
    }
}
