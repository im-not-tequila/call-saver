package com.example.callsaver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == null ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            restartService(context)
        }
    }

    private fun restartService(context: Context) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, CallService::class.java))
        } catch (e: Exception) {
            Log.e("ServiceRestart", "Не удалось перезапустить сервис", e)
        }
    }
}
