package com.omniview.app.ui
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.os.Build
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
import com.omniview.app.storage.AppStateManager
import com.omniview.app.intelligence.OcrWorkScheduler
import com.omniview.app.intelligence.EmbeddingWorkScheduler
import com.omniview.app.ingestion.ScreenshotService
import com.omniview.app.ui.theme.OmniViewTheme
import com.omniview.app.ingestion.ScreenshotService.Companion.ACTION_RESTART_CAPTURE

class MainActivity : ComponentActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var appStateManager: AppStateManager

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenshotService::class.java).apply {
                    putExtra("resultCode", result.resultCode)
                    putExtra("data", result.data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
            }
        }
    
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        appStateManager = AppStateManager(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

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
                            onOpenAccessibility = { openAccessibilitySettings() },
                            onRequestNotifications = { 
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            onRunOcrNow = { runOcrNow() },
                            onRunEmbeddingNow = { runEmbeddingNow() },
                            appStateManager = appStateManager
                        )
                    }
                }
            }
        }

        // Handle 'Restart Capture' tap from the expired-projection notification
        if (intent?.action == ACTION_RESTART_CAPTURE) {
            requestScreenCapture()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_RESTART_CAPTURE) {
            requestScreenCapture()
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
        } catch (_: Exception) {
            // Fallback to general settings if specific path fails
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)
        }
    }

    private fun runOcrNow() {
        OcrWorkScheduler.scheduleNow(this)
    }

    private fun runEmbeddingNow() {
        EmbeddingWorkScheduler.scheduleNow(this)
    }
}

@Composable
fun MainScreen(
    onStartCapture: () -> Unit,
    onPauseResume: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRunOcrNow: () -> Unit,
    onRunEmbeddingNow: () -> Unit,
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

        Spacer(modifier = Modifier.height(12.dp))

        Button(onClick = onOpenAccessibility) {
            Text("Enable Accessibility Service")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onRequestNotifications) {
                Text("Grant Notification Permission")
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        Text("Developer Tools", style = MaterialTheme.typography.labelLarge)
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = onRunOcrNow,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Text("Run OCR Processing Now")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onRunEmbeddingNow,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text("Run Embedding Generation Now")
        }
    }
}