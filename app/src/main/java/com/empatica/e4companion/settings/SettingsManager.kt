package com.empatica.e4companion.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "api_settings"
        private const val PREF_API_URL = "api_url"
        private const val PREF_UPLOAD_INTERVAL = "upload_interval_seconds"
        private const val PREF_UPLOAD_TEMP = "upload_temperature"
        private const val PREF_UPLOAD_EDA = "upload_eda"
        private const val PREF_UPLOAD_HR = "upload_hr"
        private const val PREF_UPLOAD_ACC = "upload_acc"

        const val DEFAULT_API_URL = ""
        const val DEFAULT_UPLOAD_INTERVAL_SECONDS = 60
        const val MIN_UPLOAD_INTERVAL_SECONDS = 1
        const val MAX_UPLOAD_INTERVAL_SECONDS = 300
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _apiUrl = MutableStateFlow(prefs.getString(PREF_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL)
    val apiUrl: StateFlow<String> = _apiUrl.asStateFlow()

    private val _uploadIntervalSeconds = MutableStateFlow(
        prefs.getInt(PREF_UPLOAD_INTERVAL, DEFAULT_UPLOAD_INTERVAL_SECONDS)
    )
    val uploadIntervalSeconds: StateFlow<Int> = _uploadIntervalSeconds.asStateFlow()

    // Sensor toggles
    private val _uploadTemp = MutableStateFlow(prefs.getBoolean(PREF_UPLOAD_TEMP, true))
    val uploadTemp: StateFlow<Boolean> = _uploadTemp.asStateFlow()

    private val _uploadEda = MutableStateFlow(prefs.getBoolean(PREF_UPLOAD_EDA, true))
    val uploadEda: StateFlow<Boolean> = _uploadEda.asStateFlow()

    private val _uploadHr = MutableStateFlow(prefs.getBoolean(PREF_UPLOAD_HR, true))
    val uploadHr: StateFlow<Boolean> = _uploadHr.asStateFlow()

    private val _uploadAcc = MutableStateFlow(prefs.getBoolean(PREF_UPLOAD_ACC, false))
    val uploadAcc: StateFlow<Boolean> = _uploadAcc.asStateFlow()

    fun saveApiUrl(url: String) {
        val trimmed = url.trim().trimEnd('/')
        prefs.edit().putString(PREF_API_URL, trimmed).apply()
        _apiUrl.value = trimmed
    }

    fun saveUploadInterval(seconds: Int) {
        val clamped = seconds.coerceIn(MIN_UPLOAD_INTERVAL_SECONDS, MAX_UPLOAD_INTERVAL_SECONDS)
        prefs.edit().putInt(PREF_UPLOAD_INTERVAL, clamped).apply()
        _uploadIntervalSeconds.value = clamped
    }

    fun saveUploadTemp(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_UPLOAD_TEMP, enabled).apply()
        _uploadTemp.value = enabled
    }

    fun saveUploadEda(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_UPLOAD_EDA, enabled).apply()
        _uploadEda.value = enabled
    }

    fun saveUploadHr(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_UPLOAD_HR, enabled).apply()
        _uploadHr.value = enabled
    }

    fun saveUploadAcc(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_UPLOAD_ACC, enabled).apply()
        _uploadAcc.value = enabled
    }

    fun hasValidApiUrl(): Boolean {
        val url = _apiUrl.value
        return url.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://"))
    }
}