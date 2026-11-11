package com.masterofchessstrategy.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.SavedStateHandle
import com.masterofchessstrategy.games.go.GoInvalidLessonPracticeScreen
import com.masterofchessstrategy.games.go.GoLearningLibrary
import com.masterofchessstrategy.games.go.GoLessonPracticeLink
import com.masterofchessstrategy.games.go.GoGameRoute
import com.masterofchessstrategy.games.go.GoLearningRoute
import com.masterofchessstrategy.games.go.GoEndgameRoute
import com.masterofchessstrategy.games.go.data.GoLocalStorage
import com.masterofchessstrategy.settings.LocalDataWriterRegistry

/** NavBackStackEntry owns each VM; restored data always receives a fresh epoch-specific owner. */
@Composable
internal fun GoLocalNavigationRoute(storage: GoLocalStorage, writers: LocalDataWriterRegistry, onBack: () -> Unit) {
    val epoch by storage.gate.epoch.collectAsState()
    val repository = remember(storage, epoch) { storage.sessionRepository() }
    GoGameRoute(onBack, repository = repository, viewModelKey = "go-local-$epoch", onWriterReady = writers::register)
}

@Composable
internal fun GoLearningNavigationRoute(storage: GoLocalStorage, writers: LocalDataWriterRegistry, onBack: () -> Unit,
    onPractice: ((GoLessonPracticeLink) -> Unit)? = null,
) {
    val epoch by storage.gate.epoch.collectAsState()
    val repository = remember(storage, epoch) { storage.learningRepository() }
    GoLearningRoute(onBack, repository = repository, viewModelKey = "go-learning-$epoch", onWriterReady = writers::register,
        onPractice = onPractice)
}

@Composable
internal fun GoEndgameNavigationRoute(storage: GoLocalStorage, writers: LocalDataWriterRegistry, onBack: () -> Unit,
    practice: GoLessonPracticeLink? = null,
    requestState: SavedStateHandle? = null,
) {
    check(practice == null || requestState != null) { "Practice navigation needs stable entry state" }
    val epoch by storage.gate.epoch.collectAsState()
    val repository = remember(storage, epoch) { storage.endgameRepository() }
    GoEndgameRoute(onBack, repository = repository, viewModelKey = "go-endgame-$epoch-${practice?.puzzleId ?: "mainline"}",
        onWriterReady = writers::register, initialPuzzleId = practice?.puzzleId,
        sourceLessonTitle = practice?.let { GoLearningLibrary.lesson(it.lessonId)?.title }, initialRequestState = requestState)
}

/** Invalid restored/deep-link arguments never create a VM, load storage or open the native engine. */
@Composable
internal fun GoLessonPracticeNavigationRoute(storage: GoLocalStorage, writers: LocalDataWriterRegistry,
    lessonId: String?, puzzleId: String?, requestState: SavedStateHandle, onBack: () -> Unit,
) {
    val practice = GoLessonPracticeLink.resolve(lessonId, puzzleId)
    if (practice == null) GoInvalidLessonPracticeScreen(onBack)
    // Epoch-specific VM keys retire stale readers, but the epoch resets after process death.
    // The consumption flag therefore belongs to this stable navigation entry, not its VM key.
    else GoEndgameNavigationRoute(storage, writers, onBack, practice, requestState)
}
