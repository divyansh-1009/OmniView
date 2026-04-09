package com.example.omniview.embedding

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf

object EmbeddingWorkScheduler {

    private const val TAG = "OmniView:EmbedScheduler"
    private const val WORK_NAME = "omniview_embedding_batch"

    /**
     * Schedules an [EmbeddingWorker] immediately via WorkManager.
     * The worker checks charging inside [EmbeddingWorker.doWork] and exits
     * cleanly if conditions are not met — queue is preserved for next attempt.
     *
     * Skips scheduling if a run is already in progress.
     */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)

        val existing = wm.getWorkInfosForUniqueWork(WORK_NAME).get()
        if (existing.any { it.state == WorkInfo.State.RUNNING }) {
            Log.d(TAG, "Embedding batch already running — skipping re-enqueue")
            return
        }

        val request = OneTimeWorkRequestBuilder<EmbeddingWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        wm.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        Log.d(TAG, "Embedding work enqueued — worker will check charging state")
    }

    /** Bypasses the charging check — for dev/testing only. */
    fun scheduleNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<EmbeddingWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(EmbeddingWorker.KEY_FORCE_RUN to true))
            .build()

        WorkManager.getInstance(context).enqueue(request)
        Log.d(TAG, "Embedding work force-scheduled (dev mode)")
    }
}
