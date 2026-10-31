package com.example.callsaver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    Intent(context, CallService::class.java)
                )
            } catch (e: Exception) {
                android.util.Log.e("BootReceiver", "Не удалось запустить сервис", e)
            }
        }
    }
}