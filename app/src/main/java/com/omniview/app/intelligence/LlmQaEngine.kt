package com.omniview.app.intelligence

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.SessionConfig
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class LlmQaEngine(private val context: Context) {

    private val modelFile = File(context.filesDir, "models/gemma3_1b_int4.litertlm")

    fun answer(
        query: String,
        contextChunks: List<String>,
        onToken: (String) -> Unit
    ) {
        try {
            if (!modelFile.exists()) {
                throw FileNotFoundException("Gemma 3 1B LiteRT-LM model not found: ${modelFile.absolutePath}")
            }

            val session = engine(context, modelFile).createSession(SessionConfig())
            try {
                val latch = CountDownLatch(1)
                val failure = AtomicReference<Throwable?>()
                val prompt = buildPrompt(query, contextChunks)

                session.generateContentStream(
                    listOf(InputData.Text(prompt)),
                    object : ResponseCallback {
                        override fun onNext(response: String) {
                            onToken(response)
                        }

                        override fun onDone() {
                            latch.countDown()
                        }

                        override fun onError(throwable: Throwable) {
                            failure.set(throwable)
                            latch.countDown()
                        }
                    }
                )

                latch.await()
                failure.get()?.let { throw it }
            } finally {
                session.close()
            }
        } catch (error: Throwable) {
            Log.e(TAG, "LiteRT-LM answer generation failed", error)
            onToken("Recall Q&A is unavailable on this device until LiteRT-LM is initialized with ${modelFile.absolutePath}.")
        }
    }

    private fun buildPrompt(query: String, contextChunks: List<String>): String {
        val contextText = contextChunks.joinToString(separator = "\n---\n")
        return """
            $SYSTEM_PROMPT

            Context:
            $contextText

            Question: $query

            Answer:
        """.trimIndent()
    }

    companion object {
        private const val TAG = "OmniView:LlmQaEngine"
        private val engineLock = Any()
        @Volatile private var engineSingleton: Engine? = null

        private val SYSTEM_PROMPT = """
            You are a personal recall assistant. Use only the context below
            from the user's past on-device screen activity to answer.
            If the answer is not present in the context, say that you cannot find it.
        """.trimIndent()

        private fun engine(context: Context, modelFile: File): Engine {
            engineSingleton?.let { return it }
            return synchronized(engineLock) {
                engineSingleton ?: Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = Backend.CPU(numOfThreads = 4),
                        visionBackend = Backend.CPU(numOfThreads = 4),
                        audioBackend = Backend.CPU(numOfThreads = 4),
                        maxNumTokens = null,
                        cacheDir = File(context.cacheDir, "litertlm").absolutePath
                    )
                ).also { engine ->
                    engine.initialize()
                    engineSingleton = engine
                }
            }
        }
    }
}
