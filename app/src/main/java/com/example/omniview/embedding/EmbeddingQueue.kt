package com.example.omniview.embedding

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Items waiting to be embedded by [EmbeddingWorker] when the device is charging.
 *
 * Both accessibility-text items (no screenshot) and OCR items (with screenshot
 * URI for later deletion) share the same queue.
 */
data class PendingEmbedItem(
    val accessText: String?,      // nullable — null for OCR-only items
    val ocrText: String?,         // nullable — null for accessibility-only items
    val appName: String,
    val screenshotPath: String?,  // content:// URI or file path; deleted after embedding
    val timestamp: Long
)

/**
 * Thread-safe, file-backed queue of [PendingEmbedItem]s.
 *
 * Each item is serialised as a single JSON line in [FILE_NAME].  Draining the
 * queue atomically deletes the file so items are never processed twice even if
 * the process is killed between enqueue and drain.
 */
object EmbeddingQueue {

    private const val TAG = "OmniView:EmbedQueue"
    private const val FILE_NAME = "embedding_queue.jsonl"
    private val lock = Any()

    fun enqueue(context: Context, item: PendingEmbedItem) {
        synchronized(lock) {
            try {
                context.applicationContext
                    .getFileStreamPath(FILE_NAME)
                    .appendText(serialize(item) + "\n")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enqueue item for app=${item.appName}", e)
            }
        }
    }

    /**
     * Returns all pending items and clears the queue atomically.
     * Returns an empty list if the queue file does not exist.
     */
    fun drainAll(context: Context): List<PendingEmbedItem> {
        synchronized(lock) {
            val file = context.applicationContext.getFileStreamPath(FILE_NAME)
            if (!file.exists()) return emptyList()

            val items = file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull(::deserialize)

            file.delete()
            Log.i(TAG, "Drained ${items.size} items from embedding queue")
            return items
        }
    }

    fun size(context: Context): Int {
        synchronized(lock) {
            val file = context.applicationContext.getFileStreamPath(FILE_NAME)
            if (!file.exists()) return 0
            return file.readLines().count { it.isNotBlank() }
        }
    }

    // ── Serialisation ─────────────────────────────────────────────────────────

    private fun serialize(item: PendingEmbedItem): String =
        JSONObject().apply {
            // putOpt skips the key entirely when value is null — clean round-trip.
            putOpt("accessText",    item.accessText)
            putOpt("ocrText",       item.ocrText)
            put("appName",          item.appName)
            putOpt("screenshotPath", item.screenshotPath)
            put("timestamp",        item.timestamp)
        }.toString()

    private fun deserialize(line: String): PendingEmbedItem? =
        try {
            val j = JSONObject(line)
            PendingEmbedItem(
                accessText     = j.optString("accessText").ifEmpty { null },
                ocrText        = j.optString("ocrText").ifEmpty { null },
                appName        = j.optString("appName", "unknown"),
                screenshotPath = j.optString("screenshotPath").ifEmpty { null },
                timestamp      = j.getLong("timestamp")
            )
        } catch (e: Exception) {
            Log.w(TAG, "Skipping malformed queue line: $line")
            null
        }
}
