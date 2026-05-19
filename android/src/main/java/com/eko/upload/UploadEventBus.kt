package com.eko.upload

/**
 * Process-local pub/sub between [UploadWorker] (publisher) and
 * [com.eko.RNBackgroundDownloaderModuleImpl] (subscriber).
 *
 * The worker runs in the same process as the RN module, so we can deliver
 * events via a direct reference without going through LocalBroadcastManager.
 *
 * If no listener is attached (cold start, JS not loaded yet), events drop
 * silently. WorkManager's own state and persisted MMKV state are sufficient
 * to reconcile on the next module init.
 */
object UploadEventBus {

    interface Listener {
        fun onBegin(id: String, expectedBytes: Long)
        fun onProgress(id: String, bytesUploaded: Long, bytesTotal: Long)
        fun onComplete(id: String, responseCode: Int, responseBody: String, bytesUploaded: Long, bytesTotal: Long)
        fun onError(id: String, error: String, errorCode: Int)
    }

    @Volatile
    private var listener: Listener? = null

    fun setListener(l: Listener?) {
        listener = l
    }

    fun emitBegin(id: String, expectedBytes: Long) {
        listener?.onBegin(id, expectedBytes)
    }

    fun emitProgress(id: String, bytesUploaded: Long, bytesTotal: Long) {
        listener?.onProgress(id, bytesUploaded, bytesTotal)
    }

    fun emitComplete(id: String, responseCode: Int, responseBody: String, bytesUploaded: Long, bytesTotal: Long) {
        listener?.onComplete(id, responseCode, responseBody, bytesUploaded, bytesTotal)
    }

    fun emitError(id: String, error: String, errorCode: Int) {
        listener?.onError(id, error, errorCode)
    }
}
