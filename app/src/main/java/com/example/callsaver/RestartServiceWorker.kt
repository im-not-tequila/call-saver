package com.example.callsaver

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

class RestartServiceWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        val context = applicationContext
        if (isServiceRunning(context, CallService::class.java)) {
            return Result.success()
        }
        return try {
            ContextCompat.startForegroundService(context, Intent(context, CallService::class.java))
            Result.success()
        } catch (e: Exception) {
            Log.e("RestartServiceWorker", "Не удалось перезапустить сервис", e)
            Result.retry()
        }
    }

    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}