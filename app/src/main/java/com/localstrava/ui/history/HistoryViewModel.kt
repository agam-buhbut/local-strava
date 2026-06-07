package com.localstrava.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localstrava.data.Activity
import com.localstrava.data.ActivityRepository
import com.localstrava.data.RunDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ActivityRepository(RunDatabase.get(app).runDao())

    init {
        // Finalize/discard any run left in-progress by a crash or kill (spec §3.9).
        viewModelScope.launch(Dispatchers.IO) { repo.recoverInProgress() }
    }

    val runs: StateFlow<List<Activity>> =
        repo.observeCompleted().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
