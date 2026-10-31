package com.example.callsaver

import android.net.Uri
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import kotlin.concurrent.thread

/**
 * На Android 10 и новее номер входящего получает приложение,
 * которому пользователь выдал роль фильтра звонков. Звонок при этом не блокируется.
 */
class IncomingCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            response.setSilenceCall(false)
        }
        respondToCall(callDetails, response.build())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            callDetails.callDirection != Call.Details.DIRECTION_INCOMING
        ) {
            return
        }

        val number = numberFrom(callDetails) ?: return
        val appContext = applicationContext
        thread(name = "save-incoming", isDaemon = true) {
            Log.d(TAG, "Входящий номер из фильтра звонков: $number")
            IncomingCallRecorder.recordDirect(appContext, number, "Входящий")
        }
    }

    private fun numberFrom(callDetails: Call.Details): String? {
        val handle = callDetails.handle ?: return null
        val raw = handle.schemeSpecificPart ?: return null
        return Uri.decode(raw)
    }

    companion object {
        private const val TAG = "CallScreening"
    }
}
