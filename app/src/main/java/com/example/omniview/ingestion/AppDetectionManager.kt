package com.example.omniview.ingestion

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.example.omniview.util.AppStateHolder

/**
 * Detects the currently active/foreground application package name.
 * Uses a layered approach:
 * 1. AppStateHolder (updated in real-time by Accessibility Service)
 * 2. UsageStatsManager (fallback with a wide query window)
 */
class AppDetectionManager(context: Context) {

    private val appContext = context.applicationContext
    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private val myPackage = context.packageName

    companion object {
        private const val TAG = "AppDetectionManager"
        private const val DETECTION_WINDOW_MS = 60 * 60 * 1000L // 1 hour
    }

    /**
     * Get the currently active application package name.
     */
    fun getCurrentActiveApp(): String? {
        // Priority 1: Real-time data from Accessibility Service
        val accessibilityApp = AppStateHolder.lastKnownApp
        if (accessibilityApp != "unknown" && 
            accessibilityApp != myPackage && 
            accessibilityApp != "com.android.systemui") {
            Log.v(TAG, "Detection: Using real-time Accessibility data: $accessibilityApp")
            return accessibilityApp
        }

        // Priority 2: Fallback to UsageStatsManager
        return try {
            val usageStatsResult = getActiveAppViaUsageStats()
            if (usageStatsResult != null) {
                Log.v(TAG, "Detection: Fallback to UsageStats successful: $usageStatsResult")
                return usageStatsResult
            }

            Log.w(TAG, "Detection failed: No recent app transitions found.")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Detection error", e)
            null
        }
    }

    /**
     * Detection method using UsageStatsManager.
     * Searches for the absolute most recent ACTIVITY_RESUMED event in a wide window.
     */
    private fun getActiveAppViaUsageStats(): String? {
        if (usageStatsManager == null) return null

        val currentTime = System.currentTimeMillis()
        val queryWindowStart = currentTime - DETECTION_WINDOW_MS
        
        val events = usageStatsManager.queryEvents(queryWindowStart, currentTime) ?: return null

        var mostRecentPackage: String? = null
        var mostRecentTime = 0L

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            
            // Only consider ACTIVITY_RESUMED events (apps coming to foreground)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                // Ignore our own app - we want to know what's active when we're in background
                if (event.packageName == myPackage) continue
                
                if (event.timeStamp > mostRecentTime) {
                    mostRecentTime = event.timeStamp
                    mostRecentPackage = event.packageName
                }
            }
        }

        return mostRecentPackage
    }
}
