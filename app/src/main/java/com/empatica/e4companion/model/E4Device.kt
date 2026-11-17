package com.empatica.e4companion.model

import android.bluetooth.BluetoothDevice

/**
 * Device model reconstructed from decompiled EmpaticaDevice.java
 * Now uses DeviceLabelManager for serial number lookup.
 */
data class E4Device(
    val bluetoothDevice: BluetoothDevice,
    val name: String,
    val hardwareId: String,
    val serialNumber: String,
    val rssi: Int,
    val firmwareVersion: String = "",
    val deviceType: Int = 4,
    val hwMajor: Int = 0,
    val hwMinor: Int = 0,
    val fwMajor: Int = 0,
    val fwMinor: Int = 0,
    val fwRev: Int = 0,
    val fwBuild: Int = 0,
    val batteryLevel: Int = 0,
    val isSerialKnown: Boolean = false  // true if SN came from the mapping
) {
    val address: String get() = bluetoothDevice.address
    val isFaulty: Boolean get() = hardwareId.equals("000046", ignoreCase = true)
}