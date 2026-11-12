package com.masterofchessstrategy.games.competition_mahjong.auto

import com.masterofchessstrategy.engine.NativeCompetitionMahjongAutoSession
import java.util.UUID

/** Reproducible openings authored from project seeds and replayed by the same rules as normal play. */
internal object CompetitionMahjongAutoOpeningLibrary {
    private data class Opening(val id: String, val title: String, val seed: Long, val events: Int)

    private val openings = listOf(
        Opening("first-discard", "首打观察（第 1 步）", 2026111201, 1),
        Opening("early-exchanges", "初期摸打（第 8 步）", 2026111202, 8),
    )

    fun load(viewerPlayerId: Int): List<CompetitionMahjongAutoOpening> {
        val admission = CompetitionMahjongAutoAdmission(::NativeCompetitionMahjongAutoSession)
        return openings.map { opening ->
            val id = UUID.nameUUIDFromBytes(opening.id.toByteArray(Charsets.US_ASCII)).toString()
            val config = CompetitionMahjongAutoRunConfig(id, opening.seed, viewerPlayerId)
            val hand = config.handConfig(0, 0, hasOpening = true)
            val record = admission.create(hand).use { session ->
                val advanced = session.advance(opening.events)
                check(!advanced.terminal && advanced.acceptedActions == opening.events)
                admission.requireBaseline(session)
                session.serialize()
            }
            CompetitionMahjongAutoOpening(opening.id, opening.title, config, record)
        }
    }
}
