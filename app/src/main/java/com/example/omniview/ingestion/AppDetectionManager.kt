package com.example.omniview.ingestion

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import kotlin.math.abs

/**
 * Detects the currently active/foreground application package name.
 * Uses dual-method approach: AccessibilityService (preferred) with UsageStatsManager fallback.
 *
 * Responsibilities:
 * - Query current foreground app via Accessibility API (if available)
 * - Fallback to UsageStatsManager for app detection
 * - Provide thread-safe, error-resilient app detection
 * - Support REQ-11 (maintain blacklist) and REQ-12 (block capture for blacklisted apps)
 */
class AppDetectionManager(context: Context) {

    private val appContext = context.applicationContext
    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    companion object {
        private const val TAG = "AppDetectionManager"
    }

    /**
     * Get the currently active application package name.
     * Uses UsageStatsManager as the primary detection method.
     * Returns null if detection fails or permission is not granted.
     */
    fun getCurrentActiveApp(): String? {
        return try {
            val usageStatsResult = getActiveAppViaUsageStats()
            if (usageStatsResult != null) {
                Log.d(TAG, "Active app detected: $usageStatsResult")
                return usageStatsResult
            }

            Log.w(TAG, "Failed to detect active app. Ensure Usage Access permission is granted.")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting active app", e)
            null
        }
    }


    /**
     * Detection method using UsageStatsManager to get foreground app.
     * Requires PACKAGE_USAGE_STATS permission (Usage Access).
     *
     * Uses a 2-second query window to ensure we catch recent transitions.
     */
    private fun getActiveAppViaUsageStats(): String? {
        return try {
            if (usageStatsManager == null) {
                Log.w(TAG, "UsageStatsManager is null")
                return null
            }

            val currentTime = System.currentTimeMillis()
            val queryWindowStart = currentTime - 2000  // 2 seconds back
            
            val events = usageStatsManager.queryEvents(queryWindowStart, currentTime)
            if (events == null) {
                Log.w(TAG, "UsageStatsManager.queryEvents returned null")
                return null
            }

            var mostRecentPackage: String? = null
            var mostRecentTime = 0L

            val event = UsageEvents.Event()
            var eventCount = 0
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                eventCount++
                
                // Track the most recent ACTIVITY_RESUMED event
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (event.timeStamp > mostRecentTime) {
                        mostRecentTime = event.timeStamp
                        mostRecentPackage = event.packageName
                    }
                }
            }

            Log.v(TAG, "Analyzed $eventCount usage events in window. Most recent: $mostRecentPackage")

            if (mostRecentPackage != null && mostRecentPackage.isNotEmpty()) {
                // Ignore our own app if detected, we want to know what's BEHIND us or active
                // Actually, if we are in the foreground (Settings), we should report it correctly
                return mostRecentPackage
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "UsageStatsManager method failed: ${e.message}")
            null
        }
    }

    /**
     * Check if given package name appears to be a system package.
     * Useful for avoiding false positives with launcher/system UI.
     */
    private fun isSystemPackage(packageName: String): Boolean {
        val systemPrefixes = listOf(
            "com.android",
            "android.",
            "com.google.android.systemui"
        )
        return systemPrefixes.any { packageName.startsWith(it) }
    }
}
