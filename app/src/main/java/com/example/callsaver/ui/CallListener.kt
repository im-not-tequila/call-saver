package com.example.callsaver.ui

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import com.example.callsaver.IncomingCallRecorder

@Suppress("DEPRECATION")
class CallListener(private val context: android.content.Context) : PhoneStateListener() {
    private val handler = Handler(Looper.getMainLooper())
    private val readCallLogSoon = Runnable { IncomingCallRecorder.consumeRecentCallLog(context) }
    private val readCallLogLater = Runnable { IncomingCallRecorder.consumeRecentCallLog(context) }

    override fun onCallStateChanged(state: Int, incomingNumber: String?) {
        super.onCallStateChanged(state, incomingNumber)

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                Log.d(TAG, "Входящий звонок от: $incomingNumber")
                if (!incomingNumber.isNullOrBlank()) {
                    IncomingCallRecorder.recordDirect(context, incomingNumber, "Входящий")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    scheduleCallLogRead()
                }
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    scheduleCallLogRead()
                }
            }
        }
    }

    fun dispose() {
        handler.removeCallbacks(readCallLogSoon)
        handler.removeCallbacks(readCallLogLater)
    }

    private fun scheduleCallLogRead() {
        handler.removeCallbacks(readCallLogSoon)
        handler.removeCallbacks(readCallLogLater)
        handler.postDelayed(readCallLogSoon, 1_000)
        handler.postDelayed(readCallLogLater, 3_000)
    }

    companion object {
        private const val TAG = "CallListener"
    }
}
