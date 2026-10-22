package com.masterofchessstrategy.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import java.util.WeakHashMap

/** Retains owners until both their scope and any separately owned resources finish safely. */
internal class LocalDataWriterRegistry {
    private data class Entry(val scope: Job, val retirement: Deferred<Unit>?) {
        // Exceptional cleanup is deliberately retained: a later restore must still fail closed.
        val released: Boolean get() = scope.isCompleted &&
            (retirement == null || retirement.isCompleted && !retirement.isCancelled)
    }

    private val lock = Any()
    private val owners = linkedMapOf<ViewModel, Entry>()

    fun register(owner: ViewModel) {
        val job = checkNotNull(owner.viewModelScope.coroutineContext[Job])
        val entry = Entry(job, (owner as? LocalDataWriterRetirement)?.retirementCompletion)
        synchronized(lock) {
            if (entry.released || owners.containsKey(owner)) return
            owners[owner] = entry
        }
        fun removeReleased() = synchronized(lock) {
            if (owners[owner] === entry && entry.released) owners.remove(owner)
        }
        job.invokeOnCompletion { removeReleased() }
        entry.retirement?.invokeOnCompletion { removeReleased() }
    }

    fun snapshot(): List<ViewModel> = synchronized(lock) {
        owners.entries.removeAll { it.value.released }
        owners.keys.toList()
    }

    companion object {
        // The same Room instance keeps its fence across activity recreation/reopening.
        private val databases = WeakHashMap<Any, LocalDataWriterRegistry>()
        @Synchronized fun forDatabase(database: Any): LocalDataWriterRegistry =
            databases.getOrPut(database) { LocalDataWriterRegistry() }
    }
}
