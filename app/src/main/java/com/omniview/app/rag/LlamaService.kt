package com.omniview.app.rag

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.omniview.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class LlamaService : Service() {

    companion object {
        private const val TAG = "OmniView:LlamaService"
        private const val NOTIFICATION_ID = 404
        private const val CHANNEL_ID = "llama_service_channel"

        const val ACTION_START_AND_LOAD = "com.omniview.app.rag.action.START_AND_LOAD"
        const val ACTION_STOP = "com.omniview.app.rag.action.STOP"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stopping LlamaService and releasing model")
            LlamaEngine.getInstance().release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START_AND_LOAD) {
            startForeground(NOTIFICATION_ID, buildNotification())
            
            // Load the model in the background if not already loaded
            if (!LlamaEngine.getInstance().isReady()) {
                serviceScope.launch {
                    LlamaEngine.getInstance().load(this@LlamaService) { success ->
                        if (success) {
                            Log.i(TAG, "LlamaEngine loaded successfully in Foreground Service")
                        } else {
                            Log.e(TAG, "Failed to load LlamaEngine in Foreground Service")
                        }
                    }
                }
            }
        }
        
        // Restart the service if it gets killed (to keep the notification, though we'd have to reload Llama)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // We don't need IPC bound bindings since it's same process
    }

    override fun onDestroy() {
        super.onDestroy()
        LlamaEngine.getInstance().release()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Model Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the AI Model cached in RAM for instant access"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OmniView AI is loaded")
            .setContentText("Keeping model in RAM for instant answers")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
