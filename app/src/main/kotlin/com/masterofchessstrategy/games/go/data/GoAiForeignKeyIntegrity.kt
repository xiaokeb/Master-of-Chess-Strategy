package com.masterofchessstrategy.games.go.data

import android.database.sqlite.SQLiteConstraintException
import com.masterofchessstrategy.data.MocsDatabase

/** Call at the end of the same Room write transaction, before it is marked successful.
 * Deferred FKs still allow child-first writes; an invalid final graph throws inside
 * the body so framework SQLite executes ROLLBACK instead of a failing COMMIT.
 * This structural check does not replace Native admission or provenance cycle checks.
 */
internal suspend fun MocsDatabase.requireGoAiArchiveParents() {
    if (goAiCheckpointDao().hasMissingArchiveParent()) {
        throw SQLiteConstraintException("Unresolved Go AI archive parent before commit")
    }
}
