package com.shilapi.xcertplay

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/** Saves an app-owned report without depending on an OEM's document-picker activity. */
internal object DiagnosticExportStore {
    data class SavedReport(
        val uri: Uri,
        val savedInApp: Boolean = false,
        val savedPath: String? = null,
    )

    /** Android 9 and OEMs without working Downloads storage can still export privately. */
    fun saveWithoutPicker(context: Context, fileName: String, report: String): SavedReport {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return SavedReport(saveToDownloads(context.contentResolver, fileName, report))
            } catch (_: Exception) {
                // Preserve the report even when the OEM's public storage provider is absent.
            }
        }
        val saved = try {
            // Use Android's package-specific directory, including debug application IDs.
            // No storage permission or document-picker activity is needed.
            val externalFiles = context.getExternalFilesDir(null)
            if (externalFiles != null) {
                saveInDirectory(context, File(externalFiles, "diagnostic-reports"), fileName, report)
            } else {
                saveInDirectory(context, File(context.filesDir, "diagnostic-reports"), fileName, report, savedInApp = true)
            }
        } catch (_: Exception) {
            // A missing, read-only or full external volume must not prevent export.
            saveInDirectory(context, File(context.filesDir, "diagnostic-reports"), fileName, report, savedInApp = true)
        }
        exportCopiesToAccessibleStorage(context, fileName, report)
        return saved
    }

    /** Best-effort copies so a driver with no picker can pull the report off a stick or card. */
    private fun exportCopiesToAccessibleStorage(context: Context, fileName: String, report: String) {
        runCatching {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            writeReportTo(File(downloads, "DiPlay"), fileName, report)
        }
        runCatching {
            writeReportTo(File(Environment.getExternalStorageDirectory(), "DiPlay"), fileName, report)
        }
        runCatching {
            val volumes = context.getExternalFilesDirs(null) ?: return@runCatching
            for (index in 1 until volumes.size) {
                val volume = volumes[index] ?: continue
                writeReportTo(File(volume, "diagnostic-reports"), fileName, report)
            }
        }
        runCatching {
            usbVolumeDirectories().forEach { writeReportTo(File(it, "DiPlay"), fileName, report) }
        }
    }

    // A stick is mounted as a child of these roots, not at the root itself: E01 puts it at
    // /storage/usbotg/usbotg-sda1, with the raw twin under /mnt/media_rw.
    private val usbMountRoots = listOf(
        "/storage/usbotg",
        "/storage/udisk",
        "/mnt/usb_storage",
        "/mnt/udisk",
        "/mnt/usb",
        "/mnt/media_rw",
    )

    private fun usbVolumeDirectories(): List<File> = usbMountRoots.flatMap { path ->
        val root = File(path)
        if (!root.isDirectory) emptyList()
        else listOf(root) + root.listFiles().orEmpty().filter { it.isDirectory }
    }

    private fun writeReportTo(directory: File, fileName: String, report: String) {
        if (!directory.isDirectory && !directory.mkdirs()) return
        if (!directory.canWrite()) return
        File(directory, fileName).writeText(report, Charsets.UTF_8)
    }

    private fun saveInDirectory(
        context: Context,
        directory: File,
        fileName: String,
        report: String,
        savedInApp: Boolean = false,
    ): SavedReport {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Report storage is unavailable")
        // Each export has a new URI: an earlier share grant cannot read a later report.
        val file = File.createTempFile(fileName.removeSuffix(".txt") + "-", ".txt", directory)
        try {
            file.writeText(report, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file)
            // Retain only the newest eight reports; never prune the export being returned.
            directory.listFiles()?.filter { it != file && it.isFile }
                ?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.delete() }
            return SavedReport(uri, savedInApp = savedInApp, savedPath = if (savedInApp) null else file.absolutePath)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToDownloads(resolver: ContentResolver, fileName: String, report: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DiPlay")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads could not create the report")
        try {
            write(resolver, uri, report)
            val published = resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
            if (published != 1) throw IOException("Downloads could not publish the report")
            return uri
        } catch (error: Exception) {
            // Only remove the entry created by this call; never leave a partial report behind.
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    fun write(resolver: ContentResolver, uri: Uri, report: String) {
        val stream = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("Report destination is unavailable")
        stream.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
    }
}
