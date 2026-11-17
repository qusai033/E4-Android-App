package com.empatica.e4companion.model

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Device label manager — replaces the decompiled DeviceLabelHandler.java
 *
 * The original app used:
 *   DeviceLabelHandler → downloads encrypted AES file from
 *   https://e4.empatica.com/connect/device_labels_get.php
 *   Maps hardwareId → serial number label
 *
 * We use Firebase Remote Config instead, which allows updating
 * the mapping without republishing the app [2].
 *
 * The mapping is stored as a JSON parameter in Remote Config [3]:
 *   Parameter: "device_serial_map"
 *   Type: JSON
 *   Value: { "hwid1": "SN001", "hwid2": "SN002", ... }
 *
 * Fallback chain:
 *   1. Firebase Remote Config (fetched periodically) [2]
 *   2. Local SharedPreferences cache (offline fallback)
 *   3. Bundled default mapping (app ships with known devices)
 *   4. MAC-derived identifier (last resort)
 */
class DeviceLabelManager private constructor(context: Context) {

    companion object {
        private const val TAG = "DeviceLabelManager"
        private const val REMOTE_CONFIG_KEY = "device_serial_map"
        private const val PREFS_NAME = "device_labels"
        private const val PREFS_MAP_KEY = "serial_map_json"

        // Minimum fetch interval — 1 hour for production, 0 for debug
        private const val FETCH_INTERVAL_SECONDS = 3600L

        @Volatile
        private var instance: DeviceLabelManager? = null

        fun getInstance(context: Context): DeviceLabelManager {
            return instance ?: synchronized(this) {
                instance ?: DeviceLabelManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val remoteConfig: FirebaseRemoteConfig = FirebaseRemoteConfig.getInstance()

    // The current HW ID → SN mapping
    private val _serialMap = MutableStateFlow<Map<String, String>>(emptyMap())
    val serialMap: StateFlow<Map<String, String>> = _serialMap.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    // ═══ Bundled default mapping ═══
    // Ships with the app — your known devices go here
    // This is the fallback when Remote Config isn't available [2]
    private val bundledDefaults: Map<String, String> = mapOf(
        "70735a" to "A04C82",
        "a513c6" to "A03BFE",
        "b1705a" to "A04B81",
        "0dcc11" to "A0318A",
        "dd6f5a" to "A04B45",
        "65cc11" to "A03201",
        "6a13c6" to "A0346D",
        "df6f5a" to "A04A59",
        "9914c6" to "A03BF1",
        "cdcb11" to "A03003"
    )

    init {
        // Configure Remote Config [4]
        val configSettings = FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(FETCH_INTERVAL_SECONDS)
            .build()
        remoteConfig.setConfigSettingsAsync(configSettings)

        // Set bundled defaults as in-app defaults [2]
        val defaults = mapOf(REMOTE_CONFIG_KEY to serializeMap(bundledDefaults))
        remoteConfig.setDefaultsAsync(defaults)

        // Load cached map immediately
        loadCachedMap()
    }

    /**
     * Fetch latest mapping from Firebase Remote Config.
     * Call this on app startup.
     *
     * "Your app can safely and efficiently fetch parameter values from the
     * Remote Config backend periodically and activate those fetched values" [2]
     */
    suspend fun fetchAndActivate() {
        try {
            val activated = remoteConfig.fetchAndActivate().await()
            Log.d(TAG, "Remote Config fetch: activated=$activated")

            // Parse the JSON mapping from Remote Config
            val jsonString = remoteConfig.getString(REMOTE_CONFIG_KEY)
            if (jsonString.isNotBlank()) {
                val map = parseJsonMap(jsonString)
                if (map.isNotEmpty()) {
                    _serialMap.value = map
                    // Cache locally for offline use
                    saveCachedMap(jsonString)
                    Log.d(TAG, "Loaded ${map.size} device mappings from Remote Config")
                }
            }

            _isLoaded.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Remote Config fetch failed: ${e.message}")
            // Already loaded from cache in init, so we're ok
            _isLoaded.value = true
        }
    }

    /**
     * Look up serial number for a given hardware ID.
     *
     * Falls back through:
     * 1. Remote Config mapping [2]
     * 2. Bundled defaults
     * 3. MAC-derived identifier
     */
    fun getSerialNumber(hardwareId: String, macAddress: String = ""): String {
        val hwIdLower = hardwareId.lowercase().trim()

        // Try Remote Config mapping
        val fromRemote = _serialMap.value[hwIdLower]
        if (fromRemote != null) return fromRemote

        // Try bundled defaults
        val fromBundled = bundledDefaults[hwIdLower]
        if (fromBundled != null) return fromBundled

        // Derive from MAC address (last 3 octets → "A%05X" format)
        if (macAddress.isNotBlank()) {
            val macParts = macAddress.split(":")
            if (macParts.size == 6) {
                try {
                    val derived = (macParts[5] + macParts[4] + macParts[3]).lowercase()
                    val num = derived.toLong(16)
                    return "A%05X".format(num)
                } catch (_: Exception) { }
            }
        }

        // Fallback: return HW ID itself
        return hwIdLower.uppercase()
    }

    /**
     * Check if we have a known serial number for this hardware ID
     * (as opposed to a derived/fallback one).
     */
    fun hasKnownSerialNumber(hardwareId: String): Boolean {
        val hwIdLower = hardwareId.lowercase().trim()
        return _serialMap.value.containsKey(hwIdLower) ||
                bundledDefaults.containsKey(hwIdLower)
    }

    // ═══ Local caching ═══

    private fun loadCachedMap() {
        try {
            val json = prefs.getString(PREFS_MAP_KEY, null)
            if (json != null) {
                val map = parseJsonMap(json)
                if (map.isNotEmpty()) {
                    _serialMap.value = map
                    Log.d(TAG, "Loaded ${map.size} cached device mappings")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cached map: ${e.message}")
        }
    }

    private fun saveCachedMap(json: String) {
        prefs.edit().putString(PREFS_MAP_KEY, json).apply()
    }

    // ═══ JSON helpers ═══

    private fun parseJsonMap(jsonString: String): Map<String, String> {
        return try {
            val json = JSONObject(jsonString)
            val map = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.getString(key)
                map[key.lowercase().trim()] = value
            }
            map
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: ${e.message}")
            emptyMap()
        }
    }

    private fun serializeMap(map: Map<String, String>): String {
        val json = JSONObject()
        map.forEach { (key, value) -> json.put(key, value) }
        return json.toString()
    }
}