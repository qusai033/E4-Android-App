package com.empatica.e4companion.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client for uploading Empatica E4 sensor data to a remote server.
 *
 * Configurable:
 *   - API endpoint URL (user sets in Settings)
 *   - Upload interval (1s to 300s, user sets in Settings)
 *   - Which sensors to upload (temp, eda, hr, acc — user toggles in Settings)
 */
class EmpaticaApiClient(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "EmpaticaApiClient"
        private const val DEFAULT_UPLOAD_INTERVAL_MS = 60000L
    }

    // ── Configurable settings ──
    private var customEndpoint: String = ""
    private var uploadIntervalMs: Long = DEFAULT_UPLOAD_INTERVAL_MS

    // ── Sensor toggles (controlled from Settings) ──
    private var uploadTemperature: Boolean = true
    private var uploadEda: Boolean = true
    private var uploadHr: Boolean = true
    private var uploadAcc: Boolean = false

    // ── Upload state ──
    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _lastUploadStatus = MutableStateFlow("")
    val lastUploadStatus: StateFlow<String> = _lastUploadStatus.asStateFlow()

    private val _uploadCount = MutableStateFlow(0)
    val uploadCount: StateFlow<Int> = _uploadCount.asStateFlow()

    // ── Current window accumulators ──
    private val tempCurrentWindow = mutableListOf<Float>()
    private var tempWindowEnd: Double = 0.0

    private val edaCurrentWindow = mutableListOf<Float>()
    private var edaWindowEnd: Double = 0.0

    private val hrCurrentWindow = mutableListOf<Float>()
    private var hrWindowEnd: Double = 0.0

    private val accCurrentWindow = mutableListOf<Triple<Float, Float, Float>>()
    private var accWindowEnd: Double = 0.0

    // ── Session history (all interval averages) ──
    private val tempSessionData = mutableListOf<SessionDataPoint>()
    private val edaSessionData = mutableListOf<SessionDataPoint>()
    private val hrSessionData = mutableListOf<SessionDataPoint>()
    private val accSessionData = mutableListOf<AccSessionDataPoint>()

    private var uploadJob: Job? = null
    private var deviceId: String = "unknown"
    private var sessionId: String = ""

    data class SessionDataPoint(
        val timestamp: Double,
        val value: Float
    )

    data class AccSessionDataPoint(
        val timestamp: Double,
        val x: Float,
        val y: Float,
        val z: Float
    )

    /**
     * Configure the API endpoint and upload interval from user settings.
     * Call before startUploading().
     */
    fun configure(
        apiUrl: String,
        uploadIntervalSeconds: Int,
        enableTemp: Boolean,
        enableEda: Boolean,
        enableHr: Boolean,
        enableAcc: Boolean
    ) {
        customEndpoint = if (apiUrl.isNotBlank() &&
            (apiUrl.startsWith("http://") || apiUrl.startsWith("https://"))
        ) {
            apiUrl.trim().trimEnd('/')
        } else {
            ""
        }
        uploadIntervalMs = (uploadIntervalSeconds * 1000L).coerceAtLeast(1000L)
        uploadTemperature = enableTemp
        uploadEda = enableEda
        uploadHr = enableHr
        uploadAcc = enableAcc

        Log.d(TAG, "Configured: endpoint=$customEndpoint, interval=${uploadIntervalMs}ms, " +
                "temp=$enableTemp, eda=$enableEda, hr=$enableHr, acc=$enableAcc")
    }

    fun startUploading(deviceSerialNumber: String, sessionFolder: String) {
        deviceId = deviceSerialNumber
        sessionId = sessionFolder
        _uploadCount.value = 0
        _lastUploadStatus.value = if (customEndpoint.isBlank()) "No API configured" else "Not synced"

        // Clear everything for the new session
        synchronized(tempCurrentWindow) { tempCurrentWindow.clear() }
        synchronized(edaCurrentWindow) { edaCurrentWindow.clear() }
        synchronized(hrCurrentWindow) { hrCurrentWindow.clear() }
        synchronized(accCurrentWindow) { accCurrentWindow.clear() }
        synchronized(tempSessionData) { tempSessionData.clear() }
        synchronized(edaSessionData) { edaSessionData.clear() }
        synchronized(hrSessionData) { hrSessionData.clear() }
        synchronized(accSessionData) { accSessionData.clear() }

        // Don't start upload job if no endpoint configured
        if (customEndpoint.isBlank()) {
            Log.d(TAG, "No API endpoint configured — data saved locally only")
            return
        }

        // Single upload job for all sensors combined
        uploadJob?.cancel()
        uploadJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(uploadIntervalMs)
                computeAveragesAndUpload()
            }
        }

        Log.d(TAG, "Started uploading for device: $deviceId, session: $sessionId")
    }

    fun stopUploading() {
        uploadJob?.cancel()
        uploadJob = null

        // Final flush
        if (customEndpoint.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                computeAveragesAndUpload()
            }
        }

        Log.d(TAG, "Stopped uploading. Total syncs: ${_uploadCount.value}")
    }

    // ══════════════════════════════════════════
    // SENSOR DATA INPUTS
    // ══════════════════════════════════════════

    fun addTemperature(timestamp: Double, value: Float) {
        if (!uploadTemperature) return
        synchronized(tempCurrentWindow) {
            tempCurrentWindow.add(value)
            tempWindowEnd = timestamp
        }
    }

    fun addEda(timestamp: Double, value: Float) {
        if (!uploadEda) return
        synchronized(edaCurrentWindow) {
            edaCurrentWindow.add(value)
            edaWindowEnd = timestamp
        }
    }

    fun addHr(timestamp: Double, bpm: Float) {
        if (!uploadHr) return
        if (bpm <= 0f) return
        synchronized(hrCurrentWindow) {
            hrCurrentWindow.add(bpm)
            hrWindowEnd = timestamp
        }
    }

    fun addAcc(timestamp: Double, x: Float, y: Float, z: Float) {
        if (!uploadAcc) return
        synchronized(accCurrentWindow) {
            accCurrentWindow.add(Triple(x, y, z))
            accWindowEnd = timestamp
        }
    }

    // ══════════════════════════════════════════
    // COMBINED UPLOAD
    // ══════════════════════════════════════════

    private suspend fun computeAveragesAndUpload() {
        // Compute temperature average
        if (uploadTemperature) {
            synchronized(tempCurrentWindow) {
                if (tempCurrentWindow.isNotEmpty()) {
                    val avg = tempCurrentWindow.sum() / tempCurrentWindow.size
                    synchronized(tempSessionData) {
                        tempSessionData.add(SessionDataPoint(tempWindowEnd, avg))
                    }
                    tempCurrentWindow.clear()
                }
            }
        }

        // Compute EDA average
        if (uploadEda) {
            synchronized(edaCurrentWindow) {
                if (edaCurrentWindow.isNotEmpty()) {
                    val avg = edaCurrentWindow.sum() / edaCurrentWindow.size
                    synchronized(edaSessionData) {
                        edaSessionData.add(SessionDataPoint(edaWindowEnd, avg))
                    }
                    edaCurrentWindow.clear()
                }
            }
        }

        // Compute HR average
        if (uploadHr) {
            synchronized(hrCurrentWindow) {
                if (hrCurrentWindow.isNotEmpty()) {
                    val avg = hrCurrentWindow.sum() / hrCurrentWindow.size
                    synchronized(hrSessionData) {
                        hrSessionData.add(SessionDataPoint(hrWindowEnd, avg))
                    }
                    hrCurrentWindow.clear()
                }
            }
        }

        // Compute ACC average
        if (uploadAcc) {
            synchronized(accCurrentWindow) {
                if (accCurrentWindow.isNotEmpty()) {
                    val avgX = accCurrentWindow.map { it.first }.average().toFloat()
                    val avgY = accCurrentWindow.map { it.second }.average().toFloat()
                    val avgZ = accCurrentWindow.map { it.third }.average().toFloat()
                    synchronized(accSessionData) {
                        accSessionData.add(AccSessionDataPoint(accWindowEnd, avgX, avgY, avgZ))
                    }
                    accCurrentWindow.clear()
                }
            }
        }

        // Build combined payload
        val tempHistory: List<SessionDataPoint>
        val edaHistory: List<SessionDataPoint>
        val hrHistory: List<SessionDataPoint>
        val accHistory: List<AccSessionDataPoint>

        synchronized(tempSessionData) { tempHistory = tempSessionData.toList() }
        synchronized(edaSessionData) { edaHistory = edaSessionData.toList() }
        synchronized(hrSessionData) { hrHistory = hrSessionData.toList() }
        synchronized(accSessionData) { accHistory = accSessionData.toList() }

        // Only send if we have at least one sensor's data
        if (tempHistory.isEmpty() && edaHistory.isEmpty() &&
            hrHistory.isEmpty() && accHistory.isEmpty()) return

        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("session_id", sessionId)
            put("data", JSONObject().apply {
                if (tempHistory.isNotEmpty()) {
                    put("temperature", JSONArray().apply {
                        for (point in tempHistory) {
                            put(JSONObject().apply {
                                put("timestamp", point.timestamp)
                                put("value", point.value.toDouble())
                            })
                        }
                    })
                }

                if (edaHistory.isNotEmpty()) {
                    put("eda", JSONArray().apply {
                        for (point in edaHistory) {
                            put(JSONObject().apply {
                                put("timestamp", point.timestamp)
                                put("value", point.value.toDouble())
                            })
                        }
                    })
                }

                if (hrHistory.isNotEmpty()) {
                    put("heart_rate", JSONArray().apply {
                        for (point in hrHistory) {
                            put(JSONObject().apply {
                                put("timestamp", point.timestamp)
                                put("value", point.value.toDouble())
                            })
                        }
                    })
                }

                if (accHistory.isNotEmpty()) {
                    put("accelerometer", JSONArray().apply {
                        for (point in accHistory) {
                            put(JSONObject().apply {
                                put("timestamp", point.timestamp)
                                put("x", point.x.toDouble())
                                put("y", point.y.toDouble())
                                put("z", point.z.toDouble())
                            })
                        }
                    })
                }
            })
        }

        Log.d(TAG, "Sending combined: temp=${tempHistory.size}, eda=${edaHistory.size}, " +
                "hr=${hrHistory.size}, acc=${accHistory.size} intervals")

        sendToServer(json)
    }

    // ══════════════════════════════════════════
    // HTTP POST
    // ══════════════════════════════════════════

    private suspend fun sendToServer(payload: JSONObject) {
        if (customEndpoint.isBlank()) return

        _isUploading.value = true

        try {
            withContext(Dispatchers.IO) {
                val url = URL(customEndpoint)
                val conn = url.openConnection() as HttpURLConnection

                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

                val writer = OutputStreamWriter(conn.outputStream)
                writer.write(payload.toString())
                writer.flush()
                writer.close()

                val responseCode = conn.responseCode
                val responseBody = try {
                    conn.inputStream.bufferedReader().readText()
                } catch (e: Exception) {
                    conn.errorStream?.bufferedReader()?.readText() ?: "No response"
                }

                conn.disconnect()

                if (responseCode in 200..299) {
                    _uploadCount.value += 1

                    val data = payload.optJSONObject("data")
                    val tempCount = data?.optJSONArray("temperature")?.length() ?: 0
                    val edaCount = data?.optJSONArray("eda")?.length() ?: 0
                    val hrCount = data?.optJSONArray("heart_rate")?.length() ?: 0
                    val accCount = data?.optJSONArray("accelerometer")?.length() ?: 0

                    _lastUploadStatus.value = "✓ Synced (${tempCount + edaCount + hrCount + accCount} pts)"
                    Log.d(TAG, "Sync OK ($responseCode): temp=$tempCount, eda=$edaCount, hr=$hrCount, acc=$accCount")
                } else {
                    _lastUploadStatus.value = "✗ Error $responseCode"
                    Log.e(TAG, "Sync FAILED ($responseCode): $responseBody")
                }
            }
        } catch (e: Exception) {
            _lastUploadStatus.value = "✗ ${e.message}"
            Log.e(TAG, "Sync error: ${e.message}")
        } finally {
            _isUploading.value = false
        }
    }

    fun reset() {
        uploadJob?.cancel()
        uploadJob = null

        synchronized(tempCurrentWindow) { tempCurrentWindow.clear() }
        synchronized(edaCurrentWindow) { edaCurrentWindow.clear() }
        synchronized(hrCurrentWindow) { hrCurrentWindow.clear() }
        synchronized(accCurrentWindow) { accCurrentWindow.clear() }
        synchronized(tempSessionData) { tempSessionData.clear() }
        synchronized(edaSessionData) { edaSessionData.clear() }
        synchronized(hrSessionData) { hrSessionData.clear() }
        synchronized(accSessionData) { accSessionData.clear() }

        _uploadCount.value = 0
        _lastUploadStatus.value = ""
    }
}