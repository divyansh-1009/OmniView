package com.example.omniview.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.nio.ByteBuffer

class ScreenshotService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private lateinit var imageReader: ImageReader

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    private var lastPHash: LongArray? = null

    companion object {
        private const val CHANNEL_ID = "ScreenshotServiceChannel"
        private const val NOTIFICATION_ID = 1

        private const val HASH_SIZE = 8

        private const val RESIZE = 32

        private const val SIMILARITY_THRESHOLD = 10
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        startForeground(NOTIFICATION_ID, createNotification())

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: return START_NOT_STICKY
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("data")
        }
        if (data == null) return START_NOT_STICKY

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        startCapturing()

        return START_STICKY
    }

    private fun startCapturing() {

        val metrics = resources.displayMetrics
        val density = metrics.densityDpi
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                virtualDisplay?.release()
                virtualDisplay = null
                serviceScope.cancel()
            }
        }, Handler(Looper.getMainLooper()))

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null
        )

        serviceScope.launch {
            while (isActive) {
                captureScreenshot()
                delay(10_000)
            }
        }
    }

    private fun captureScreenshot() {

        val image = imageReader.acquireLatestImage() ?: return

        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )

        bitmap.copyPixelsFromBuffer(buffer)

        image.close()

        val currentHash = computePHash(bitmap)
        val previous = lastPHash

        if (previous != null) {
            val distance = hammingDistance(previous, currentHash)
            Log.d("ScreenshotService", "Hamming distance from last screenshot: $distance")
            if (distance <= SIMILARITY_THRESHOLD) {
                Log.d("ScreenshotService", "Screenshot too similar (distance=$distance), skipping save")
                bitmap.recycle()
                return
            }
        }

        lastPHash = currentHash
        saveBitmap(bitmap)

        Log.d("ScreenshotService", "Screenshot saved at ${System.currentTimeMillis()}")
    }

    private fun saveBitmap(bitmap: Bitmap) {

        val filename = "screenshot_${System.currentTimeMillis()}.png"

        val resolver = contentResolver

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ScreenshotService")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val imageUri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        )

        imageUri?.let { uri ->
            resolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }

            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }
    }

    private fun computePHash(bitmap: Bitmap): LongArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, RESIZE, RESIZE, true)
        val grey = Array(RESIZE) { y ->
            DoubleArray(RESIZE) { x ->
                val pixel = scaled.getPixel(x, y)
                0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)
            }
        }
        if (scaled != bitmap) scaled.recycle()

        val dct = applyDCT(grey)

        val coeffs = mutableListOf<Double>()
        for (y in 0 until HASH_SIZE) {
            for (x in 0 until HASH_SIZE) {
                if (x == 0 && y == 0) continue  
                coeffs.add(dct[y][x])
            }
        }

        val sorted = coeffs.sorted()
        val median = sorted[sorted.size / 2]

        var hash = 0L
        var bit = 0
        for (y in 0 until HASH_SIZE) {
            for (x in 0 until HASH_SIZE) {
                if (x == 0 && y == 0) {
                    bit++
                    continue
                }
                if (dct[y][x] >= median) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }

        return longArrayOf(hash)
    }

    private fun applyDCT(input: Array<DoubleArray>): Array<DoubleArray> {
        val n = input.size
        val output = Array(n) { DoubleArray(n) }
        for (u in 0 until n) {
            for (v in 0 until n) {
                var sum = 0.0
                for (i in 0 until n) {
                    for (j in 0 until n) {
                        sum += input[i][j] *
                                Math.cos((2.0 * i + 1) * u * Math.PI / (2.0 * n)) *
                                Math.cos((2.0 * j + 1) * v * Math.PI / (2.0 * n))
                    }
                }
                val cu = if (u == 0) 1.0 / Math.sqrt(2.0) else 1.0
                val cv = if (v == 0) 1.0 / Math.sqrt(2.0) else 1.0
                output[u][v] = (2.0 / n) * cu * cv * sum
            }
        }
        return output
    }

    private fun hammingDistance(a: LongArray, b: LongArray): Int {
        return java.lang.Long.bitCount(a[0] xor b[0])
    }

    private fun createNotification(): Notification {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screenshot Service",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screenshot Service Running")
            .setContentText("Capturing screen every 10 seconds")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        virtualDisplay?.release()
        mediaProjection?.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}