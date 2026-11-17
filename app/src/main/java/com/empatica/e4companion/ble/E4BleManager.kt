package com.empatica.e4companion.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.util.Log
import com.empatica.e4companion.model.ConnectionState
import com.empatica.e4companion.model.DeviceLabelManager
import com.empatica.e4companion.model.E4Device
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@SuppressLint("MissingPermission")
class E4BleManager(private val context: Context) {

    companion object {
        private const val TAG = "E4BleManager"

        val SENSOR_SERVICE: UUID    = UUID.fromString("00003ea0-0000-1000-8000-00805f9b34fb")
        val CMD_SERVICE: UUID       = UUID.fromString("00003e70-0000-1000-8000-00805f9b34fb")
        val STATUS_SERVICE: UUID    = UUID.fromString("00003eb0-0000-1000-8000-00805f9b34fb")

        val BVP_CHAR: UUID     = UUID.fromString("00003ea1-0000-1000-8000-00805f9b34fb")
        val GSR_CHAR: UUID     = UUID.fromString("00003ea8-0000-1000-8000-00805f9b34fb")
        val ACC_CHAR: UUID     = UUID.fromString("00003ea3-0000-1000-8000-00805f9b34fb")
        val ST_CHAR: UUID      = UUID.fromString("00003ea6-0000-1000-8000-00805f9b34fb")
        val BATTERY_CHAR: UUID = UUID.fromString("00003eb3-0000-1000-8000-00805f9b34fb")
        val CONTROL_CHAR: UUID = UUID.fromString("00003eb2-0000-1000-8000-00805f9b34fb")
        val CMD_CHAR: UUID     = UUID.fromString("00003e71-0000-1000-8000-00805f9b34fb")

        val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val DISCONNECT_BYTE: Byte = 0x0A
        private const val DISCONNECT_TIMEOUT_MS = 2000L
        private const val OPERATION_TIMEOUT_MS = 5000L
    }

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val btAdapter: BluetoothAdapter? = btManager.adapter
    private var scanner: BluetoothLeScanner? = null
    private var btGatt: BluetoothGatt? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<E4Device>>(emptyList())
    val discoveredDevices: StateFlow<List<E4Device>> = _discoveredDevices.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val autoReconnect = E4AutoReconnect(scope)
    val isReconnecting: StateFlow<Boolean> = autoReconnect.isReconnecting
    val reconnectAttempt: StateFlow<Int> = autoReconnect.attemptCount

    private var sessionStarted = false
    private var disconnectionRequested = false

    private val bleMutex = Mutex()
    private val gattResponseChannel = Channel<GattResponse>(Channel.CONFLATED)

    // ═══ RSSI monitoring — moved to class level ═══
    private val _rssi = MutableStateFlow(0)
    val rssi: StateFlow<Int> = _rssi.asStateFlow()
    private var rssiJob: Job? = null

    // ═══ Data callbacks ═══
    var onBvpPacketReceived: ((ByteArray) -> Unit)? = null
    var onGsrPacketReceived: ((ByteArray) -> Unit)? = null
    var onAccPacketReceived: ((ByteArray) -> Unit)? = null
    var onTempPacketReceived: ((ByteArray) -> Unit)? = null
    var onBatteryPacketReceived: ((ByteArray) -> Unit)? = null
    var onControlPacketReceived: ((ByteArray) -> Unit)? = null
    var onRawPacketReceived: ((String, ByteArray) -> Unit)? = null

    private var labelManager: DeviceLabelManager? = null

    private sealed class GattResponse {
        data class DescriptorWritten(val charUuid: UUID, val status: Int) : GattResponse()
        data class CharacteristicWritten(val charUuid: UUID, val status: Int) : GattResponse()
    }

    fun setLabelManager(manager: DeviceLabelManager) {
        labelManager = manager
    }

    private fun refreshDeviceCache(gatt: BluetoothGatt): Boolean {
        return try {
            val method = gatt.javaClass.getMethod("refresh")
            method.invoke(gatt) as? Boolean ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Could not refresh device cache: ${e.message}")
            false
        }
    }

    // ═══ RSSI monitoring functions at class level ═══

    /**
     * Start periodic RSSI monitoring during active connection.
     * Uses BluetoothGatt.readRemoteRssi() which triggers
     * BluetoothGattCallback.onReadRemoteRssi() [3][4]
     */
    private fun startRssiMonitoring() {
        rssiJob?.cancel()
        rssiJob = scope.launch {
            while (isActive) {
                btGatt?.readRemoteRssi()
                delay(5000)
            }
        }
    }

    private fun stopRssiMonitoring() {
        rssiJob?.cancel()
        _rssi.value = 0
    }

    // ═══ SCANNING ═══

    fun startScan() {
        val adapter = btAdapter ?: run {
            _connectionState.value = ConnectionState.Error("Bluetooth not available")
            return
        }
        if (!adapter.isEnabled) {
            _connectionState.value = ConnectionState.Error("Bluetooth disabled")
            return
        }

        // Always stop any existing scan first
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) { }

        // Clear previous results for a fresh scan
        _discoveredDevices.value = emptyList()
        _connectionState.value = ConnectionState.Scanning

        // Get a fresh scanner instance
        scanner = adapter.bluetoothLeScanner

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .apply {
                if (Build.VERSION.SDK_INT >= 23) {
                    setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                }
            }
            .build()

        val filters = listOf(
            ScanFilter.Builder().setDeviceName("Empatica E4").build()
        )

        scanner?.startScan(filters, settings, scanCallback)
        Log.d(TAG, "Scan started (fresh)")
    }

    fun stopScan() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan exception: ${e.message}")
        }
        scanner = null
        if (_connectionState.value is ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    // ScanCallback — only handles scan results, NOT RSSI [3]
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = device.name ?: result.scanRecord?.deviceName ?: return
            if (!name.contains("Empatica", ignoreCase = true)) return

            val scanBytes = result.scanRecord?.bytes
            var hardwareId = device.address.replace(":", "").takeLast(6).lowercase()

            if (scanBytes != null && scanBytes.size > 12) {
                try {
                    val idBytes = byteArrayOf(scanBytes[10], scanBytes[11], scanBytes[12])
                    hardwareId = idBytes.joinToString("") { "%02x".format(it) }
                } catch (_: Exception) { }
            }

            var fwMajor = 0; var fwMinor = 0; var fwRev = 0; var fwBuild = 0
            var hwMajor = 4; var hwMinor = 0; var battLevel = 0
            if (scanBytes != null && scanBytes.size > 25) {
                try {
                    hwMajor = scanBytes[17].toInt() and 0xFF
                    hwMinor = scanBytes[18].toInt() and 0xFF
                    fwMajor = scanBytes[19].toInt() and 0xFF
                    fwMinor = scanBytes[20].toInt() and 0xFF
                    fwRev = scanBytes[21].toInt() and 0xFF
                    fwBuild = (scanBytes[22].toInt() and 0xFF) or
                            ((scanBytes[23].toInt() and 0xFF) shl 8)
                    battLevel = (scanBytes[24].toInt() and 0xFF) or
                            ((scanBytes[25].toInt() and 0xFF) shl 8)
                } catch (_: Exception) { }
            }

            val manager = labelManager
            val serialNumber = manager?.getSerialNumber(hardwareId, device.address)
                ?: hardwareId.uppercase()
            val isSerialKnown = manager?.hasKnownSerialNumber(hardwareId) ?: false

            val fwString = "FW $fwMajor.$fwMinor.$fwRev ($fwBuild)"

            val e4Device = E4Device(
                bluetoothDevice = device,
                name = name,
                hardwareId = hardwareId,
                serialNumber = serialNumber,
                rssi = result.rssi,
                firmwareVersion = fwString,
                deviceType = 4,
                hwMajor = hwMajor,
                hwMinor = hwMinor,
                fwMajor = fwMajor,
                fwMinor = fwMinor,
                fwRev = fwRev,
                fwBuild = fwBuild,
                batteryLevel = battLevel,
                isSerialKnown = isSerialKnown
            )

            val current = _discoveredDevices.value.toMutableList()
            current.removeAll { it.address == device.address }
            current.add(e4Device)
            _discoveredDevices.value = current

            Log.d(TAG, "Found: ${e4Device.name} SN:${e4Device.serialNumber} " +
                    "[${e4Device.hardwareId}] known=$isSerialKnown RSSI=${e4Device.rssi}")
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            _connectionState.value = ConnectionState.Error("Scan failed (code $errorCode)")
        }
    }

    // ═══ CONNECTION ═══

    fun connect(device: E4Device) {
        stopScan()
        autoReconnect.onUserDisconnect()

        btGatt?.let { oldGatt ->
            try { oldGatt.disconnect(); oldGatt.close() } catch (_: Exception) { }
            btGatt = null
        }

        scope.launch {
            delay(300)
            _connectionState.value = ConnectionState.Connecting
            sessionStarted = false
            disconnectionRequested = false
            connectToDevice(device.bluetoothDevice)
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to ${device.name} @ ${device.address}")

        btGatt = when {
            Build.VERSION.SDK_INT >= 26 -> device.connectGatt(
                context, false, gattCallback,
                BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M
            )
            Build.VERSION.SDK_INT >= 23 -> device.connectGatt(
                context, false, gattCallback, BluetoothDevice.TRANSPORT_LE
            )
            else -> device.connectGatt(context, false, gattCallback)
        }
        btGatt?.let { refreshDeviceCache(it) }
    }

    // ═══ GATT CALLBACK ═══
    // onReadRemoteRssi belongs HERE in BluetoothGattCallback, NOT in ScanCallback [3][4]

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")

            if (newState == BluetoothProfile.STATE_CONNECTED &&
                status == BluetoothGatt.GATT_SUCCESS
            ) {
                autoReconnect.onConnected(gatt.device)
                _connectionState.value = ConnectionState.DiscoveringServices
                refreshDeviceCache(gatt)
                scope.launch {
                    delay(200)
                    gatt.discoverServices()
                }
            } else {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "GATT error: $status")
                }
                scope.launch {
                    afterDisconnection()
                    if (!disconnectionRequested) {
                        autoReconnect.onUnexpectedDisconnect { device ->
                            _connectionState.value = ConnectionState.Connecting
                            connectToDevice(device)
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                scope.launch { performHandshakeSequence(gatt) }
            } else {
                _connectionState.value = ConnectionState.Error("Service discovery failed")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val charUuid = descriptor.characteristic.uuid
            Log.d(TAG, "onDescriptorWrite status=$status")
            gattResponseChannel.trySend(GattResponse.DescriptorWritten(charUuid, status))
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val uuid = characteristic.uuid
            Log.d(TAG, "onCharacteristicWrite status=$status")
            gattResponseChannel.trySend(GattResponse.CharacteristicWritten(uuid, status))

            if (uuid == CMD_CHAR && disconnectionRequested) {
                gatt.disconnect()
            }
        }

        /**
         * RSSI callback — triggered by BluetoothGatt.readRemoteRssi() [3][4]
         * This is the correct location for this callback — it belongs in
         * BluetoothGattCallback, NOT in ScanCallback.
         */
        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _rssi.value = rssi
            }
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Fixed: single check, no duplicate nesting
            if (!sessionStarted) {
                sessionStarted = true
                _connectionState.value = ConnectionState.Streaming
                startRssiMonitoring()
                Log.d(TAG, "First data packet received — streaming started")
            }

            val data = characteristic.value ?: return
            val uuid = characteristic.uuid

            val charName = when (uuid) {
                BVP_CHAR     -> "BVP"
                GSR_CHAR     -> "EDA"
                ACC_CHAR     -> "ACC"
                ST_CHAR      -> "TEMP"
                BATTERY_CHAR -> "BATT"
                CONTROL_CHAR -> "CTRL"
                else -> "UNKN"
            }
            onRawPacketReceived?.invoke(charName, data)

            when (uuid) {
                BVP_CHAR     -> onBvpPacketReceived?.invoke(data)
                GSR_CHAR     -> onGsrPacketReceived?.invoke(data)
                ACC_CHAR     -> onAccPacketReceived?.invoke(data)
                ST_CHAR      -> onTempPacketReceived?.invoke(data)
                BATTERY_CHAR -> onBatteryPacketReceived?.invoke(data)
                CONTROL_CHAR -> onControlPacketReceived?.invoke(data)
            }
        }
    }

    // ═══ HANDSHAKE SEQUENCE ═══

    private suspend fun performHandshakeSequence(gatt: BluetoothGatt) {
        _connectionState.value = ConnectionState.Subscribing

        val subscriptionChain = listOf(
            Triple(SENSOR_SERVICE, BVP_CHAR, "BVP sensor"),
            Triple(SENSOR_SERVICE, GSR_CHAR, "EDA sensor"),
            Triple(SENSOR_SERVICE, ACC_CHAR, "Accelerometer"),
            Triple(SENSOR_SERVICE, ST_CHAR, "Temperature sensor"),
            Triple(STATUS_SERVICE, BATTERY_CHAR, "Battery monitor"),
            Triple(STATUS_SERVICE, CONTROL_CHAR, "Control channel"),
        )

        for ((serviceUuid, charUuid, friendlyName) in subscriptionChain) {
            val ok = enableNotificationSequential(gatt, serviceUuid, charUuid)
            if (!ok) {
                _connectionState.value =
                    ConnectionState.Error("Failed to subscribe to $friendlyName")
                return
            }
            Log.d(TAG, "✓ Subscribed to $friendlyName")
        }

        _connectionState.value = ConnectionState.StartingStream
        writeStartCommand(gatt)
    }

    private suspend fun enableNotificationSequential(
        gatt: BluetoothGatt,
        serviceUuid: UUID,
        charUuid: UUID
    ): Boolean = bleMutex.withLock {

        val service = gatt.getService(serviceUuid)
        if (service == null) {
            Log.e(TAG, "Required service not found")
            return@withLock false
        }

        val characteristic = service.getCharacteristic(charUuid)
        if (characteristic == null) {
            Log.e(TAG, "Required sensor not found")
            return@withLock false
        }

        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Log.e(TAG, "Failed to enable sensor notifications")
            return@withLock false
        }

        val descriptor = characteristic.getDescriptor(CCC_DESCRIPTOR)
        if (descriptor == null) {
            Log.e(TAG, "Sensor configuration not available")
            return@withLock false
        }

        @Suppress("DEPRECATION")
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        @Suppress("DEPRECATION")
        if (!gatt.writeDescriptor(descriptor)) {
            Log.e(TAG, "Failed to configure sensor")
            return@withLock false
        }

        val response = withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
            gattResponseChannel.receive()
        }

        return@withLock when (response) {
            is GattResponse.DescriptorWritten ->
                response.charUuid == charUuid &&
                        response.status == BluetoothGatt.GATT_SUCCESS
            else -> {
                Log.e(TAG, "Sensor subscription timed out")
                false
            }
        }
    }

    private suspend fun writeStartCommand(gatt: BluetoothGatt) = bleMutex.withLock {
        val service = gatt.getService(CMD_SERVICE)
        val cmdChar = service?.getCharacteristic(CMD_CHAR)

        if (cmdChar == null) {
            _connectionState.value = ConnectionState.Error("Device command interface not found")
            return@withLock
        }

        val timestampSec = System.currentTimeMillis() / 1000
        val tsBytes = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(timestampSec)
            .array()

        val startCmd = byteArrayOf(0x01, tsBytes[0], tsBytes[1], tsBytes[2], tsBytes[3])

        @Suppress("DEPRECATION")
        cmdChar.value = startCmd

        @Suppress("DEPRECATION")
        val written = gatt.writeCharacteristic(cmdChar)

        if (written) {
            Log.d(TAG, "Start command sent successfully")
            withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
                gattResponseChannel.receive()
            }
        } else {
            _connectionState.value = ConnectionState.Error("Failed to start streaming")
        }
    }

    // ═══ DISCONNECT ═══

    fun disconnect() {
        val gatt = btGatt ?: return
        disconnectionRequested = true
        autoReconnect.onUserDisconnect()
        _connectionState.value = ConnectionState.Disconnecting

        scope.launch {
            bleMutex.withLock {
                val service = gatt.getService(CMD_SERVICE)
                val cmdChar = service?.getCharacteristic(CMD_CHAR)

                if (cmdChar != null) {
                    @Suppress("DEPRECATION")
                    cmdChar.value = byteArrayOf(DISCONNECT_BYTE)

                    @Suppress("DEPRECATION")
                    val written = gatt.writeCharacteristic(cmdChar)

                    if (written) {
                        Log.d(TAG, "Disconnect command sent")
                        withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
                            gattResponseChannel.receive()
                        }
                    } else {
                        Log.w(TAG, "Could not send disconnect command, forcing disconnect")
                        gatt.disconnect()
                    }
                } else {
                    Log.w(TAG, "Device not ready, forcing disconnect")
                    gatt.disconnect()
                }
            }
        }
    }

    private fun afterDisconnection() {
        Log.d(TAG, "afterDisconnection")
        stopRssiMonitoring()
        try { btGatt?.close() } catch (_: Exception) { }
        btGatt = null
        sessionStarted = false
        if (disconnectionRequested) {
            disconnectionRequested = false
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    fun cleanup() {
        stopScan()
        stopRssiMonitoring()
        try { btGatt?.close() } catch (_: Exception) { }
        btGatt = null
        scope.cancel()
    }
}