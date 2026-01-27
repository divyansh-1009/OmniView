package com.omniview.app.intelligence

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf

object OcrWorkScheduler {

    private const val TAG = "OmniView:OcrScheduler"
    private const val WORK_NAME = "omniview_ocr_processing"

    /**
     * Called after every screenshot capture.
     *
     * Runs the worker immediately with no OS-level constraints — the worker
     * itself checks charging + battery > 80% and exits cleanly if conditions
     * are not met. This makes the check eager and emulator-friendly rather
     * than depending on WorkManager's passive constraint tracker.
     */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)

        val existing = wm.getWorkInfosForUniqueWork(WORK_NAME).get()
        if (existing.any { it.state == WorkInfo.State.RUNNING }) {
            Log.d(TAG, "OCR already running — skipping re-enqueue")
            return
        }

        val request = OneTimeWorkRequestBuilder<OcrWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        wm.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        Log.d(TAG, "OCR work enqueued — worker will check conditions")
    }

    /**
     * Force-runs OCR bypassing all condition checks — dev / testing only.
     */
    fun scheduleNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<OcrWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(OcrWorker.KEY_FORCE_RUN to true))
            .build()

        WorkManager.getInstance(context).enqueue(request)
        Log.d(TAG, "OCR work force-scheduled (dev mode)")
    }
}
