package com.example.omniview.embedding

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * WorkManager worker that runs the full embedding pipeline batch.
 *
 * Condition check is performed inside [doWork] (not via WorkManager OS constraints)
 * so the worker is scheduled immediately after every enqueue and exits cleanly
 * when the device is not charging — matching the same pattern as OcrWorker.
 *
 * Set input key [KEY_FORCE_RUN] = true to bypass the charging check (dev only).
 */
class EmbeddingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "OmniView:EmbedWorker"
        const val KEY_FORCE_RUN = "force_run"
    }

    override suspend fun doWork(): Result {
        val forceRun = inputData.getBoolean(KEY_FORCE_RUN, false)

        if (!forceRun) {
            if (!isCharging()) {
                Log.i(TAG, "Not charging — skipping batch, queue preserved")
                return Result.success()
            }
            Log.i(TAG, "Device is charging — starting embedding batch")
        } else {
            Log.i(TAG, "Force-run mode — skipping charging check")
        }

        PipelineManager.processAll()
        return Result.success()
    }

    // Reads the sticky ACTION_BATTERY_CHANGED broadcast — reliable on emulators.
    private fun isCharging(): Boolean {
        val intent = applicationContext.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return false
        val status  = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return plugged != 0 &&
                (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                 status == BatteryManager.BATTERY_STATUS_FULL)
    }
}
