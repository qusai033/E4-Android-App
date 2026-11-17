package com.empatica.e4companion

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.empatica.e4companion.auth.AuthManager
import com.empatica.e4companion.ble.E4MemoryWiper
import com.empatica.e4companion.decoder.SessionExporter
import com.empatica.e4companion.ui.*
import com.empatica.e4companion.ui.theme.E4CompanionTheme
import com.empatica.e4companion.ui.theme.ThemeState
import com.empatica.e4companion.viewmodel.E4ViewModel
import android.view.WindowManager
import com.empatica.e4companion.security.SecurityUtils
import com.empatica.e4companion.ui.RootDetectedScreen
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.launch
import com.empatica.e4companion.settings.SettingsManager

class MainActivity : ComponentActivity() {

    private val viewModel: E4ViewModel by viewModels()
    private lateinit var memoryWiper: E4MemoryWiper
    private lateinit var sessionExporter: SessionExporter
    private lateinit var authManager: AuthManager

    @OptIn(ExperimentalPermissionsApi::class)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevents screenshots, screen recording, and hides the app in the "Recent Apps" switcher
        //window.setFlags(
            //WindowManager.LayoutParams.FLAG_SECURE,
            //WindowManager.LayoutParams.FLAG_SECURE
        //)
        // 2. The Root Check
        if (SecurityUtils.isDeviceRooted()) {
            // Lock them out. Do not initialize ViewModels or BLE.
            setContent {
                E4CompanionTheme {
                    RootDetectedScreen()
                }
            }
            return // <--- CRITICAL: This stops the rest of onCreate from executing!
        }



        memoryWiper = E4MemoryWiper(applicationContext)
        memoryWiper.setLabelManager(viewModel.labelManager)
        sessionExporter = SessionExporter(applicationContext)
        authManager = AuthManager(applicationContext)

        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        ThemeState.isDarkMode = prefs.getBoolean("dark_mode", false)
        val hasSeenOnboarding = prefs.getBoolean("onboarding_complete", false)

        // Handle deep link if app was launched via e4companion://auth?token=...
        handleDeepLink(intent)

        setContent {
            E4CompanionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val authState by authManager.authState.collectAsState()
                    val scope = rememberCoroutineScope()

                    // Track if user skipped login
                    // ── REMOVE THIS WHEN AUTH IS READY ──
                    var loginSkipped by remember { mutableStateOf(false) }

                    // Check existing session on launch
                    LaunchedEffect(Unit) {
                        authManager.checkExistingSession()
                    }

                    when {
                        // ── REMOVE THIS BLOCK WHEN AUTH IS READY ──
                        loginSkipped -> {
                            AuthenticatedContent(
                                prefs = prefs,
                                hasSeenOnboarding = hasSeenOnboarding,
                                onSignOut = { loginSkipped = false }
                            )
                        }

                        authState is AuthManager.AuthState.Loading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        authState is AuthManager.AuthState.Authenticated -> {
                            AuthenticatedContent(
                                prefs = prefs,
                                hasSeenOnboarding = hasSeenOnboarding,
                                onSignOut = { authManager.signOut() }
                            )
                        }

                        else -> {
                            LoginScreen(
                                authManager = authManager,
                                onSkipLogin = { loginSkipped = true }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == AuthManager.AUTH_CALLBACK_SCHEME &&
            uri.host == AuthManager.AUTH_CALLBACK_HOST
        ) {
            authManager.handleAuthCallback(uri)
        }
    }

    override fun onResume() {
        super.onResume()
        if (authManager.isLoggingIn.value && intent?.data == null) {
            window.decorView.postDelayed({
                if (authManager.isLoggingIn.value) {
                    authManager.cancelLogin()
                }
            }, 1500)
        }
    }

    @OptIn(ExperimentalPermissionsApi::class)
    @Composable
    private fun AuthenticatedContent(
        prefs: android.content.SharedPreferences,
        hasSeenOnboarding: Boolean,
        onSignOut: () -> Unit
    ) {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT < 31) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val permissionsState = rememberMultiplePermissionsState(permissions)

        if (permissionsState.allPermissionsGranted) {
            var showOnboarding by remember { mutableStateOf(!hasSeenOnboarding) }
            var currentScreen by remember { mutableStateOf("dashboard") }

            if (showOnboarding) {
                OnboardingScreen(
                    onComplete = {
                        showOnboarding = false
                        prefs.edit().putBoolean("onboarding_complete", true).apply()
                    }
                )
            } else {
                when (currentScreen) {
                    "dashboard" -> E4DashboardScreen(
                        viewModel = viewModel,
                        onToggleTheme = {
                            ThemeState.isDarkMode = !ThemeState.isDarkMode
                            prefs.edit()
                                .putBoolean("dark_mode", ThemeState.isDarkMode)
                                .apply()
                        },
                        onNavigateToWipe = { currentScreen = "wipe" },
                        onNavigateToExport = { currentScreen = "export" },
                        onNavigateToSettings = { currentScreen = "settings" },
                        onShowOnboarding = { showOnboarding = true },
                        onSignOut = onSignOut
                    )
                    "settings" -> SettingsScreen(
                        settingsManager = viewModel.settingsManager,
                        onBack = { currentScreen = "dashboard" }
                    )
                    "wipe" -> MemoryWipeScreen(
                        wiper = memoryWiper,
                        onBack = {
                            memoryWiper.reset()
                            currentScreen = "dashboard"
                        }
                    )
                    "export" -> SessionExportScreen(
                        exporter = sessionExporter,
                        onBack = { currentScreen = "dashboard" }
                    )
                }
            }
        } else {
            PermissionRequestScreen {
                permissionsState.launchMultiplePermissionRequest()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        memoryWiper.destroy()
    }
}

@Composable
private fun PermissionRequestScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Permissions Required",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 22.sp
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "This app needs Bluetooth and location permissions to communicate with the Empatica E4 wristband.",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            fontSize = 14.sp
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) {
            Text("Grant Permissions")
        }
    }
}