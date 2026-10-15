package com.masterofchessstrategy.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import java.util.WeakHashMap

/** Retains a destination owner until its scope really finishes, including cancelled SQL cleanup. */
internal class LocalDataWriterRegistry {
    private val lock = Any()
    private val owners = linkedMapOf<ViewModel, Job>()

    fun register(owner: ViewModel) {
        val job = checkNotNull(owner.viewModelScope.coroutineContext[Job])
        synchronized(lock) {
            if (job.isCompleted || owners.containsKey(owner)) return
            owners[owner] = job
        }
        job.invokeOnCompletion { synchronized(lock) { owners.remove(owner) } }
    }

    fun snapshot(): List<ViewModel> = synchronized(lock) {
        owners.entries.removeAll { it.value.isCompleted }
        owners.keys.toList()
    }

    companion object {
        // The same Room instance keeps its fence across activity recreation/reopening.
        private val databases = WeakHashMap<Any, LocalDataWriterRegistry>()
        @Synchronized fun forDatabase(database: Any): LocalDataWriterRegistry =
            databases.getOrPut(database) { LocalDataWriterRegistry() }
    }
}
