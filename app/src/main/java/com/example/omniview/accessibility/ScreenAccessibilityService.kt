package com.example.omniview.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.omniview.db.ContextDatabase
import com.example.omniview.db.ContextEntity
import com.example.omniview.db.ContextRepository
import com.example.omniview.model.RawContext
import com.example.omniview.processing.ContextCleaner
import com.example.omniview.processing.ContextDeduplicator
import com.example.omniview.util.AppStateHolder
import kotlinx.coroutines.*

class ScreenAccessibilityService : AccessibilityService() {

    private val deduplicator = ContextDeduplicator(windowMs = 10_000L)

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var repository: ContextRepository

    // Buffer filled on the main thread; flushed on IO.
    private val buffer = ArrayList<ContextEntity>(BATCH_SIZE)

    companion object {
        private const val TAG = "OmniView:A11y"
        private const val BATCH_SIZE = 20
        private const val FLUSH_INTERVAL_MS = 30_000L
    }

    override fun onServiceConnected() {
        repository = ContextRepository(ContextDatabase.getInstance(this).contextDao())

        serviceInfo = serviceInfo.apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags =
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }

        serviceScope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushBuffer()
            }
        }

        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val packageName = event.packageName?.toString() ?: return
        AppStateHolder.lastKnownApp = packageName

        val root = rootInActiveWindow ?: return

        val rawTokens = mutableListOf<String>()
        extractText(root, rawTokens)
        root.recycle()

        val cleanTokens = rawTokens
            .mapNotNull { ContextCleaner.cleanToken(it) }
            .distinct()

        if (cleanTokens.isEmpty()) return

        val cleanText = cleanTokens.joinToString(separator = " | ")

        if (deduplicator.isDuplicate(packageName, cleanText)) return

        val context = RawContext(
            app = packageName,
            text = cleanText,
            timestamp = System.currentTimeMillis()
        )

        Log.d(TAG, "[${context.app}] ${context.text}")

        enqueue(ContextEntity(app = context.app, text = context.text, timestamp = context.timestamp, source = "accessibility"))
    }

    /**
     * Depth-first traversal of the node tree, collecting non-blank text and
     * contentDescription values from every node.
     */
    private fun extractText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        node ?: return

        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractText(child, out)
            child.recycle()
        }
    }

    // Called from the main thread (accessibility event callback).
    private fun enqueue(entity: ContextEntity) {
        val snapshot: List<ContextEntity>? = synchronized(buffer) {
            buffer.add(entity)
            if (buffer.size >= BATCH_SIZE) {
                val copy = ArrayList(buffer)
                buffer.clear()
                copy
            } else null
        }
        snapshot?.let { serviceScope.launch { repository.insertAll(it) } }
    }

    private suspend fun flushBuffer() {
        val snapshot = synchronized(buffer) {
            if (buffer.isEmpty()) return
            val copy = ArrayList(buffer)
            buffer.clear()
            copy
        }
        repository.insertAll(snapshot)
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.launch { flushBuffer() }.invokeOnCompletion { serviceScope.cancel() }
    }
}
