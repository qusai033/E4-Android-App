package com.empatica.e4companion.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.empatica.e4companion.R
import com.empatica.e4companion.model.ConnectionState
import com.empatica.e4companion.model.E4Device
import com.empatica.e4companion.processing.AccelData
import com.empatica.e4companion.processing.AcquisitionStatus
import com.empatica.e4companion.processing.SessionTimer
import com.empatica.e4companion.ui.components.BatteryLowAlert
import com.empatica.e4companion.ui.components.BvpSparkline
import com.empatica.e4companion.ui.components.HeartRateCard
import com.empatica.e4companion.ui.theme.E4Colors
import com.empatica.e4companion.ui.theme.ThemeState
import com.empatica.e4companion.viewmodel.E4ViewModel
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import kotlin.math.sqrt

@Composable
fun E4DashboardScreen(
    viewModel: E4ViewModel,
    onToggleTheme: () -> Unit,
    onNavigateToWipe: () -> Unit,
    onNavigateToExport: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onShowOnboarding: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val devices by viewModel.discoveredDevices.collectAsState()
    val heartRate by viewModel.heartRate.collectAsState()
    val peakHR by viewModel.peakBasedHR.collectAsState()
    val chartData by viewModel.chartData.collectAsState()
    val battery by viewModel.batteryLevel.collectAsState()
    val temperature by viewModel.temperature.collectAsState()
    val gsrValue by viewModel.gsrValue.collectAsState()
    val accel by viewModel.acceleration.collectAsState()
    val calibStatus by viewModel.calibrationStatus.collectAsState()
    val calibProgress by viewModel.calibrationProgress.collectAsState()
    val tagCount by viewModel.tagCount.collectAsState()
    val accHistory by viewModel.accHistory.collectAsState()
    val elapsedSeconds by viewModel.elapsedSeconds.collectAsState()
    val isReconnecting by viewModel.isReconnecting.collectAsState()
    val reconnectAttempt by viewModel.reconnectAttempt.collectAsState()
    val isServerUploading by viewModel.isUploading.collectAsState()
    val lastUploadStatus by viewModel.lastUploadStatus.collectAsState()
    val serverUploadCount by viewModel.uploadCount.collectAsState()

    val displayHR = if (heartRate > 0f) heartRate else peakHR
    val isStreaming = connectionState is ConnectionState.Streaming
    val textSecondary = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        @Suppress("unused")
        val constraints = this
        val isLandscape = this.maxWidth > this.maxHeight

        Column(modifier = Modifier.fillMaxSize()) {
            // ── Top bar: status + theme + logout ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    ConnectionStatusBar(
                        state = connectionState,
                        battery = battery,
                        elapsedSeconds = elapsedSeconds
                    )
                }
                Spacer(Modifier.width(4.dp))
                SettingsButton(onNavigateToSettings)
                Spacer(Modifier.width(4.dp))
                ThemeToggleButton(onToggleTheme)
                Spacer(Modifier.width(4.dp))
                LogoutButton(onSignOut)
            }

            // ── Reconnecting banner ──
            if (isReconnecting) {
                Spacer(Modifier.height(4.dp))
                ReconnectingBanner(reconnectAttempt)
            }

            // ── Sync status ──
            if (isStreaming) {
                Spacer(Modifier.height(4.dp))
                SyncStatusIndicator(
                    uploadStatus = lastUploadStatus,
                    uploadCount = serverUploadCount,
                    isServerUploading = isServerUploading
                )
            }

            // ── Tag indicator ──
            if (isStreaming && tagCount > 0) {
                Spacer(Modifier.height(4.dp))
                TagIndicator(tagCount)
            }

            Spacer(Modifier.height(4.dp))

            // ── Main content ──
            if (isStreaming) {
                if (isLandscape) {
                    LandscapeStreamingContent(
                        displayHR = displayHR,
                        battery = battery,
                        temperature = temperature,
                        gsrValue = gsrValue,
                        accel = accel,
                        accHistory = accHistory,
                        chartData = chartData,
                        calibStatus = calibStatus,
                        calibProgress = calibProgress,
                        connectionState = connectionState,
                        viewModel = viewModel,
                        context = context,
                        onNavigateToExport = onNavigateToExport,
                        onNavigateToWipe = onNavigateToWipe,
                        onShowOnboarding = onShowOnboarding,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    PortraitStreamingContent(
                        displayHR = displayHR,
                        battery = battery,
                        temperature = temperature,
                        gsrValue = gsrValue,
                        accel = accel,
                        accHistory = accHistory,
                        chartData = chartData,
                        calibStatus = calibStatus,
                        calibProgress = calibProgress,
                        connectionState = connectionState,
                        viewModel = viewModel,
                        context = context,
                        onNavigateToExport = onNavigateToExport,
                        onNavigateToWipe = onNavigateToWipe,
                        onShowOnboarding = onShowOnboarding,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                if (isLandscape) {
                    LandscapeHomeContent(
                        connectionState = connectionState,
                        viewModel = viewModel,
                        devices = devices,
                        context = context,
                        onNavigateToExport = onNavigateToExport,
                        onNavigateToWipe = onNavigateToWipe,
                        onShowOnboarding = onShowOnboarding,
                        isStreaming = false,
                        textSecondary = textSecondary,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    PortraitHomeContent(
                        connectionState = connectionState,
                        viewModel = viewModel,
                        devices = devices,
                        context = context,
                        onNavigateToExport = onNavigateToExport,
                        onNavigateToWipe = onNavigateToWipe,
                        onShowOnboarding = onShowOnboarding,
                        isStreaming = false,
                        textSecondary = textSecondary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════
//  LANDSCAPE STREAMING LAYOUT
// ═══════════════════════════════════════════

@Composable
private fun LandscapeStreamingContent(
    displayHR: Float,
    battery: Float,
    temperature: Float,
    gsrValue: Float,
    accel: AccelData,
    accHistory: List<Float>,
    chartData: FloatArray,
    calibStatus: AcquisitionStatus,
    calibProgress: Float,
    connectionState: ConnectionState,
    viewModel: E4ViewModel,
    context: android.content.Context,
    onNavigateToExport: () -> Unit,
    onNavigateToWipe: () -> Unit,
    onShowOnboarding: () -> Unit,
    modifier: Modifier = Modifier
) {
    val wristStatus by viewModel.multiSensor.wristStatus.collectAsState()
    Row(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(end = 8.dp)
        ) {
            if (calibStatus == AcquisitionStatus.Calibrating) {
                CalibrationBar(calibProgress)
                Spacer(Modifier.height(6.dp))
            }
            HeartRateCard(heartRate = displayHR, isStreaming = true)
            val batteryPct = (battery * 100).toInt().coerceIn(0, 100)
            BatteryLowAlert(batteryPercent = batteryPct, visible = battery >= 0f && batteryPct <= 10)
            Spacer(Modifier.height(6.dp))
            SensorSummaryRow(temperature, gsrValue, isOnWrist = wristStatus == 1)
            Spacer(Modifier.height(6.dp))
            AccelerometerCard(accel, accHistory)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 8.dp)
        ) {
            if (chartData.isNotEmpty()) {
                BvpSparkline(data = chartData, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
            }
            ActionButton(connectionState, viewModel)
            Spacer(Modifier.height(6.dp))
            ActionButtonRow(context, viewModel, onNavigateToExport, onNavigateToWipe, onShowOnboarding, true)
        }
    }
}

// ═══════════════════════════════════════════
//  PORTRAIT STREAMING LAYOUT
// ═══════════════════════════════════════════

@Composable
private fun PortraitStreamingContent(
    displayHR: Float,
    battery: Float,
    temperature: Float,
    gsrValue: Float,
    accel: AccelData,
    accHistory: List<Float>,
    chartData: FloatArray,
    calibStatus: AcquisitionStatus,
    calibProgress: Float,
    connectionState: ConnectionState,
    viewModel: E4ViewModel,
    context: android.content.Context,
    onNavigateToExport: () -> Unit,
    onNavigateToWipe: () -> Unit,
    onShowOnboarding: () -> Unit,
    modifier: Modifier = Modifier
) {
    val wristStatus by viewModel.multiSensor.wristStatus.collectAsState()
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        if (calibStatus == AcquisitionStatus.Calibrating) {
            CalibrationBar(calibProgress)
            Spacer(Modifier.height(8.dp))
        }
        HeartRateCard(heartRate = displayHR, isStreaming = true)
        val batteryPct = (battery * 100).toInt().coerceIn(0, 100)
        BatteryLowAlert(batteryPercent = batteryPct, visible = battery >= 0f && batteryPct <= 10)
        Spacer(Modifier.height(8.dp))
        SensorSummaryRow(temperature, gsrValue, isOnWrist = wristStatus == 1)
        Spacer(Modifier.height(8.dp))
        AccelerometerCard(accel, accHistory)
        Spacer(Modifier.height(8.dp))
        if (chartData.isNotEmpty()) {
            BvpSparkline(data = chartData, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        ActionButton(connectionState, viewModel)
        Spacer(Modifier.height(8.dp))
        ActionButtonRow(context, viewModel, onNavigateToExport, onNavigateToWipe, onShowOnboarding, true)
    }
}

// ═══════════════════════════════════════════
//  LANDSCAPE HOME LAYOUT
// ═══════════════════════════════════════════

@Composable
private fun LandscapeHomeContent(
    connectionState: ConnectionState,
    viewModel: E4ViewModel,
    devices: List<E4Device>,
    context: android.content.Context,
    onNavigateToExport: () -> Unit,
    onNavigateToWipe: () -> Unit,
    onShowOnboarding: () -> Unit,
    isStreaming: Boolean,
    textSecondary: Color,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 8.dp),
            contentAlignment = Alignment.Center
        ) { AppLogo() }
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp)
        ) {
            ActionButton(connectionState, viewModel)
            Spacer(Modifier.height(8.dp))
            ActionButtonRow(context, viewModel, onNavigateToExport, onNavigateToWipe, onShowOnboarding, isStreaming)
            if (connectionState is ConnectionState.Scanning ||
                (connectionState is ConnectionState.Disconnected && devices.isNotEmpty())
            ) {
                Spacer(Modifier.height(8.dp))
                if (devices.isNotEmpty()) {
                    Text("Discovered Devices", color = textSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(devices, key = { it.address }) { device ->
                        DeviceCard(device) { viewModel.connectDevice(device) }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════
//  PORTRAIT HOME LAYOUT
// ═══════════════════════════════════════════

@Composable
private fun PortraitHomeContent(
    connectionState: ConnectionState,
    viewModel: E4ViewModel,
    devices: List<E4Device>,
    context: android.content.Context,
    onNavigateToExport: () -> Unit,
    onNavigateToWipe: () -> Unit,
    onShowOnboarding: () -> Unit,
    isStreaming: Boolean,
    textSecondary: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) { AppLogo() }
            ActionButton(connectionState, viewModel)
            Spacer(Modifier.height(8.dp))
            ActionButtonRow(context, viewModel, onNavigateToExport, onNavigateToWipe, onShowOnboarding, isStreaming)
        }
        if (connectionState is ConnectionState.Scanning ||
            (connectionState is ConnectionState.Disconnected && devices.isNotEmpty())
        ) {
            if (devices.isNotEmpty()) {
                Text("Discovered Devices", color = textSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(devices, key = { it.address }) { device ->
                    DeviceCard(device) { viewModel.connectDevice(device) }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════
//  SHARED ACTION BUTTONS ROW
//  Now receives isStreaming and onShowOnboarding as parameters
// ═══════════════════════════════════════════

@Composable
private fun ActionButtonRow(
    context: android.content.Context,
    viewModel: E4ViewModel,
    onNavigateToExport: () -> Unit,
    onNavigateToWipe: () -> Unit,
    onShowOnboarding: () -> Unit,
    isStreaming: Boolean
) {
    val textSecondary = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    OutlinedButton(
        onClick = {
            try { context.startActivity(viewModel.getDataFolderIntent()) }
            catch (_: Exception) { }
        },
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(Icons.Filled.Folder, contentDescription = "Open Data", modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Open Data Folder", fontSize = 13.sp)
    }

    Spacer(Modifier.height(4.dp))

    OutlinedButton(
        onClick = onNavigateToExport,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
    ) {
        Icon(Icons.Filled.Share, contentDescription = "Export", modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Decode & Share Session", fontSize = 13.sp)
    }

    Spacer(Modifier.height(4.dp))

    TextButton(
        onClick = onNavigateToWipe,
        modifier = Modifier.fillMaxWidth().height(36.dp),
    ) {
        Icon(Icons.Filled.DeleteSweep, contentDescription = "Wipe Memory",
            tint = E4Colors.DangerRed, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text("Wipe E4 Device Memory", color = E4Colors.DangerRed, fontSize = 11.sp)
    }

    // Data path + subtle help button (only when not streaming)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Data: Documents/E4Companion/",
            color = textSecondary,
            fontSize = 10.sp,
            modifier = Modifier.weight(1f)
        )
        if (!isStreaming) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    .clickable { onShowOnboarding() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "?",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ═══════════════════════════════════════════
//  REUSABLE COMPOSABLES
// ═══════════════════════════════════════════
@Composable
private fun SettingsButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = "Settings",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}
@Composable
private fun ThemeToggleButton(onToggle: () -> Unit) {
    val isDark = ThemeState.isDarkMode
    IconButton(
        onClick = onToggle,
        modifier = Modifier.size(40.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Icon(
            imageVector = if (isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
            contentDescription = if (isDark) "Switch to light mode" else "Switch to dark mode",
            tint = if (isDark) Color(0xFFFFC107) else Color(0xFF5C6BC0),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun AppLogo() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(E4Colors.LogoBackground)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.dhts_core),
            contentDescription = "E4 Companion",
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun ReconnectingBanner(attempt: Int) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(E4Colors.WarningOrange.copy(alpha = 0.15f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = E4Colors.WarningOrange
        )
        Spacer(Modifier.width(8.dp))
        Text("Reconnecting… (attempt $attempt)", color = E4Colors.WarningOrange,
            fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SyncStatusIndicator(
    uploadStatus: String,
    uploadCount: Int,
    isServerUploading: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        if (isServerUploading) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = E4Colors.WarningOrange
            )
            Spacer(Modifier.width(4.dp))
            Text("Syncing…", color = E4Colors.WarningOrange, fontSize = 9.sp)
        } else if (uploadCount > 0) {
            Icon(
                Icons.Filled.CloudDone,
                contentDescription = "Synced",
                tint = E4Colors.StreamingGreen,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(3.dp))
            Text("Synced ($uploadCount)", color = E4Colors.StreamingGreen, fontSize = 9.sp)
        } else {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = "Not synced",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(3.dp))
            Text(
                "Not synced",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                fontSize = 9.sp
            )
        }
    }
}

@Composable
private fun TagIndicator(count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(E4Colors.StreamingGreen.copy(alpha = 0.15f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.BookmarkAdded, contentDescription = "Tag",
            tint = E4Colors.StreamingGreen, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("TAG MARKED ($count)", color = E4Colors.StreamingGreen,
            fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AccelerometerCard(accel: AccelData, accHistory: List<Float>) {
    val textSecondary = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Vibration, contentDescription = "Accelerometer",
                    tint = E4Colors.ChartLine, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("ACCELEROMETER", color = textSecondary, fontSize = 11.sp, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                AccelAxis("X", accel.x, Color(0xFFD32F2F))
                AccelAxis("Y", accel.y, Color(0xFF2E7D32))
                AccelAxis("Z", accel.z, Color(0xFF1565C0))
            }
            Spacer(Modifier.height(10.dp))
            val mag = sqrt(accel.x * accel.x + accel.y * accel.y + accel.z * accel.z)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MAGNITUDE", color = textSecondary, fontSize = 10.sp, letterSpacing = 1.sp)
                Spacer(Modifier.width(8.dp))
                Text("%.2f g".format(mag), color = E4Colors.WarningOrange,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(6.dp))
            if (accHistory.isNotEmpty()) {
                MagnitudeGraph(data = accHistory,
                    modifier = Modifier.fillMaxWidth().height(80.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant))
            }
        }
    }
}

@Composable
private fun AccelAxis(label: String, value: Float, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("%.2f".format(value), color = MaterialTheme.colorScheme.onSurface,
            fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text("g", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 10.sp)
    }
}

@Composable
private fun LogoutButton(onSignOut: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    IconButton(
        onClick = { showConfirm = true },
        modifier = Modifier.size(36.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Logout,
            contentDescription = "Sign out",
            tint = E4Colors.HeartRed,
            modifier = Modifier.size(18.dp)
        )
    }
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = E4Colors.HeartRed) },
            title = { Text("Sign Out?") },
            text = { Text("You will need to sign in again to use the app.") },
            confirmButton = {
                Button(
                    onClick = {
                        onSignOut()
                        showConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = E4Colors.HeartRed)
                ) { Text("Sign Out") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun MagnitudeGraph(data: List<Float>, modifier: Modifier = Modifier) {
    val lineColor = E4Colors.WarningOrange
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    Canvas(modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
        if (data.size < 2) return@Canvas
        val w = size.width; val h = size.height
        val min = data.min().coerceAtMost(0f)
        val max = data.max().coerceAtLeast(2f)
        val range = (max - min).coerceAtLeast(0.1f)
        for (i in 1..3) {
            val y = h * i / 4f
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 0.5f)
        }
        val oneGy = h - ((1f - min) / range) * h
        drawLine(gridColor.copy(alpha = 0.6f), Offset(0f, oneGy), Offset(w, oneGy), strokeWidth = 1f)
        val path = Path()
        val stepX = w / (data.size - 1).toFloat()
        data.forEachIndexed { index, value ->
            val x = index * stepX
            val y = h - ((value - min) / range) * h
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 2f))
    }
}

@Composable
private fun ConnectionStatusBar(
    state: ConnectionState,
    battery: Float,
    elapsedSeconds: Long
) {
    val textSecondary = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val (color, icon) = when (state) {
        is ConnectionState.Disconnected -> E4Colors.DisconnectedGray to Icons.Filled.BluetoothDisabled
        is ConnectionState.Scanning -> E4Colors.WarningOrange to Icons.AutoMirrored.Filled.BluetoothSearching
        is ConnectionState.Connecting, is ConnectionState.DiscoveringServices,
        is ConnectionState.Subscribing, is ConnectionState.StartingStream ->
            E4Colors.WarningOrange to Icons.Filled.BluetoothConnected
        is ConnectionState.Streaming -> E4Colors.StreamingGreen to Icons.Filled.BluetoothConnected
        is ConnectionState.Disconnecting -> E4Colors.WarningOrange to Icons.Filled.BluetoothDisabled
        is ConnectionState.Error -> E4Colors.HeartRed to Icons.Filled.ErrorOutline
    }
    val animatedColor by animateColorAsState(color, label = "statusColor")

    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(animatedColor))
        Spacer(Modifier.width(8.dp))
        Icon(icon, contentDescription = null, tint = animatedColor, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(state.displayName, color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))

        if (battery >= 0f && state is ConnectionState.Streaming) {
            val pct = (battery * 100).toInt().coerceIn(0, 100)
            val batteryColor = when {
                pct > 20 -> Color(0xFF4CAF50)
                pct > 10 -> Color(0xFFFF9800)
                else -> Color(0xFFF44336)
            }
            val battIcon = when {
                pct > 80 -> Icons.Filled.BatteryFull
                pct > 50 -> Icons.Filled.BatteryStd
                pct > 20 -> Icons.Filled.Battery3Bar
                pct > 10 -> Icons.Filled.Battery2Bar
                else -> Icons.Filled.Battery0Bar
            }
            Icon(battIcon, contentDescription = "Battery $pct%",
                tint = batteryColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(3.dp))
            Text("$pct%", color = batteryColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        if (state is ConnectionState.Streaming) {
            Spacer(Modifier.width(6.dp))
            Text(SessionTimer.formatTime(elapsedSeconds), color = textSecondary,
                fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun CalibrationBar(progress: Float) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Tune, contentDescription = null, tint = E4Colors.CalibrationBlue,
                modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Calibrating… ${(progress * 100).toInt()}%", color = E4Colors.CalibrationBlue, fontSize = 13.sp)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = E4Colors.CalibrationBlue,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun SensorSummaryRow(temperature: Float, gsr: Float, isOnWrist: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SensorChip(
            icon = Icons.Filled.Thermostat,
            label = "Temp",
            value = if (temperature > 0f) "%.1f °C".format(temperature) else "--",
            modifier = Modifier.weight(1f)
        )
        SensorChip(
            // Use the Watch and WatchOff icons instead of WaterDrop
            icon = if (isOnWrist) Icons.Filled.Watch else Icons.Filled.WatchOff,
            label = if (isOnWrist) "EDA" else "EDA (Off)",
            value = if (gsr > 0f) "%.3f μS".format(gsr) else "--",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SensorChip(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    val textSecondary = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = label, tint = textSecondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Column {
                Text(label, color = textSecondary, fontSize = 10.sp)
                Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ActionButton(state: ConnectionState, viewModel: E4ViewModel) {
    val (text, enabled, containerColor) = when (state) {
        is ConnectionState.Disconnected, is ConnectionState.Error ->
            Triple("Scan & Connect", true, MaterialTheme.colorScheme.primary)
        is ConnectionState.Scanning -> Triple("Stop Scan", true, E4Colors.WarningOrange)
        is ConnectionState.Streaming -> Triple("Disconnect", true, E4Colors.HeartRed)
        else -> Triple("Please wait…", false, MaterialTheme.colorScheme.primary)
    }
    Button(
        onClick = {
            when (state) {
                is ConnectionState.Disconnected, is ConnectionState.Error -> viewModel.startScan()
                is ConnectionState.Scanning -> viewModel.stopScan()
                is ConnectionState.Streaming -> viewModel.disconnect()
                else -> { }
            }
        },
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp)
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun DeviceCard(device: E4Device, onClick: () -> Unit) {
    val textSecondary = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Watch, contentDescription = "Device", tint = E4Colors.ChartLine,
                modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SN: ", color = textSecondary, fontSize = 11.sp)
                    Text(device.serialNumber,
                        color = if (device.isSerialKnown) E4Colors.StreamingGreen else textSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (device.isSerialKnown) FontWeight.Medium else FontWeight.Normal)
                    if (device.isSerialKnown) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.Verified, contentDescription = "Verified SN",
                            tint = E4Colors.StreamingGreen, modifier = Modifier.size(12.dp))
                    }
                }
                Text("MAC: ${device.address}", color = textSecondary, fontSize = 10.sp)
                if (device.firmwareVersion.isNotEmpty()) {
                    Text(device.firmwareVersion, color = textSecondary, fontSize = 10.sp)
                }
            }
            Text("${device.rssi} dBm", color = E4Colors.ChartLine, fontSize = 12.sp,
                fontWeight = FontWeight.Medium)
        }
    }
}
