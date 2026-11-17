package com.empatica.e4companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.empatica.e4companion.ui.theme.E4Colors
import kotlinx.coroutines.launch

data class OnboardingPage(
    val icon: ImageVector,
    val iconTint: Color,
    val title: String,
    val description: String
)

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val pages = listOf(
        OnboardingPage(
            icon = Icons.Filled.Watch,
            iconTint = Color(0xFF4FC3F7),
            title = "Power On Your E4",
            description = "Hold the E4's button for 2 seconds until the LED blinks blue. " +
                    "This means it's in Bluetooth discovery mode and ready to pair."
        ),
        OnboardingPage(
            icon = Icons.Filled.BluetoothConnected,
            iconTint = Color(0xFF66BB6A),
            title = "Scan & Connect",
            description = "Tap 'Scan & Connect' on the home screen. Your E4 will appear " +
                    "in the device list. Tap it to connect. The app will subscribe to all " +
                    "sensors and start streaming data automatically."
        ),
        OnboardingPage(
            icon = Icons.Filled.Sensors,
            iconTint = Color(0xFFFFA726),
            title = "Real-Time Monitoring",
            description = "Once connected, you'll see heart rate, BVP waveform, temperature, " +
                    "EDA, and accelerometer data updating in real-time. Raw BLE packets are " +
                    "saved automatically for later analysis."
        ),
        OnboardingPage(
            icon = Icons.Filled.BookmarkAdded,
            iconTint = Color(0xFFEF5350),
            title = "Mark Events",
            description = "Press the physical button on the E4 to mark event tags. " +
                    "Tags are saved with Unix timestamps and can be used to annotate " +
                    "important moments during your study."
        ),
        OnboardingPage(
            icon = Icons.Filled.Share,
            iconTint = Color(0xFF7E57C2),
            title = "Export Your Data",
            description = "Use 'Decode & Share Session' to convert raw BLE packets into " +
                    "CSV files (ACC, BVP, EDA, TEMP) with proper timestamps and filters. " +
                    "Data is saved in Documents/E4Companion/ organized by device and session."
        )
    )

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Skip button
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onComplete) {
                Text("Skip", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }

        // Pager
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            OnboardingPageContent(pages[page])
        }

        // Dots indicator + Next/Done button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Page dots
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(pages.size) { index ->
                    val isActive = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .size(if (isActive) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline
                            )
                    )
                }
            }

            // Next / Done button
            val isLastPage = pagerState.currentPage == pages.size - 1
            Button(
                onClick = {
                    if (isLastPage) {
                        onComplete()
                    } else {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (isLastPage) "Get Started" else "Next")
            }
        }
    }
}

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            page.icon,
            contentDescription = page.title,
            tint = page.iconTint,
            modifier = Modifier.size(80.dp)
        )

        Spacer(Modifier.height(32.dp))

        Text(
            page.title,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        Text(
            page.description,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
    }
}