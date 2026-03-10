package com.example.omniview.ocr

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object OcrWorkScheduler {

    private const val TAG = "OmniView:OcrScheduler"
    private const val WORK_NAME = "omniview_ocr_processing"

    /**
     * Enqueues OCR processing to run once the device is charging AND battery > 80%.
     *
     * Uses KEEP policy — if work is already queued or running, this call is a no-op,
     * so it's safe to call on every screenshot capture.
     */
    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .build()

        val request = OneTimeWorkRequestBuilder<OcrWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )

        Log.d(TAG, "OCR work scheduled (will run when charging + battery > 80%)")
    }

    /**
     * Runs OCR immediately with no constraints — for development/testing only.
     * Bypasses charging and battery checks inside the worker.
     */
    fun scheduleNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<OcrWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(OcrWorker.KEY_FORCE_RUN to true))
            .build()

        WorkManager.getInstance(context).enqueue(request)
        Log.d(TAG, "OCR work scheduled immediately (dev mode)")
    }
}
