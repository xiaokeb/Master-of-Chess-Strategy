package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.PreparedGameBackup
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.GoPlayer
import com.masterofchessstrategy.games.go.*
import org.junit.Assert.*
import org.junit.Test

/** Immutable admitted-fact doubles; no Native replay, Room write or reward eligibility claim. */
class GoAiBackupSnapshotGuardTest {
    private val source = GoAiCheckpointLineage(GoAiCheckpointOrigin.UNVERIFIED_SESSION)
    private fun match(id: String = "one", terminal: Boolean = true, reserved: Boolean = false,
        series: Boolean = false, max: Int = 5, number: Int = 2, payload: Int = 0,
        undo: Int = 0, hint: Int = 0, automaticUsed: Boolean = true,
        human: GoPlayer? = null, opening: ByteArray? = null) =
        GoAiSavedMatch(id, GoAiMatchConfig(9, humanPlayer = human),
            byteArrayOf(if (terminal) 1 else 0, payload.toByte()), undo, hint, reserved,
            human ?: GoPlayer.BLACK, automaticUsed, opening, series,
            if (series) max else 1, if (series) number else 1)
    private fun admit(saved: GoAiSavedMatch, phase: GoPhase =
        if (saved.copyBytes()[0] == 1.toByte()) GoPhase.FINISHED else GoPhase.PLAYING,
        result: GameResult = if (phase == GoPhase.FINISHED) GameResult.DRAW else GameResult.ONGOING) =
        GoAdmittedAiCheckpoint(saved, phase, result, 2, 4, 0)
    private fun archive(saved: GoAiSavedMatch = match(), lineage: GoAiCheckpointLineage = source, time: Long = 11) =
        PreparedGoAiArchiveBackup(admit(saved), lineage, time)
    private fun active(saved: GoAiSavedMatch = match(), lineage: GoAiCheckpointLineage = source) =
        PreparedGoAiSessionBackup(admit(saved), lineage, 19)
    private fun childOf(parent: String) = GoAiCheckpointLineage(GoAiCheckpointOrigin.TERMINAL_CONTINUATION, parent)

    @Test fun independentArchivesAndAnUnarchivedOngoingActiveAreValid() {
        GoAiBackupSnapshotGuard.validate(listOf(archive(), archive(match("two")), active(match("three", terminal = false))))
        GoAiBackupSnapshotGuard.validate(emptyList())
    }

    @Test fun terminalActiveRequiresTheSameFirstArchiveButDoesNotRewriteItsTimestamp() {
        rejects { GoAiBackupSnapshotGuard.validate(listOf(active())) }
        val first = archive(time = 11)
        val latest = active()
        GoAiBackupSnapshotGuard.validate(listOf(first, latest))
        assertEquals(11L, first.completedAtEpochMillis)
        assertEquals(19L, latest.updatedAtEpochMillis)
    }

    @Test fun terminalIdentityCanNeverReturnToAnOngoingActiveOrUnfinishedArchive() {
        rejects { GoAiBackupSnapshotGuard.validate(listOf(archive(), active(match(terminal = false)))) }
        rejects { GoAiBackupSnapshotGuard.validate(listOf(archive(match(terminal = false)))) }
        rejects { GoAiBackupSnapshotGuard.validate(listOf(PreparedGoAiArchiveBackup(
            admit(match(), GoPhase.FINISHED, GameResult.ONGOING), source, 1))) }
        rejects { GoAiBackupSnapshotGuard.validate(listOf(PreparedGoAiSessionBackup(
            admit(match(terminal = false), GoPhase.PLAYING, GameResult.DRAW), source, 1))) }
    }

    @Test fun duplicateArchivesAndActiveRowsAreRejectedEvenIfByteIdentical() {
        rejects { GoAiBackupSnapshotGuard.validate(listOf(archive(), archive())) }
        rejects { GoAiBackupSnapshotGuard.validate(listOf(active(match(terminal = false)), active(match(terminal = false)))) }
    }

    @Test fun everyContinuationRequiresADistinctExistingTerminalParent() {
        val parent = archive(match("parent"))
        val child = archive(match("child"), childOf(parent.recordId))
        GoAiBackupSnapshotGuard.validate(listOf(parent, child))
        GoAiBackupSnapshotGuard.validate(listOf(parent, active(match("running", terminal = false), childOf(parent.recordId))))
        rejects { GoAiBackupSnapshotGuard.validate(listOf(child)) }
        rejects { GoAiBackupSnapshotGuard.validate(listOf(active(match("running", terminal = false), childOf(parent.recordId)))) }
        val self = archive()
        rejects { GoAiBackupSnapshotGuard.validate(listOf(archive(lineage = childOf(self.recordId)))) }
        rejects { GoAiBackupSnapshotGuard.validate(listOf(archive(), active(lineage = childOf(self.recordId)))) }
    }

    @Test fun directAndArbitrarilyLongCyclesAreRejectedBeforeAnyReplacement() {
        val a = archive(match("a")); val b = archive(match("b")); val c = archive(match("c"))
        rejects { GoAiBackupSnapshotGuard.validate(listOf(archive(match("a"), childOf(b.recordId)),
            archive(match("b"), childOf(a.recordId)))) }
        rejects { GoAiBackupSnapshotGuard.validate(listOf(archive(match("a"), childOf(b.recordId)),
            archive(match("b"), childOf(c.recordId)), archive(match("c"), childOf(a.recordId)))) }
        // A disconnected valid root does not hide a cycle in another component.
        rejects { GoAiBackupSnapshotGuard.validate(listOf(archive(match("root")),
            archive(match("a"), childOf(b.recordId)), archive(match("b"), childOf(a.recordId)))) }
    }

    @Test fun longParentChainsAreTraversedIterativelyAndDoNotRequireWireOrder() {
        val records = mutableListOf<PreparedGameBackup>()
        var parent: String? = null
        repeat(20_000) { index ->
            val next = archive(match("chain-$index"), parent?.let(::childOf) ?: source)
            records += next
            parent = next.recordId
        }
        GoAiBackupSnapshotGuard.validate(records.asReversed())
    }

    @Test fun reservationMayAdvanceButCannotRewindTheFirstArchiveFlag() {
        GoAiBackupSnapshotGuard.validate(listOf(archive(), active(match(reserved = true))))
        GoAiBackupSnapshotGuard.validate(listOf(archive(match(reserved = true)), active(match(reserved = true))))
        rejects { GoAiBackupSnapshotGuard.validate(listOf(archive(match(reserved = true)), active())) }
    }

    @Test fun originalSeriesMayRemainOrEndButCannotRestartRenumberOrChangeBound() {
        val first = archive(match(series = true))
        GoAiBackupSnapshotGuard.validate(listOf(first, active(match(series = true))))
        GoAiBackupSnapshotGuard.validate(listOf(first, active(match(reserved = true))))
        for (invalid in listOf(match(series = true, max = 6), match(series = true, number = 3))) {
            rejects { GoAiBackupSnapshotGuard.validate(listOf(first, active(invalid))) }
        }
        rejects { GoAiBackupSnapshotGuard.validate(listOf(archive(), active(match(series = true)))) }
    }

    @Test fun terminalHistoryPoliciesAndProvenanceMustBeExactlyTheSame() {
        val first = archive()
        for (invalid in listOf(match(payload = 1), match(undo = 1), match(hint = 1),
            match(human = GoPlayer.BLACK), match(opening = byteArrayOf(1, 2)))) {
            rejects { GoAiBackupSnapshotGuard.validate(listOf(first, active(invalid))) }
        }
        rejects { GoAiBackupSnapshotGuard.validate(listOf(first, active(lineage =
            GoAiCheckpointLineage(GoAiCheckpointOrigin.LEGACY_UNVERIFIED)))) }
        val humanFirst = archive(match(human = GoPlayer.BLACK, automaticUsed = false))
        rejects { GoAiBackupSnapshotGuard.validate(listOf(humanFirst, active(match(human = GoPlayer.BLACK)))) }
    }

    @Test fun inconsistentAdmittedFactsAreRejectedWithoutReopeningNative() {
        val first = archive()
        val saved = match()
        for (facts in listOf(GoAdmittedAiCheckpoint(saved, GoPhase.FINISHED, GameResult.FIRST_PLAYER_WIN, 2, 4, 0),
            GoAdmittedAiCheckpoint(saved, GoPhase.FINISHED, GameResult.DRAW, 3, 4, 0),
            GoAdmittedAiCheckpoint(saved, GoPhase.FINISHED, GameResult.DRAW, 2, 5, 0),
            GoAdmittedAiCheckpoint(saved, GoPhase.FINISHED, GameResult.DRAW, 2, 4, 1))) {
            rejects { GoAiBackupSnapshotGuard.validate(listOf(first, PreparedGoAiSessionBackup(facts, source, 12))) }
        }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Invalid Go AI snapshot accepted") } catch (_: IllegalArgumentException) { }
    }
}
