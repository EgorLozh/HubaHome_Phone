package com.hubahome.phone.core.settings

import android.content.SharedPreferences
import com.hubahome.phone.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class SharedPrefsConnectionSettingsStore @Inject constructor(
    private val prefs: SharedPreferences,
) : ConnectionSettingsStore {
    private val _settings = MutableStateFlow(readSettings())
    override val settings: StateFlow<ConnectionSettings> = _settings.asStateFlow()

    override fun save(wsUrl: String, apiKey: String): Result<Unit> {
        val normalizedUrl = wsUrl.trim()
        val normalizedKey = apiKey.trim()
        if (!isValidWsUrl(normalizedUrl)) {
            return Result.failure(IllegalArgumentException("WS URL must start with ws:// or wss://"))
        }
        if (normalizedKey.isBlank()) {
            return Result.failure(IllegalArgumentException("API key cannot be blank"))
        }

        prefs.edit()
            .putString(KEY_WS_URL, normalizedUrl)
            .putString(KEY_API_KEY, normalizedKey)
            .apply()
        _settings.value = readSettings()
        return Result.success(Unit)
    }

    override fun resetToDefault() {
        prefs.edit()
            .putString(KEY_WS_URL, BuildConfig.DEFAULT_WS_URL)
            .putString(KEY_API_KEY, BuildConfig.DEFAULT_API_KEY)
            .apply()
        _settings.value = readSettings()
    }

    private fun readSettings(): ConnectionSettings {
        val wsUrl = prefs.getString(KEY_WS_URL, BuildConfig.DEFAULT_WS_URL).orEmpty()
        val apiKey = prefs.getString(KEY_API_KEY, BuildConfig.DEFAULT_API_KEY).orEmpty()
        return ConnectionSettings(wsUrl = wsUrl, apiKey = apiKey)
    }

    private fun isValidWsUrl(url: String): Boolean {
        return url.startsWith("ws://") || url.startsWith("wss://")
    }

    private companion object {
        private const val KEY_WS_URL = "ws_url"
        private const val KEY_API_KEY = "api_key"
    }
}
