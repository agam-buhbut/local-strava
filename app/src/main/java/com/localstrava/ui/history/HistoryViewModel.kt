package com.localstrava.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localstrava.data.Activity
import com.localstrava.data.ActivityRepository
import com.localstrava.data.RunDatabase
import com.localstrava.tracking.TrackingStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ActivityRepository(RunDatabase.get(app).runDao())

    init {
        // Recover only orphans left by a crash/kill — never the run that's currently
        // recording (its endedAt is null for the whole run, so it would match otherwise).
        if (!TrackingStateHolder.state.value.isActive) {
            viewModelScope.launch(Dispatchers.IO) { repo.recoverInProgress() }
        }
    }

    val runs: StateFlow<List<Activity>> =
        repo.observeCompleted().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
