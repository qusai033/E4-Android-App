package com.empatica.e4companion.decoder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SessionExporter(private val context: Context) {

    companion object {
        private const val TAG = "SessionExporter"
        private const val ROOT_FOLDER = "E4Companion"
    }

    data class ExportResult(
        val zipUri: Uri?,
        val decoded: DecodedSession,
        val error: String? = null
    )

    /**
     * Decode a session's raw packet file and create a shareable ZIP.
     */
    suspend fun exportSession(
        deviceFolder: String,
        sessionFolder: String
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            val basePath = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "$ROOT_FOLDER/$deviceFolder/$sessionFolder"
            )

            if (!basePath.exists() || !basePath.isDirectory) {
                return@withContext ExportResult(
                    null, DecodedSession(),
                    "Session folder not found: $sessionFolder"
                )
            }

            // Find the raw packet file (may be .log, .log.csv, or .csv)
            val rawFile = basePath.listFiles()?.find { file ->
                val name = file.name.lowercase()
                name.startsWith("raw_packets") && file.length() > 100
            }

            if (rawFile == null) {
                // List what files ARE in the directory for debugging
                val files = basePath.listFiles()?.map { it.name } ?: emptyList()
                return@withContext ExportResult(
                    null, DecodedSession(),
                    "Raw packet file not found.\nFiles in session: ${files.joinToString(", ")}"
                )
            }

            Log.d(TAG, "Found raw packet file: ${rawFile.name} (${rawFile.length()} bytes)")

            // Read all lines
            val lines = rawFile.readLines()
            if (lines.size < 5) {
                return@withContext ExportResult(
                    null, DecodedSession(),
                    "Raw packet file is too small (${lines.size} lines)"
                )
            }

            // Create temp directory for decoded files
            val tempDir = File(context.cacheDir, "export_${sessionFolder}")
            if (tempDir.exists()) tempDir.deleteRecursively()
            tempDir.mkdirs()

            // Create output files
            val accFile = File(tempDir, "ACC.csv")
            val bvpFile = File(tempDir, "BVP.csv")
            val edaFile = File(tempDir, "EDA.csv")
            val tempFile = File(tempDir, "TEMP.csv")
            val ibiFile = File(tempDir, "IBI.csv")
            val hrFile = File(tempDir, "HR.csv")


            val accStream = FileOutputStream(accFile)
            val bvpStream = FileOutputStream(bvpFile)
            val edaStream = FileOutputStream(edaFile)
            val tempStream = FileOutputStream(tempFile)
            val ibiStream = FileOutputStream(ibiFile)
            val hrStream = FileOutputStream(hrFile)



            // Decode
            val decoder = RawPacketDecoder()
            decoder.setDeviceHardwareId(deviceFolder)  // ← ADD THIS LINE
            val result = decoder.decodeSinglePass(lines, accStream, bvpStream,
                edaStream, tempStream, ibiStream, hrStream)

            accStream.close()
            bvpStream.close()
            edaStream.close()
            tempStream.close()
            ibiStream.close()
            hrStream.close()


            // Copy TAG file if it exists
            val tagSource = basePath.listFiles()?.find { file ->
                file.name.lowercase().startsWith("tag") && file.length() > 0
            }
            val tagDest = File(tempDir, "TAG.csv")
            if (tagSource != null) {
                tagSource.copyTo(tagDest, overwrite = true)
            }

            // Create info.txt
            // In SessionExporter.kt, update the info.txt creation:

            // Replace the info.txt creation in SessionExporter.exportSession():

            val infoFile = File(tempDir, "info.txt")
            infoFile.writeText(buildString {
                appendLine("E4 Session Export")
                appendLine("Device: $deviceFolder")
                appendLine("Session: $sessionFolder")
                appendLine("Source: ${rawFile.name}")
                appendLine("Decoded from raw BLE packets with signal filtering applied")
                appendLine()
                appendLine("Files (each contains raw and filtered columns):")
                appendLine("  ACC.csv  — timestamp, x, y, z, x_filtered, y_filtered, z_filtered")
                appendLine("  BVP.csv  — timestamp, bvp, bvp_filtered")
                appendLine("  EDA.csv  — timestamp, eda, eda_filtered, on_wrist")
                appendLine("  TEMP.csv — timestamp, temp, temp_filtered")
                appendLine("  HR.csv   — timestamp, hr")
                appendLine("  IBI.csv  — timestamp, ibi (inter-beat interval in seconds)")
                if (tagSource != null) appendLine("  TAG.csv  — Event markers (Unix timestamps)")
                appendLine()
                appendLine("Sample rates:")
                appendLine("  ACC:  32 Hz (6 samples per BLE packet)")
                appendLine("  BVP:  64 Hz (9 samples per BLE packet)")
                appendLine("  EDA:   4 Hz (4 float samples per BLE packet)")
                appendLine("  TEMP:  4 Hz (8 samples per BLE packet)")
                appendLine("  HR:    1 Hz (1 sample per BLE packet)")
                appendLine("  IBI:   1 Hz (1 sample per BLE packet)")
                appendLine()
                appendLine("Filters applied:")
                appendLine("  BVP:  2nd-order Butterworth bandpass 0.7–3.5 Hz")
                appendLine("        Removes baseline drift and HF noise, preserves cardiac signal")
                appendLine("        Reference: Langevin et al. (2021)")
                appendLine("  EDA:  Median filter (window=5) + 4th-order Butterworth LPF 1.0 Hz")
                appendLine("        Median removes motion spikes, LPF removes HF noise")
                appendLine("        Preserves tonic (SCL) and phasic (SCR) components")
                appendLine("  ACC:  4th-order Butterworth low-pass 5.0 Hz per axis")
                appendLine("        Retains human movement (walk 0.5-3 Hz, stairs ~4 Hz)")
                appendLine("        Removes vibration and high-frequency noise")
                appendLine("  TEMP: 2nd-order Butterworth low-pass 0.1 Hz")
                appendLine("        Smooths sensor noise, preserves slow thermal trends")
                appendLine()
                appendLine("Conversion formulas:")
                appendLine("  ACC: x_g = raw_int8 * 2 / 128  (raw value 64 = 1g)")
                appendLine("  TEMP: temp_C = raw_uint16 / 500.0")
                appendLine("  EDA: IEEE 754 float32 LE → direct μS value")
                appendLine("  BVP: signed int16 LE → raw PPG signal")
                appendLine()
                appendLine("Samples decoded:")
                appendLine("  ACC:  ${result.accSamples}")
                appendLine("  BVP:  ${result.bvpSamples}")
                appendLine("  EDA:  ${result.edaSamples}")
                appendLine("  TEMP: ${result.tempSamples}")
                appendLine("  IBI:  ${result.ibiSamples}")
                appendLine("  HR:   ${result.hrSamples}")

                appendLine()
                appendLine("Packet drop summary:")
                appendLine("  Total dropped BLE packets: ${result.droppedPackets}")
                appendLine("  Large gaps (>5 seconds):   ${result.largeGaps}")
                appendLine("  Small gaps (≤5s) skipped silently, stream continues")
                appendLine("  Large gaps marked with NaN rows for timestamp alignment")
            })

            // Build list of files to zip (only include non-empty files)
            val filesToZip = mutableListOf(infoFile)
            if (accFile.length() > 50) filesToZip.add(accFile)
            if (bvpFile.length() > 50) filesToZip.add(bvpFile)
            if (edaFile.length() > 50) filesToZip.add(edaFile)
            if (tempFile.length() > 50) filesToZip.add(tempFile)
            if (ibiFile.length() > 15) filesToZip.add(ibiFile)
            if (hrFile.length() > 15) filesToZip.add(hrFile)

            if (tagDest.exists() && tagDest.length() > 0) filesToZip.add(tagDest)

            // Create ZIP
            val zipFileName = "${deviceFolder}_${sessionFolder}.zip"
            val zipFile = File(tempDir, zipFileName)
            createZip(zipFile, filesToZip)

            // Get shareable URI via FileProvider [3]
            val zipUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                zipFile
            )

            Log.d(TAG, "Export complete: $zipFileName " +
                    "(ACC=${result.accSamples}, BVP=${result.bvpSamples}, " +
                    "EDA=${result.edaSamples}, TEMP=${result.tempSamples}), " +
                    "IBI=${result.ibiSamples}, HR=${result.hrSamples})")

            ExportResult(zipUri, result)

        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}", e)
            ExportResult(null, DecodedSession(), "Export failed: ${e.message}")
        }
    }

    private fun createZip(zipFile: File, files: List<File>) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            for (file in files) {
                if (!file.exists()) continue
                zos.putNextEntry(ZipEntry(file.name))
                BufferedInputStream(FileInputStream(file)).use { input ->
                    val buffer = ByteArray(4096)
                    var count: Int
                    while (input.read(buffer).also { count = it } != -1) {
                        zos.write(buffer, 0, count)
                    }
                }
                zos.closeEntry()
            }
        }
    }

    fun getShareIntent(zipUri: Uri, sessionName: String): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, zipUri)
            putExtra(Intent.EXTRA_SUBJECT, "E4 Session: $sessionName")
            putExtra(Intent.EXTRA_TEXT, "Decoded E4 sensor data from session $sessionName")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}