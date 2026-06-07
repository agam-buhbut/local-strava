package com.localstrava.ui.record

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.localstrava.tracking.TrackingService
import com.localstrava.tracking.TrackingStateHolder

class RecordViewModel(app: Application) : AndroidViewModel(app) {
    val state = TrackingStateHolder.state
    fun start() = TrackingService.start(getApplication())
    fun pause() = TrackingService.pause(getApplication())
    fun resume() = TrackingService.resume(getApplication())
    fun stop() = TrackingService.stop(getApplication())
    fun consumeTerminal() = TrackingStateHolder.consumeTerminal()
}
