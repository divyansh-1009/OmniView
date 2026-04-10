package com.omniview.app.intelligence

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.omniview.app.storage.VectorStore
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

class EmbeddingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val forceRun = inputData.getBoolean(KEY_FORCE_RUN, false)
        // Only promote to foreground when force-run; periodic runs always get foreground
        setForeground(createForegroundInfo("Preparing Recall embeddings"))

        val vectorStore = VectorStore(applicationContext)
        val ocrProcessor = OcrProcessor(applicationContext)

        val tokenizer: Tokenizer
        val embeddingEngine: EmbeddingEngine
        try {
            tokenizer = Tokenizer(applicationContext)
            embeddingEngine = EmbeddingEngine(applicationContext)
        } catch (missingModel: FileNotFoundException) {
            Log.e(TAG, "Recall model asset missing", missingModel)
            vectorStore.close()
            return Result.retry()
        }

        return try {
            val captures = discoverUnprocessedScreenshots(vectorStore)
            if (captures.isEmpty()) {
                Log.i(TAG, "No unprocessed Recall screenshots found")
                return Result.success()
            }

            Log.i(TAG, "Processing ${captures.size} Recall screenshots")
            for ((captureIndex, capture) in captures.withIndex()) {
                setForeground(createForegroundInfo("Embedding ${captureIndex + 1}/${captures.size}"))

                val canonical = ocrProcessor.extractCanonicalMoment(
                    screenshotPath = capture.screenshot.absolutePath,
                    accessibilityJsonPath = capture.accessibilityJson?.absolutePath
                )

                if (canonical == null) {
                    vectorStore.markScreenshotHandled(
                        screenshotPath = capture.screenshot.absolutePath,
                        timestamp = capture.timestamp,
                        status = "skipped",
                        packageName = null
                    )
                    continue
                }

                val chunks = TextChunker.chunk(canonical.text)
                if (chunks.isEmpty()) {
                    vectorStore.markScreenshotHandled(
                        screenshotPath = capture.screenshot.absolutePath,
                        timestamp = canonical.timestamp,
                        status = "empty",
                        packageName = canonical.packageName
                    )
                    continue
                }

                chunks.forEachIndexed { chunkIndex, chunk ->
                    val tokenized = tokenizer.encodePassage(chunk)
                    val vector = embeddingEngine.embed(tokenized)
                    vectorStore.insert(
                        timestamp = canonical.timestamp,
                        screenshotPath = canonical.screenshotPath,
                        rawText = chunk,
                        chunkIndex = chunkIndex,
                        packageName = canonical.packageName,
                        embedding = vector
                    )
                }

                vectorStore.markScreenshotHandled(
                    screenshotPath = canonical.screenshotPath,
                    timestamp = canonical.timestamp,
                    status = "processed",
                    packageName = canonical.packageName
                )
            }

            Result.success()
        } catch (error: Exception) {
            Log.e(TAG, "Recall embedding job failed", error)
            Result.retry()
        } finally {
            tokenizer.close()
            embeddingEngine.close()
            vectorStore.close()
        }
    }

    private fun discoverUnprocessedScreenshots(vectorStore: VectorStore): List<PendingCapture> {
        val filesDir = applicationContext.filesDir
        val candidateDirs = listOf(
            File(filesDir, "screenshots"),
            File(filesDir, "recall/screenshots"),
            File(filesDir, "omniview/screenshots"),
            filesDir
        ).filter { it.exists() && it.isDirectory }

        return candidateDirs
            .flatMap { directory ->
                directory.walkTopDown()
                    .maxDepth(if (directory == filesDir) 2 else Int.MAX_VALUE)
                    .filter { it.isFile && it.extension.lowercase() in SCREENSHOT_EXTENSIONS }
                    .toList()
            }
            .distinctBy { it.absolutePath }
            .filter { !vectorStore.isScreenshotHandled(it.absolutePath) }
            .map { screenshot ->
                PendingCapture(
                    screenshot = screenshot,
                    accessibilityJson = findAccessibilityJson(screenshot),
                    timestamp = inferTimestamp(screenshot)
                )
            }
            .sortedBy { it.timestamp }
    }

    private fun findAccessibilityJson(screenshot: File): File? {
        val timestamp = inferTimestamp(screenshot).toString()
        val sameBase = File(screenshot.parentFile, "${screenshot.nameWithoutExtension}.json")
        if (sameBase.exists()) return sameBase

        val filesDir = applicationContext.filesDir
        val candidates = listOf(
            File(screenshot.parentFile?.parentFile, "accessibility/$timestamp.json"),
            File(filesDir, "accessibility/$timestamp.json"),
            File(filesDir, "recall/accessibility/$timestamp.json"),
            File(filesDir, "omniview/accessibility/$timestamp.json")
        )
        return candidates.firstOrNull { it.exists() && it.isFile }
    }

    private fun inferTimestamp(file: File): Long {
        return TIMESTAMP_REGEX.find(file.nameWithoutExtension)?.value?.toLongOrNull()
            ?: file.lastModified()
    }

    private fun createForegroundInfo(contentText: String): ForegroundInfo {
        createNotificationChannel()
        val notification = createNotification(contentText)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("OmniView Recall")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Recall Embedding Worker",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "OmniView:EmbeddingWorker"
        private const val WORK_NAME = "embedding_worker"
        private const val CHANNEL_ID = "RecallEmbeddingWorker"
        private const val NOTIFICATION_ID = 2001
        private val SCREENSHOT_EXTENSIONS = setOf("jpg", "jpeg", "png")
        private val TIMESTAMP_REGEX = Regex("""\d{10,}""")

        /** Set to true via inputData to bypass charging-only scheduling. */
        const val KEY_FORCE_RUN = "force_run"

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<EmbeddingWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}

private data class PendingCapture(
    val screenshot: File,
    val accessibilityJson: File?,
    val timestamp: Long
)
