package com.hubahome.phone.core.settings

import kotlinx.coroutines.flow.StateFlow

interface ConnectionSettingsStore {
    val settings: StateFlow<ConnectionSettings>

    fun save(wsUrl: String, apiKey: String): Result<Unit>
    fun resetToDefault()
}
