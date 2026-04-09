package com.example.omniview.embedding

import android.content.Context
import android.util.Log
import io.objectbox.BoxStore

/**
 * Process-level singleton that holds the single [BoxStore] instance.
 *
 * Call [init] exactly once from [com.example.omniview.OmniViewApplication.onCreate].
 * The generated [MyObjectBox] class is created at compile time by the ObjectBox
 * Gradle plugin after the first successful build.
 */
object ObjectBoxStore {

    private const val TAG = "OmniView:ObjectBox"

    @Volatile
    private var _store: BoxStore? = null

    val store: BoxStore
        get() = _store
            ?: error("ObjectBoxStore not initialised — call init() in Application.onCreate()")

    fun init(context: Context) {
        if (_store != null) return
        synchronized(this) {
            if (_store != null) return
            _store = buildStore(context.applicationContext)
            Log.i(TAG, "Store ready (size on disk: ${_store!!.sizeOnDisk()} bytes)")
        }
    }

    fun close() {
        _store?.close()
        _store = null
    }

    private fun buildStore(context: Context): BoxStore {
        val clazz = try {
            Class.forName("com.example.omniview.embedding.MyObjectBox")
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException(
                "MyObjectBox not generated. Rebuild project to generate ObjectBox sources.",
                e
            )
        }
        return try {
            val builder = clazz.getMethod("builder").invoke(null)
            builder.javaClass.getMethod("androidContext", Context::class.java).invoke(builder, context)
            builder.javaClass.getMethod("build").invoke(builder) as BoxStore
        } catch (e: Throwable) {
            throw IllegalStateException("Failed to initialize ObjectBox store via MyObjectBox.", e)
        }
    }
}
