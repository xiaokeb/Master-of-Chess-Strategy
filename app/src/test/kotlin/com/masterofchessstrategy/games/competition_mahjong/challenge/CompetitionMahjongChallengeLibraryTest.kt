package com.masterofchessstrategy.games.competition_mahjong.challenge

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.zip.CRC32
import kotlin.random.Random

class CompetitionMahjongChallengeLibraryTest {
    @Test fun catalogHasFourLevelsThemesAndTraceableOfficialFanIds() {
        val values = CompetitionMahjongChallengeLibrary.challenges
        assertEquals(14, values.size)
        assertEquals(values.size, values.map { it.id }.toSet().size)
        CompetitionMahjongChallengeLevel.entries.forEach { level ->
            assertEquals(if (level == CompetitionMahjongChallengeLevel.MASTER) 5 else 3, values.count { it.level == level })
        }
        CompetitionMahjongChallengeTheme.entries.forEach { theme ->
            assertTrue(values.any { it.theme == theme })
        }
        values.forEach { challenge ->
            assertTrue(challenge.sourceFanIds.all { it in 1..81 })
            val expected = if (challenge.id == CompetitionMahjongPracticePosition.DISCARD_PUZZLE_ID) 0 else 1
            assertEquals(expected, challenge.choices.count { it.id == challenge.correctChoiceId })
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
