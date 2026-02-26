package com.example.omniview.ingestion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Monitors system-level permission changes and reacts to media projection
 * permission revocation (REQ-2).
 *
 * Responsibilities:
 * - Listen for MEDIA_PROJECTION_PERMISSION_CHANGE broadcasts
 * - Automatically pause capture when permissions are revoked
 * - Provide manual permission check before capture attempts
 */
class PermissionMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val appStateManager = AppStateManager(appContext)

    companion object {
        private const val TAG = "PermissionMonitor"
        const val ACTION_PERMISSION_CHANGED = "android.media.projection.PERMISSION_CHANGE"
    }

    /**
     * BroadcastReceiver for permission change events.
     * Automatically triggered when user revokes MEDIA_PROJECTION permission
     * from Settings > Apps > OmniView > Permissions
     */
    class PermissionChangeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null) return
            
            if (intent?.action == ACTION_PERMISSION_CHANGED) {
                Log.w(TAG, "MEDIA_PROJECTION permission was revoked")
                val appStateManager = AppStateManager(context)
                appStateManager.pauseCapture()
                
                // Log for debugging
                Log.i(TAG, "Capture automatically paused due to permission revocation")
            }
        }
    }

    /**
     * Verify that media projection permission is still granted before capture.
     * This serves as a fallback check in case the broadcast receiver wasn't triggered.
     * Returns true if permission check passes (capture should proceed).
     *
     * Note: MEDIA_PROJECTION is a special permission that doesn't appear in manifest
     * but is controlled via MediaProjectionManager.getMediaProjection().
     * If MediaProjection object is null, permission was revoked.
     */
    fun hasMediaProjectionPermission(mediaProjection: Any?): Boolean {
        if (mediaProjection == null) {
            Log.w(TAG, "MediaProjection is null - permission was revoked")
            appStateManager.pauseCapture()
            return false
        }
        return true
    }

    /**
     * Check if capture should proceed. Combines pause state and permission status.
     */
    fun canCaptureNow(mediaProjection: Any?): Boolean {
        if (appStateManager.isPaused()) {
            Log.v(TAG, "Capture skipped: paused by user")
            return false
        }
        
        if (!hasMediaProjectionPermission(mediaProjection)) {
            Log.w(TAG, "Capture skipped: permission revoked")
            return false
        }
        
        return true
    }
}
