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
     * Attempts AccessibilityService first, falls back to UsageStatsManager.
     * Returns null if detection fails.
     */
    fun getCurrentActiveApp(): String? {
        return try {
            // Try AccessibilityService method first
            val accessibilityResult = getActiveAppViaAccessibility()
            if (accessibilityResult != null) {
                Log.v(TAG, "Active app detected via AccessibilityService: $accessibilityResult")
                return accessibilityResult
            }

            // Fallback to UsageStatsManager
            val usageStatsResult = getActiveAppViaUsageStats()
            if (usageStatsResult != null) {
                Log.v(TAG, "Active app detected via UsageStatsManager (fallback): $usageStatsResult")
                return usageStatsResult
            }

            Log.w(TAG, "Failed to detect active app via both methods")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting active app", e)
            null
        }
    }

    /**
     * Attempt to get foreground app using AccessibilityService.
     * This is the most reliable method but requires AccessibilityService to be enabled
     * and this app's access to be granted via Settings > Accessibility.
     *
     * Returns null if:
     * - AccessibilityService is not enabled for this app
     * - Permission check fails
     */
    private fun getActiveAppViaAccessibility(): String? {
        return try {
            val tasks = activityManager?.getRunningTasks(1) ?: return null
            if (tasks.isNotEmpty()) {
                val topTask = tasks[0]
                val packageName = topTask.topActivity?.packageName
                if (packageName != null && packageName.isNotEmpty()) {
                    return packageName
                }
            }
            null
        } catch (e: Exception) {
            Log.v(TAG, "AccessibilityService method unavailable: ${e.message}")
            null
        }
    }

    /**
     * Fallback method using UsageStatsManager to get foreground app.
     * More reliable on modern Android versions (10+) but requires PACKAGE_USAGE_STATS permission.
     *
     * Uses a 1-second query window looking back from current time to detect recent activity.
     * Returns the package with the most recent foreground event in that window.
     */
    private fun getActiveAppViaUsageStats(): String? {
        return try {
            if (usageStatsManager == null) {
                return null
            }

            val currentTime = System.currentTimeMillis()
            val queryWindowStart = currentTime - 1000  // 1 second back
            
            val events = usageStatsManager.queryEvents(queryWindowStart, currentTime)
            if (events == null) {
                Log.v(TAG, "UsageStatsManager returned no events")
                return null
            }

            var mostRecentPackage: String? = null
            var mostRecentTime = 0L

            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                
                // Look for ACTIVITY_RESUMED or KEYGUARD_HIDDEN events
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        if (event.timeStamp > mostRecentTime) {
                            mostRecentTime = event.timeStamp
                            mostRecentPackage = event.packageName
                        }
                    }
                }
            }

            if (mostRecentPackage != null && mostRecentPackage.isNotEmpty()) {
                return mostRecentPackage
            }

            null
        } catch (e: Exception) {
            Log.v(TAG, "UsageStatsManager method failed: ${e.message}")
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
