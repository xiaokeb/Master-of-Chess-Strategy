package com.masterofchessstrategy.games.competition_mahjong.challenge

import org.junit.Assert.*
import org.junit.Test
import com.masterofchessstrategy.progress.EndgameRewardTotals
import java.time.LocalDate
import java.util.zip.CRC32
import kotlin.random.Random

class CompetitionMahjongChallengeLibraryTest {
    @Test fun catalogHasFourLevelsThemesAndTraceableOfficialFanIds() {
        val values = CompetitionMahjongChallengeLibrary.challenges
        assertEquals(26, values.size)
        assertEquals(values.size, values.map { it.id }.toSet().size)
        CompetitionMahjongChallengeLevel.entries.forEach { level ->
            assertEquals(if (level == CompetitionMahjongChallengeLevel.MASTER) 17 else 3, values.count { it.level == level })
        }
        CompetitionMahjongChallengeTheme.entries.forEach { theme ->
            assertTrue(values.any { it.theme == theme })
        }
        values.forEach { challenge ->
            assertTrue(challenge.sourceFanIds.all { it in 1..81 })
            if (CompetitionMahjongPracticePosition.isFalseWinReview(challenge.id))
                assertEquals("3.11.6", challenge.sourceRuleSection)
            else assertTrue(challenge.sourceFanIds.isNotEmpty())
            val expected = if (CompetitionMahjongPracticePosition.isDiscardTraining(challenge.id) ||
                challenge.id == CompetitionMahjongPracticePosition.ROB_KONG_ID) 0 else 1
            assertEquals(expected, challenge.choices.count { it.id == challenge.correctChoiceId })
        }
    }

    @Test fun priorFifteenQuestionPrefixAndRewardsStayStableWhileNewRulesUnlockInOrder() {
        val values = CompetitionMahjongChallengeLibrary.challenges
        val oldIds = listOf("seven-plus-flowers", "last-wall-self-draw", "edge-wait", "four-identical-chows",
            "exact-eight", "concealed-self-draw", "seven-pairs-exclusion", "seven-honors-unconnected",
            "robbing-fourth", "four-wind-pungs", "four-kongs", "nine-gates", "live-seven-pairs",
            "discard-for-seven-pairs", "live-nine-gates-exclusion")
        assertEquals(oldIds, values.take(15).map { it.id })
        var progress = CompetitionMahjongChallengeProgress(oldIds.toSet(), oldIds.last())
        assertEquals(oldIds.toSet(), checkNotNull(CompetitionMahjongChallengeProgressCodec.decode(
            CompetitionMahjongChallengeProgressCodec.encode(progress))).completedIds)
        assertEquals(EndgameRewardTotals(15, 15, 690), competitionChallengeRewards(progress.completedIds))
        val green = values[15]
        val terminals = values[16]
        assertTrue(progress.isUnlocked(green.id))
        assertFalse(progress.isUnlocked(terminals.id))
        val session = CompetitionMahjongChallengeSession(green)
        assertFalse(session.answer("b").completed)
        assertTrue(session.answer("a").completed)
        progress = progress.complete(green.id)
        assertTrue(progress.isUnlocked(terminals.id))
        assertEquals(EndgameRewardTotals(16, 16, 770), competitionChallengeRewards(progress.completedIds))
        val terminalSession = CompetitionMahjongChallengeSession(terminals)
        assertFalse(terminalSession.answer("b").completed)
        assertTrue(terminalSession.answer("c").completed)
        progress = progress.complete(terminals.id)
        assertEquals(progress.completedIds, progress.complete(terminals.id).completedIds)
        assertEquals(values.take(17).map { it.id }.toSet(), progress.completedIds)
        assertEquals(EndgameRewardTotals(17, 17, 850), competitionChallengeRewards(progress.completedIds))
        val orphans = values[17]
        assertEquals(CompetitionMahjongPracticePosition.ORPHANS_DISCARD_ID, orphans.id)
        assertTrue(progress.isUnlocked(orphans.id))
        val restored = checkNotNull(CompetitionMahjongChallengeProgressCodec.decode(
            CompetitionMahjongChallengeProgressCodec.encode(progress)))
        assertEquals(progress.completedIds, restored.completedIds)
        progress = restored.complete(orphans.id)
        assertEquals(EndgameRewardTotals(18, 18, 930), competitionChallengeRewards(progress.completedIds))
        assertEquals(values.take(18).map { it.id }.toSet(), progress.completedIds)
        for ((id, points) in listOf(CompetitionMahjongPracticePosition.PURE_STRAIGHT_DISCARD_ID to 1010,
            CompetitionMahjongPracticePosition.MIXED_TRIPLE_CHOW_DISCARD_ID to 1090,
            CompetitionMahjongPracticePosition.FOUR_CONCEALED_CLAIM_ID to 1170,
            CompetitionMahjongPracticePosition.WRONG_TILE_ID to 1250,
            CompetitionMahjongPracticePosition.NOT_READY_ID to 1330,
            CompetitionMahjongPracticePosition.ROB_KONG_ID to 1410,
            CompetitionMahjongPracticePosition.TRIPLE_PUNG_DISCARD_ID to 1490,
            CompetitionMahjongPracticePosition.FLOWER_REPLACEMENT_ID to 1570)) {
            assertTrue(progress.isUnlocked(id))
            progress = progress.complete(id)
            assertEquals(EndgameRewardTotals(progress.completedIds.size, progress.completedIds.size, points),
                competitionChallengeRewards(progress.completedIds))
        }
    }

    @Test fun sessionKeepsQuestionStableAcrossWrongAnswerAndLocksAfterCorrectAnswer() {
        val challenge = CompetitionMahjongChallengeLibrary.challenge("last-wall-self-draw")!!
        val session = CompetitionMahjongChallengeSession(challenge)
        val wrong = session.answer("b")
        assertFalse(wrong.completed)
        assertEquals(1, wrong.attempts)
        assertSame(challenge, wrong.challenge)

        val correct = session.answer("a")
        assertTrue(correct.completed)
        assertEquals(2, correct.attempts)
        assertTrue(correct.feedback.contains("妙手回春"))
        assertEquals(correct, session.answer("b"))
        assertEquals(0, session.reset().attempts)
    }

    @Test fun mainlineProgressUnlocksExactlyOneNextQuestionAndRejectsGaps() {
        val values = CompetitionMahjongChallengeLibrary.challenges
        var progress = CompetitionMahjongChallengeProgress()
        assertTrue(progress.isUnlocked(values[0].id))
        assertFalse(progress.isUnlocked(values[1].id))
        assertThrows(IllegalArgumentException::class.java) { progress.complete(values[1].id) }

        progress = progress.complete(values[0].id)
        assertTrue(progress.isUnlocked(values[1].id))
        assertFalse(progress.isUnlocked(values[2].id))
        val selected = progress.select(values[1].id, CompetitionMahjongChallengeMode.THEME)
        assertEquals(values[1].id, selected.lastChallengeId)
        assertEquals(CompetitionMahjongChallengeMode.THEME, selected.lastMode)
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongChallengeProgress(setOf(values[1].id))
        }
    }

    @Test fun progressCodecRoundTripsCanonicalStateAndRejectsDamageOrReordering() {
        val values = CompetitionMahjongChallengeLibrary.challenges
        val base = CompetitionMahjongChallengeProgress().complete(values[0].id)
            .select(values[1].id, CompetitionMahjongChallengeMode.RANDOM)
        val bytes = CompetitionMahjongChallengeProgressCodec.encode(base)
        val decoded = CompetitionMahjongChallengeProgressCodec.decode(bytes)!!
        assertEquals(base.completedIds, decoded.completedIds)
        assertEquals(base.lastChallengeId, decoded.lastChallengeId)
        assertEquals(base.lastMode, decoded.lastMode)

        assertNull(CompetitionMahjongChallengeProgressCodec.decode(bytes.copyOf().also {
            it[it.lastIndex - 2] = (it[it.lastIndex - 2].toInt() xor 1).toByte()
        }))
        assertNull(CompetitionMahjongChallengeProgressCodec.decode(ByteArray(
            CompetitionMahjongChallengeProgressCodec.MAX_BYTES + 1)))

        val text = bytes.toString(Charsets.UTF_8).replace("\n${values[0].id}\n", "\n${values[1].id},${values[0].id}\n")
        assertNull(CompetitionMahjongChallengeProgressCodec.decode(withFixedCrc(text)))
    }

    @Test fun dailyIsStableAndRandomNeverReturnsLockedContent() {
        val values = CompetitionMahjongChallengeLibrary.challenges
        var progress = CompetitionMahjongChallengeProgress()
        repeat(5) { progress = progress.complete(values[it].id) }
        val date = LocalDate.of(2026, 10, 15)
        val daily = CompetitionMahjongChallenges.daily(progress, date)
        assertTrue(progress.isUnlocked(daily.id))
        val fixed = progress.selectDaily(date, daily.id)
        assertEquals(daily.id, CompetitionMahjongChallenges.daily(fixed, date).id)

        repeat(50) {
            val challenge = CompetitionMahjongChallenges.random(progress, Random(it))!!
            assertTrue(progress.isUnlocked(challenge.id))
        }
        assertNull(CompetitionMahjongChallenges.random(progress,
            level = CompetitionMahjongChallengeLevel.MASTER))
    }

    private fun withFixedCrc(text: String): ByteArray {
        val lines = text.split('\n').toMutableList()
        val payload = lines.take(8).joinToString("\n", postfix = "\n")
        lines[8] = CRC32().apply { update(payload.toByteArray(Charsets.UTF_8)) }
            .value.toString(16).padStart(8, '0')
        return lines.joinToString("\n").toByteArray(Charsets.UTF_8)
    }
}
