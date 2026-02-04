package com.omniview.app.intelligence

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf

/**
 * Schedules [EmbeddingWorker] runs via WorkManager.
 *
 * Mirrors [OcrWorkScheduler] exactly:
 *   - [schedule] enqueues with REPLACE policy (de-duped by unique work name)
 *   - [scheduleNow] force-runs skipping battery checks (dev only)
 */
object EmbeddingWorkScheduler {

    private const val TAG = "OmniView:EmbedScheduler"
    private const val WORK_NAME = "omniview_embedding_processing"

    /**
     * Called after new context entries are created (by OcrWorker or ScreenAccessibilityService).
     *
     * Runs the worker immediately with no OS-level constraints — the worker
     * itself checks charging + battery > 80% and exits cleanly if conditions
     * are not met.
     */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)

        val existing = wm.getWorkInfosForUniqueWork(WORK_NAME).get()
        if (existing.any { it.state == WorkInfo.State.RUNNING }) {
            Log.d(TAG, "Embedding already running — skipping re-enqueue")
            return
        }

        val request = OneTimeWorkRequestBuilder<EmbeddingWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        wm.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        Log.d(TAG, "Embedding work enqueued — worker will check conditions")
    }

    /**
     * Force-runs embedding bypassing all condition checks — dev / testing only.
     */
    fun scheduleNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<EmbeddingWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(EmbeddingWorker.KEY_FORCE_RUN to true))
            .build()

        WorkManager.getInstance(context).enqueue(request)
        Log.d(TAG, "Embedding work force-scheduled (dev mode)")
    }
}
