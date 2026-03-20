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
import com.omniview.app.ingestion.AppDetectionManager
import com.omniview.app.storage.AppStateManager
import com.omniview.app.intelligence.OcrQueue
import com.omniview.app.intelligence.PendingOcrItem
import java.io.OutputStream
import java.nio.ByteBuffer
import android.widget.Toast

class ScreenshotService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var isScreenOn = true
    private var isProjectionRevoked = false

    private lateinit var appDetectionManager: AppDetectionManager
    private lateinit var appStateManager: AppStateManager
    private var lastScreenshotHash: Long? = null

    companion object {
        private const val TAG = "ScreenshotService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ScreenshotServiceChannel"
        private const val CAPTURE_INTERVAL = 10000L // 10 seconds
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "Screen OFF - pausing capture loop and releasing display")
                    isScreenOn = false
                    releaseVirtualDisplay()
                    updateNotification()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.i(TAG, "Screen ON - attempting resume")
                    isScreenOn = true
                    if (mediaProjection != null && !isProjectionRevoked) {
                        initializeVirtualDisplay()
                    } else if (isProjectionRevoked) {
                        Toast.makeText(this@ScreenshotService, "OmniView: Permissions Expired. Tap notification to restart.", Toast.LENGTH_LONG).show()
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
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode == Activity.RESULT_OK && data != null) {
            isProjectionRevoked = false
            startForegroundServiceInternal()

            Log.i(TAG, "Initializing MediaProjection session")
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.w(TAG, "MediaProjection revoked by system")
                    isProjectionRevoked = true
                    releaseVirtualDisplay()
                    updateNotification()
                }
            }, handler)

            initializeVirtualDisplay()
            startCaptureLoop()
        } else if (mediaProjection == null) {
            Log.w(TAG, "No MediaProjection data. Service in WAITING state.")
            startForegroundServiceInternal()
        }

        return START_STICKY
    }

    private fun startForegroundServiceInternal() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun initializeVirtualDisplay() {
        if (mediaProjection == null || isProjectionRevoked) return
        
        try {
            val metrics = resources.displayMetrics
            imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
            
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
            Log.e(TAG, "SecurityException: MediaProjection token expired on Android 14+", e)
            isProjectionRevoked = true
            releaseVirtualDisplay()
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize VirtualDisplay", e)
        }
    }

    private fun releaseVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

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

    private fun stopCaptureLoop() {
        isRunning = false
    }

    private fun captureScreenshot() {
        if (!isScreenOn || isProjectionRevoked) return
        
        if (appStateManager.isPaused()) {
            Log.d(TAG, "Skipping capture: Paused by user.")
            return
        }

        val foregroundApp = appDetectionManager.getCurrentActiveApp()
        if (foregroundApp == null) {
            Log.w(TAG, "Skipping capture: No active app detected.")
            return
        }
        
        if (appStateManager.isBlacklisted(foregroundApp)) {
            Log.d(TAG, "Skipping capture: $foregroundApp is blacklisted.")
            return
        }

        val reader = imageReader ?: run {
            Log.v(TAG, "Attempting to re-acquire display...")
            initializeVirtualDisplay()
            return
        }

        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
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
                    OcrQueue.enqueue(this, PendingOcrItem(uri.toString(), packageName, System.currentTimeMillis()))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save screenshot", e)
            }
        }
        bitmap.recycle()
    }

    private fun calculatePHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        var avg = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                avg += (scaled.getPixel(x, y) and 0xFF)
            }
        }
        avg /= 64
        var hash = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if ((scaled.getPixel(x, y) and 0xFF) >= avg) {
                    hash = hash or (1L shl (y * 8 + x))
                }
            }
        }
        scaled.recycle()
        return hash
    }

    private fun calculateHammingDistance(h1: Long, h2: Long): Int {
        return java.lang.Long.bitCount(h1 xor h2)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Screenshot Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        val (title, content) = when {
            isProjectionRevoked -> "Capture Expired" to "Screen capture permission revoked by system. Tap to restart."
            !isScreenOn -> "Capture Paused" to "Screen is off. Waiting for wake..."
            mediaProjection == null -> "Service Waiting" to "Tap to start capturing screen context."
            else -> "OmniView Active" to "Monitoring screen context..."
        }

        val priority = if (isProjectionRevoked) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(priority)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service onDestroy - cleaning up")
        isRunning = false
        unregisterReceiver(screenStateReceiver)
        handler.removeCallbacksAndMessages(null)
        releaseVirtualDisplay()
        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}