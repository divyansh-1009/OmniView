package com.omniview.app.intelligence

import android.content.Context
import android.util.Log

/**
 * An item waiting for embedding generation.
 *
 * @param contextEntityId  ID from context_entries (0 if not yet known).
 * @param text             The cleaned text to embed.
 * @param app              Package name of the source app.
 * @param timestamp        Capture timestamp in millis.
 */
data class PendingEmbeddingItem(
    val contextEntityId: Int,
    val text: String,
    val app: String,
    val timestamp: Long
)

/**
 * Thread-safe, file-backed queue of context entries waiting for embedding.
 *
 * Each entry is persisted as a single pipe-delimited line:
 *   <contextEntityId>|<app>|<timestamp>|<text (base64)>
 *
 * Text is Base64-encoded so pipe characters inside the text don't break parsing.
 *
 * Draining atomically deletes the backing file so items are never
 * processed twice, even if the process is killed between enqueue and drain.
 */
object EmbeddingQueue {

    private const val TAG = "OmniView:EmbedQueue"
    private const val FILE_NAME = "embedding_queue.txt"
    private val lock = Any()

    fun enqueue(context: Context, item: PendingEmbeddingItem) {
        synchronized(lock) {
            try {
                val encodedText = android.util.Base64.encodeToString(
                    item.text.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )
                context.applicationContext
                    .getFileStreamPath(FILE_NAME)
                    .appendText("${item.contextEntityId}|${item.app}|${item.timestamp}|$encodedText\n")
                Log.d(TAG, "Enqueued embedding item (app=${item.app}, len=${item.text.length})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enqueue item", e)
            }
        }
    }

    /**
     * Returns all pending items and clears the queue atomically.
     */
    fun drainAll(context: Context): List<PendingEmbeddingItem> {
        synchronized(lock) {
            val file = context.applicationContext.getFileStreamPath(FILE_NAME)
            if (!file.exists()) return emptyList()

            val items = file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull(::parseLine)

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

    private fun parseLine(line: String): PendingEmbeddingItem? {
        val parts = line.split("|", limit = 4)
        if (parts.size < 4) return null
        return try {
            val decodedText = String(
                android.util.Base64.decode(parts[3], android.util.Base64.NO_WRAP),
                Charsets.UTF_8
            )
            PendingEmbeddingItem(
                contextEntityId = parts[0].toInt(),
                app = parts[1],
                timestamp = parts[2].toLong(),
                text = decodedText
            )
        } catch (e: Exception) {
            Log.w(TAG, "Skipping malformed queue line: ${line.take(80)}", e)
            null
        }
    }
}
