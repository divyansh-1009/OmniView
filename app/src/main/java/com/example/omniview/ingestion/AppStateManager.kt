package com.example.omniview.ingestion

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages app-level state including pause status and app blacklist.
 * Uses SharedPreferences for persistent storage across app restarts.
 *
 * Responsibilities:
 * - Track capture pause/resume state (REQ-2, REQ-14)
 * - Maintain blacklist of sensitive apps (REQ-11)
 * - Provide thread-safe read/write operations
 */
class AppStateManager(context: Context) {

    private val preferences: SharedPreferences =
        context.getSharedPreferences("omniview_state", Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "omniview_state"
        private const val KEY_IS_PAUSED = "capture_is_paused"
        private const val KEY_BLACKLIST = "app_blacklist"
        
        // Default sensitive apps that should not be captured
        private val DEFAULT_BLACKLIST = setOf(
            "com.android.settings",
            "com.android.systemui",
            "com.android.internal.systemui",
            "com.google.android.gms",
            "com.android.keyguard",
            "com.android.launcher",
            "com.android.launcher3"
        )
    }

    init {
        // Initialize blacklist with defaults if not already set
        if (!preferences.contains(KEY_BLACKLIST)) {
            val editor = preferences.edit()
            editor.putStringSet(KEY_BLACKLIST, DEFAULT_BLACKLIST)
            editor.apply()
        }
    }

    /**
     * Pause screenshot capture (REQ-2, REQ-14)
     */
    fun pauseCapture() {
        preferences.edit().putBoolean(KEY_IS_PAUSED, true).apply()
    }

    /**
     * Resume screenshot capture (REQ-14)
     */
    fun resumeCapture() {
        preferences.edit().putBoolean(KEY_IS_PAUSED, false).apply()
    }

    /**
     * Check if capture is currently paused
     */
    fun isPaused(): Boolean {
        return preferences.getBoolean(KEY_IS_PAUSED, false)
    }

    /**
     * Add an app package name to the blacklist (REQ-13)
     */
    fun addToBlacklist(packageName: String) {
        val currentBlacklist = preferences.getStringSet(KEY_BLACKLIST, emptySet())?.toMutableSet() ?: mutableSetOf()
        currentBlacklist.add(packageName)
        preferences.edit().putStringSet(KEY_BLACKLIST, currentBlacklist).apply()
    }

    /**
     * Remove an app package name from the blacklist (REQ-13)
     */
    fun removeFromBlacklist(packageName: String) {
        val currentBlacklist = preferences.getStringSet(KEY_BLACKLIST, emptySet())?.toMutableSet() ?: mutableSetOf()
        currentBlacklist.remove(packageName)
        preferences.edit().putStringSet(KEY_BLACKLIST, currentBlacklist).apply()
    }

    /**
     * Check if a given app package is blacklisted (REQ-11, REQ-12)
     */
    fun isBlacklisted(packageName: String): Boolean {
        val blacklist = preferences.getStringSet(KEY_BLACKLIST, emptySet()) ?: emptySet()
        return blacklist.contains(packageName)
    }

    /**
     * Get entire blacklist as an immutable Set
     */
    fun getBlacklist(): Set<String> {
        return preferences.getStringSet(KEY_BLACKLIST, emptySet()) ?: emptySet()
    }

    /**
     * Clear entire blacklist (useful for testing)
     */
    fun clearBlacklist() {
        preferences.edit().remove(KEY_BLACKLIST).apply()
        // Re-initialize with defaults
        val editor = preferences.edit()
        editor.putStringSet(KEY_BLACKLIST, DEFAULT_BLACKLIST)
        editor.apply()
    }

    /**
     * Reset all state to defaults (useful for testing)
     */
    fun resetAll() {
        preferences.edit().clear().apply()
        init()
    }
}
