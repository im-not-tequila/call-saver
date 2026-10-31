package com.example.callsaver

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.app.AlertDialog
import android.content.ContentProviderOperation
import android.net.Uri
import android.provider.ContactsContract
import android.app.role.RoleManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.example.callsaver.ui.CallListener

class MainActivity : ComponentActivity() {
    companion object {
        const val CHANNEL_ID = "call_saver_channel"
        const val REQUEST_CODE_FOREGROUND_SERVICE = 103
    }

    private val phoneStateListener = CallListener(this)

    private val callScreeningRoleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.d("CallScreening", "Роль фильтра звонков: ${result.resultCode}")
        }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->

            // Логируем результат запроса разрешений
            permissions.forEach { (perm, granted) ->
                Log.d(
                    "Permissions",
                    "Разрешение $perm: ${if (granted) "получено" else "отклонено"}"
                )
            }

            if (permissions[Manifest.permission.READ_PHONE_STATE] == false) {
                Toast.makeText(this, "Нет доступа к состоянию телефона!", Toast.LENGTH_LONG).show()
            }
            if (hasPermission(Manifest.permission.READ_PHONE_STATE)) {
                initializePhoneStateListener()
            }

            if (permissions[Manifest.permission.WRITE_CONTACTS] == true) {
                Log.d("CallService", "Разрешение на запись контактов получено!")
                Toast.makeText(this, "Доступ к контактам разрешен!", Toast.LENGTH_LONG).show()
                val sharedPrefs = getSharedPreferences("call_logs", Context.MODE_PRIVATE)
                val pendingNumber = sharedPrefs.getString("pending_number", null)

                if (!pendingNumber.isNullOrBlank()) {
                    saveContact(this, pendingNumber)
                    sharedPrefs.edit().remove("pending_number").apply()
                }
            } else if (permissions[Manifest.permission.WRITE_CONTACTS] == false) {
                Toast.makeText(this, "Нет доступа к контактам!", Toast.LENGTH_LONG).show()
            }

            if (permissions[Manifest.permission.READ_CALL_LOG] == false) {
                Toast.makeText(this, "Нет доступа к журналу звонков!", Toast.LENGTH_LONG).show()
            }
            if (permissions[Manifest.permission.POST_NOTIFICATIONS] == false) {
                Toast.makeText(this, "Нет разрешения на уведомления!", Toast.LENGTH_LONG).show()
            }

            startCallService()
            requestCallScreeningRole(automatic = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val askedForPermissions = requestPermissionsIfNeeded()
        startCallService()
        createNotificationChannel()
        setContent {
            CallLoggerApp(
                context = this,
                onRequestCallScreening = { requestCallScreeningRole(automatic = false) }
            )
        }
        checkAndRequestBatteryOptimization(this)
        if (!askedForPermissions) {
            requestCallScreeningRole(automatic = true)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCallScreeningRole(automatic: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val roleManager = getSystemService(RoleManager::class.java) ?: return
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            if (!automatic) {
                Toast.makeText(this, "На этом телефоне нет фильтра звонков", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            if (!automatic) {
                Toast.makeText(this, "Определение номера уже включено", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val prefs = getSharedPreferences("call_logs", Context.MODE_PRIVATE)
        if (automatic && prefs.getBoolean("screening_prompt_shown", false)) return
        if (automatic) {
            prefs.edit().putBoolean("screening_prompt_shown", true).apply()
        }
        callScreeningRoleLauncher.launch(
            roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
        )
    }

    private fun requestPermissionsIfNeeded(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionsToRequest = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest)
            return true
        }
        initializePhoneStateListener()
        return false
    }

    @Suppress("DEPRECATION")
    private fun initializePhoneStateListener() {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } else {
            Log.e("CallService", "Нет разрешения на прослушивание состояния звонков")
        }
    }

    private fun startCallService() {
        val serviceIntent = Intent(this, CallService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Call Saver Channel"
            val descriptionText = "Channel for call saver app"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        super.onDestroy()
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        phoneStateListener.dispose()
    }
}

fun checkAndRequestPermissions(activity: Activity) {
    val permissions = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS
    )

    val missingPermissions = permissions.filter {
        ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
    }

    if (missingPermissions.isNotEmpty()) {
        ActivityCompat.requestPermissions(activity, missingPermissions.toTypedArray(), 1001)
    }
}

@Composable
fun CallLoggerApp(context: Context, onRequestCallScreening: () -> Unit) {
    val sharedPreferences = context.getSharedPreferences("call_logs", Context.MODE_PRIVATE)
    var shopPhoneNumber by remember {
        mutableStateOf(
            sharedPreferences.getString(
                "shop_phone_number",
                ""
            ) ?: ""
        )
    }
    val (calls, setCalls) = remember { mutableStateOf<List<String>>(emptyList()) }

    // Обновление списка звонков
    fun updateCallLogs() {
        val savedCalls = sharedPreferences.getString("calls", "Нет данных") ?: "Нет данных"
        setCalls(savedCalls.split("\n").filter { it.isNotEmpty() })
    }

    fun saveShopPhoneNumber(phone: String) {
        sharedPreferences.edit().putString("shop_phone_number", phone).apply()
        shopPhoneNumber = phone // Обновляем номер в UI
        Toast.makeText(context, "Номер успешно сохранён!", Toast.LENGTH_LONG).show()
    }

    LaunchedEffect(Unit) {
        updateCallLogs()
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Блок проверки оптимизации батареи
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Убедитесь, что отключена оптимизация батареи.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )

                    Button(
                        onClick = { checkAndRequestBatteryOptimization(context as Activity) },
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text("Проверить")
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "На Android 10 и новее включите определение номера входящего.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )

                        Button(
                            onClick = onRequestCallScreening,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text("Включить")
                        }
                    }
                }

                // Блок проверки разрешений
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Проверьте, что у приложения есть доступ к контактам.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )

                    Button(
                        onClick = { checkAndRequestPermissions(context as Activity) },
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text("Запросить")
                    }
                }

                // Блок проверки ватсап
//                Row(
//                    modifier = Modifier
//                        .fillMaxWidth()
//                        .padding(bottom = 8.dp),
//                    horizontalArrangement = Arrangement.SpaceBetween
//                ) {
//                    Text(
//                        text = "Проверка доступа к уведомлении whatsapp.",
//                        style = MaterialTheme.typography.bodyMedium,
//                        modifier = Modifier.weight(1f)
//                    )
//
//                    Button(
//                        onClick = {  if (!isNotificationListenerEnabled()) {
//                            requestNotificationPermission()
//                            return
//                        } },
//                        modifier = Modifier.padding(start = 8.dp)
//                    ) {
//                        Text("Проверить")
//                    }
//                }

                Text(
                    text = "Журнал вызовов",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Отображение номера телефона магазина
                if (shopPhoneNumber.isNotEmpty()) {
                    Text(
                        text = "Номер магазина: $shopPhoneNumber",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                } else {
                    Text(
                        text = "Номер магазина не установлен",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                // Поле для ввода номера телефона магазина
                TextField(
                    value = shopPhoneNumber,
                    onValueChange = { newValue -> shopPhoneNumber = newValue },
                    label = { Text("Номер телефона магазина") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { saveShopPhoneNumber(shopPhoneNumber) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Сохранить номер магазина")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { updateCallLogs() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Обновить список")
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (calls.isEmpty()) {
                    Text(
                        text = "Нет записанных звонков.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn {
                        items(calls) { call ->
                            CallLogItem(number = call)
                        }
                    }
                }
            }
        }
    }

}

fun checkAndRequestBatteryOptimization(activity: Activity) {
    val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
    val packageName = activity.packageName

    if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
        // Оптимизация включена → предлагаем отключить
        AlertDialog.Builder(activity) // Используем activity, а не applicationContext
            .setTitle("Отключение оптимизации батареи")
            .setMessage("Для стабильной работы приложения отключите оптимизацию батареи.")
            .setPositiveButton("ОК") { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                activity.startActivity(intent)
            }
            .setNegativeButton("Отмена", null)
            .show()
    } else {
        Toast.makeText(activity, "Оптимизация батареи отключена!", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun CallLogItem(number: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        elevation = CardDefaults.elevatedCardElevation(4.dp)
    ) {
        Text(
            text = number,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(16.dp)
        )
    }
}

fun getNextClientIndex(context: Context): Int {
    val sharedPreferences = context.getSharedPreferences("call_logs", Context.MODE_PRIVATE)
    val lastIndex = sharedPreferences.getInt("last_client_index", 0) + 1
    sharedPreferences.edit().putInt("last_client_index", lastIndex).apply()
    return lastIndex
}

fun saveContact(context: Context, phoneNumber: String) {
    if (phoneNumber.isBlank()) return // Если номер пустой - выходим

    val resolver = context.contentResolver
    val cursor = resolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
        ContactsContract.CommonDataKinds.Phone.NUMBER + " = ?",
        arrayOf(phoneNumber),
        null
    )

    if (cursor != null) {
        try {
            if (cursor.moveToFirst()) {
                Log.d("CallService", "Контакт уже существует: $phoneNumber")
                return
            }
        } finally {
            cursor.close()
        }
    }

    // Получаем номер следующего клиента
    val clientIndex = getNextClientIndex(context)

    // Создаём новый контакт
    val contactOperations = ArrayList<ContentProviderOperation>()

    val lastFourDigits = if (phoneNumber.length >= 4) phoneNumber.takeLast(4) else phoneNumber
    val displayName = "Клиент $clientIndex $lastFourDigits"

    contactOperations.add(
        ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build()
    )

    contactOperations.add(
        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
            )
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
            .build()
    )

    contactOperations.add(
        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
            )
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
            .withValue(
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
            )
            .build()
    )

    try {
        resolver.applyBatch(ContactsContract.AUTHORITY, contactOperations)
        Log.d("CallService", "Контакт сохранен: Клиент $clientIndex ($phoneNumber)")
    } catch (e: Exception) {
        Log.e("CallService", "Ошибка сохранения контакта", e)
    }
}