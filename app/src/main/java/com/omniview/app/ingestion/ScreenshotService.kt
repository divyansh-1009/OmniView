package com.omniview.app.ingestion

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.omniview.app.ui.MainActivity
import com.omniview.app.storage.AppStateManager
import com.omniview.app.intelligence.OcrQueue
import com.omniview.app.intelligence.PendingOcrItem
import java.nio.ByteBuffer

class ScreenshotService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var isScreenOn = true

    /**
     * True only when Android (or the user via Settings) has permanently revoked the
     * MediaProjection token.  Screen-off does NOT set this flag any more — we keep
     * the VirtualDisplay alive across screen-off events to avoid the Android 14+
     * single-use token problem.
     */
    private var isProjectionRevoked = false

    private lateinit var appDetectionManager: AppDetectionManager
    private lateinit var appStateManager: AppStateManager
    private var lastScreenshotHash: Long? = null

    companion object {
        private const val TAG = "ScreenshotService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ScreenshotServiceChannel"
        private const val CAPTURE_INTERVAL = 10000L // 10 seconds
        const val ACTION_RESTART_CAPTURE = "com.omniview.app.ACTION_RESTART_CAPTURE"
    }

    // ── Screen on/off ─────────────────────────────────────────────────────────
    //
    // KEY DESIGN DECISION (Android 14+):
    //
    //  On Android 14+, each MediaProjection token is "single-shot" per
    //  VirtualDisplay session.  If you release the VirtualDisplay (or close the
    //  ImageReader whose surface it renders into) Android considers the session
    //  finished and immediately fires MediaProjection.Callback.onStop().
    //  Attempting to create a second VirtualDisplay from the same token then
    //  throws a SecurityException.
    //
    //  Previous code called releaseVirtualDisplay() on every SCREEN_OFF, which
    //  instantly invalidated the token and kicked off the "Capture Expired" loop.
    //
    //  Fix: keep the VirtualDisplay (and its ImageReader) alive while the screen
    //  is off.  We simply stop *reading* from it (isScreenOn gate in the capture
    //  loop).  The display continues to render in the background but we never
    //  call acquireLatestImage(), so there is zero CPU/memory cost from captures.
    // ──────────────────────────────────────────────────────────────────────────
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "Screen OFF — pausing captures (VirtualDisplay stays alive)")
                    isScreenOn = false
                    updateNotification()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.i(TAG, "Screen ON — resuming captures")
                    isScreenOn = true
                    if (isProjectionRevoked) {
                        // Truly revoked (user removed permission in Settings)
                        Log.w(TAG, "Projection was revoked while screen was off — need re-auth")
                    }
                    updateNotification()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")
        appDetectionManager = AppDetectionManager(this)
        appStateManager = AppStateManager(this)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenStateReceiver, filter)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            intent?.getParcelableExtra("data")
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            // ── New permission grant (fresh start or user re-authorised) ──────
            // Tear down any stale session first so we don't leak resources.
            tearDownProjection()

            isProjectionRevoked = false
            // We have a token — start with mediaProjection type
            startForegroundServiceInternal(hasProjectionToken = true)

            Log.i(TAG, "Initializing new MediaProjection session")
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = pm.getMediaProjection(resultCode, data)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    // This fires when Android or the user truly revokes the token,
                    // NOT just because the screen turned off (we no longer release
                    // the VirtualDisplay on screen-off).
                    Log.w(TAG, "MediaProjection.onStop() — token revoked by system")
                    isProjectionRevoked = true
                    // Don't call releaseVirtualDisplay() here; it's already gone.
                    virtualDisplay = null
                    imageReader = null
                    updateNotification()
                }
            }, handler)

            initializeVirtualDisplay()
            startCaptureLoop()

        } else if (mediaProjection == null) {
            // Service restarted by OS (START_STICKY) with no projection data.
            // Must NOT use mediaProjection FGS type here — use dataSync instead.
            Log.w(TAG, "No MediaProjection data. Service in WAITING state.")
            startForegroundServiceInternal(hasProjectionToken = false)
        }

        return START_STICKY
    }

    // ── Foreground / notification ──────────────────────────────────────────────

    /**
     * Promotes this service to foreground.
     *
     * @param hasProjectionToken  When true, declares [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION]
     *   which requires an active MediaProjection grant.  When false (OS-restart / waiting state),
     *   uses [ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC] which is always safe.
     */
    private fun startForegroundServiceInternal(hasProjectionToken: Boolean = false) {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (hasProjectionToken)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, createNotification())
    }

    // ── VirtualDisplay lifecycle ───────────────────────────────────────────────

    private fun initializeVirtualDisplay() {
        if (mediaProjection == null || isProjectionRevoked) return

        try {
            val metrics = resources.displayMetrics
            imageReader = ImageReader.newInstance(
                metrics.widthPixels, metrics.heightPixels,
                PixelFormat.RGBA_8888, 2
            )

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenshotDisplay",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                handler
            )
            Log.i(TAG, "VirtualDisplay initialized: ${metrics.widthPixels}x${metrics.heightPixels}")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException creating VirtualDisplay — token already consumed?", e)
            isProjectionRevoked = true
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize VirtualDisplay", e)
        }
    }

    /**
     * Releases only the VirtualDisplay + ImageReader surface, leaving the
     * MediaProjection token itself untouched.
     * Call this only from [tearDownProjection] or [onDestroy].
     */
    private fun releaseVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    /**
     * Full teardown: releases VirtualDisplay then stops the MediaProjection.
     * Called before creating a brand-new session (user tapped Restart) or in
     * onDestroy.
     */
    private fun tearDownProjection() {
        releaseVirtualDisplay()
        mediaProjection?.stop()
        mediaProjection = null
    }

    // ── Capture loop ──────────────────────────────────────────────────────────

    private fun startCaptureLoop() {
        if (isRunning) return
        isRunning = true

        handler.post(object : Runnable {
            override fun run() {
                if (isRunning) {
                    if (isScreenOn && !isProjectionRevoked) {
                        captureScreenshot()
                    }
                    handler.postDelayed(this, CAPTURE_INTERVAL)
                }
            }
        })
    }

    private fun captureScreenshot() {
        if (!isScreenOn || isProjectionRevoked) return

        if (appStateManager.isPaused()) {
            Log.d(TAG, "Skipping capture: paused by user.")
            return
        }

        val foregroundApp = appDetectionManager.getCurrentActiveApp()
        if (foregroundApp == null) {
            Log.w(TAG, "Skipping capture: no active app detected.")
            return
        }

        if (appStateManager.isBlacklisted(foregroundApp)) {
            Log.d(TAG, "Skipping capture: $foregroundApp is blacklisted.")
            return
        }

        val reader = imageReader
        if (reader == null) {
            // VirtualDisplay was never created or was unexpectedly lost.
            // Do NOT try to re-init here — on Android 14+ the token is gone.
            Log.e(TAG, "ImageReader is null — projection may have been revoked externally")
            isProjectionRevoked = true
            updateNotification()
            return
        }

        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.e(TAG, "acquireLatestImage failed", e)
            null
        }
        if (image == null) return

        try {
            val metrics = resources.displayMetrics
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * metrics.widthPixels

            val bitmap = Bitmap.createBitmap(
                metrics.widthPixels + rowPadding / pixelStride,
                metrics.heightPixels,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            processBitmap(bitmap, foregroundApp)
        } catch (e: Exception) {
            Log.e(TAG, "Capture error", e)
            image.close()
        }
    }

    // ── Bitmap processing ─────────────────────────────────────────────────────

    private fun processBitmap(bitmap: Bitmap, packageName: String) {
        val currentHash = calculatePHash(bitmap)

        if (lastScreenshotHash != null) {
            val distance = calculateHammingDistance(lastScreenshotHash!!, currentHash)
            if (distance < 10) {
                bitmap.recycle()
                return
            }
        }

        lastScreenshotHash = currentHash
        saveScreenshot(bitmap, packageName)
    }

    private fun saveScreenshot(bitmap: Bitmap, packageName: String) {
        val filename = "screenshot_${System.currentTimeMillis()}.png"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OmniView")
        }

        val uri: Uri? = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    Log.i(TAG, "Screenshot saved: $uri ($packageName)")
                    OcrQueue.enqueue(
                        this,
                        PendingOcrItem(uri.toString(), packageName, System.currentTimeMillis())
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save screenshot", e)
            }
        }
        bitmap.recycle()
    }

    // ── Hash helpers ──────────────────────────────────────────────────────────

    private fun calculatePHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        var avg = 0L
        for (y in 0 until 8) for (x in 0 until 8) avg += (scaled.getPixel(x, y) and 0xFF)
        avg /= 64
        var hash = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            if ((scaled.getPixel(x, y) and 0xFF) >= avg) hash = hash or (1L shl (y * 8 + x))
        }
        scaled.recycle()
        return hash
    }

    private fun calculateHammingDistance(h1: Long, h2: Long): Int =
        java.lang.Long.bitCount(h1 xor h2)

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screenshot Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val mainIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        val (title, content) = when {
            isProjectionRevoked ->
                "Capture Expired" to "Permission revoked by system. Tap \"Restart\" to re-authorise."
            !isScreenOn ->
                "Capture Paused" to "Screen is off — will resume on wake."
            mediaProjection == null ->
                "Service Waiting" to "Tap 'Start Screenshot Service' in the app."
            else ->
                "OmniView Active" to "Monitoring screen context…"
        }

        val priority =
            if (isProjectionRevoked) NotificationCompat.PRIORITY_HIGH
            else NotificationCompat.PRIORITY_DEFAULT

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(priority)
            .setContentIntent(mainIntent)
            .setOngoing(true)

        if (isProjectionRevoked) {
            val restartIntent = Intent(this, MainActivity::class.java).apply {
                action = ACTION_RESTART_CAPTURE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            builder.addAction(
                android.R.drawable.ic_menu_camera,
                "Restart Capture",
                PendingIntent.getActivity(
                    this, 1, restartIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }

        return builder.build()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service onDestroy — cleaning up")
        isRunning = false
        unregisterReceiver(screenStateReceiver)
        handler.removeCallbacksAndMessages(null)
        tearDownProjection()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}