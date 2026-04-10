package com.omniview.app.ingestion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.omniview.app.storage.ContextDatabase
import com.omniview.app.storage.ContextEntity
import com.omniview.app.storage.ContextRepository
import com.omniview.app.storage.AppStateManager
import com.omniview.app.storage.RawContext
import com.omniview.app.intelligence.ContextCleaner
import com.omniview.app.intelligence.ContextDeduplicator
import com.omniview.app.storage.AppStateHolder
import kotlinx.coroutines.*

class ScreenAccessibilityService : AccessibilityService() {

    private val deduplicator = ContextDeduplicator(windowMs = 10_000L)
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var repository: ContextRepository
    private lateinit var appStateManager: AppStateManager

    // Buffer filled on the main thread; flushed on IO.
    private val buffer = ArrayList<ContextEntity>(BATCH_SIZE)

    companion object {
        private const val TAG = "OmniView:A11y"
        private const val BATCH_SIZE = 20
        private const val FLUSH_INTERVAL_MS = 30_000L
        private const val SAMPLING_INTERVAL_MS = 10_000L // Sync with ScreenshotService
    }

    override fun onServiceConnected() {
        repository = ContextRepository(ContextDatabase.getInstance(this).contextDao())
        appStateManager = AppStateManager(this)

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }

        // Background loop for DB flushing
        serviceScope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushBuffer()
            }
        }

        // NEW: Background loop for periodic screen sampling (prevents spamming)
        serviceScope.launch {
            while (isActive) {
                delay(SAMPLING_INTERVAL_MS)
                captureA11ySnapshot()
            }
        }

        Log.i(TAG, "AccessibilityService connected and ready with 10s sampling loop")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Only update the detected app on major window transitions.
        // This prevents minor "content changed" events from system overlays (like the status bar)
        // from over-writing the actual app the user is interacting with.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { pkg ->
                AppStateHolder.lastKnownApp = pkg
            }
        }
    }

    private fun captureA11ySnapshot() {
        // 1. Check if paused
        if (appStateManager.isPaused()) {
            Log.v(TAG, "Sampling: Skipping accessibility extraction (Paused)")
            return
        }

        // 2. Identify foreground app
        val packageName = AppStateHolder.lastKnownApp
        if (packageName == "unknown" || packageName == "com.omniview.app.ui") {
            return
        }

        // 3. Blacklist gating
        if (appStateManager.isBlacklisted(packageName)) {
            Log.v(TAG, "Sampling: Skipping blacklisted app: $packageName")
            return
        }

        // 4. Perform extraction
        val root = rootInActiveWindow ?: return
        
        try {
            val rawTokens = mutableListOf<String>()
            extractText(root, rawTokens)
            @Suppress("DEPRECATION") // recycle() is a no-op on API 33+; needed below for older versions
            root.recycle()

            val cleanTokens = rawTokens
                .mapNotNull { ContextCleaner.cleanToken(it) }
                .distinct()

            if (cleanTokens.isEmpty()) return

            val cleanText = cleanTokens.joinToString(separator = " | ")

            if (deduplicator.isDuplicate(packageName, cleanText)) {
                Log.v(TAG, "Sampling: Duplicate filtered for $packageName")
                return
            }

            val context = RawContext(
                app = packageName,
                text = cleanText,
                timestamp = System.currentTimeMillis()
            )

            Log.d(TAG, "Sampling Success [${context.app}]: ${context.text.take(150)}...")

            enqueue(ContextEntity(
                app = context.app, 
                text = context.text, 
                timestamp = context.timestamp, 
                source = "accessibility"
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Error during sampling extraction", e)
        }
    }

    private fun extractText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        node ?: return

        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractText(child, out)
            @Suppress("DEPRECATION") // auto-managed on API 33+
            child.recycle()
        }
    }

    private fun enqueue(entity: ContextEntity) {
        val snapshot: List<ContextEntity>? = synchronized(buffer) {
            buffer.add(entity)
            if (buffer.size >= BATCH_SIZE) {
                val copy = ArrayList(buffer)
                buffer.clear()
                copy
            } else null
        }
        snapshot?.let { items ->
            Log.i(TAG, "Buffer threshold reached, inserting ${items.size} a11y items to DB")
            serviceScope.launch { repository.insertAll(items) } 
        }
    }

    private suspend fun flushBuffer() {
        val snapshot = synchronized(buffer) {
            if (buffer.isEmpty()) return
            val copy = ArrayList(buffer)
            buffer.clear()
            copy
        }
        Log.i(TAG, "Timed flush: inserting ${snapshot.size} a11y items to DB")
        repository.insertAll(snapshot)
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "AccessibilityService shutting down")
        serviceScope.launch { flushBuffer() }.invokeOnCompletion { serviceScope.cancel() }
    }
}
