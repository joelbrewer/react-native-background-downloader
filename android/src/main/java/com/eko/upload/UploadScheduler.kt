package com.eko.upload

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.eko.DownloadConstants
import com.eko.RNBGDUploadTaskConfig
import com.eko.RNBackgroundDownloaderModuleImpl

/**
 * Thin facade over [WorkManager] for upload tasks.
 *
 * Replaces the public surface of the legacy `Uploader` so
 * `RNBackgroundDownloaderModuleImpl` keeps its existing call sites:
 * enqueue, pause, resume, cancel, isActive, getStateConstant.
 *
 * All work is funnelled into one unique chain so uploads run serially —
 * matches the legacy single-thread executor and keeps the foreground
 * notification down to one at a time.
 */
class UploadScheduler(private val context: Context) {

    companion object {
        private const val TAG = "UploadScheduler"
    }

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /**
     * Enqueue an upload. If one with the same configId is already in the
     * chain (queued or running) it is replaced. Distinct uploads append
     * and run serially.
     */
    fun enqueue(config: RNBGDUploadTaskConfig) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (config.isAllowedOverMetered) NetworkType.CONNECTED
                else NetworkType.UNMETERED
            )
            .build()

        // Per-config replacement: cancel any existing work for this configId so
        // a fresh enqueue (e.g. resume) doesn't sit behind a stale entry.
        workManager.cancelAllWorkByTag(UploadConstants.tagFor(config.id))

        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(Data.Builder().putString(UploadConstants.KEY_CONFIG_ID, config.id).build())
            .addTag(UploadConstants.tagFor(config.id))
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniqueWork(
            UploadConstants.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Enqueued upload ${config.id}")
    }

    /**
     * Pause = cancel. Re-enqueue (resume) restarts from byte 0 since HTTP
     * doesn't support upload resume without server cooperation.
     */
    fun pause(configId: String): Boolean {
        workManager.cancelAllWorkByTag(UploadConstants.tagFor(configId))
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Paused upload $configId")
        return true
    }

    fun resume(config: RNBGDUploadTaskConfig) {
        enqueue(config)
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Resumed upload ${config.id}")
    }

    fun cancel(configId: String): Boolean {
        workManager.cancelAllWorkByTag(UploadConstants.tagFor(configId))
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Cancelled upload $configId")
        return true
    }

    /** True if WorkManager has a non-terminal entry for this configId. */
    fun isActive(configId: String): Boolean =
        workManager.getWorkInfosByTag(UploadConstants.tagFor(configId))
            .get()
            .any { !it.state.isFinished }

    /**
     * Map WorkManager's state to one of our DownloadConstants.
     *
     * WorkManager keeps historical WorkInfos around (a paused upload leaves a
     * CANCELLED entry behind even after resume re-enqueues), so we can't
     * pick "the latest" by enum ordinal — we have to look for active work
     * first, then completion, and fall back to the persisted state.
     */
    fun getStateConstant(configId: String, persistedState: Int): Int {
        val infos = workManager.getWorkInfosByTag(UploadConstants.tagFor(configId)).get()
        return when {
            infos.any {
                it.state == WorkInfo.State.RUNNING ||
                    it.state == WorkInfo.State.ENQUEUED ||
                    it.state == WorkInfo.State.BLOCKED
            } -> DownloadConstants.TASK_RUNNING
            infos.any { it.state == WorkInfo.State.SUCCEEDED } -> DownloadConstants.TASK_COMPLETED
            else -> persistedState // FAILED, CANCELLED, or no record — trust persisted
        }
    }
}
