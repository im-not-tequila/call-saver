package com.example.callsaver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.callsaver.ui.CallListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class CallService : Service() {
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var callListener: CallListener
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val client = OkHttpClient()
    private var callLogObserver: ContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        IncomingCallRecorder.ensureCallLogCursor(this)
        Log.d(TAG, "Сервис создан")
        callListener = CallListener(this)
        startRepeatingRequest()
        try {
            schedulePeriodicRestart()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось запланировать периодический перезапуск", e)
        }
    }

    private fun startRepeatingRequest() {
        serviceScope.launch {
            while (isActive) {
                val shopPhoneNumber = getSharedPreferences("call_logs", Context.MODE_PRIVATE)
                    .getString("shop_phone_number", null)
                if (!shopPhoneNumber.isNullOrBlank()) {
                    sendPostRequest(shopPhoneNumber)
                } else {
                    Log.e(TAG, "shop_phone_number не найден в SharedPreferences")
                }
                delay(15_000)
            }
        }
    }

    private fun sendPostRequest(phoneNumber: String) {
        val url = "http://91.243.71.120/api/recent-leads/"
        val json = JSONObject().apply {
            put("shop_phone_number", phoneNumber)
        }

        val body = RequestBody.create("application/json".toMediaTypeOrNull(), json.toString())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        Log.d(TAG, "Отправка запроса: $url ${json}")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Ошибка запроса: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    Log.d(TAG, "Ответ от сервера: $responseData")

                    try {
                        val jsonArray = JSONArray(responseData)
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val number = obj.getString("phone_number")
                            if (number != phoneNumber) {
                                saveNumber(applicationContext, number, "Whatsapp")
                                saveContact(applicationContext, number)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка разбора JSON: ${e.message}")
                    }
                } else {
                    Log.e(TAG, "Ошибка ответа: ${response.code}")
                }
            }
        })
    }

    private fun isContactExists(context: Context, phoneNumber: String): Boolean {
        val resolver = context.contentResolver
        val cursor = resolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER} = ?",
            arrayOf(phoneNumber),
            null
        )

        val exists = cursor != null && cursor.moveToFirst()
        cursor?.close()
        return exists
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Call tracking service started")
        startAsForeground()

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            telephonyManager.listen(callListener, PhoneStateListener.LISTEN_CALL_STATE)
        } else {
            Log.e(TAG, "Нет разрешения на состояние телефона")
        }
        registerCallLogObserver()
        IncomingCallRecorder.consumeRecentCallLog(this)

        return START_STICKY
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        serviceScope.cancel()
        callLogObserver?.let { contentResolver.unregisterContentObserver(it) }
        callLogObserver = null
        if (::telephonyManager.isInitialized && ::callListener.isInitialized) {
            telephonyManager.listen(callListener, PhoneStateListener.LISTEN_NONE)
            callListener.dispose()
        }
        try {
            val once = OneTimeWorkRequestBuilder<RestartServiceWorker>()
                .setInitialDelay(10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                "restart-call-service-once",
                ExistingWorkPolicy.REPLACE,
                once
            )
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось запланировать перезапуск", e)
        }
        super.onDestroy()
        Log.d(TAG, "Сервис уничтожен, перезапуск поставлен в очередь")
    }

    private fun schedulePeriodicRestart() {
        val periodic = PeriodicWorkRequestBuilder<RestartServiceWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "restart-call-service",
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )
    }

    private fun startAsForeground() {
        val channelId = "call_tracking_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Call Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Мир Телевизоров")
            .setContentText("Наблюдение за входящими звонками...")
            .setSmallIcon(R.drawable.ic_call)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun registerCallLogObserver() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (callLogObserver != null) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                IncomingCallRecorder.consumeRecentCallLog(this@CallService)
            }
        }
        contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
        callLogObserver = observer
    }

    private fun saveNumber(context: Context, number: String, type: String) {
        val sharedPrefs = context.getSharedPreferences("call_logs", Context.MODE_PRIVATE)
        val editor = sharedPrefs.edit()

        val calls = sharedPrefs.getString("calls", "") ?: ""
        val currentId = sharedPrefs.getInt("call_count", 0)

        if (calls.contains(number)) {
            Log.d(TAG, "Номер $number уже сохранён, пропускаем.")
            return
        }

        val newName = "Номер ${currentId + 1}"
        val newCall = "$newName: $number ($type)"
        val updatedCalls = "$calls\n$newCall"

        editor.putString("calls", updatedCalls)
        editor.putInt("call_count", currentId + 1)
        editor.apply()

        Log.d(TAG, "Сохранён новый номер: $newCall")
    }

    fun getNextClientIndex(context: Context): Int {
        val sharedPreferences = context.getSharedPreferences("call_logs", Context.MODE_PRIVATE)
        val lastIndex = sharedPreferences.getInt("last_client_index", 0) + 1
        sharedPreferences.edit().putInt("last_client_index", lastIndex).apply()
        return lastIndex
    }

    fun saveContact(context: Context, phoneNumber: String) {
        if (phoneNumber.isBlank()) return
        if (isContactExists(applicationContext, phoneNumber)) return

        val resolver = context.contentResolver
        val cursor = resolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER + " = ?",
            arrayOf(phoneNumber),
            null
        )

        if (cursor != null && cursor.moveToFirst()) {
            cursor.close()
            Log.d(TAG, "Контакт уже существует: $phoneNumber")
            return
        }
        cursor?.close()

        val clientIndex = getNextClientIndex(context)
        val contactOperations = ArrayList<android.content.ContentProviderOperation>()

        contactOperations.add(
            android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.RawContacts.CONTENT_URI)
                .withValue(android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(android.provider.ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()
        )

        contactOperations.add(
            android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(android.provider.ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    android.provider.ContactsContract.Data.MIMETYPE,
                    android.provider.ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                )
                .withValue(
                    android.provider.ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                    "Клиент $clientIndex"
                )
                .build()
        )

        contactOperations.add(
            android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(android.provider.ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    android.provider.ContactsContract.Data.MIMETYPE,
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                )
                .withValue(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
                .withValue(
                    android.provider.ContactsContract.CommonDataKinds.Phone.TYPE,
                    android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                )
                .build()
        )

        try {
            resolver.applyBatch(android.provider.ContactsContract.AUTHORITY, contactOperations)
            Log.d(TAG, "Контакт сохранен: Клиент $clientIndex ($phoneNumber)")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения контакта", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CallService"
        private const val NOTIFICATION_ID = 1
    }
}
