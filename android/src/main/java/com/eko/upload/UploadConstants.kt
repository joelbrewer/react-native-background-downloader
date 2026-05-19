package com.eko.upload

object UploadConstants {
    const val UNIQUE_WORK_NAME = "rnbgd-upload-queue"
    const val TAG_PREFIX = "upload-"
    const val KEY_CONFIG_ID = "configId"

    const val NOTIFICATION_CHANNEL_VISIBLE = "rnbgd_uploads_visible"
    const val NOTIFICATION_CHANNEL_SILENT = "rnbgd_uploads_silent"
    const val NOTIFICATION_ID = 0x52424755 // "RBGU"

    const val BUFFER_SIZE = 8192
    const val CONNECT_TIMEOUT_MS = 60000
    const val READ_TIMEOUT_MS = 60000

    fun tagFor(configId: String): String = "$TAG_PREFIX$configId"
}
