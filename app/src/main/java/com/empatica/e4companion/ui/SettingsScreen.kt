package com.empatica.e4companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.empatica.e4companion.settings.SettingsManager
import com.empatica.e4companion.ui.theme.E4Colors

@Composable
fun SettingsScreen(
    settingsManager: SettingsManager,
    onBack: () -> Unit
) {
    val currentApiUrl by settingsManager.apiUrl.collectAsState()
    val currentInterval by settingsManager.uploadIntervalSeconds.collectAsState()
    val currentTemp by settingsManager.uploadTemp.collectAsState()
    val currentEda by settingsManager.uploadEda.collectAsState()
    val currentHr by settingsManager.uploadHr.collectAsState()
    val currentAcc by settingsManager.uploadAcc.collectAsState()

    var apiUrlInput by remember { mutableStateOf(currentApiUrl) }
    var intervalInput by remember { mutableStateOf(currentInterval.toString()) }
    var tempEnabled by remember { mutableStateOf(currentTemp) }
    var edaEnabled by remember { mutableStateOf(currentEda) }
    var hrEnabled by remember { mutableStateOf(currentHr) }
    var accEnabled by remember { mutableStateOf(currentAcc) }
    var showSavedMessage by remember { mutableStateOf(false) }

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
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Settings",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            // ── API Endpoint Section ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Cloud,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "API Endpoint",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        "Enter the URL where sensor data will be uploaded via POST requests.",
                        color = textSecondary,
                        fontSize = 12.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = apiUrlInput,
                        onValueChange = { apiUrlInput = it },
                        label = { Text("API URL") },
                        placeholder = { Text("https://your-server.com/empatica") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Link,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(Modifier.height(8.dp))
                    val urlValid = apiUrlInput.isNotBlank() &&
                            (apiUrlInput.startsWith("http://") || apiUrlInput.startsWith("https://"))
                    if (apiUrlInput.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (urlValid) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                contentDescription = null,
                                tint = if (urlValid) E4Colors.StreamingGreen else E4Colors.HeartRed,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (urlValid) "Valid URL" else "Must start with http:// or https://",
                                color = if (urlValid) E4Colors.StreamingGreen else E4Colors.HeartRed,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Upload Frequency Section ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Timer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Upload Frequency",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        "How often sensor data is sent to the server. Min: 1s, Max: 5 minutes.",
                        color = textSecondary,
                        fontSize = 12.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = intervalInput,
                        onValueChange = { newValue ->
                            if (newValue.all { it.isDigit() } || newValue.isEmpty()) {
                                intervalInput = newValue
                            }
                        },
                        label = { Text("Interval (seconds)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        suffix = { Text("seconds") },
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(Modifier.height(8.dp))

                    // Quick preset buttons
                    Text("Quick presets:", color = textSecondary, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1, 5, 10, 30, 60).forEach { seconds ->
                            val label = if (seconds >= 60) "${seconds / 60}m" else "${seconds}s"
                            FilterChip(
                                selected = intervalInput == seconds.toString(),
                                onClick = { intervalInput = seconds.toString() },
                                label = { Text(label, fontSize = 11.sp) },
                                modifier = Modifier.height(30.dp)
                            )
                        }
                    }

                    val intervalValue = intervalInput.toIntOrNull()
                    if (intervalInput.isNotEmpty() && intervalValue != null && intervalValue !in 1..300) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Must be between 1 and 300 seconds",
                            color = E4Colors.HeartRed,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Sensor Selection Section ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Sensors,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Sensors to Upload",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        "Choose which sensor data gets sent to the server.",
                        color = textSecondary,
                        fontSize = 12.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    // Temperature toggle
                    SensorToggleRow(
                        icon = Icons.Filled.Thermostat,
                        label = "Temperature",
                        description = "Skin temperature (°C)",
                        checked = tempEnabled,
                        onCheckedChange = { tempEnabled = it }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // EDA toggle
                    SensorToggleRow(
                        icon = Icons.Filled.Watch,
                        label = "EDA (Electrodermal Activity)",
                        description = "Skin conductance (μS)",
                        checked = edaEnabled,
                        onCheckedChange = { edaEnabled = it }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Heart Rate toggle
                    SensorToggleRow(
                        icon = Icons.Filled.Favorite,
                        label = "Heart Rate",
                        description = "BPM from BVP signal",
                        checked = hrEnabled,
                        onCheckedChange = { hrEnabled = it }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Accelerometer toggle
                    SensorToggleRow(
                        icon = Icons.Filled.Vibration,
                        label = "Accelerometer",
                        description = "X, Y, Z axes (g)",
                        checked = accEnabled,
                        onCheckedChange = { accEnabled = it }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

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
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Data is sent as a JSON POST request containing averaged " +
                                "sensor values for each interval. " +
                                "If no API URL is set, data is only saved locally.",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Save Button ──
            Button(
                onClick = {
                    settingsManager.saveApiUrl(apiUrlInput)
                    val interval = intervalInput.toIntOrNull()
                        ?: SettingsManager.DEFAULT_UPLOAD_INTERVAL_SECONDS
                    settingsManager.saveUploadInterval(interval)
                    settingsManager.saveUploadTemp(tempEnabled)
                    settingsManager.saveUploadEda(edaEnabled)
                    settingsManager.saveUploadHr(hrEnabled)
                    settingsManager.saveUploadAcc(accEnabled)
                    showSavedMessage = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save Settings", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            if (showSavedMessage) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = E4Colors.StreamingGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Settings saved! Changes apply on next connection.",
                        color = E4Colors.StreamingGreen,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SensorToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (checked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                description,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}