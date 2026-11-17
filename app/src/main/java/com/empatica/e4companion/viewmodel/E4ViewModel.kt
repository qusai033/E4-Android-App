package com.empatica.e4companion.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.empatica.e4companion.ble.E4BleManager
import com.empatica.e4companion.logging.SessionLogger
import com.empatica.e4companion.model.ConnectionState
import com.empatica.e4companion.model.DeviceLabelManager
import com.empatica.e4companion.model.E4Device
import com.empatica.e4companion.processing.*
import com.empatica.e4companion.service.E4StreamingService
import com.empatica.e4companion.ui.HapticHelper
import com.empatica.e4companion.network.EmpaticaApiClient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.sqrt
import android.util.Log
import com.empatica.e4companion.settings.SettingsManager


class E4ViewModel(private val app: Application) : AndroidViewModel(app) {

    val labelManager = DeviceLabelManager.getInstance(app.applicationContext)

    val bleManager = E4BleManager(app.applicationContext)
    val settingsManager = SettingsManager(app.applicationContext)
    val bvpProcessor = BVPProcessor()
    val multiSensor = MultiSensorProcessor()
    val hrCalculator = HRCalculator(viewModelScope)
    val calibrationManager = CalibrationManager(viewModelScope)
    val sessionLogger = SessionLogger(app.applicationContext)
    val sessionTimer = SessionTimer(viewModelScope)

    val connectionState: StateFlow<ConnectionState> = bleManager.connectionState
    val discoveredDevices = bleManager.discoveredDevices
    val calibrationStatus = calibrationManager.status
    val calibrationProgress = calibrationManager.progress

    val heartRate: StateFlow<Float> = hrCalculator.heartRate
    val peakBasedHR: StateFlow<Float> = bvpProcessor.heartRate
    val chartData: StateFlow<FloatArray> = bvpProcessor.chartData
    val latestSample = bvpProcessor.latestSample

    val gsrValue = multiSensor.gsrValue
    val temperature = multiSensor.temperature
    val batteryLevel = multiSensor.batteryLevel
    val wristStatus = multiSensor.wristStatus
    val acceleration = multiSensor.acceleration
    val accCumulativeValue = multiSensor.accCumulativeValue
    val tagTimestamp = multiSensor.lastTagTimestamp

    // Session timer
    val elapsedSeconds = sessionTimer.elapsedSeconds

    // RSSI for connection quality
    val rssi = bleManager.rssi

    // Auto-reconnect state
    val isReconnecting = bleManager.isReconnecting
    val reconnectAttempt = bleManager.reconnectAttempt
    val apiClient = EmpaticaApiClient(viewModelScope)
    val isUploading = apiClient.isUploading
    val lastUploadStatus = apiClient.lastUploadStatus
    val uploadCount = apiClient.uploadCount

    // ACC magnitude history
    private val _accHistory = MutableStateFlow<List<Float>>(emptyList())
    val accHistory: StateFlow<List<Float>> = _accHistory.asStateFlow()
    private val accMagnitudeBuffer = ArrayDeque<Float>(128)

    // Tag count
    private val _tagCount = MutableStateFlow(0)
    val tagCount: StateFlow<Int> = _tagCount.asStateFlow()

    // Battery alert tracking
    private var batteryAlertShown = false


    // Connected device SN
    private var connectedDeviceSN: String = "unknown"

    init {
        bleManager.setLabelManager(labelManager)

        viewModelScope.launch {
            try {
                labelManager.fetchAndActivate()
                Log.d("E4ViewModel", "Label manager loaded: ${labelManager.serialMap.value.size} devices")
            } catch (e: Exception) {
                Log.e("E4ViewModel", "Label manager fetch failed: ${e.message}")
            }

            // Wire ACC magnitude to BVP for motion rejection
            multiSensor.acceleration.collect { accData ->
                bvpProcessor.setAccMagnitude(accData.magnitude)
            }

            // Optionally load remote credentials to override local ones
            // This allows adding/removing users without rebuilding the app
            // If Remote Config is unavailable, local credentials still work
            try {
                val remoteConfig = com.google.firebase.remoteconfig.FirebaseRemoteConfig.getInstance()
                val credJson = remoteConfig.getString("app_credentials")
                if (credJson.isNotBlank() && credJson != "{}") {
                    val json = org.json.JSONObject(credJson)
                    val remoteCreds = mutableMapOf<String, String>()
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        remoteCreds[key.lowercase().trim()] = json.getString(key)
                    }
                    // Pass to AuthManager — remote creds override local ones
                    // authManager.updateRemoteCredentials(remoteCreds)
                }
            } catch (_: Exception) {
                // Remote Config unavailable — local credentials work fine
            }
        }

        wireDataPipeline()
        observeConnectionState()
        observeBatteryLevel()
    }

    private fun wireDataPipeline() {
        bleManager.onBvpPacketReceived = { packet ->
            bvpProcessor.processPacket(packet)
            calibrationManager.onBvpPacket()
        }

        bleManager.onGsrPacketReceived = { packet ->
            multiSensor.processGsrPacket(packet)
        }

        bleManager.onAccPacketReceived = { packet ->
            multiSensor.processAccPacket(packet)
        }
        multiSensor.onAccSample = { ts, x, y, z ->
            val mag = sqrt(x * x + y * y + z * z)
            synchronized(accMagnitudeBuffer) {
                if (accMagnitudeBuffer.size >= 128) accMagnitudeBuffer.removeFirst()
                accMagnitudeBuffer.addLast(mag)
                _accHistory.value = accMagnitudeBuffer.toList()
            }
            apiClient.addAcc(ts, x, y, z)
        }

        bleManager.onTempPacketReceived = { packet ->
            multiSensor.processTemperaturePacket(packet)
        }


        multiSensor.onTempSample = { ts, value ->
            // Upload temperature to server (every 1 minute batch)
            apiClient.addTemperature(ts, value)

            // ── Uncomment to upload other sensors ──
            // Note: add these in their respective callbacks, not here
        }

        // ── To enable EDA upload, update the EDA callback: ──
         multiSensor.onEdaSample = { ts, value ->
             apiClient.addEda(ts, value)
         }

        bvpProcessor.onSampleDecoded = { sample ->
            // Existing code stays...

            // Upload HR to server
            if (sample.heartRate > 0f) {
                apiClient.addHr(sample.timestamp, sample.heartRate)
            }
        }

        // ── To enable ACC upload, update the ACC callback: ──
        // multiSensor.onAccSample = { ts, x, y, z ->
        //     apiClient.addAcc(ts, x, y, z)
        //     // ... existing magnitude buffer code ...
        // }

        bleManager.onBatteryPacketReceived = { packet ->
            multiSensor.processBatteryPacket(packet)
        }

        bleManager.onControlPacketReceived = { packet ->
            multiSensor.processControlPacket(packet)
        }
        multiSensor.onTagEvent = { unixTimestamp ->
            sessionLogger.logTag(unixTimestamp)
            _tagCount.value += 1
            // Haptic feedback for tag [4]
            HapticHelper.tagMarked(app.applicationContext)
        }

        bleManager.onRawPacketReceived = { charName, data ->
            val ts = System.currentTimeMillis() / 1000.0
            sessionLogger.logRawPacket(ts, charName, data)
        }

        calibrationManager.onCalibrationFailed = {
            bleManager.disconnect()
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Streaming -> {
                        val startTime = System.currentTimeMillis() / 1000.0
                        multiSensor.setSessionStartTime(startTime)
                        sessionLogger.startSession(connectedDeviceSN)
                        calibrationManager.startCalibration()
                        sessionTimer.start()
                        // Start server uploads (temperature every 1 minute)

                        val apiUrl = settingsManager.apiUrl.value
                        val interval = settingsManager.uploadIntervalSeconds.value
                        apiClient.configure(
                            apiUrl = settingsManager.apiUrl.value,
                            uploadIntervalSeconds = settingsManager.uploadIntervalSeconds.value,
                            enableTemp = settingsManager.uploadTemp.value,
                            enableEda = settingsManager.uploadEda.value,
                            enableHr = settingsManager.uploadHr.value,
                            enableAcc = settingsManager.uploadAcc.value
                        )
                        apiClient.startUploading(connectedDeviceSN, sessionLogger.sessionFolder)

                        // Setup foreground service with live flows
                        E4StreamingService.heartRateFlow = bvpProcessor.heartRate
                        E4StreamingService.sessionSecondsFlow = sessionTimer.elapsedSeconds
                        E4StreamingService.deviceName = connectedDeviceSN
                        E4StreamingService.start(app.applicationContext)

                        // Haptic feedback [4]
                        HapticHelper.connected(app.applicationContext)
                    }
                    is ConnectionState.Disconnected -> {
                        sessionLogger.stopSession()
                        bvpProcessor.reset()
                        multiSensor.reset()
                        hrCalculator.reset()
                        calibrationManager.reset()
                        sessionTimer.stop()
                        accMagnitudeBuffer.clear()
                        _accHistory.value = emptyList()
                        _tagCount.value = 0
                        batteryAlertShown = false
                        E4StreamingService.stop(app.applicationContext)
                    }
                    is ConnectionState.Error -> {
                        sessionLogger.stopSession()
                        apiClient.stopUploading()
                        bvpProcessor.reset()
                        multiSensor.reset()
                        hrCalculator.reset()
                        calibrationManager.reset()
                        sessionTimer.stop()
                        accMagnitudeBuffer.clear()
                        _accHistory.value = emptyList()
                        _tagCount.value = 0
                        batteryAlertShown = false
                        E4StreamingService.stop(app.applicationContext)
                        // Haptic feedback [4]
                        HapticHelper.error(app.applicationContext)
                    }
                    else -> { }
                }
            }
        }
    }

    /**
     * Monitor battery level and trigger haptic alert when low.
     */
    private fun observeBatteryLevel() {
        viewModelScope.launch {
            batteryLevel.collect { level ->
                if (level >= 0f && level <= 0.10f && !batteryAlertShown &&
                    connectionState.value is ConnectionState.Streaming
                ) {
                    batteryAlertShown = true
                    HapticHelper.batteryLow(app.applicationContext)
                }
                // Reset alert when battery recovers above 15%
                if (level > 0.15f) {
                    batteryAlertShown = false
                }
            }
        }
    }

    fun startScan() = bleManager.startScan()
    fun stopScan() = bleManager.stopScan()

    fun connectDevice(device: E4Device) {
        connectedDeviceSN = device.serialNumber
        multiSensor.setDeviceHardwareId(device.hardwareId)
        bleManager.connect(device)
    }

    fun disconnect() = bleManager.disconnect()

    fun setCalibrationSamples(samples: Int) {
        calibrationManager.setCalibrationSamples(samples)
    }

    fun getDisplayHeartRate(): Float {
        val ibiHr = hrCalculator.heartRate.value
        return if (ibiHr > 0f) ibiHr else bvpProcessor.heartRate.value
    }

    fun getDataFolderIntent(): Intent = sessionLogger.getOpenDocumentsIntent()
    fun getDataPath(): String = sessionLogger.getRootDataPath()

    override fun onCleared() {
        super.onCleared()
        sessionLogger.cleanup()
        bleManager.cleanup()
        apiClient.reset()
        sessionTimer.reset()
    }
}