package com.example.omniview.ocr

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.omniview.db.ContextDatabase
import com.example.omniview.db.ContextRepository
import com.example.omniview.db.toEntity

class OcrWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "OmniView:OcrWorker"
        private const val BATTERY_THRESHOLD = 80

        /** Set to true via inputData to bypass battery/charging checks (dev only). */
        const val KEY_FORCE_RUN = "force_run"
    }

    override suspend fun doWork(): Result {
        val forceRun = inputData.getBoolean(KEY_FORCE_RUN, false)

        if (!forceRun) {
            val charging = isCharging()
            val battery = batteryLevel()
            Log.d(TAG, "Condition check — charging=$charging, battery=$battery%")
            if (!charging || battery < BATTERY_THRESHOLD) {
                Log.i(TAG, "Conditions not met (charging=$charging, battery=$battery%) — skipping, queue preserved")
                return Result.success()
            }
            Log.i(TAG, "Conditions met — charging=$charging, battery=$battery%")
        } else {
            Log.i(TAG, "Force-run mode — skipping condition checks")
        }

        val items = OcrQueue.drainAll(applicationContext)
        if (items.isEmpty()) {
            Log.i(TAG, "OCR queue is empty — nothing to process")
            return Result.success()
        }

        Log.i(TAG, "Processing ${items.size} queued screenshots")

        val repository = ContextRepository(
            ContextDatabase.getInstance(applicationContext).contextDao()
        )

        val results = mutableListOf<com.example.omniview.model.ExtractedContext>()

        for ((index, item) in items.withIndex()) {
            if (!forceRun && (!isCharging() || batteryLevel() < BATTERY_THRESHOLD)) {
                val remaining = items.drop(index)
                remaining.forEach { OcrQueue.enqueue(applicationContext, it) }
                Log.i(TAG, "Conditions changed mid-run — re-queued ${remaining.size} items")
                break
            }

            val result = OcrProcessor.process(applicationContext, item)
            if (result != null) {
                Log.d(TAG, "[${result.app}] ${result.text.take(200)}")
                results.add(result)
            }
        }

        repository.insertAll(results.map { it.toEntity() })
        Log.i(TAG, "OCR complete — stored ${results.size} / ${items.size} results")

        return Result.success()
    }

    private fun isCharging(): Boolean {
        // BatteryManager.isCharging is unreliable on emulators; the sticky
        // ACTION_BATTERY_CHANGED broadcast is updated by Extended Controls.
        val intent = applicationContext.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return plugged != 0 &&
                (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                 status == BatteryManager.BATTERY_STATUS_FULL)
    }

    private fun batteryLevel(): Int {
        val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
}
