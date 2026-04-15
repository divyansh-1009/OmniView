package com.omniview.app.intelligence

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.omniview.app.storage.ContextDatabase
import com.omniview.app.storage.EmbeddingEntity
import com.omniview.app.storage.EmbeddingRepository

/**
 * WorkManager worker that processes the [EmbeddingQueue] in batch.
 *
 * Follows the same battery-gating pattern as [OcrWorker]:
 *   - Only runs when charging AND battery > 80%
 *   - Supports [KEY_FORCE_RUN] for dev/testing
 *   - Re-checks conditions mid-batch and re-queues remaining items if unplugged
 */
class EmbeddingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "OmniView:EmbedWorker"
        private const val BATTERY_THRESHOLD = 80

        /** Set to true via inputData to bypass battery/charging checks (dev only). */
        const val KEY_FORCE_RUN = "force_run"
    }

    override suspend fun doWork(): Result {
        val forceRun = inputData.getBoolean(KEY_FORCE_RUN, false)

        if (!forceRun) {
            val charging = isCharging()
            val battery = batteryLevel()
            Log.d(TAG, "Condition check: charging=$charging, battery=$battery% " +
                    "(Required: charging && >$BATTERY_THRESHOLD%)")

            if (!charging || battery < BATTERY_THRESHOLD) {
                Log.i(TAG, "Battery conditions not met. Skipping embedding run. Queue preserved.")
                return Result.success()
            }
            Log.i(TAG, "Conditions met. Starting embedding batch processing.")
        } else {
            Log.i(TAG, "Force-run mode enabled via UI trigger. Skipping condition checks.")
        }

        val items = EmbeddingQueue.drainAll(applicationContext)
        if (items.isEmpty()) {
            Log.i(TAG, "Embedding queue is empty. Nothing to process.")
            return Result.success()
        }

        Log.i(TAG, "Processing batch of ${items.size} queued items for embedding...")

        val embeddingRepository = EmbeddingRepository(
            ContextDatabase.getInstance(applicationContext).embeddingDao()
        )

        var engine: EmbeddingEngine? = null
        try {
            engine = EmbeddingEngine(applicationContext)
            val results = mutableListOf<EmbeddingEntity>()

            for ((index, item) in items.withIndex()) {
                // Re-check conditions mid-batch for battery health
                if (!forceRun && (!isCharging() || batteryLevel() < BATTERY_THRESHOLD)) {
                    val remaining = items.drop(index)
                    remaining.forEach { EmbeddingQueue.enqueue(applicationContext, it) }
                    Log.w(TAG, "Conditions changed mid-run. Re-queued ${remaining.size} items.")
                    break
                }

                // Skip items with very short text (unlikely to produce useful embeddings)
                if (item.text.length < 10) {
                    Log.v(TAG, "Skipping item ${index + 1}: text too short (${item.text.length} chars)")
                    continue
                }

                Log.d(TAG, "Embedding item ${index + 1}/${items.size}: ${item.app} " +
                        "(${item.text.length} chars)")

                try {
                    val startMs = System.currentTimeMillis()
                    val embedding = engine.generateEmbedding(item.text)
                    val elapsedMs = System.currentTimeMillis() - startMs

                    Log.d(TAG, "Inference complete: ${embedding.size}-dim vector in ${elapsedMs}ms")

                    val embeddingJson = embedding.joinToString(",", "[", "]")

                    results.add(
                        EmbeddingEntity(
                            contextId = item.contextEntityId,
                            app = item.app,
                            text = item.text,
                            embedding = embeddingJson,
                            embeddingDim = embedding.size,
                            timestamp = item.timestamp
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to generate embedding for item ${index + 1}", e)
                }
            }

            if (results.isNotEmpty()) {
                embeddingRepository.insertAll(results)
                Log.i(TAG, "Batch Complete: Stored ${results.size}/${items.size} embeddings.")
            } else {
                Log.w(TAG, "Batch Complete: Processed ${items.size} items but generated no embeddings.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Fatal error during embedding batch", e)
            // Re-queue all items so they aren't lost
            items.forEach { EmbeddingQueue.enqueue(applicationContext, it) }
            Log.w(TAG, "Re-queued all ${items.size} items after fatal error")
            return Result.failure()
        } finally {
            engine?.close()
        }

        return Result.success()
    }

    private fun isCharging(): Boolean {
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
