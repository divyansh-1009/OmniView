package com.example.omniview.processing

/**
 * Suppresses repeated content within a sliding time window.
 *
 * The accessibility service can fire 10+ identical events per second for the
 * same window. This deduplicator keeps one entry per (app, textHash) pair and
 * ignores re-firings until [windowMs] milliseconds have elapsed.
 *
 * Not thread-safe — construct one instance per service and call from a single
 * thread (the main thread for accessibility events).
 */
class ContextDeduplicator(private val windowMs: Long = 10_000L) {

    // key = app + text hash, value = timestamp of last acceptance
    private val seen = LinkedHashMap<Long, Long>()

    /**
     * Returns true if this (app, text) pair was already accepted within
     * the current window and should be dropped.
     */
    fun isDuplicate(app: String, text: String): Boolean {
        val now = System.currentTimeMillis()
        evictExpired(now)

        val key = hashKey(app, text)
        if (seen.containsKey(key)) return true

        seen[key] = now
        return false
    }

    fun reset() = seen.clear()

    private fun evictExpired(now: Long) {
        val iter = seen.iterator()
        while (iter.hasNext()) {
            if (now - iter.next().value > windowMs) iter.remove() else break
        }
    }

    private fun hashKey(app: String, text: String): Long {
        // Combine two 32-bit hashes into one 64-bit key to minimise collisions
        val appHash = app.hashCode().toLong() and 0xFFFFFFFFL
        val textHash = text.hashCode().toLong() and 0xFFFFFFFFL
        return (appHash shl 32) or textHash
    }
}
