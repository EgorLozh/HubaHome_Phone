package com.hubahome.phone.core.wakeword

import kotlinx.coroutines.flow.Flow

sealed class WakewordEvent {
    data object Detected : WakewordEvent()
    data class Error(val message: String) : WakewordEvent()
}

interface WakewordDetector {
    val events: Flow<WakewordEvent>
    fun start()
    fun stop()
}
