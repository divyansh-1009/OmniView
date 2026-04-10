package com.omniview.app.intelligence

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf

/**
 * Schedules the EmbeddingWorker to run immediately (one-shot, no constraints).
 *
 * This is the companion to [EmbeddingWorker.schedulePeriodic] which only runs
 * when charging. Use [scheduleNow] to trigger embedding right after OCR completes
 * or via a manual "Run Embeddings Now" button in the UI.
 */
object EmbeddingWorkScheduler {

    private const val TAG = "OmniView:EmbeddingScheduler"
    private const val WORK_NAME = "omniview_embedding_now"

    /**
     * Enqueues a one-shot EmbeddingWorker bypassing all constraints.
     * Safe to call multiple times — uses KEEP policy so a running job
     * won't be interrupted by a re-enqueue.
     */
    fun scheduleNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<EmbeddingWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(EmbeddingWorker.KEY_FORCE_RUN to true))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP, // don't interrupt an already-running job
            request
        )
        Log.d(TAG, "Embedding work enqueued (force-run)")
    }
}
