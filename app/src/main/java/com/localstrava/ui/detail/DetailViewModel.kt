package com.localstrava.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localstrava.data.Activity
import com.localstrava.data.ActivityRepository
import com.localstrava.data.RunDatabase
import com.localstrava.data.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DetailState(
    val activity: Activity? = null,
    val points: List<TrackPoint> = emptyList(),
    val loading: Boolean = true,
)

class DetailViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ActivityRepository(RunDatabase.get(app).runDao())
    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    fun load(id: Long) = viewModelScope.launch {
        val activity = withContext(Dispatchers.IO) { repo.getActivity(id) }
        val points = withContext(Dispatchers.IO) { repo.getPoints(id) }
        _state.value = DetailState(activity, points, loading = false)
    }

    fun delete(id: Long, onDone: () -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.delete(id) }
        onDone()
    }
}
