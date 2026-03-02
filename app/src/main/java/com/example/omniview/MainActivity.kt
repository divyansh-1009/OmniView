package com.example.omniview

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.omniview.service.ScreenshotService
import com.example.omniview.ui.SettingsActivity
import com.example.omniview.ui.theme.OmniViewTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.example.omniview.ingestion.AppStateManager

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
        // Force recomposition by recreating content
        recreate()
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }
}

@Composable
fun MainScreen(
    onStartCapture: () -> Unit,
    onPauseResume: () -> Unit,
    onOpenSettings: () -> Unit,
    appStateManager: AppStateManager
) {
    val isPaused = remember { mutableStateOf(appStateManager.isPaused()) }
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        StartCaptureButton(onClick = onStartCapture)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        PauseResumeButton(
            isPaused = isPaused.value,
            onClick = onPauseResume
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        SettingsButton(onClick = onOpenSettings)
    }
}

@Composable
fun StartCaptureButton(onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text("Start Screenshot Service")
    }
}

@Composable
fun PauseResumeButton(isPaused: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text(if (isPaused) "Resume Capture" else "Pause Capture")
    }
}

@Composable
fun SettingsButton(onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text("Settings")
    }
}