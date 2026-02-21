package com.omniview.app.intelligence
import com.omniview.app.storage.ExtractedContext

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.omniview.app.storage.ContextDatabase
import com.omniview.app.storage.ContextRepository
import com.omniview.app.storage.toEntity

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
            Log.d(TAG, "Condition check: charging=$charging, battery=$battery% (Required: charging && >$BATTERY_THRESHOLD%)")
            
            if (!charging || battery < BATTERY_THRESHOLD) {
                Log.i(TAG, "Battery conditions not met. Skipping OCR run. Queue preserved.")
                return Result.success()
            }
            Log.i(TAG, "Conditions met. Starting OCR batch processing.")
        } else {
            Log.i(TAG, "Force-run mode enabled via UI trigger. Skipping condition checks.")
        }

        val items = OcrQueue.drainAll(applicationContext)
        if (items.isEmpty()) {
            Log.i(TAG, "OCR queue is empty. Nothing to process.")
            return Result.success()
        }

        Log.i(TAG, "Processing batch of ${items.size} queued screenshots...")

        val repository = ContextRepository(
            ContextDatabase.getInstance(applicationContext).contextDao()
        )

        val results = mutableListOf<com.omniview.app.storage.ExtractedContext>()

        for ((index, item) in items.withIndex()) {
            // Re-check conditions mid-batch for battery health
            if (!forceRun && (!isCharging() || batteryLevel() < BATTERY_THRESHOLD)) {
                val remaining = items.drop(index)
                remaining.forEach { OcrQueue.enqueue(applicationContext, it) }
                Log.w(TAG, "Conditions changed mid-run. Re-queued ${remaining.size} items for later.")
                break
            }

            Log.d(TAG, "Processing item ${index + 1}/${items.size}: ${item.app}")
            val result = OcrProcessor.process(applicationContext, item)
            if (result != null) {
                results.add(result)
            }
            
            // Delete the processed screenshot from MediaStore to save space
            try {
                applicationContext.contentResolver.delete(Uri.parse(item.uri), null, null)
                Log.d(TAG, "Deleted processed screenshot: ${item.uri}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete processed screenshot: ${item.uri}", e)
            }
        }

        if (results.isNotEmpty()) {
            repository.insertAll(results.map { it.toEntity() })
            Log.i(TAG, "Batch Complete: Stored ${results.size} / ${items.size} results to database.")

            // Enqueue successful OCR results for embedding generation
            results.forEach { result ->
                EmbeddingQueue.enqueue(
                    applicationContext,
                    PendingEmbeddingItem(
                        contextEntityId = 0,
                        text = result.text,
                        app = result.app,
                        timestamp = result.timestamp
                    )
                )
            }
            EmbeddingWorkScheduler.schedule(applicationContext)
        } else {
            Log.w(TAG, "Batch Complete: Processed ${items.size} items but found no usable text.")
        }

        return Result.success()
    }

    private fun isCharging(): Boolean {
        val intent = applicationContext.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val isCharging = plugged != 0 &&
                (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                 status == BatteryManager.BATTERY_STATUS_FULL)
        return isCharging
    }

    private fun batteryLevel(): Int {
        val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
}
