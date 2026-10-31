package com.example.callsaver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Одна точка записи входящего номера: журнал в приложении, контакт и отправка на сервер.
 * Прямой захват (Android 7–9 или роль фильтра звонков) и чтение журнала звонков
 * относятся к одному и тому же звонку и не создают вторую заявку.
 */
object IncomingCallRecorder {
    private const val TAG = "IncomingCall"
    private const val PREFS = "call_logs"
    private const val KEY_LAST_CALL_LOG_MS = "last_call_log_ms"
    private const val KEY_SCREENED = "screened_calls"
    private const val KEY_LOGGED = "logged_calls"
    private const val PAIR_WINDOW_MS = 120_000L
    private const val RETENTION_MS = 24 * 60 * 60 * 1000L

    fun ensureCallLogCursor(context: Context) {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_LAST_CALL_LOG_MS)) {
            prefs.edit().putLong(KEY_LAST_CALL_LOG_MS, System.currentTimeMillis()).apply()
        }
    }

    fun recordDirect(context: Context, rawNumber: String, type: String) {
        val number = normalize(rawNumber) ?: return
        val key = keyOf(number)
        val now = System.currentTimeMillis()
        synchronized(this) {
            prune(context, now)
            val loggedAt = logged(context)[key]
            if (loggedAt != null && now - loggedAt < PAIR_WINDOW_MS) {
                Log.d(TAG, "Номер $number уже взят из журнала")
                return
            }
            val screenedAt = screened(context)[key]
            if (screenedAt != null && now - screenedAt < PAIR_WINDOW_MS) {
                Log.d(TAG, "Номер $number уже записан")
                return
            }
            remember(context, KEY_SCREENED, key, now)
            persist(context, number, type)
        }
    }

    fun consumeRecentCallLog(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureCallLogCursor(context)
        val prefs = prefs(context)
        val since = prefs.getLong(KEY_LAST_CALL_LOG_MS, 0L)
        val cursor = try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} > ?",
                arrayOf(since.toString()),
                "${CallLog.Calls.DATE} ASC"
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Нет доступа к журналу звонков", e)
            return
        } ?: return

        var newest = since
        cursor.use {
            val numberIdx = it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val typeIdx = it.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIdx = it.getColumnIndexOrThrow(CallLog.Calls.DATE)
            while (it.moveToNext()) {
                val date = it.getLong(dateIdx)
                if (date > newest) newest = date
                val callType = it.getInt(typeIdx)
                val incoming = callType == CallLog.Calls.INCOMING_TYPE ||
                    callType == CallLog.Calls.MISSED_TYPE ||
                    callType == CallLog.Calls.REJECTED_TYPE
                if (!incoming) continue
                val number = it.getString(numberIdx) ?: continue
                recordFromCallLog(context, number, date)
            }
        }
        if (newest > since) {
            prefs.edit().putLong(KEY_LAST_CALL_LOG_MS, newest).apply()
        }
    }

    private fun recordFromCallLog(context: Context, rawNumber: String, date: Long) {
        val number = normalize(rawNumber) ?: return
        val key = keyOf(number)
        synchronized(this) {
            val alreadyLogged = logged(context)[key]
            if (alreadyLogged != null && date <= alreadyLogged) return
            val screenedAt = screened(context)[key]
            if (screenedAt != null && kotlin.math.abs(screenedAt - date) < PAIR_WINDOW_MS) {
                remember(context, KEY_LOGGED, key, date)
                Log.d(TAG, "Журнал подтвердил уже записанный номер $number")
                return
            }
            remember(context, KEY_LOGGED, key, date)
            persist(context, number, "Входящий")
        }
    }

    private fun persist(context: Context, number: String, type: String) {
        val prefs = prefs(context)
        val calls = prefs.getString("calls", "") ?: ""
        val currentId = prefs.getInt("call_count", 0)
        val newName = "Номер ${currentId + 1}"
        val newCall = "$newName: $number ($type)"
        prefs.edit()
            .putString("calls", "$calls\n$newCall")
            .putInt("call_count", currentId + 1)
            .apply()
        Log.d(TAG, "Сохранён $newCall")

        saveContact(context, number)

        val shopPhoneNumber = prefs.getString("shop_phone_number", null)
        if (!shopPhoneNumber.isNullOrBlank()) {
            sendPhoneNumberToBackend(number, shopPhoneNumber)
        } else {
            Log.e(TAG, "Номер магазина не задан, на сервер не отправлено")
        }
    }

    private fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "-1" || trimmed == "-2") return null
        val digits = trimmed.count { it.isDigit() }
        if (digits < 5) return null
        return trimmed
    }

    private fun keyOf(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length > 10) digits.takeLast(10) else digits
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun screened(context: Context) = readMap(context, KEY_SCREENED)

    private fun logged(context: Context) = readMap(context, KEY_LOGGED)

    private fun readMap(context: Context, key: String): Map<String, Long> {
        val raw = prefs(context).getString(key, "") ?: return emptyMap()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split("|", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val time = parts[1].toLongOrNull() ?: return@mapNotNull null
            parts[0] to time
        }.toMap()
    }

    private fun remember(context: Context, key: String, numberKey: String, time: Long) {
        val updated = readMap(context, key).toMutableMap()
        updated[numberKey] = time
        writeMap(context, key, updated)
    }

    private fun prune(context: Context, now: Long) {
        for (key in listOf(KEY_SCREENED, KEY_LOGGED)) {
            val fresh = readMap(context, key).filterValues { now - it < RETENTION_MS }
            writeMap(context, key, fresh)
        }
    }

    private fun writeMap(context: Context, key: String, values: Map<String, Long>) {
        val raw = values.entries.joinToString("\n") { "${it.key}|${it.value}" }
        prefs(context).edit().putString(key, raw).apply()
    }
}
