package com.omniview.app.intelligence

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

class EmbeddingEngine(private val context: Context) : Closeable {

    private val interpreter: Interpreter

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(4)
            setUseXNNPACK(true)
        }
        
        interpreter = try {
            Interpreter(loadModelBuffer(), options)
        } catch (e: Exception) {
            throw FileNotFoundException(
                "EmbeddingGemma model not found or invalid.\n" +
                "Ensure embeddinggemma_512.tflite is placed in: app/src/main/assets/embedding_model/."
            )
        }
    }

    private fun loadModelBuffer(): MappedByteBuffer {
        // 1. Prefer bundled asset
        try {
            val assetFd = context.assets.openFd("embedding_model/embeddinggemma_512.tflite")
            val inputStream = FileInputStream(assetFd.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = assetFd.startOffset
            val declaredLength = assetFd.declaredLength
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength).also {
                // Do not close the FileChannel here, Interpreter needs it
            }
        } catch (_: Exception) { /* Not in assets */ }

        // 2. Runtime override in filesDir
        val overrideFile = File(context.filesDir, "models/embeddinggemma_512.tflite")
        if (overrideFile.exists()) {
            val inputStream = FileInputStream(overrideFile)
            val fileChannel = inputStream.channel
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
        }

        throw FileNotFoundException("Model file missing.")
    }

    fun embed(input: TokenizedInput): FloatArray {
        require(input.tokenIds.size == MAX_SEQUENCE_LENGTH) {
            "Expected $MAX_SEQUENCE_LENGTH token IDs, got ${input.tokenIds.size}"
        }
        require(input.attentionMask.size == MAX_SEQUENCE_LENGTH) {
            "Expected $MAX_SEQUENCE_LENGTH attention mask values, got ${input.attentionMask.size}"
        }

        val inputIds = arrayOf(input.tokenIds)
        val attentionMask = arrayOf(input.attentionMask)
        val rawOutput = Array(1) { FloatArray(RAW_EMBEDDING_DIMENSIONS) }

        interpreter.runForMultipleInputsOutputs(
            arrayOf(inputIds, attentionMask),
            mapOf(0 to rawOutput)
        )

        return normalize(rawOutput[0].copyOfRange(0, STORED_EMBEDDING_DIMENSIONS))
    }

    private fun normalize(vector: FloatArray): FloatArray {
        val squaredNorm = vector.sumOf { (it * it).toDouble() }
        val norm = sqrt(squaredNorm).toFloat()
        if (norm <= 0f) return vector
        return FloatArray(vector.size) { index -> vector[index] / norm }
    }

    override fun close() {
        interpreter.close()
    }

    companion object {
        const val MAX_SEQUENCE_LENGTH = 512
        const val RAW_EMBEDDING_DIMENSIONS = 768
        const val STORED_EMBEDDING_DIMENSIONS = 256
    }
}
