package com.empatica.e4companion.ble

import android.bluetooth.BluetoothDevice
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Auto-reconnect manager for the E4 BLE connection.
 *
 * When the E4 disconnects unexpectedly (BLE range loss, power glitch),
 * this manager automatically attempts to reconnect.
 *
 * From BLE best practices [3]:
 *   "setting autoConnect to true doesn't cause Android to automatically
 *    try to reconnect to the BluetoothDevice in the event of a connection"
 *   "In the event of a connection failure, we can simply try to connect
 *    again with autoConnect set to false"
 *
 * Strategy:
 *   - On unexpected disconnect, wait a short delay then retry
 *   - Exponential backoff: 1s, 2s, 4s, 8s, 16s, 30s max
 *   - Max attempts: 10 (then give up and notify user)
 *   - Only reconnect if user hasn't explicitly disconnected
 */
class E4AutoReconnect(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "E4AutoReconnect"
        private const val MAX_ATTEMPTS = 10
        private const val INITIAL_DELAY_MS = 1000L
        private const val MAX_DELAY_MS = 30000L
    }

    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    private val _attemptCount = MutableStateFlow(0)
    val attemptCount: StateFlow<Int> = _attemptCount.asStateFlow()

    private var reconnectJob: Job? = null
    private var lastDevice: BluetoothDevice? = null
    private var userInitiatedDisconnect = false

    /**
     * Called when a device connects successfully.
     * Stores the device reference for potential reconnection.
     */
    fun onConnected(device: BluetoothDevice) {
        lastDevice = device
        userInitiatedDisconnect = false
        _attemptCount.value = 0
        _isReconnecting.value = false
        reconnectJob?.cancel()
    }

    /**
     * Called when the user explicitly disconnects.
     * Prevents auto-reconnect from triggering.
     */
    fun onUserDisconnect() {
        userInitiatedDisconnect = true
        _isReconnecting.value = false
        _attemptCount.value = 0
        reconnectJob?.cancel()
    }

    /**
     * Called when an unexpected disconnect occurs.
     * Starts the auto-reconnect sequence with exponential backoff.
     *
     * @param onReconnectAttempt Callback to trigger the actual BLE reconnection
     */
    fun onUnexpectedDisconnect(onReconnectAttempt: (BluetoothDevice) -> Unit) {
        if (userInitiatedDisconnect) {
            Log.d(TAG, "User-initiated disconnect — not reconnecting")
            return
        }

        val device = lastDevice ?: run {
            Log.d(TAG, "No device to reconnect to")
            return
        }

        _isReconnecting.value = true

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var attempt = 0

            while (attempt < MAX_ATTEMPTS && isActive) {
                attempt++
                _attemptCount.value = attempt

                // Exponential backoff: 1s, 2s, 4s, 8s, 16s, 30s...
                val delayMs = (INITIAL_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(5)))
                    .coerceAtMost(MAX_DELAY_MS)

                Log.d(TAG, "Reconnect attempt $attempt/$MAX_ATTEMPTS in ${delayMs}ms")
                delay(delayMs)

                if (!isActive || userInitiatedDisconnect) break

                try {
                    onReconnectAttempt(device)
                    // If we get here without exception, the connect was initiated
                    // The actual success/failure will come through onConnectionStateChange
                    // Wait a reasonable time for the connection to establish
                    delay(10000)

                    // If still reconnecting after 10s, the attempt failed
                    if (_isReconnecting.value && isActive) {
                        Log.d(TAG, "Reconnect attempt $attempt timed out")
                        continue
                    } else {
                        // Successfully reconnected
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Reconnect attempt $attempt failed: ${e.message}")
                }
            }

            if (_isReconnecting.value) {
                Log.d(TAG, "Max reconnect attempts reached — giving up")
                _isReconnecting.value = false
                _attemptCount.value = 0
            }
        }
    }

    /**
     * Called when reconnection succeeds.
     */
    fun onReconnectSuccess() {
        _isReconnecting.value = false
        _attemptCount.value = 0
        reconnectJob?.cancel()
        Log.d(TAG, "Reconnect successful")
    }

    fun reset() {
        reconnectJob?.cancel()
        _isReconnecting.value = false
        _attemptCount.value = 0
        lastDevice = null
        userInitiatedDisconnect = false
    }
}