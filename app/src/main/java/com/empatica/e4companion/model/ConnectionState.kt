package com.empatica.e4companion.model

/**
 * Connection state model.
 * Error messages are user-friendly — no characteristic UUIDs exposed.
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Scanning : ConnectionState()
    data object Connecting : ConnectionState()
    data object DiscoveringServices : ConnectionState()
    data object Subscribing : ConnectionState()
    data object StartingStream : ConnectionState()
    data object Streaming : ConnectionState()
    data object Disconnecting : ConnectionState()
    data class Error(val message: String) : ConnectionState()

    val isConnected: Boolean
        get() = this is Streaming

    val displayName: String
        get() = when (this) {
            is Disconnected -> "Disconnected"
            is Scanning -> "Scanning…"
            is Connecting -> "Connecting…"
            is DiscoveringServices -> "Discovering Services…"
            is Subscribing -> "Subscribing Sensors…"
            is StartingStream -> "Starting Stream…"
            is Streaming -> "Streaming"
            is Disconnecting -> "Disconnecting…"
            is Error -> "Error: $message"
        }
}