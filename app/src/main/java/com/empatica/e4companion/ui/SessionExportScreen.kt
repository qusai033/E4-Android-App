package com.empatica.e4companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.empatica.e4companion.decoder.DecodedSession
import com.empatica.e4companion.decoder.SessionExporter
import com.empatica.e4companion.ui.theme.E4Colors
import kotlinx.coroutines.launch
import java.io.File
import android.os.Environment

data class DeviceEntry(
    val folderName: String,
    val sessions: List<SessionEntry>
)

data class SessionEntry(
    val folderName: String,
    val rawPacketFile: File?,
    val tagFile: File?,
    val fileSize: Long,
    val hasRawPackets: Boolean
)

@Composable
fun SessionExportScreen(
    exporter: SessionExporter,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val textSecondary = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    // State
    var devices by remember { mutableStateOf<List<DeviceEntry>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<DeviceEntry?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<String?>(null) }
    var exportSuccess by remember { mutableStateOf(false) }

    // Scan for sessions on launch
    LaunchedEffect(Unit) {
        devices = scanForSessions()
    }

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
                if (selectedDevice != null && !isExporting) {
                    selectedDevice = null
                    exportResult = null
                    exportSuccess = false
                } else {
                    onBack()
                }
            }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (selectedDevice != null) "Sessions: ${selectedDevice!!.folderName}"
                else "Export Session Data",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── Info Card ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = "Info",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Select a session to decode raw BLE packets into " +
                            "ACC, BVP, EDA, TEMP, IBI, and HR CSV files, then share as a ZIP.",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Export progress/result ──
        if (isExporting) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Decoding raw packets…",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 16.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This may take a moment for large sessions",
                        color = textSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        } else if (exportSuccess && exportResult != null) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Success",
                        tint = E4Colors.StreamingGreen,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Export Successful!",
                        color = E4Colors.StreamingGreen,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        exportResult!!,
                        color = textSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = {
                            exportResult = null
                            exportSuccess = false
                            selectedDevice = null
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Export Another Session")
                    }
                }
            }
        } else if (exportResult != null && !exportSuccess) {
            // Error state
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
                        exportResult!!,
                        color = E4Colors.HeartRed,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = {
                            exportResult = null
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Try Again")
                    }
                }
            }
        } else if (selectedDevice != null) {
            // ── Session list for selected device ──
            val device = selectedDevice!!

            if (device.sessions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No sessions found for this device", color = textSecondary)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(device.sessions) { session ->
                        SessionCard(session) {
                            // Start export
                            scope.launch {
                                isExporting = true
                                exportResult = null
                                exportSuccess = false

                                val result = exporter.exportSession(
                                    deviceFolder = device.folderName,
                                    sessionFolder = session.folderName
                                )

                                isExporting = false

                                if (result.zipUri != null) {
                                    exportSuccess = true
                                    // In SessionExportScreen.kt, update the export result string:

                                    exportResult = "Decoded:\n" +
                                            "  ACC: ${result.decoded.accSamples} samples\n" +
                                            "  BVP: ${result.decoded.bvpSamples} samples\n" +
                                            "  EDA: ${result.decoded.edaSamples} samples\n" +
                                            "  TEMP: ${result.decoded.tempSamples} samples\n" +
                                            "  IBI: ${result.decoded.ibiSamples} samples\n" +
                                            "  HR: ${result.decoded.hrSamples} samples\n" +
                                            "\n" +
                                            "  Dropped packets: ${result.decoded.droppedPackets}\n" +
                                            "  Large gaps (>5s): ${result.decoded.largeGaps}"

                                    // Launch share intent
                                    val intent = exporter.getShareIntent(
                                        result.zipUri,
                                        session.folderName
                                    )
                                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(
                                        android.content.Intent.createChooser(intent, "Share E4 Session")
                                    )
                                } else {
                                    exportSuccess = false
                                    exportResult = "Export failed: ${result.error}"
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // ── Device list ──
            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.FolderOff,
                            contentDescription = "No data",
                            tint = textSecondary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("No recorded sessions found", color = textSecondary, fontSize = 16.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Connect to an E4 device and start\nstreaming to create session data",
                            color = textSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Text("Select a device:", color = textSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(devices) { device ->
                        DeviceCard(device) {
                            selectedDevice = device
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceEntry, onClick: () -> Unit) {
    val textSecondary = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Watch,
                contentDescription = "Device",
                tint = E4Colors.ChartLine,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.folderName,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                )
                Text(
                    "${device.sessions.size} session${if (device.sessions.size != 1) "s" else ""}",
                    color = textSecondary,
                    fontSize = 12.sp
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = "Open",
                tint = textSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun SessionCard(session: SessionEntry, onClick: () -> Unit) {
    val textSecondary = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(enabled = session.hasRawPackets) { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (session.hasRawPackets)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (session.hasRawPackets) Icons.Filled.DataObject else Icons.Filled.BrokenImage,
                contentDescription = "Session",
                tint = if (session.hasRawPackets) E4Colors.ChartLine else textSecondary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.folderName,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )

                // File info
                val sizeKb = session.fileSize / 1024
                val sizeMb = sizeKb / 1024
                val sizeStr = if (sizeMb > 0) "${sizeMb} MB" else "${sizeKb} KB"

                Row {
                    if (session.hasRawPackets) {
                        Text("Raw data: $sizeStr", color = textSecondary, fontSize = 11.sp)
                    } else {
                        Text("No raw packet data", color = E4Colors.HeartRed, fontSize = 11.sp)
                    }
                    if (session.tagFile != null) {
                        Text(" • ", color = textSecondary, fontSize = 11.sp)
                        Text("Has tags", color = E4Colors.StreamingGreen, fontSize = 11.sp)
                    }
                }
            }

            if (session.hasRawPackets) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = "Export",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * Scans the Documents/E4Companion/ folder for device folders and sessions [2].
 * Each device folder contains session subfolders with raw_packets.log.csv files.
 */
private fun scanForSessions(): List<DeviceEntry> {
    val rootDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        "E4Companion"
    )

    if (!rootDir.exists() || !rootDir.isDirectory) return emptyList()

    val devices = mutableListOf<DeviceEntry>()

    // Each subfolder of E4Companion is a device folder
    rootDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }?.forEach { deviceDir ->
        val sessions = mutableListOf<SessionEntry>()

        // Each subfolder of the device folder is a session
        deviceDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }?.forEach { sessionDir ->
            // Look for raw_packets.log or raw_packets.log.csv
            val rawFile = findRawPacketFile(sessionDir)
            val tagFile = sessionDir.listFiles()?.find {
                it.name.startsWith("TAG", ignoreCase = true) && it.length() > 0
            }

            sessions.add(
                SessionEntry(
                    folderName = sessionDir.name,
                    rawPacketFile = rawFile,
                    tagFile = tagFile,
                    fileSize = rawFile?.length() ?: 0,
                    hasRawPackets = rawFile != null && rawFile.length() > 100
                )
            )
        }

        if (sessions.isNotEmpty()) {
            devices.add(DeviceEntry(
                folderName = deviceDir.name,
                sessions = sessions
            ))
        }
    }

    return devices
}

/**
 * Find the raw packet file in a session directory.
 * MediaStore may save it as raw_packets.log, raw_packets.log.csv,
 * or raw_packets.csv depending on the Android version [2].
 */
private fun findRawPacketFile(sessionDir: File): File? {
    return sessionDir.listFiles()?.find { file ->
        val name = file.name.lowercase()
        (name.startsWith("raw_packets") || name.contains("raw_packet")) &&
                file.length() > 100  // Must have actual data
    }
}