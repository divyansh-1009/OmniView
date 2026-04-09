package com.example.omniview.embedding

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Wraps the MobileBERT TFLite model.  Tokenisation is handled separately by
 * [BertTokenizer]; this class is responsible only for inference.
 *
 * Model contract (verify with [printTensorInfo] for your specific file):
 *   Input  0 → input_ids      int32  [MAX_LEN]
 *   Input  1 → attention_mask int32  [MAX_LEN]
 *   Output 0 → sequence_output float32 [1, MAX_LEN, EMBEDDING_DIM]
 *
 * Asset required: app/src/main/assets/mobilebert.tflite
 *   Download from https://tfhub.dev/google/lite-model/mobilebert/1/default/1
 */
class MobileBertEmbedder(context: Context) : Closeable {

    companion object {
        private const val TAG = "OmniView:Embedder"
        const val EMBEDDING_DIM = 512
        private const val MODEL_ASSET = "mobilebert.tflite"
    }

    private val interpreter: Interpreter

    init {
        val opts = Interpreter.Options().apply { numThreads = 2 }
        interpreter = Interpreter(loadModelFile(context, MODEL_ASSET), opts)
        Log.i(TAG, "MobileBERT model loaded")
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Runs inference on pre-built token arrays and returns the mean-pooled
     * 512-float sentence embedding.
     *
     * Mirrors MobileBertManager.generateEmbedding() exactly:
     *   output shape [1, MAX_LEN, 512] → mean-pool over the MAX_LEN axis.
     */
    fun generateEmbedding(inputIds: IntArray, attentionMask: IntArray): FloatArray {
        val output = Array(1) { Array(BertTokenizer.MAX_LEN) { FloatArray(EMBEDDING_DIM) } }

        interpreter.runForMultipleInputsOutputs(
            arrayOf(inputIds, attentionMask),
            mapOf(0 to output)
        )

        return meanPooling(output[0])
    }

    /** Logs all tensor names, shapes, and dtypes — call once when testing a new model. */
    fun printTensorInfo() {
        repeat(interpreter.inputTensorCount) { i ->
            val t = interpreter.getInputTensor(i)
            Log.d(TAG, "Input[$i]  name=${t.name()} shape=${t.shape().contentToString()} dtype=${t.dataType()}")
        }
        repeat(interpreter.outputTensorCount) { i ->
            val t = interpreter.getOutputTensor(i)
            Log.d(TAG, "Output[$i] name=${t.name()} shape=${t.shape().contentToString()} dtype=${t.dataType()}")
        }
    }

    override fun close() = interpreter.close()

    // ── Private ───────────────────────────────────────────────────────────────

    /**
     * Memory-maps the TFLite model file from assets without decompressing it.
     * Equivalent to FileUtil.loadMappedFile() but with no extra dependency.
     */
    private fun loadModelFile(context: Context, fileName: String): MappedByteBuffer {
        val fd = context.assets.openFd(fileName)
        return FileInputStream(fd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY,
            fd.startOffset,
            fd.declaredLength
        )
    }

    /**
     * Averages all MAX_LEN token vectors into a single EMBEDDING_DIM-float vector.
     * Identical to MobileBertManager.meanPooling().
     */
    private fun meanPooling(tokens: Array<FloatArray>): FloatArray {
        val result = FloatArray(EMBEDDING_DIM)
        for (token in tokens) {
            for (i in 0 until EMBEDDING_DIM) result[i] += token[i]
        }
        for (i in 0 until EMBEDDING_DIM) result[i] /= tokens.size
        return result
    }
}
