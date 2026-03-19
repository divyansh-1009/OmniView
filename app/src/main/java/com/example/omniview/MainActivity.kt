package com.example.omniview

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.omniview.ingestion.AppStateManager
import com.example.omniview.ocr.OcrWorkScheduler
import com.example.omniview.service.ScreenshotService
import com.example.omniview.ui.SettingsActivity
import com.example.omniview.ui.theme.OmniViewTheme

class MainActivity : ComponentActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var appStateManager: AppStateManager

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenshotService::class.java).apply {
                    putExtra("resultCode", result.resultCode)
                    putExtra("data", result.data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        appStateManager = AppStateManager(this)

        setContent {
            OmniViewTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding(),
                        contentAlignment = Alignment.Center
                    ) {
                        MainScreen(
                            onStartCapture = { requestScreenCapture() },
                            onPauseResume = { togglePause() },
                            onOpenSettings = { openSettings() },
                            onOpenUsageAccess = { openUsageAccessSettings() },
                            onRunOcrNow = { runOcrNow() },
                            appStateManager = appStateManager
                        )
                    }
                }
            }
        }
    }

    private fun requestScreenCapture() {
        val captureIntent = projectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun togglePause() {
        if (appStateManager.isPaused()) {
            appStateManager.resumeCapture()
        } else {
            appStateManager.pauseCapture()
        }
        // Force recomposition
        recreate()
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun openUsageAccessSettings() {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback to general settings if specific path fails
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)
        }
    }

    private fun runOcrNow() {
        OcrWorkScheduler.scheduleNow(this)
    }
}

@Composable
fun MainScreen(
    onStartCapture: () -> Unit,
    onPauseResume: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onRunOcrNow: () -> Unit,
    appStateManager: AppStateManager
) {
    val isPaused = remember { mutableStateOf(appStateManager.isPaused()) }
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(24.dp)
    ) {
        Text(
            "OmniView Ingestion & OCR",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onStartCapture) {
            Text("Start Screenshot Service")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(onClick = onPauseResume) {
            Text(if (isPaused.value) "Resume Capture" else "Pause Capture")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(onClick = onOpenSettings) {
            Text("Settings (Blacklist)")
        }

        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            "Detection Permissions",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            "Required for app blacklisting to work properly",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(onClick = onOpenUsageAccess) {
            Text("Grant Usage Access")
        }

        Spacer(modifier = Modifier.height(48.dp))

        Text("Developer Tools", style = MaterialTheme.typography.labelLarge)
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = onRunOcrNow,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text("Run OCR Processing Now")
        }
    }
}