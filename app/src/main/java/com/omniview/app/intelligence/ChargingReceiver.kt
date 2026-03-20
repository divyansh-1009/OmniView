package com.omniview.app.intelligence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/**
 * Fires when the charger is physically plugged in.
 * If battery is already > 80%, immediately schedules OCR so screenshots
 * queued while unplugged are processed without waiting for the next capture.
 */
class ChargingReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "OmniView:Charging"
        private const val BATTERY_THRESHOLD = 80
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_CONNECTED) return

        val batteryIntent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return
        val battery = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val queueSize = OcrQueue.size(context)

        Log.i(TAG, "Charger connected — battery=$battery%, queue=$queueSize items")

        if (battery >= BATTERY_THRESHOLD && queueSize > 0) {
            Log.i(TAG, "Conditions met on plug-in — scheduling OCR immediately")
            OcrWorkScheduler.schedule(context)
        }
    }
}
