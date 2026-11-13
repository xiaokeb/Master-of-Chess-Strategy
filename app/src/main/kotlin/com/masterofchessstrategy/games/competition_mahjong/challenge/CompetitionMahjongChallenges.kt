package com.masterofchessstrategy.games.competition_mahjong.challenge

import java.time.LocalDate
import kotlin.random.Random

object CompetitionMahjongChallenges {
    fun catalog(theme: CompetitionMahjongChallengeTheme? = null,
                level: CompetitionMahjongChallengeLevel? = null): List<CompetitionMahjongChallenge> =
        CompetitionMahjongChallengeLibrary.challenges.filter {
            (theme == null || it.theme == theme) && (level == null || it.level == level)
        }

    fun available(progress: CompetitionMahjongChallengeProgress,
                  theme: CompetitionMahjongChallengeTheme? = null,
                  level: CompetitionMahjongChallengeLevel? = null): List<CompetitionMahjongChallenge> =
        catalog(theme, level).filter { progress.isUnlocked(it.id) }

    fun daily(progress: CompetitionMahjongChallengeProgress, date: LocalDate): CompetitionMahjongChallenge {
        if (progress.dailyDate == date) return checkNotNull(
            CompetitionMahjongChallengeLibrary.challenge(checkNotNull(progress.dailyChallengeId)))
        val pool = available(progress)
        require(pool.isNotEmpty())
        val index = Math.floorMod(date.toEpochDay() + CompetitionMahjongChallengeLibrary.CONTENT_VERSION,
            pool.size.toLong()).toInt()
        return pool[index]
    }

    fun random(progress: CompetitionMahjongChallengeProgress, random: Random = Random.Default,
               theme: CompetitionMahjongChallengeTheme? = null,
               level: CompetitionMahjongChallengeLevel? = null): CompetitionMahjongChallenge? {
        val pool = available(progress, theme, level)
        return if (pool.isEmpty()) null else pool[random.nextInt(pool.size)]
    }
}
