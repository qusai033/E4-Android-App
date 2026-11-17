package com.empatica.e4companion.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.empatica.e4companion.ble.E4MemoryWiper
import com.empatica.e4companion.ble.WipeDevice
import com.empatica.e4companion.ble.WipeState
import com.empatica.e4companion.ui.theme.E4Colors

@Composable
fun MemoryWipeScreen(
    wiper: E4MemoryWiper,
    onBack: () -> Unit
) {
    val state by wiper.state.collectAsState()
    val devices by wiper.discoveredDevices.collectAsState()
    var showConfirmDialog by remember { mutableStateOf<WipeDevice?>(null) }

    val textSecondary = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // ── Top Bar ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                wiper.reset()
                onBack()
            }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Wipe E4 Memory",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── Warning Card ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = E4Colors.DangerRed.copy(alpha = 0.1f)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = "Warning",
                    tint = E4Colors.DangerRed,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "This will permanently delete all data stored on the E4's internal memory. " +
                            "The device will turn off after the wipe is complete.",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Status Indicator ──
        WipeStatusIndicator(state)

        Spacer(Modifier.height(16.dp))

        // ── Content based on state ──
        when (state) {
            is WipeState.Idle -> {
                Button(
                    onClick = { wiper.startScan() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scan for E4 Devices", fontSize = 16.sp)
                }
            }

            is WipeState.Scanning -> {
                Button(
                    onClick = { wiper.stopScan() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = E4Colors.WarningOrange),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Stop Scan", fontSize = 16.sp)
                }

                Spacer(Modifier.height(12.dp))

                if (devices.isNotEmpty()) {
                    Text("Select device to wipe:", color = textSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                }

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(devices, key = { it.address }) { device ->
                        WipeDeviceCard(device) {
                            showConfirmDialog = device
                        }
                    }
                }
            }

            is WipeState.Connecting,
            is WipeState.Subscribing,
            is WipeState.StartingSession,
            is WipeState.SendingWipeCommand -> {
                // Show progress
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = E4Colors.WarningOrange,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            state.displayName,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 16.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Do not disconnect the device",
                            color = textSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            is WipeState.WipeSuccessful -> {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Success",
                            tint = E4Colors.StreamingGreen,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Memory Wipe Successful!",
                            color = E4Colors.StreamingGreen,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "The E4 device memory has been wiped.\nThe device is now in Bluetooth discovery mode\nand ready to be paired again.",
                            color = textSecondary,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = {
                                wiper.reset()
                                onBack()
                            },
                            modifier = Modifier.fillMaxWidth(0.6f).height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Done")
                        }
                    }
                }
            }

            is WipeState.Error -> {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Error,
                            contentDescription = "Error",
                            tint = E4Colors.HeartRed,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            (state as WipeState.Error).message,
                            color = E4Colors.HeartRed,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { wiper.reset() },
                            modifier = Modifier.fillMaxWidth(0.6f).height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Try Again")
                        }
                    }
                }
            }
        }
    }

    // ── Confirmation Dialog ──
    showConfirmDialog?.let { device ->
        AlertDialog(
            onDismissRequest = { showConfirmDialog = null },
            icon = {
                Icon(
                    Icons.Filled.DeleteForever,
                    contentDescription = "Delete",
                    tint = E4Colors.DangerRed,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text("Wipe Device Memory?", fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        "This will permanently erase all stored data on:",
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(device.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("SN: ${device.serialNumber}", fontSize = 13.sp)
                    Text("MAC: ${device.address}", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The device will enter Bluetooth discovery mode after the wipe. This action cannot be undone.",
                        fontSize = 14.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = null
                        wiper.wipeDevice(device)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = E4Colors.DangerRed)
                ) {
                    Text("Wipe Memory")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showConfirmDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun WipeStatusIndicator(state: WipeState) {
    val (color, icon) = when (state) {
        is WipeState.Idle -> E4Colors.DisconnectedGray to Icons.Filled.Storage
        is WipeState.Scanning -> E4Colors.WarningOrange to Icons.Filled.Search
        is WipeState.Connecting, is WipeState.Subscribing -> E4Colors.WarningOrange to Icons.Filled.BluetoothConnected
        is WipeState.StartingSession, is WipeState.SendingWipeCommand -> E4Colors.WarningOrange to Icons.Filled.DeleteSweep
        is WipeState.WipeSuccessful -> E4Colors.StreamingGreen to Icons.Filled.CheckCircle
        is WipeState.Error -> E4Colors.HeartRed to Icons.Filled.ErrorOutline
    }

    val animatedColor by animateColorAsState(color, label = "wipeStatusColor")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(animatedColor))
        Spacer(Modifier.width(10.dp))
        Icon(icon, contentDescription = null, tint = animatedColor, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            state.displayName,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun WipeDeviceCard(device: WipeDevice, onClick: () -> Unit) {
    val textSecondary = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Watch,
                contentDescription = "Device",
                tint = E4Colors.DangerRed,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                )

                // SN with verified indicator — same as home screen
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SN: ", color = textSecondary, fontSize = 11.sp)
                    Text(
                        device.serialNumber,
                        color = if (device.isSerialKnown) E4Colors.StreamingGreen else textSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (device.isSerialKnown) FontWeight.Medium else FontWeight.Normal
                    )
                    if (device.isSerialKnown) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.Verified,
                            contentDescription = "Verified SN",
                            tint = E4Colors.StreamingGreen,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }

                Text("MAC: ${device.address}", color = textSecondary, fontSize = 10.sp)
            }
            Icon(
                Icons.Filled.DeleteForever,
                contentDescription = "Wipe",
                tint = E4Colors.DangerRed,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}