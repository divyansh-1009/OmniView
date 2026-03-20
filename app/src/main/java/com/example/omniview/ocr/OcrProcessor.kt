package com.example.omniview.ocr

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.example.omniview.model.ExtractedContext
import com.example.omniview.processing.ContextCleaner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object OcrProcessor {

    private const val TAG = "OmniView:OcrProcessor"

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Runs ML Kit text recognition on a queued screenshot URI.
     * Returns null if the image has no recognisable text or an error occurs.
     *
     * Must be called from a background coroutine (Dispatchers.IO).
     */
    suspend fun process(context: Context, item: PendingOcrItem): ExtractedContext? {
        Log.d(TAG, "Starting OCR for ${item.uri} (app=${item.app})")
        
        return try {
            val uri = Uri.parse(item.uri)
            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: run {
                Log.w(TAG, "Could not open bitmap for ${item.uri}")
                return null
            }

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            Log.v(TAG, "InputImage created from bitmap (${bitmap.width}x${bitmap.height})")

            suspendCoroutine { cont ->
                recognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        bitmap.recycle()
                        val text = visionText.textBlocks
                            .mapNotNull { ContextCleaner.cleanBlock(it.text) }
                            .distinct()
                            .joinToString("\n")
                            .trim()
                        
                        if (text.isBlank()) {
                            Log.i(TAG, "OCR complete for ${item.app}: No text found.")
                            cont.resume(null)
                        } else {
                            Log.i(TAG, "OCR Success [${item.app}]: Extracted ${text.length} chars.")
                            Log.v(TAG, "Extracted text: ${text.take(300)}...")
                            cont.resume(
                                ExtractedContext(
                                    app = item.app,
                                    text = text,
                                    source = "ocr",
                                    timestamp = item.timestamp
                                )
                            )
                        }
                    }
                    .addOnFailureListener { e ->
                        bitmap.recycle()
                        Log.e(TAG, "ML Kit recognition failed for ${item.uri}", e)
                        cont.resume(null)
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error processing ${item.uri}", e)
            null
        }
    }
}
