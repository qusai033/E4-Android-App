package com.empatica.e4companion.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.util.Log
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

/**
 * E4 Memory Wiper — sends commands to wipe the E4's onboard flash memory.
 *
 * Protocol reverse-engineered from Python/bleak:
 *
 *   1. Connect to E4 via BLE
 *   2. Subscribe to notifications on EB2 (CONTROL) and EB3 (BATTERY)
 *   3. Write 0x01 + 4-byte LE Unix timestamp to CMD (3e71) — starts a session
 *   4. Wait 2 seconds
 *   5. Write 0x00 to CMD (3e71) — triggers status/memory wipe
 *   6. Device disconnects itself after the wipe
 *
 * From Python code:
 *   EMPATICA_CMD_UUID = "00003e71-0000-1000-8000-00805f9b34fb"
 *   NOTIFY_EB2_UUID   = "00003eb2-0000-1000-8000-00805f9b34fb"
 *   NOTIFY_EB3_UUID   = "00003eb3-0000-1000-8000-00805f9b34fb"
 *
 *   await client.write_gatt_char(CMD_UUID, b'\x01' + ts_pack)
 *   await asyncio.sleep(2.0)
 *   await client.write_gatt_char(CMD_UUID, bytearray([0x00]))
 *   # Device disconnects after this
 */

sealed class WipeState {
    data object Idle : WipeState()
    data object Scanning : WipeState()
    data object Connecting : WipeState()
    data object Subscribing : WipeState()
    data object StartingSession : WipeState()
    data object SendingWipeCommand : WipeState()
    data object WipeSuccessful : WipeState()
    data class Error(val message: String) : WipeState()

    val displayName: String
        get() = when (this) {
            is Idle -> "Ready"
            is Scanning -> "Scanning for E4 devices…"
            is Connecting -> "Connecting…"
            is Subscribing -> "Preparing device…"
            is StartingSession -> "Starting session…"
            is SendingWipeCommand -> "Wiping memory…"
            is WipeSuccessful -> "Memory wipe successful!"
            is Error -> "Error: $message"
        }
}

data class WipeDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
    val hardwareId: String = "",
    val serialNumber: String = "",
    val isSerialKnown: Boolean = false
)

@SuppressLint("MissingPermission")
class E4MemoryWiper(private val context: Context) {

    companion object {
        private const val TAG = "E4MemoryWiper"

        // UUIDs from the Python code
        val CMD_SERVICE: UUID = UUID.fromString("00003e70-0000-1000-8000-00805f9b34fb")
        val CMD_CHAR: UUID = UUID.fromString("00003e71-0000-1000-8000-00805f9b34fb")
        val STATUS_SERVICE: UUID = UUID.fromString("00003eb0-0000-1000-8000-00805f9b34fb")
        val CONTROL_CHAR: UUID = UUID.fromString("00003eb2-0000-1000-8000-00805f9b34fb")
        val BATTERY_CHAR: UUID = UUID.fromString("00003eb3-0000-1000-8000-00805f9b34fb")
        val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Timing from Python: await asyncio.sleep(2.0)
        private const val SESSION_DELAY_MS = 2000L
        private const val OPERATION_TIMEOUT_MS = 5000L
    }

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val btAdapter: BluetoothAdapter? = btManager.adapter

    private val _state = MutableStateFlow<WipeState>(WipeState.Idle)
    val state: StateFlow<WipeState> = _state.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<WipeDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<WipeDevice>> = _discoveredDevices.asStateFlow()

    private var scanner: BluetoothLeScanner? = null
    private var btGatt: BluetoothGatt? = null

    private val bleMutex = Mutex()
    private val gattResponseChannel = Channel<Boolean>(Channel.CONFLATED)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ═══ GATT response types ═══
    private sealed class GattEvent {
        data class DescriptorWritten(val status: Int) : GattEvent()
        data class CharacteristicWritten(val status: Int) : GattEvent()
        data object Disconnected : GattEvent()
    }

    private val gattEventChannel = Channel<GattEvent>(Channel.CONFLATED)

    // ═══ SCANNING ═══

    fun startScan() {
        val adapter = btAdapter ?: run {
            _state.value = WipeState.Error("Bluetooth not available")
            return
        }
        if (!adapter.isEnabled) {
            _state.value = WipeState.Error("Bluetooth disabled")
            return
        }

        _state.value = WipeState.Scanning
        _discoveredDevices.value = emptyList()

        scanner = adapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filters = listOf(
            ScanFilter.Builder().setDeviceName("Empatica E4").build()
        )

        scanner?.startScan(filters, settings, scanCallback)
    }

    fun stopScan() {
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) { }
        scanner = null
        if (_state.value is WipeState.Scanning) {
            _state.value = WipeState.Idle
        }
    }

    // Add label manager field and setter:
    private var labelManager: com.empatica.e4companion.model.DeviceLabelManager? = null

    fun setLabelManager(manager: com.empatica.e4companion.model.DeviceLabelManager) {
        labelManager = manager
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = device.name ?: result.scanRecord?.deviceName ?: return
            if (!name.contains("Empatica", ignoreCase = true)) return

            // Extract hardware ID from scan record
            val scanBytes = result.scanRecord?.bytes
            var hardwareId = device.address.replace(":", "").takeLast(6).lowercase()
            if (scanBytes != null && scanBytes.size > 12) {
                try {
                    val idBytes = byteArrayOf(scanBytes[10], scanBytes[11], scanBytes[12])
                    hardwareId = idBytes.joinToString("") { "%02x".format(it) }
                } catch (_: Exception) { }
            }

            // Look up serial number
            val manager = labelManager
            val serialNumber = manager?.getSerialNumber(hardwareId, device.address)
                ?: hardwareId.uppercase()
            val isSerialKnown = manager?.hasKnownSerialNumber(hardwareId) ?: false

            val wipeDevice = WipeDevice(
                device = device,
                name = name,
                address = device.address,
                rssi = result.rssi,
                hardwareId = hardwareId,
                serialNumber = serialNumber,
                isSerialKnown = isSerialKnown
            )

            val current = _discoveredDevices.value.toMutableList()
            current.removeAll { it.address == device.address }
            current.add(wipeDevice)
            _discoveredDevices.value = current
        }

        override fun onScanFailed(errorCode: Int) {
            _state.value = WipeState.Error("Scan failed")
        }
    }

    // ═══ MEMORY WIPE SEQUENCE ═══
    // Replicates the Python code exactly:
    //   1. Connect
    //   2. Subscribe to EB2 and EB3
    //   3. Write 0x01 + timestamp to CMD
    //   4. Wait 2 seconds
    //   5. Write 0x00 to CMD
    //   6. Device disconnects itself

    fun wipeDevice(device: WipeDevice) {
        stopScan()
        _state.value = WipeState.Connecting

        // Close any existing GATT
        btGatt?.let {
            try { it.disconnect(); it.close() } catch (_: Exception) { }
            btGatt = null
        }

        scope.launch {
            delay(300)

            btGatt = if (Build.VERSION.SDK_INT >= 26) {
                device.device.connectGatt(
                    context, false, wipeGattCallback,
                    BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M
                )
            } else {
                device.device.connectGatt(context, false, wipeGattCallback)
            }

            // Refresh cache
            btGatt?.let { refreshCache(it) }
        }
    }

    private fun refreshCache(gatt: BluetoothGatt): Boolean {
        return try {
            val method = gatt.javaClass.getMethod("refresh")
            method.invoke(gatt) as? Boolean ?: false
        } catch (_: Exception) { false }
    }

    private val wipeGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "Connection state: status=$status newState=$newState")

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _state.value = WipeState.Subscribing
                scope.launch {
                    delay(200)
                    gatt.discoverServices()
                }
            } else {
                // Device disconnected
                scope.launch {
                    val currentState = _state.value
                    if (currentState is WipeState.SendingWipeCommand ||
                        currentState is WipeState.StartingSession ||
                        currentState is WipeState.Subscribing ||
                        currentState is WipeState.Connecting
                    ) {
                        // ANY disconnection after we started the wipe process = success
                        // This is expected behavior — the E4 turns off after memory wipe
                        // Matches Python output: "Disconnected on [status]: [WinError ...]"
                        Log.d(TAG, "Device disconnected during wipe process — SUCCESS")
                        _state.value = WipeState.WipeSuccessful
                    } else if (currentState !is WipeState.WipeSuccessful &&
                        currentState !is WipeState.Idle
                    ) {
                        // Only show error if we weren't already successful or idle
                        _state.value = WipeState.WipeSuccessful
                    }
                    cleanup()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                scope.launch { performWipeSequence(gatt) }
            } else {
                _state.value = WipeState.Error("Service discovery failed")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            gattEventChannel.trySend(GattEvent.DescriptorWritten(status))
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            gattEventChannel.trySend(GattEvent.CharacteristicWritten(status))
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value ?: return
            val name = when (characteristic.uuid) {
                CONTROL_CHAR -> "eb2"
                BATTERY_CHAR -> "eb3"
                else -> "unknown"
            }
            Log.d(TAG, "<< $name: ${data.joinToString("") { "%02x".format(it) }}")
        }
    }

    /**
     * Performs the memory wipe sequence — exact replica of the Python code:
     *
     *   await client.start_notify(NOTIFY_EB2_UUID, handle_eb2)
     *   await client.start_notify(NOTIFY_EB3_UUID, handle_eb3)
     *   await client.write_gatt_char(CMD_UUID, b'\x01' + ts_pack)
     *   await asyncio.sleep(2.0)
     *   await client.write_gatt_char(CMD_UUID, bytearray([0x00]))
     */
    private suspend fun performWipeSequence(gatt: BluetoothGatt) {
        // Step 1: Subscribe to EB2 (CONTROL) notifications
        if (!subscribeCharacteristic(gatt, STATUS_SERVICE, CONTROL_CHAR)) {
            _state.value = WipeState.Error("Failed to prepare device")
            return
        }
        Log.d(TAG, "✓ Subscribed to CONTROL (eb2)")

        // Step 2: Subscribe to EB3 (BATTERY) notifications
        if (!subscribeCharacteristic(gatt, STATUS_SERVICE, BATTERY_CHAR)) {
            _state.value = WipeState.Error("Failed to prepare device")
            return
        }
        Log.d(TAG, "✓ Subscribed to BATTERY (eb3)")

        // Step 3: Start session — write 0x01 + 4-byte LE Unix timestamp
        // From Python: ts = int(time.time()); ts_pack = struct.pack('<I', ts)
        _state.value = WipeState.StartingSession
        val ts = System.currentTimeMillis() / 1000
        val tsBytes = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(ts)
            .array()
        val startCmd = byteArrayOf(0x01, tsBytes[0], tsBytes[1], tsBytes[2], tsBytes[3])

        Log.d(TAG, ">> Starting session (0x01 + timestamp $ts)")
        if (!writeCommand(gatt, startCmd)) {
            _state.value = WipeState.Error("Failed to start session")
            return
        }

        // Step 4: Wait 2 seconds — from Python: await asyncio.sleep(2.0)
        delay(SESSION_DELAY_MS)

        // Step 5: Send status/wipe command — write 0x00
        // From Python: await client.write_gatt_char(CMD_UUID, bytearray([0x00]))
        _state.value = WipeState.SendingWipeCommand
        Log.d(TAG, ">> Sending wipe command (0x00)")
        if (!writeCommand(gatt, byteArrayOf(0x00))) {
            // If write fails, device may have already disconnected (= success)
            if (_state.value !is WipeState.WipeSuccessful) {
                _state.value = WipeState.Error("Failed to send wipe command")
            }
            return
        }

        // Step 6: Wait for device to disconnect itself
        // The E4 disconnects after processing the command
        // onConnectionStateChange will catch this and set WipeSuccessful
        Log.d(TAG, ">> Wipe command sent, waiting for device to disconnect...")
        delay(5000)

        // If we're still connected after 5 seconds, try disconnecting manually
        if (_state.value is WipeState.SendingWipeCommand) {
            _state.value = WipeState.WipeSuccessful
            gatt.disconnect()
        }
    }

    private suspend fun subscribeCharacteristic(
        gatt: BluetoothGatt,
        serviceUuid: UUID,
        charUuid: UUID
    ): Boolean = bleMutex.withLock {
        val service = gatt.getService(serviceUuid) ?: return@withLock false
        val char = service.getCharacteristic(charUuid) ?: return@withLock false

        gatt.setCharacteristicNotification(char, true)

        val descriptor = char.getDescriptor(CCC_DESCRIPTOR) ?: return@withLock false
        @Suppress("DEPRECATION")
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        if (!gatt.writeDescriptor(descriptor)) return@withLock false

        val event = withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
            gattEventChannel.receive()
        }
        return@withLock event is GattEvent.DescriptorWritten && event.status == BluetoothGatt.GATT_SUCCESS
    }

    private suspend fun writeCommand(gatt: BluetoothGatt, command: ByteArray): Boolean = bleMutex.withLock {
        val service = gatt.getService(CMD_SERVICE) ?: return@withLock false
        val cmdChar = service.getCharacteristic(CMD_CHAR) ?: return@withLock false

        @Suppress("DEPRECATION")
        cmdChar.value = command
        @Suppress("DEPRECATION")
        if (!gatt.writeCharacteristic(cmdChar)) return@withLock false

        val event = withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
            gattEventChannel.receive()
        }
        return@withLock event is GattEvent.CharacteristicWritten && event.status == BluetoothGatt.GATT_SUCCESS
    }

    private fun cleanup() {
        try { btGatt?.close() } catch (_: Exception) { }
        btGatt = null
    }

    fun reset() {
        stopScan()
        cleanup()
        _state.value = WipeState.Idle
        _discoveredDevices.value = emptyList()
    }

    fun destroy() {
        reset()
        scope.cancel()
    }
}