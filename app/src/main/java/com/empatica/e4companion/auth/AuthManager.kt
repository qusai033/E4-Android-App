package com.empatica.e4companion.auth

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * WebAuth CAS authentication manager.
 *
 * Flow:
 *   1. App opens Chrome Custom Tab → BACKEND_URL/auth/login
 *   2. Backend redirects to WebAuth CAS for university login
 *   3. After CAS validation, backend issues a JWT and redirects to
 *      e4companion://auth?token=<jwt>
 *   4. App receives the deep link, stores the JWT, and is authenticated.
 *
 * Session persistence:
 *   - JWT is stored in SharedPreferences
 *   - On app launch, token expiry is checked locally (works offline)
 *   - Optionally, /auth/me endpoint validates the session server-side
 *   - Users stay logged in until token expires or they sign out
 */
class AuthManager(private val context: Context) {

    companion object {
        private const val TAG = "AuthManager"
        private const val PREFS_NAME = "auth_prefs"
        private const val PREF_TOKEN = "session_token"
        private const val PREF_EMAIL = "user_email"
        private const val PREF_NAME = "user_name"
        private const val PREF_IS_ADMIN = "is_admin"

        // ══════════════════════════════════════════
        // Update these to match your deployment
        // ══════════════════════════════════════════
        const val BACKEND_URL = "https://34.220.12.125"
        const val AUTH_CALLBACK_SCHEME = "e4companion"
        const val AUTH_CALLBACK_HOST = "auth"
    }

    sealed class AuthState {
        data object Loading : AuthState()
        data object NotAuthenticated : AuthState()
        data class Authenticated(
            val email: String,
            val displayName: String = "",
            val isAdmin: Boolean = false
        ) : AuthState()
        data class Error(val message: String) : AuthState()
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _isLoggingIn = MutableStateFlow(false)
    val isLoggingIn: StateFlow<Boolean> = _isLoggingIn.asStateFlow()

    /**
     * Check for an existing valid JWT session on app startup.
     * Works offline — only checks token expiry locally.
     */
    suspend fun checkExistingSession() {
        _authState.value = AuthState.Loading

        val token = prefs.getString(PREF_TOKEN, null)
        if (token != null && !isTokenExpired(token)) {
            val email = prefs.getString(PREF_EMAIL, "") ?: ""
            val name = prefs.getString(PREF_NAME, "") ?: ""
            val isAdmin = prefs.getBoolean(PREF_IS_ADMIN, false)
            _authState.value = AuthState.Authenticated(email, name, isAdmin)
            Log.d(TAG, "Existing session valid for: $email")
        } else {
            if (token != null) {
                Log.d(TAG, "Token expired, clearing session")
            }
            clearSession()
            _authState.value = AuthState.NotAuthenticated
        }
    }

    /**
     * Launch Chrome Custom Tab to start the WebAuth CAS login flow.
     * The browser will redirect back to the app via deep link after authentication.
     */
    fun launchWebAuthLogin(activityContext: Context) {
        _isLoggingIn.value = true

        val callbackUri = "$AUTH_CALLBACK_SCHEME://$AUTH_CALLBACK_HOST"
        val loginUrl = "$BACKEND_URL/auth/login?mobile_redirect=$callbackUri"

        Log.d(TAG, "Launching WebAuth login: $loginUrl")

        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.launchUrl(activityContext, Uri.parse(loginUrl))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Chrome Custom Tab", e)
            _authState.value = AuthState.Error("Could not open login page. Please check your internet connection.")
            _isLoggingIn.value = false
        }
    }

    /**
     * Handle the deep link callback after WebAuth login.
     *
     * Call from your Activity's onCreate/onNewIntent when receiving
     * an intent with data matching e4companion://auth?token=...
     *
     * @return true if authentication was successful
     */
    fun handleAuthCallback(uri: Uri): Boolean {
        Log.d(TAG, "Handling auth callback: ${uri.scheme}://${uri.host}")

        // Check for error from backend
        val error = uri.getQueryParameter("error_msg")
            ?: uri.getQueryParameter("error")
        if (error != null) {
            _authState.value = AuthState.Error(error)
            _isLoggingIn.value = false
            return false
        }

        // Extract token
        val token = uri.getQueryParameter("token")
        if (token == null) {
            _authState.value = AuthState.Error("No authentication token received")
            _isLoggingIn.value = false
            return false
        }

        return try {
            val payload = decodeJwtPayload(token)

            // Extract user info from JWT claims
            val email = payload.optString("email", "")
                .ifBlank { payload.optString("sub", "unknown") }
            val name = payload.optString("name", "")
                .ifBlank { payload.optString("display_name", email) }
            val isAdmin = payload.optBoolean("is_admin", false)

            // Check expiry
            if (isTokenExpired(token)) {
                _authState.value = AuthState.Error("Authentication token has expired. Please try again.")
                _isLoggingIn.value = false
                return false
            }

            // Save and authenticate
            saveSession(token, email, name, isAdmin)
            _authState.value = AuthState.Authenticated(email, name, isAdmin)
            _isLoggingIn.value = false

            Log.d(TAG, "WebAuth login successful: $email ($name)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process auth token", e)
            _authState.value = AuthState.Error("Invalid authentication token")
            _isLoggingIn.value = false
            false
        }
    }

    /**
     * Cancel a login attempt (e.g., user closed the browser tab).
     */
    fun cancelLogin() {
        _isLoggingIn.value = false
        if (_authState.value is AuthState.Loading || _authState.value is AuthState.Error) {
            _authState.value = AuthState.NotAuthenticated
        }
    }

    /**
     * Verify the session is still valid by calling /auth/me on the backend.
     * Falls back to local token expiry check if offline.
     */
    suspend fun verifySession(): Boolean {
        val token = prefs.getString(PREF_TOKEN, null) ?: return false

        return withContext(Dispatchers.IO) {
            try {
                val conn = URL("$BACKEND_URL/auth/me").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Cookie", "sfaas_session=$token")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val valid = conn.responseCode == 200
                conn.disconnect()

                if (!valid) {
                    Log.d(TAG, "Session rejected by server")
                    clearSession()
                    _authState.value = AuthState.NotAuthenticated
                }
                valid
            } catch (e: Exception) {
                // Offline — fall back to local token check
                Log.w(TAG, "Session verification failed (offline?): ${e.message}")
                !isTokenExpired(token)
            }
        }
    }

    /**
     * Sign out the current user.
     */
    fun signOut() {
        clearSession()
        _authState.value = AuthState.NotAuthenticated
        Log.d(TAG, "User signed out")
    }

    fun getCurrentUserEmail(): String? = prefs.getString(PREF_EMAIL, null)
    fun getCurrentUserName(): String? = prefs.getString(PREF_NAME, null)
    fun isAdmin(): Boolean = prefs.getBoolean(PREF_IS_ADMIN, false)

    // ═══ Private helpers ═══

    private fun saveSession(token: String, email: String, name: String, isAdmin: Boolean) {
        prefs.edit()
            .putString(PREF_TOKEN, token)
            .putString(PREF_EMAIL, email)
            .putString(PREF_NAME, name)
            .putBoolean(PREF_IS_ADMIN, isAdmin)
            .apply()
    }

    private fun clearSession() {
        prefs.edit()
            .remove(PREF_TOKEN)
            .remove(PREF_EMAIL)
            .remove(PREF_NAME)
            .remove(PREF_IS_ADMIN)
            .apply()
    }

    /**
     * Decode the payload (middle part) of a JWT token.
     * JWT format: header.payload.signature
     */
    private fun decodeJwtPayload(token: String): JSONObject {
        val parts = token.split(".")
        require(parts.size == 3) { "Invalid JWT format" }
        val payload = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
        return JSONObject(payload)
    }

    /**
     * Check if a JWT token has expired by reading its 'exp' claim.
     */
    private fun isTokenExpired(token: String): Boolean {
        return try {
            val payload = decodeJwtPayload(token)
            val exp = payload.getLong("exp")
            val now = System.currentTimeMillis() / 1000
            val expired = now > exp
            if (expired) {
                Log.d(TAG, "Token expired: now=$now, exp=$exp")
            }
            expired
        } catch (e: Exception) {
            Log.w(TAG, "Could not check token expiry: ${e.message}")
            true // Assume expired if we can't parse
        }
    }
}