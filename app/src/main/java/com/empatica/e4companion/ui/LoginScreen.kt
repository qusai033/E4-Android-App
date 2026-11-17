package com.empatica.e4companion.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.empatica.e4companion.R
import com.empatica.e4companion.auth.AuthManager
import com.empatica.e4companion.ui.theme.E4Colors

private val UofACardinal = Color(0xFFAB0520)

@Composable
fun LoginScreen(
    authManager: AuthManager,
    onSkipLogin: () -> Unit
) {
    val authState by authManager.authState.collectAsState()
    val isLoggingIn by authManager.isLoggingIn.collectAsState()
    val context = LocalContext.current

    val errorMessage = remember(authState) {
        (authState as? AuthManager.AuthState.Error)?.message
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Logo
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(E4Colors.LogoBackground)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.dhts_core),
                contentDescription = "Logo",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 80.dp),
                contentScale = ContentScale.Fit
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            "E4 Companion",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Sign in with your university NetID",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            fontSize = 14.sp
        )

        Spacer(Modifier.height(32.dp))

        // Error message
        if (errorMessage != null) {
            Text(
                errorMessage,
                color = UofACardinal,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )
            Spacer(Modifier.height(12.dp))
        }

        // NetID login button
        Button(
            onClick = { authManager.launchWebAuthLogin(context) },
            enabled = !isLoggingIn,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = UofACardinal,
                contentColor = Color.White,
                disabledContainerColor = UofACardinal.copy(alpha = 0.5f),
                disabledContentColor = Color.White.copy(alpha = 0.7f)
            )
        ) {
            if (isLoggingIn) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(12.dp))
                Text("Opening NetID Login…", fontSize = 16.sp)
            } else {
                Icon(Icons.Filled.School, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Sign In with NetID", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "You'll be redirected to the University of Arizona\nWebAuth login page",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            lineHeight = 16.sp
        )

        Spacer(Modifier.height(32.dp))

        // ── Skip login button — REMOVE THIS WHEN AUTH IS READY ──
        TextButton(
            onClick = onSkipLogin,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Continue without login",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "Digital Health Technology & Sensor Core",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
            fontSize = 10.sp,
            textAlign = TextAlign.Center
        )
    }
}