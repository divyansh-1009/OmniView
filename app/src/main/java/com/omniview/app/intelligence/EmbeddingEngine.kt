package com.omniview.app.intelligence

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tflite.java.TfLite
import org.tensorflow.lite.InterpreterApi
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

/**
 * Wraps the EmbeddingGemma TFLite model for on-device text embedding inference.
 *
 * Lifecycle:
 *   1. Construct with Application context → model is loaded from assets
 *   2. Call [generateEmbedding] for each text to embed
 *   3. Call [close] when done (e.g. after a batch) to free native resources
 *
 * Must be used from a background thread (Dispatchers.IO).
 */
class EmbeddingEngine(context: Context) {

    companion object {
        private const val TAG = "OmniView:EmbEngine"
        private const val MODEL_ASSET = "embeddingmodels/embeddinggemma_512.tflite"
        private const val MODEL_CACHE = "embeddinggemma_512.tflite"
    }

    private val tokenizer = WordPieceTokenizer(context)
    private val interpreter: InterpreterApi
    private var embeddingDim: Int = -1

    init {
        // Standard TFLite Interpreter requires mapping the file
        val modelFile = ensureModelFile(context)
        Log.i(TAG, "Loading model from ${modelFile.absolutePath} (${modelFile.length() / 1024}KB)")

        // Initialize Play Services TFLite dynamically (we are safely in Dispatchers.IO in the Worker)
        Log.i(TAG, "Initializing Google Play Services TFLite...")
        val initTask = TfLite.initialize(context)
        Tasks.await(initTask)
        
        val fileInputStream = FileInputStream(modelFile)
        val fileChannel = fileInputStream.channel
        val mappedByteBuffer: MappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, modelFile.length())

        val options = InterpreterApi.Options()
        // Force the engine to strictly use the dynamically updated systemic Play Services APIs
        options.setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY)
        
        interpreter = InterpreterApi.create(mappedByteBuffer, options)
        
        // Force the Play Services framework to physically allocate memory mapping before we query the native shapes!
        interpreter.allocateTensors()
        
        // Discover embedding dimension directly from the tensor shape
        val shape = interpreter.getOutputTensor(0).shape() // Usually [batch, sequence, dim] or [batch, dim]
        embeddingDim = shape[shape.size - 1]
        
        Log.i(TAG, "Interpreter created successfully. Discovered embedding dimension: $embeddingDim")
    }

    /**
     * Generates an L2-normalised embedding vector for the given text.
     *
     * @return a [FloatArray] of dimension [embeddingDim] (discovered on first call).
     */
    fun generateEmbedding(text: String): FloatArray {
        val tokenized = tokenizer.tokenize(text)

        // Native TFLite Java Interpreter strictly maps ND arrays
        // Input sequence must be [1, sequence_length]
        val inputIds = arrayOf(tokenized.inputIds)
        val attentionMask = arrayOf(tokenized.attentionMask)
        
        // Output embedding for sequence is [batch_size, sequence_length, embedding_dim] i.e. [1, 128, 384]
        val outputBuffer = Array(1) { Array(WordPieceTokenizer.MAX_SEQ_LENGTH) { FloatArray(embeddingDim) } }
        
        // Gemma embedding models typically take input_ids at 0 and attention_mask at 1
        val inputs = arrayOf<Any>(inputIds, attentionMask)
        val outputs = mapOf(0 to outputBuffer)

        // Run inference
        interpreter.runForMultipleInputsOutputs(inputs, outputs)

        // Extract raw vector via [CLS] pooling (the very first token representing the whole sequence)
        val embedding = outputBuffer[0][0]

        // L2-normalise so cosine similarity = dot product
        l2Normalize(embedding)
        return embedding
    }

    /**
     * Returns the dimensionality of the embedding vectors produced by the model.
     * Only valid after at least one call to [generateEmbedding].
     */
    fun getEmbeddingDim(): Int = embeddingDim

    /** Release native model resources. */
    fun close() {
        try {
            interpreter.close()
            Log.i(TAG, "Interpreter closed")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing CompiledModel", e)
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun ensureModelFile(context: Context): File {
        val cacheFile = File(context.cacheDir, MODEL_CACHE)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return cacheFile
        }
        Log.i(TAG, "Copying model from assets to cache...")
        context.assets.open(MODEL_ASSET).use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output, bufferSize = 8192)
            }
        }
        Log.i(TAG, "Model copied: ${cacheFile.length() / (1024 * 1024)} MB")
        return cacheFile
    }

    private fun l2Normalize(vec: FloatArray) {
        var sumSq = 0f
        for (v in vec) sumSq += v * v
        val norm = sqrt(sumSq)
        if (norm > 1e-12f) {
            for (i in vec.indices) vec[i] /= norm
        }
    }
}
