package com.example.omniview

import android.app.Application
import com.example.omniview.embedding.ObjectBoxStore
import com.example.omniview.embedding.PipelineManager

class OmniViewApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ObjectBoxStore.init(this)
        PipelineManager.init(this)
    }
}
