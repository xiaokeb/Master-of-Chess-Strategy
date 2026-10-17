package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.games.go.GoSavedSession
import com.masterofchessstrategy.games.go.GoSessionFileCodec
import com.masterofchessstrategy.games.go.GoSessionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** No ranked/reward dependency: local checkpoint, agreed record and provenance commit together. */
internal class RoomGoSessionRepository(
    private val store: GoLocalSessionStore,
    private val legacy: GoLocalLegacySource? = null,
    private val admit: (GoSavedSession) -> GoAdmittedLocalSession = ::admitLocalGoSession,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) : GoSessionRepository {
    constructor(database: MocsDatabase, legacy: GoLocalLegacySource? = null) :
        this(RoomGoLocalSessionStore(database), legacy)

    override suspend fun load(): GoSavedSession? = store.transaction {
        val current = readActive()
        if (current != null) {
            val admitted = prepare(decode(current))
            validateArchive(admitted, allowInsert = false)
            return@transaction admitted.session()
        }
        if (legacyHandled()) return@transaction null
        val old = legacy?.readCommitted()
        if (old == null) {
            markLegacyHandled()
            return@transaction null
        }
        val admitted = prepare(old)
        commit(admitted)
        admitted.session()
    }

    override suspend fun save(session: GoSavedSession) {
        val owned = session.copy(engineState = session.engineState.copyOf())
        val admitted = prepare(owned)
        store.transaction { commit(admitted) }
    }

    override suspend fun clear() = store.transaction {
        clearActive()
        // Absence after an explicit clear is not permission to import the old file again.
        markLegacyHandled()
    }

    private suspend fun prepare(session: GoSavedSession) = withContext(worker) {
        admit(session.copy(engineState = session.engineState.copyOf()))
    }

    private suspend fun GoLocalSessionTransaction.commit(admitted: GoAdmittedLocalSession) {
        val next = admitted.session()
        readActive()?.let { envelope ->
            val previous = prepare(decode(envelope))
            // Even a different new-game ID cannot hide a damaged, unarchived terminal row.
            validateArchive(previous, allowInsert = false)
            val prior = previous.session().identity
            if (prior.sessionId == next.identity.sessionId) {
                require(prior.origin == next.identity.origin) { "Go session provenance cannot change" }
                require(next.identity.archiveRevision >= prior.archiveRevision) { "Go archive branch cannot rewind" }
            }
        }
        validateArchive(admitted, allowInsert = true)
        val now = clock()
        require(now >= 0)
        writeActive(GoSessionFileCodec.encode(next), now)
        markLegacyHandled()
    }

    private suspend fun GoLocalSessionTransaction.validateArchive(
        admitted: GoAdmittedLocalSession,
        allowInsert: Boolean,
    ) {
        val session = admitted.session()
        val identity = session.identity
        val prior = findRecord(identity.recordId)
        val source = findSource(identity.recordId)
        if (admitted.phase != GoPhase.FINISHED) {
            require(prior == null && source == null) { "An archived Go branch cannot become ongoing" }
            return
        }
        val now = clock()
        require(now >= 0)
        val record = GameRecord(identity.recordId, GameType.GO, StoredGameMode.LOCAL_TWO_PLAYER,
            difficulty = null, result = admitted.result, engineState = session.engineState,
            moveCount = admitted.moveCount, completedAtEpochMillis = now, engineFormatVersion = 3)
        if (prior != null) {
            require(source == identity && record.engineState.contentEquals(prior.engineState) &&
                record.copy(engineState = prior.engineState, isFavorite = prior.isFavorite,
                    completedAtEpochMillis = prior.completedAtEpochMillis) == prior) {
                "Conflicting completed Go record"
            }
        } else {
            require(source == null && allowInsert) { "Go terminal checkpoint has no matching record" }
            insertRecord(record, identity)
        }
    }

    private fun decode(bytes: ByteArray): GoSavedSession {
        require(bytes.size > 4 && bytes[4].toInt() == 3) { "Invalid Go Room envelope version" }
        return requireNotNull(GoSessionFileCodec.decode(bytes)) { "Invalid Go Room checkpoint" }
    }
}
