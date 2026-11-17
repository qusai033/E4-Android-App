package com.empatica.e4companion.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

private val E4DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF003544),
    primaryContainer = Color(0xFF004D64),
    secondary = Color(0xFF81C784),
    onSecondary = Color(0xFF003A00),
    background = Color(0xFF0F0F0F),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF252525),
    error = Color(0xFFEF5350),
    onError = Color.White,
    outline = Color(0xFF444444),
)

private val E4LightColorScheme = lightColorScheme(
    primary = Color(0xFF0277BD),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB3E5FC),
    secondary = Color(0xFF2E7D32),
    onSecondary = Color.White,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFE8E8E8),
    error = Color(0xFFD32F2F),
    onError = Color.White,
    outline = Color(0xFFBBBBBB),
)

object ThemeState {
    var isDarkMode by mutableStateOf(false)
}

@Composable
fun E4CompanionTheme(
    darkTheme: Boolean = ThemeState.isDarkMode,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) E4DarkColorScheme else E4LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

object E4Colors {
    val HeartRed: Color
        @Composable get() = if (ThemeState.isDarkMode) Color(0xFFEF5350) else Color(0xFFD32F2F)

    val StreamingGreen: Color
        @Composable get() = if (ThemeState.isDarkMode) Color(0xFF66BB6A) else Color(0xFF2E7D32)

    val WarningOrange: Color
        @Composable get() = if (ThemeState.isDarkMode) Color(0xFFFFA726) else Color(0xFFE65100)

    val DisconnectedGray: Color
        @Composable get() = if (ThemeState.isDarkMode) Color(0xFF757575) else Color(0xFF9E9E9E)

    val ChartLine: Color
        @Composable get() = if (ThemeState.isDarkMode) Color(0xFF4FC3F7) else Color(0xFF0277BD)

    val ChartGrid: Color
        @Composable get() = if (ThemeState.isDarkMode) Color(0xFF333333) else Color(0xFFDDDDDD)

    val CalibrationBlue: Color
        @Composable get() = if (ThemeState.isDarkMode) Color(0xFF42A5F5) else Color(0xFF1565C0)

    // Battery colors — same in both themes for clarity
    val BatteryGreen = Color(0xFF4CAF50)
    val BatteryOrange = Color(0xFFFF9800)
    val BatteryRed = Color(0xFFF44336)

    // Logo background for dark mode
    val LogoBackground: Color
        @Composable get() = if (ThemeState.isDarkMode) Color.White else Color.Transparent

    // Danger/destructive action
    val DangerRed: Color
        @Composable get() = if (ThemeState.isDarkMode) Color(0xFFFF5252) else Color(0xFFD32F2F)
}