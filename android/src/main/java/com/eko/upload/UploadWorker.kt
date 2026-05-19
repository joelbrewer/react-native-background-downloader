package com.eko.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eko.RNBGDUploadTaskConfig
import com.eko.RNBackgroundDownloaderModuleImpl
import com.eko.utils.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Performs a single file upload as a WorkManager [CoroutineWorker].
 *
 * HTTP behaviour matches the legacy `Uploader` (multipart vs raw body,
 * headers, response handling). The worker lifecycle is owned by WorkManager,
 * so uploads survive process death and OS scheduling decisions.
 *
 * Cancellation: [isStopped] is polled inside the write loop so a cancelled
 * or paused upload terminates the connection promptly.
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "UploadWorker"
        private const val FOREGROUND_UPDATE_INTERVAL_MS = 500L
    }

    private val storage by lazy {
        StorageManager(applicationContext, RNBackgroundDownloaderModuleImpl.NAME)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val configId = inputData.getString(UploadConstants.KEY_CONFIG_ID)
            ?: return@withContext Result.failure()

        val config = storage.loadUploadConfigs()[configId] ?: run {
            RNBackgroundDownloaderModuleImpl.logW(TAG, "No persisted config for $configId")
            return@withContext Result.failure()
        }

        val file = File(config.source)
        if (!file.exists()) {
            UploadEventBus.emitError(configId, "Source file not found: ${config.source}", -1)
            return@withContext Result.failure()
        }

        updateNotification(percent = 0, indeterminate = true)
        try {
            executeUpload(config, file)
            Result.success()
        } catch (e: Exception) {
            if (!isStopped) {
                RNBackgroundDownloaderModuleImpl.logE(TAG, "Upload $configId failed: ${e.message}")
                UploadEventBus.emitError(configId, e.message ?: "Upload failed", -1)
            }
            Result.failure()
        }
    }

    private suspend fun executeUpload(config: RNBGDUploadTaskConfig, file: File) {
        val useMultipart = !config.parameters.isNullOrEmpty() || config.fieldName != null
        val boundary = "----UploadBoundary${System.currentTimeMillis()}"
        val totalBytes = if (useMultipart) multipartSize(config, file, boundary) else file.length()

        val connection = (URL(config.url).openConnection() as HttpURLConnection).apply {
            requestMethod = config.method
            doOutput = true
            useCaches = false
            connectTimeout = UploadConstants.CONNECT_TIMEOUT_MS
            readTimeout = UploadConstants.READ_TIMEOUT_MS
            config.headers?.forEach { (k, v) -> setRequestProperty(k, v) }
            setRequestProperty(
                "Content-Type",
                if (useMultipart) "multipart/form-data; boundary=$boundary"
                else config.mimeType ?: "application/octet-stream"
            )
            setFixedLengthStreamingMode(totalBytes)
        }

        try {
            UploadEventBus.emitBegin(config.id, totalBytes)
            if (isStopped) return

            connection.connect()
            var uploaded = 0L
            var lastNotifMs = 0L
            val onBytes: suspend (Long) -> Unit = { delta ->
                uploaded += delta
                UploadEventBus.emitProgress(config.id, uploaded, totalBytes)
                val now = System.currentTimeMillis()
                if (now - lastNotifMs >= FOREGROUND_UPDATE_INTERVAL_MS) {
                    lastNotifMs = now
                    val pct = if (totalBytes > 0) ((uploaded * 100) / totalBytes).toInt() else 0
                    updateNotification(percent = pct, indeterminate = false)
                }
            }
            DataOutputStream(connection.outputStream).use { out ->
                if (useMultipart) writeMultipart(out, config, file, boundary, onBytes)
                else writeRaw(out, file, onBytes)
            }
            if (isStopped) return

            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""

            if (code in 200..299)
                UploadEventBus.emitComplete(config.id, code, body, uploaded, totalBytes)
            else
                UploadEventBus.emitError(config.id, "HTTP $code: $body", code)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun updateNotification(percent: Int, indeterminate: Boolean) {
        setForeground(
            UploadNotificationManager.buildForegroundInfo(
                applicationContext,
                title = "Uploading",
                contentText = if (indeterminate) "Starting upload…" else "Uploading… $percent%",
                progress = percent,
                indeterminate = indeterminate
            )
        )
    }

    private fun multipartSize(config: RNBGDUploadTaskConfig, file: File, boundary: String): Long {
        val crlf = "\r\n"
        val fieldName = config.fieldName ?: "file"
        val mimeType = config.mimeType ?: "application/octet-stream"
        var size = 0L

        config.parameters?.forEach { (k, v) ->
            size += "--$boundary$crlf".length
            size += "Content-Disposition: form-data; name=\"$k\"$crlf$crlf".length
            size += "$v$crlf".length
        }

        size += "--$boundary$crlf".length
        size += "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"${file.name}\"$crlf".length
        size += "Content-Type: $mimeType$crlf$crlf".length
        size += file.length()
        size += crlf.length
        size += "--$boundary--$crlf".length

        return size
    }

    private suspend fun writeMultipart(
        out: DataOutputStream,
        config: RNBGDUploadTaskConfig,
        file: File,
        boundary: String,
        onBytes: suspend (Long) -> Unit
    ) {
        val crlf = "\r\n"
        val fieldName = config.fieldName ?: "file"
        val mimeType = config.mimeType ?: "application/octet-stream"

        config.parameters?.forEach { (k, v) ->
            if (isStopped) return
            val part = "--$boundary$crlf" +
                "Content-Disposition: form-data; name=\"$k\"$crlf$crlf" +
                "$v$crlf"
            out.writeBytes(part)
            onBytes(part.length.toLong())
        }

        val fileHeader = "--$boundary$crlf" +
            "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"${file.name}\"$crlf" +
            "Content-Type: $mimeType$crlf$crlf"
        out.writeBytes(fileHeader)
        onBytes(fileHeader.length.toLong())

        FileInputStream(file).use { input ->
            val buffer = ByteArray(UploadConstants.BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                if (isStopped) return
                out.write(buffer, 0, read)
                onBytes(read.toLong())
            }
        }

        out.writeBytes(crlf)
        onBytes(crlf.length.toLong())

        val end = "--$boundary--$crlf"
        out.writeBytes(end)
        onBytes(end.length.toLong())
    }

    private suspend fun writeRaw(
        out: DataOutputStream,
        file: File,
        onBytes: suspend (Long) -> Unit
    ) {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(UploadConstants.BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                if (isStopped) return
                out.write(buffer, 0, read)
                onBytes(read.toLong())
            }
        }
    }
}
