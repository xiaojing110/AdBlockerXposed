package com.adblocker.xposed.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adblocker.xposed.service.AdBlockService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("adblocker_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("enabled", true)) {
                val serviceIntent = Intent(context, AdBlockService::class.java)
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
