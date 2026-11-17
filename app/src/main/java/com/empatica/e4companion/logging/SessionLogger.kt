package com.empatica.e4companion.logging

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import com.empatica.e4companion.settings.SettingsManager

/**
 * Session logger — saves raw BLE packets and tag events.
 *
 * Folder structure:
 *   Documents/E4Companion/<device_SN>/session_<timestamp>/
 *     TAG.csv          — event markers with Unix timestamps
 *     raw_packets.log  — all raw BLE packets as hex (untouched)
 *
 * Each device gets its own folder so sessions are organized per-device.
 */

sealed class LogEntry {
    data class Tag(val unixTimestamp: Double) : LogEntry()
    data class RawPacket(
        val timestamp: Double,
        val characteristic: String,
        val data: ByteArray
    ) : LogEntry()
}

class SessionLogger(private val context: Context) {

    companion object {
        private const val TAG = "SessionLogger"
        private const val ROOT_FOLDER = "E4Companion"
        private const val CHANNEL_BUFFER = 4096
    }


    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logChannel = Channel<LogEntry>(CHANNEL_BUFFER)

    private var tagStream: OutputStream? = null
    private var rawPacketStream: OutputStream? = null

    private var writerJob: Job? = null
    private var isLogging = false
    private var sessionStartUnix: Double = 0.0
    private var tagCount = 0

    var sessionFolder: String = ""
        private set

    var deviceFolder: String = ""
        private set

    /**
     * Full relative path: E4Companion/<deviceSN>/session_<timestamp>
     */
    private fun getRelativePath(): String {
        return "$ROOT_FOLDER/$deviceFolder/$sessionFolder"
    }

    fun getSessionPath(): String {
        val documentsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOCUMENTS
        )
        return "${documentsDir.absolutePath}/${getRelativePath()}"
    }

    fun getRootDataPath(): String {
        val documentsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOCUMENTS
        )
        return "${documentsDir.absolutePath}/$ROOT_FOLDER"
    }

    fun getOpenDocumentsIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val docsUri = Uri.parse(
                    "content://com.android.externalstorage.documents/document/primary:Documents%2F$ROOT_FOLDER")
                putExtra("android.provider.extra.INITIAL_URI", docsUri)
            }
        }
    }

    /**
     * Start a new session for a specific device.
     * Creates folder: E4Companion/<deviceSerialNumber>/session_<timestamp>/
     */
    fun startSession(deviceSerialNumber: String) {
        if (isLogging) return

        sessionStartUnix = System.currentTimeMillis() / 1000.0
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        // Sanitize device SN for use as folder name
        deviceFolder = deviceSerialNumber.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        sessionFolder = "session_$dateStr"
        tagCount = 0

        val startTimeStr = "%.6f".format(Locale.US, sessionStartUnix)

        // TAG.csv — Unix timestamps of tag button presses
        tagStream = createFile("TAG.csv")?.also {
            it.write("# Tag events for device: $deviceSerialNumber\n".toByteArray())
            it.write("# Session start (Unix): $startTimeStr\n".toByteArray())
            it.write("unix_timestamp\n".toByteArray())
        }

        // Raw BLE packet log — untouched bytes from the E4
        rawPacketStream = createFile("raw_packets.log")?.also {
            it.write("# E4 Raw BLE Packet Log\n".toByteArray())
            it.write("# Device: $deviceSerialNumber\n".toByteArray())
            it.write("# Session start (Unix): $startTimeStr\n".toByteArray())
            it.write("# Format: unix_timestamp,characteristic,hex_data\n".toByteArray())
            it.write("# Data is UNMODIFIED — exact bytes from BLE notifications\n".toByteArray())
        }

        isLogging = true

        writerJob = scope.launch {
            try {
                for (entry in logChannel) {
                    if (!isLogging) break
                    writeEntry(entry)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Writer error: ${e.message}")
            }
        }

        Log.d(TAG, "Session started: $ROOT_FOLDER/$deviceFolder/$sessionFolder")
    }

    private fun writeEntry(entry: LogEntry) {
        try {
            when (entry) {
                is LogEntry.Tag -> {
                    // Save the full Unix timestamp
                    tagStream?.write("%.6f\n".format(Locale.US, entry.unixTimestamp).toByteArray())
                    tagCount++
                }
                is LogEntry.RawPacket -> {
                    val hex = entry.data.joinToString("") { "%02x".format(it) }
                    rawPacketStream?.write("%.6f,%s,%s\n".format(
                        Locale.US, entry.timestamp, entry.characteristic, hex
                    ).toByteArray())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Write error: ${e.message}")
        }
    }

    /**
     * Log a tag event with the full Unix timestamp.
     */
    fun logTag(unixTimestamp: Double) {
        if (isLogging) logChannel.trySend(LogEntry.Tag(unixTimestamp))
    }

    fun logRawPacket(timestamp: Double, characteristic: String, data: ByteArray) {
        if (isLogging) logChannel.trySend(LogEntry.RawPacket(timestamp, characteristic, data))
    }

    fun getTagCount(): Int = tagCount

    fun stopSession() {
        if (!isLogging) return
        isLogging = false
        writerJob?.cancel()

        listOf(tagStream, rawPacketStream).forEach {
            try { it?.flush(); it?.close() } catch (_: Exception) { }
        }
        tagStream = null
        rawPacketStream = null

        Log.d(TAG, "Session stopped: $ROOT_FOLDER/$deviceFolder/$sessionFolder (tags: $tagCount)")
    }

    // In SessionLogger.createFile(), change the MIME type based on file extension:
    private fun createFile(fileName: String): OutputStream? {
        return try {
            val resolver = context.contentResolver

            // Use appropriate MIME type to avoid unwanted extension changes
            val mimeType = when {
                fileName.endsWith(".csv") -> "text/csv"
                fileName.endsWith(".log") -> "text/plain"
                else -> "application/octet-stream"
            }

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOCUMENTS}/${getRelativePath()}"
                    )
                }
            }

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val subDir = File(dir, getRelativePath())
                if (!subDir.exists()) subDir.mkdirs()
                Uri.fromFile(File(subDir, fileName))
            }

            uri?.let { resolver.openOutputStream(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create file: $fileName")
            null
        }
    }

    fun cleanup() {
        stopSession()
        scope.cancel()
    }
}