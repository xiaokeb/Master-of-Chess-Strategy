package com.masterofchessstrategy.games.competition_mahjong.data

import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningBackupAdapter
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningProof
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningProofCatalog
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningProofCodec
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningProofRequest
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongAssistanceUsage
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshot
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshotCodec
import com.masterofchessstrategy.games.competition_mahjong.match.storage.formalCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.storage.formalPolicy
import org.junit.Assert.*
import org.junit.Test

/** Accepted inputs contain no active Native session; locked active fixtures reject before JNI. */
class CompetitionMahjongFormalRestorePreparerTest {
    @Test fun missingLearningCreatesFourExplicitEmptySeatsWithNoUnlockedDifficulty() {
        val plan = prepare(emptyList())
        assertEquals((0..3).toList(), plan.candidates.map { it.viewerPlayerId })
        plan.candidates.forEach { candidate ->
            assertFalse(candidate.playPolicy.progress.tutorialCompleted)
            assertTrue(candidate.playPolicy.allowedBotLevels.isEmpty())
            val snapshot = CompetitionMahjongFormalSnapshotCodec.decode(candidate.mctx, candidate.viewerPlayerId)!!
            assertNull(snapshot.active)
            assertEquals(0, snapshot.statistics.seriesCount)
            assertTrue(snapshot.history.entries.isEmpty())
        }
    }

    @Test fun emptyAndPartialProofsDoNotSatisfyTheCourseGate() {
        for (count in 0..3) {
            val plan = prepare(listOf(learning(count)))
            plan.candidates.forEach { candidate ->
                assertFalse(candidate.playPolicy.progress.tutorialCompleted)
                assertTrue(candidate.playPolicy.allowedBotLevels.isEmpty())
                assertZeroWins(candidate.playPolicy.progress)
            }
        }
    }

    @Test fun allFourChaptersUnlockOnlyEasyForEverySeat() {
        val plan = prepare(listOf(learning(4)))
        plan.candidates.forEach { candidate ->
            assertTrue(candidate.playPolicy.progress.tutorialCompleted)
            assertEquals(listOf(Difficulty.EASY), candidate.playPolicy.options.filter { it.unlocked }.map { it.difficulty })
            assertZeroWins(candidate.playPolicy.progress)
        }
    }

    @Test fun candidateLearningIsOrderIndependentAndUnrelatedRecordsDoNotGrantPermission() {
        val other = GameBackupRecord("GO_PROGRESS", listOf("1", "unrelated"))
        val oldTutorial = GameBackupRecord("TUTORIAL", listOf("2", "1", "4", "true", "100"))
        assertFalse(prepare(listOf(other, oldTutorial)).candidates[0].playPolicy.progress.tutorialCompleted)
        for (records in listOf(listOf(other, learning(4), oldTutorial), listOf(learning(4), oldTutorial, other))) {
            assertTrue(prepare(records).candidates.all { it.playPolicy.progress.tutorialCompleted })
        }
    }

    @Test fun malformedLearningEnvelopeHexAndMclpAreRejected() {
        val good = learning(4)
        val empty = CompetitionMahjongLearningProofCodec.encode(CompetitionMahjongLearningProof())
        val invalid = listOf(
            listOf("3", good.fields[1]), listOf("02", good.fields[1]), listOf("2"),
            listOf("2", good.fields[1], "extra"), listOf("2", good.fields[1].uppercase()),
            listOf("2", "0"), listOf("2", "gg"), listOf("2", "00".repeat(4097)),
            listOf("2", hex(empty.copyOf().also { it[0] = 0 })),
            listOf("2", hex(empty.copyOf().also { it[4] = 2 })),
            listOf("2", hex(empty.copyOf().also { it[5] = 2 })),
            listOf("2", good.fields[1].dropLast(2)),
        )
        invalid.forEach { fields ->
            assertThrows(IllegalArgumentException::class.java) {
                prepare(listOf(GameBackupRecord(good.kind, fields)))
            }
        }
    }

    @Test fun duplicateProofsRejectInsteadOfPickingACompleteOrFirstRecord() {
        for (records in listOf(listOf(learning(4), learning(4)), listOf(learning(1), learning(4)), listOf(learning(4), learning(1)))) {
            assertThrows(IllegalArgumentException::class.java) { prepare(records) }
        }
    }

    @Test fun legacyOnlyAndLegacyMixedWithFormalRemainStrictlyRejected() {
        val legacy = GameBackupRecord("COMPETITION_MATCH", listOf("2", "legacy"))
        for (records in listOf(listOf(legacy), listOf(learning(4), legacy), listOf(legacy, emptyFormal(0), learning(4)))) {
            assertThrows(IllegalArgumentException::class.java) { prepare(records) }
        }
    }

    @Test fun wholeContainerRejectsDuplicateAndBrokenFormalSeatsWithoutReturningAPartialPlan() {
        val first = emptyFormal(0)
        assertThrows(IllegalArgumentException::class.java) { prepare(listOf(learning(4), first, first)) }
        val brokenLast = GameBackupRecord(CompetitionMahjongFormalBackupBundle.KIND, listOf("2", "3", "ff"))
        assertThrows(IllegalArgumentException::class.java) {
            prepare(listOf(learning(4), first, emptyFormal(1), emptyFormal(2), brokenLast))
        }
    }

    @Test fun explicitSeatsArePreservedAndOnlyMissingSeatsAreMaterializedEmpty() {
        val records = listOf(emptyFormal(3), learning(4), emptyFormal(1))
        val plan = prepare(records)
        for (viewer in 0..3) {
            val expected = CompetitionMahjongFormalSnapshotCodec.encode(CompetitionMahjongFormalSnapshot(viewer))
            assertArrayEquals(expected, plan.candidates[viewer].mctx)
            assertTrue(plan.candidates[viewer].playPolicy.progress.tutorialCompleted)
        }
    }

    @Test fun oldHistoryAndStatisticsSurviveButDoNotCreateTrustedWins() {
        var snapshot = CompetitionMahjongFormalSnapshot(0)
        repeat(10) { index -> snapshot = snapshot.save(formalCheckpoint(id = index + 1), formalPolicy(), index.toLong()) }
        val historyOnly = CompetitionMahjongFormalSnapshot(0, statistics = snapshot.statistics, history = snapshot.history)
        val record = CompetitionMahjongFormalBackupBundle.encode(0, CompetitionMahjongFormalSnapshotCodec.encode(historyOnly))
        val candidate = prepare(listOf(learning(4), record)).candidates[0]
        assertEquals(10, CompetitionMahjongFormalSnapshotCodec.decode(candidate.mctx, 0)!!.statistics.seriesCount)
        assertZeroWins(candidate.playPolicy.progress)
        assertEquals(listOf(Difficulty.EASY), candidate.playPolicy.options.filter { it.unlocked }.map { it.difficulty })
    }

    @Test fun missingOrPartialLearningRejectsAnActiveEasyMatchBeforeNativeCreation() {
        val record = lockedActive(Difficulty.EASY)
        for (records in listOf(listOf(record), listOf(learning(3), record))) {
            assertThrows(IllegalArgumentException::class.java) { prepare(records) }
        }
    }

    @Test fun fullLearningCannotAuthorizeMediumHardOrMasterFromRestoredActiveMetadata() {
        for (difficulty in listOf(Difficulty.MEDIUM, Difficulty.HARD, Difficulty.MASTER)) {
            assertThrows(IllegalArgumentException::class.java) { prepare(listOf(learning(4), lockedActive(difficulty))) }
        }
    }

    @Test fun strategyBindingComesFromTheCallerAndIsCopiedIntoThePolicy() {
        val bindings = Difficulty.entries.associateWith { 3 - it.code }.toMutableMap()
        val records = mutableListOf(learning(4), emptyFormal(2))
        val plan = CompetitionMahjongFormalRestorePreparer.prepare(records, bindings)
        records.clear()
        bindings[Difficulty.EASY] = 0
        assertEquals(listOf(3), plan.candidates[2].playPolicy.allowedBotLevels)
        assertTrue(plan.candidates[2].playPolicy.progress.tutorialCompleted)
    }

    @Test fun invalidOrIncompleteStrategyBindingsRejectBeforeProducingAPlan() {
        val invalid = listOf(emptyMap(), mapOf(Difficulty.EASY to 0), Difficulty.entries.associateWith { 0 },
            Difficulty.entries.associateWith { it.code + 1 })
        invalid.forEach { bindings ->
            assertThrows(IllegalArgumentException::class.java) {
                CompetitionMahjongFormalRestorePreparer.prepare(listOf(learning(4)), bindings)
            }
        }
    }

    private fun prepare(records: List<GameBackupRecord>) = CompetitionMahjongFormalRestorePreparer.prepare(
        records, Difficulty.entries.associateWith { it.code },
    )

    private fun learning(count: Int): GameBackupRecord {
        val proof = (0 until count).fold(CompetitionMahjongLearningProof()) { current, index ->
            val chapter = CompetitionMahjongLearningProofCatalog.chapterIds[index]
            current.confirm(CompetitionMahjongLearningProofRequest(
                "00000000-0000-0000-0000-${(index + 1).toString().padStart(12, '0')}",
                chapter, CompetitionMahjongLearningProofCatalog.cardIds(chapter), index.toLong()))
        }
        return CompetitionMahjongLearningBackupAdapter().encode(CompetitionMahjongLearningProofCodec.encode(proof))
    }

    private fun emptyFormal(viewer: Int) = CompetitionMahjongFormalBackupBundle.encode(viewer,
        CompetitionMahjongFormalSnapshotCodec.encode(CompetitionMahjongFormalSnapshot(viewer)))

    private fun lockedActive(difficulty: Difficulty): GameBackupRecord {
        val original = formalCheckpoint(count = 0)
        val checkpoint = CompetitionMahjongMatchCheckpoint(original.config.copy(botLevel = difficulty.code),
            original.ledger, 0, original.engineRecord, CompetitionMahjongAssistanceUsage(difficulty))
        val snapshot = CompetitionMahjongFormalSnapshot(0).save(checkpoint, formalPolicy(), 0)
        return CompetitionMahjongFormalBackupBundle.encode(0, CompetitionMahjongFormalSnapshotCodec.encode(snapshot))
    }

    private fun assertZeroWins(progress: com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongUnlockProgress) {
        assertEquals(0L, progress.easyWins)
        assertEquals(0L, progress.mediumWins)
        assertEquals(0L, progress.hardWins)
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
