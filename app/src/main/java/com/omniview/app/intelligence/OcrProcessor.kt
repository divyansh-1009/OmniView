package com.omniview.app.intelligence

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.omniview.app.storage.ExtractedContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import kotlin.coroutines.resume

data class CanonicalMomentText(
    val timestamp: Long,
    val screenshotPath: String,
    val accessibilityJsonPath: String?,
    val packageName: String,
    val text: String
)

class OcrProcessor(private val context: Context) {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractText(screenshotPath: String): String {
        val bitmap = BitmapFactory.decodeFile(screenshotPath) ?: return ""
        return try {
            recognize(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun extractCanonicalMoment(
        screenshotPath: String,
        accessibilityJsonPath: String? = null
    ): CanonicalMomentText? {
        val screenshot = File(screenshotPath)
        if (!screenshot.exists() || !screenshot.isFile) return null

        val accessibility = parseAccessibilitySnapshot(accessibilityJsonPath)
        val packageName = accessibility.packageName ?: inferPackageName(screenshot) ?: "unknown"

        if (accessibility.isSecureWindow || shouldSkipPackage(packageName)) {
            Log.i(TAG, "Skipping Recall OCR for sensitive screen: package=$packageName secure=${accessibility.isSecureWindow}")
            return null
        }

        val ocrText = extractText(screenshot.absolutePath)
        val mergedText = mergeText(ocrText, accessibility.text)

        if (mergedText.isBlank()) {
            Log.i(TAG, "No OCR/accessibility text found for ${screenshot.name}")
            return null
        }

        return CanonicalMomentText(
            timestamp = inferTimestamp(screenshot),
            screenshotPath = screenshot.absolutePath,
            accessibilityJsonPath = accessibilityJsonPath,
            packageName = packageName,
            text = mergedText
        )
    }

    private suspend fun processPendingItem(item: PendingOcrItem): ExtractedContext? {
        Log.d(TAG, "Starting OCR for ${item.uri} (app=${item.app})")

        return try {
            val uri = Uri.parse(item.uri)
            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: run {
                Log.w(TAG, "Could not open bitmap for ${item.uri}")
                return null
            }

            val text = try {
                recognize(bitmap)
            } finally {
                bitmap.recycle()
            }

            val cleaned = text.lines()
                .mapNotNull { ContextCleaner.cleanBlock(it) }
                .distinct()
                .joinToString("\n")
                .trim()

            if (cleaned.isBlank()) {
                Log.i(TAG, "OCR complete for ${item.app}: no usable text found.")
                null
            } else {
                Log.i(TAG, "OCR success [${item.app}]: extracted ${cleaned.length} chars.")
                ExtractedContext(
                    app = item.app,
                    text = cleaned,
                    source = "ocr",
                    timestamp = item.timestamp
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error processing ${item.uri}", e)
            null
        }
    }

    private suspend fun recognize(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (cont.isActive) cont.resume(visionText.text)
                }
                .addOnFailureListener { error ->
                    Log.e(TAG, "ML Kit text recognition failed", error)
                    if (cont.isActive) cont.resume("")
                }
        }
    }

    private fun parseAccessibilitySnapshot(path: String?): AccessibilitySnapshot {
        if (path.isNullOrBlank()) return AccessibilitySnapshot()

        val file = File(path)
        if (!file.exists() || !file.isFile) return AccessibilitySnapshot()

        return try {
            val rawJson = file.readText()
            val root = JSONTokener(rawJson).nextValue()
            val text = LinkedHashSet<String>()
            collectAccessibilityText(root, text)

            AccessibilitySnapshot(
                packageName = findPackageName(root),
                isSecureWindow = containsFlagSecure(root),
                text = text.joinToString("\n")
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse accessibility JSON: ${file.absolutePath}", e)
            AccessibilitySnapshot()
        }
    }

    private fun collectAccessibilityText(node: Any?, out: MutableSet<String>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    when {
                        key in TEXT_KEYS && value is String -> normalizeText(value)?.let(out::add)
                        value is JSONObject || value is JSONArray -> collectAccessibilityText(value, out)
                    }
                }
            }
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    collectAccessibilityText(node.opt(index), out)
                }
            }
        }
    }

    private fun findPackageName(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    if (key in PACKAGE_KEYS && value is String && value.isNotBlank()) {
                        return value.trim()
                    }
                    if (value is JSONObject || value is JSONArray) {
                        findPackageName(value)?.let { return it }
                    }
                }
            }
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    findPackageName(node.opt(index))?.let { return it }
                }
            }
        }
        return null
    }

    private fun containsFlagSecure(node: Any?): Boolean {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    val normalizedKey = key.lowercase()
                    if (normalizedKey.contains("secure") && value is Boolean && value) return true
                    if (value is Number && normalizedKey.contains("flags")) {
                        if (value.toInt() and WindowManager.LayoutParams.FLAG_SECURE != 0) return true
                    }
                    if (value is String && value.contains("FLAG_SECURE", ignoreCase = true)) return true
                    if (value is JSONObject || value is JSONArray) {
                        if (containsFlagSecure(value)) return true
                    }
                }
            }
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    if (containsFlagSecure(node.opt(index))) return true
                }
            }
        }
        return false
    }

    private fun mergeText(ocrText: String, accessibilityText: String): String {
        val merged = LinkedHashSet<String>()
        sequenceOf(ocrText, accessibilityText)
            .flatMap { it.lines().asSequence() }
            .mapNotNull(::normalizeText)
            .forEach(merged::add)
        return merged.joinToString("\n")
    }

    private fun normalizeText(raw: String): String? {
        val normalized = raw
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (normalized.length < 2) return null
        if (!normalized.any { it.isLetterOrDigit() }) return null
        return normalized
    }

    private fun inferTimestamp(screenshot: File): Long {
        val match = TIMESTAMP_REGEX.find(screenshot.nameWithoutExtension)
        return match?.value?.toLongOrNull() ?: screenshot.lastModified()
    }

    private fun inferPackageName(screenshot: File): String? {
        val tokens = screenshot.nameWithoutExtension.split('_', '-', '.')
        return tokens.firstOrNull { it.contains('.') && it.length > 4 }
    }

    companion object {
        private const val TAG = "OmniView:OcrProcessor"

        private val TEXT_KEYS = setOf(
            "text",
            "nodeText",
            "contentDescription",
            "description",
            "label",
            "viewIdResourceName",
            "resourceId",
            "resourceName"
        )
        private val PACKAGE_KEYS = setOf("packageName", "package", "pkg", "appPackage")
        private val TIMESTAMP_REGEX = Regex("""\d{10,}""")
        private val BANKING_PATTERNS = listOf(
            Regex(""".*\.banking\..*""", RegexOption.IGNORE_CASE),
            Regex(""".*\.bank\..*""", RegexOption.IGNORE_CASE)
        )

        suspend fun process(context: Context, item: PendingOcrItem): ExtractedContext? {
            return OcrProcessor(context).processPendingItem(item)
        }

        fun shouldSkipPackage(packageName: String): Boolean {
            if (packageName == "com.android.settings") return true
            return BANKING_PATTERNS.any { it.matches(packageName) }
        }
    }
}

private data class AccessibilitySnapshot(
    val packageName: String? = null,
    val isSecureWindow: Boolean = false,
    val text: String = ""
)
