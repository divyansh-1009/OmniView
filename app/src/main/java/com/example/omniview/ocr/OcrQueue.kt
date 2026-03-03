package com.example.omniview.ocr

import android.content.Context
import android.util.Log

data class PendingOcrItem(
    val uri: String,        // MediaStore content URI string
    val app: String,        // foreground package name at capture time
    val timestamp: Long
)

/**
 * Thread-safe, file-backed queue of screenshots waiting for OCR.
 *
 * Each entry is persisted as a single pipe-delimited line:
 *   <uri>|<app>|<timestamp>
 *
 * Draining atomically deletes the backing file so items are never
 * processed twice, even if the process is killed between enqueue and drain.
 */
object OcrQueue {

    private const val TAG = "OmniView:OcrQueue"
    private const val FILE_NAME = "ocr_queue.txt"
    private val lock = Any()

    fun enqueue(context: Context, item: PendingOcrItem) {
        synchronized(lock) {
            try {
                context.applicationContext
                    .getFileStreamPath(FILE_NAME)
                    .appendText("${item.uri}|${item.app}|${item.timestamp}\n")
                Log.d(TAG, "Enqueued ${item.uri} (app=${item.app})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enqueue item", e)
            }
        }
    }

    /**
     * Returns all pending items and clears the queue atomically.
     */
    fun drainAll(context: Context): List<PendingOcrItem> {
        synchronized(lock) {
            val file = context.applicationContext.getFileStreamPath(FILE_NAME)
            if (!file.exists()) return emptyList()

            val items = file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull(::parseLine)

            file.delete()
            Log.i(TAG, "Drained ${items.size} items from queue")
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

    private fun parseLine(line: String): PendingOcrItem? {
        // URI itself can contain colons and slashes but never '|'
        val parts = line.split("|")
        if (parts.size < 3) return null
        return try {
            PendingOcrItem(
                uri = parts[0],
                app = parts[1],
                timestamp = parts[2].toLong()
            )
        } catch (e: NumberFormatException) {
            Log.w(TAG, "Skipping malformed queue line: $line")
            null
        }
    }
}
