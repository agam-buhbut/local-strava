package com.localstrava.ui.log

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localstrava.data.ActivityRepository
import com.localstrava.data.RunDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogRunViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ActivityRepository(RunDatabase.get(app).runDao())

    fun save(startedAtEpochMs: Long, distanceMeters: Double, durationMs: Long, note: String?, onDone: () -> Unit) =
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.logRun(startedAtEpochMs, distanceMeters, durationMs, note) }
            onDone()
        }
}
