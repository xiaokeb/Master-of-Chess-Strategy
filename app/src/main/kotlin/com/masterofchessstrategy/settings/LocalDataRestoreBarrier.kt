package com.masterofchessstrategy.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll

/** Main-thread boundary: stop producers, then await all old writes, including cancellation cleanup. */
internal suspend fun awaitLocalDataWriters(
    owners: List<ViewModel>,
    stopProducers: () -> Unit,
) {
    val pending = owners.flatMap { it.viewModelScope.coroutineContext[Job]?.children?.toList().orEmpty() }
    stopProducers()
    pending.forEach { it.cancel() }
    pending.joinAll()
}
