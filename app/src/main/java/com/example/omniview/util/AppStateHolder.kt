package com.example.omniview.util

/**
 * Lightweight in-process singleton that tracks the package name of the app
 * currently in the foreground. Updated by ScreenAccessibilityService on every
 * window-content event; read by ScreenshotService when enqueuing a capture so
 * the OCR result can be attributed to the right app.
 */
object AppStateHolder {
    @Volatile var lastKnownApp: String = "unknown"
}
