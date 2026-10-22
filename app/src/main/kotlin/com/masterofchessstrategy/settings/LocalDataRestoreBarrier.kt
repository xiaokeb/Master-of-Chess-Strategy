package com.masterofchessstrategy.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext

/** Main-thread boundary, outside storage locks: seal producers and drain every old writer. */
internal suspend fun awaitLocalDataWriters(
    owners: List<ViewModel>,
    stopProducers: () -> Unit,
) {
    val distinct = owners.distinct()
    // Capture before retirement/navigation can cancel or detach the old scopes.
    val pending = distinct.flatMap { it.viewModelScope.coroutineContext[Job]?.children?.toList().orEmpty() }
    val completions = mutableListOf<Deferred<Unit>>()
    val failures = mutableListOf<Throwable>()
    fun record(error: Throwable) { failures.add(error) }
    distinct.filterIsInstance<LocalDataWriterRetirement>().forEach { owner ->
        try { completions += owner.beginRetirement() } catch (error: Throwable) { record(error) }
    }
    try { stopProducers() } catch (error: Throwable) { record(error) }
    pending.forEach { it.cancel() }
    withContext(NonCancellable) {
        pending.joinAll()
        // Joining a cleanup Job alone does not propagate its exception. Await each receipt,
        // including after another owner failed, before permitting any backup storage access.
        completions.distinct().forEach { completion ->
            try { completion.await() } catch (error: Throwable) { record(error) }
        }
    }
    currentCoroutineContext().ensureActive()
    LocalDataWriterFailure.combine(failures)?.let { throw it }
}
