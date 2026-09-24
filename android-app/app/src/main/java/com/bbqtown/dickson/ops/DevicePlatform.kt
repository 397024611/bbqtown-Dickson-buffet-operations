package com.bbqtown.dickson.ops

import android.Manifest
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val PLATFORM_BASE = "https://bbqtowndickson.com"
private const val SESSION_PREFS = "bbqtown_device_session"
private const val UPDATE_PREFS = "bbqtown_app_update"

object DeviceSession {
    private val media = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .writeTimeout(7, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun prefs(context: Context) = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
    private fun rolePrefs(context: Context) = context.getSharedPreferences("bbqtown_ops_v2", Context.MODE_PRIVATE)

    fun deviceId(context: Context): String {
        val p = prefs(context)
        p.getString("device_id", "")?.takeIf { it.isNotBlank() }?.let { return it }
        val value = UUID.randomUUID().toString()
        p.edit().putString("device_id", value).apply()
        return value
    }

    fun defaultDeviceName(context: Context): String {
        val role = role(context)
        val prefix = when (role) {
            "s1" -> "Kitchen 1"
            "s2" -> "Kitchen 2"
            "foh" -> "FOH"
            "manager" -> "Manager"
            else -> "BBQ Town"
        }
        return "$prefix · ${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }

    fun deviceName(context: Context): String = prefs(context).getString("device_name", "").orEmpty().ifBlank { defaultDeviceName(context) }
    fun role(context: Context): String = rolePrefs(context).getString("role", "").orEmpty().ifBlank { "unassigned" }
    fun isPaired(context: Context): Boolean = prefs(context).getString("refresh_token", "").orEmpty().isNotBlank()
    fun accessToken(context: Context): String = prefs(context).getString("access_token", "").orEmpty()
    fun pendingRefreshToken(context: Context): String = prefs(context).getString("refresh_token", "").orEmpty()

    fun clearPairing(context: Context) {
        prefs(context).edit().remove("refresh_token").remove("access_token").remove("access_expires_at").remove("last_heartbeat").apply()
    }

    fun pairBlocking(context: Context, pin: String, name: String): Result<Unit> = runCatching {
        val body = JSONObject()
            .put("pin", pin)
            .put("deviceId", deviceId(context))
            .put("deviceName", name.trim().ifBlank { defaultDeviceName(context) })
            .put("role", role(context))
            .put("appVersion", BuildConfig.VERSION_NAME)
        val request = Request.Builder()
            .url("$PLATFORM_BASE/api/ops/device/pair")
            .header("User-Agent", "BBQTownOpsAndroid/${BuildConfig.VERSION_NAME} Native")
            .post(body.toString().toRequestBody(media))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val root = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) throw IllegalStateException(root.optString("error", "Pairing failed (${response.code})"))
            val refresh = root.optString("refreshToken")
            val access = root.optString("accessToken")
            if (refresh.isBlank() || access.isBlank()) throw IllegalStateException("Pairing response was incomplete")
            prefs(context).edit()
                .putString("device_name", name.trim().ifBlank { defaultDeviceName(context) })
                .putString("refresh_token", refresh)
                .putString("access_token", access)
                .putLong("access_expires_at", root.optLong("accessExpiresAt", 0L))
                .putLong("last_heartbeat", System.currentTimeMillis())
                .apply()
        }
    }

    fun ensureAccessBlocking(context: Context, pendingTasks: Int = 0, force: Boolean = false): String {
        val p = prefs(context)
        val current = p.getString("access_token", "").orEmpty()
        val expires = p.getLong("access_expires_at", 0L)
        val lastHeartbeat = p.getLong("last_heartbeat", 0L)
        val now = System.currentTimeMillis()
        if (!force && current.isNotBlank() && expires > now + 90_000L && now - lastHeartbeat < 60_000L) return current

        val refresh = p.getString("refresh_token", "").orEmpty()
        if (refresh.isBlank()) return current
        val body = JSONObject()
            .put("deviceId", deviceId(context))
            .put("deviceName", deviceName(context))
            .put("role", role(context))
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("pendingTasks", pendingTasks.coerceAtLeast(0))
            .put("online", true)
            .put("meta", JSONObject().put("sdk", Build.VERSION.SDK_INT).put("model", Build.MODEL))
        val request = Request.Builder()
            .url("$PLATFORM_BASE/api/ops/devices")
            .header("User-Agent", "BBQTownOpsAndroid/${BuildConfig.VERSION_NAME} Native")
            .header("x-bbqtown-refresh-token", refresh)
            .post(body.toString().toRequestBody(media))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val root = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) {
                if (response.code == 401) clearPairing(context)
                throw IllegalStateException(root.optString("error", "Device heartbeat failed (${response.code})"))
            }
            val access = root.optString("accessToken")
            if (access.isNotBlank()) {
                p.edit()
                    .putString("access_token", access)
                    .putLong("access_expires_at", root.optLong("accessExpiresAt", now + 15 * 60_000L))
                    .putLong("last_heartbeat", now)
                    .apply()
                return access
            }
        }
        return current
    }
}

class DevicePairingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KitchenAlertChannels.ensure(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = BrandInk, secondary = BrandGold, background = BrandCream, surface = BrandSurface)) {
                PairDeviceScreen(
                    initialName = DeviceSession.deviceName(this),
                    onPair = { pin, name, done ->
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) { DeviceSession.pairBlocking(this@DevicePairingActivity, pin, name) }
                            done(result.exceptionOrNull()?.message)
                            if (result.isSuccess) {
                                startActivity(Intent(this@DevicePairingActivity, KitchenAlertBootstrapActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                                finish()
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PairDeviceScreen(initialName: String, onPair: (String, String, (String?) -> Unit) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Surface(Modifier.fillMaxSize(), color = BrandCream) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 520.dp), color = BrandSurface, shape = RoundedCornerShape(24.dp), shadowElevation = 5.dp) {
                Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OfficialBbqTownLogo(Modifier.width(230.dp).height(88.dp))
                    Text("PAIR THIS TABLET", color = BrandInk, fontWeight = FontWeight.Black, fontSize = 25.sp)
                    Text("One-time Manager PIN setup. This replaces the old spoofable Android access bypass.", color = BrandMuted, fontSize = 11.sp)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Device name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(12) }, label = { Text("Manager PIN") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    error?.let { Text(it, color = BrandRed, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
                    Button(
                        onClick = {
                            if (pin.isBlank() || busy) return@Button
                            busy = true; error = null
                            onPair(pin, name) { message -> busy = false; error = message }
                        },
                        enabled = pin.isNotBlank() && !busy,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandInk),
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                        else Text("PAIR DEVICE", fontWeight = FontWeight.Black)
                    }
                    Text("Device ID · ${DeviceSession.deviceId(androidx.compose.ui.platform.LocalContext.current).take(8)}", color = BrandMuted, fontSize = 9.sp)
                }
            }
        }
    }
}

data class AppUpdateInfo(val versionCode: Int, val versionName: String, val downloadUrl: String, val size: Long)

object AppUpdater {
    private val media = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    private fun prefs(context: Context) = context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)

    suspend fun check(context: Context, force: Boolean = false): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val last = p.getLong("last_check", 0L)
        if (!force && now - last < 30 * 60_000L) return@withContext known(context)
        val token = DeviceSession.ensureAccessBlocking(context)
        if (token.isBlank()) return@withContext null
        val request = Request.Builder()
            .url("$PLATFORM_BASE/api/ops/update")
            .header("User-Agent", "BBQTownOpsAndroid/${BuildConfig.VERSION_NAME} Native")
            .header("x-bbqtown-device-token", token)
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext known(context)
            val root = JSONObject(response.body?.string().orEmpty())
            p.edit().putLong("last_check", now).apply()
            if (!root.optBoolean("available")) return@withContext null
            val code = root.optInt("versionCode", 0)
            val url = root.optString("downloadUrl")
            if (code <= BuildConfig.VERSION_CODE || url.isBlank()) {
                p.edit().remove("version_code").remove("download_url").apply()
                return@withContext null
            }
            p.edit()
                .putInt("version_code", code)
                .putString("version_name", root.optString("versionName", "Build $code"))
                .putString("download_url", url)
                .putLong("size", root.optLong("size", 0L))
                .apply()
            return@withContext known(context)
        }
    }

    fun known(context: Context): AppUpdateInfo? {
        val p = prefs(context)
        val code = p.getInt("version_code", 0)
        val url = p.getString("download_url", "").orEmpty()
        if (code <= BuildConfig.VERSION_CODE || url.isBlank()) return null
        return AppUpdateInfo(code, p.getString("version_name", "Build $code").orEmpty(), url, p.getLong("size", 0L))
    }

    fun startPeriodic(context: Context) {
        if (started) return
        synchronized(this) { if (started) return; started = true }
        val app = context.applicationContext
        scope.launch {
            while (isActive) {
                try {
                    val update = check(app)
                    if (update != null) notifyUpdate(app, update)
                } catch (_: Exception) {}
                delay(30 * 60_000L)
            }
        }
    }

    private fun notifyUpdate(context: Context, info: AppUpdateInfo) {
        KitchenAlertChannels.ensure(context)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(context, AppUpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(context, info.versionCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, KitchenAlertChannels.UPDATE) else Notification.Builder(context)
        builder.setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("BBQ Town Ops update available")
            .setContentText("${info.versionName} · tap to update on this tablet")
            .setContentIntent(pending)
            .setAutoCancel(true)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(22011, builder.build())
    }
}

class AppUpdateActivity : ComponentActivity() {
    private var downloadId = -1L
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = manager.getUriForDownloadedFile(downloadId) ?: return
            val install = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(install)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerDownloadReceiver()
        setContent {
            val known = AppUpdater.known(this)
            var info by remember { mutableStateOf(known) }
            var checking by remember { mutableStateOf(known == null) }
            var message by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                if (info == null) {
                    info = runCatching { AppUpdater.check(this@AppUpdateActivity, force = true) }.getOrNull()
                    checking = false
                }
            }
            MaterialTheme(colorScheme = lightColorScheme(primary = BrandInk, secondary = BrandGold, background = BrandCream, surface = BrandSurface)) {
                Surface(Modifier.fillMaxSize(), color = BrandCream) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Surface(Modifier.widthIn(max = 560.dp), color = BrandSurface, shape = RoundedCornerShape(24.dp), shadowElevation = 5.dp) {
                            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                OfficialBbqTownLogo(Modifier.width(230.dp).height(88.dp))
                                Text("APP UPDATE", color = BrandInk, fontWeight = FontWeight.Black, fontSize = 27.sp)
                                when {
                                    checking -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Checking for latest build…") }
                                    info == null -> Text("This tablet is already on the latest available version.", color = BrandGreen, fontWeight = FontWeight.Bold)
                                    else -> {
                                        Text("${info!!.versionName} is ready.", color = BrandInk, fontWeight = FontWeight.Black, fontSize = 18.sp)
                                        Text("The app downloads the APK itself. Android may ask once to allow installs from BBQ Town Ops, then shows the normal install confirmation.", color = BrandMuted, fontSize = 11.sp)
                                        Button(onClick = { startUpdate(info!!, onMessage = { message = it }) }, modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = BrandGreen), shape = RoundedCornerShape(13.dp)) { Text("UPDATE NOW", fontWeight = FontWeight.Black) }
                                    }
                                }
                                message?.let { Text(it, color = BrandGold, fontWeight = FontWeight.Bold, fontSize = 10.sp) }
                                TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("LATER", color = BrandMuted) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun registerDownloadReceiver() {
        if (receiverRegistered) return
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        receiverRegistered = true
    }

    private fun startUpdate(info: AppUpdateInfo, onMessage: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            onMessage("Allow ‘Install unknown apps’ for BBQ Town Ops, then return and tap UPDATE NOW again.")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val target = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "BBQTown-Dickson-Ops-update.apk")
        runCatching { if (target.exists()) target.delete() }
        val request = DownloadManager.Request(Uri.parse(info.downloadUrl))
            .setTitle("BBQ Town Ops ${info.versionName}")
            .setDescription("Downloading app update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, target.name)
        downloadId = manager.enqueue(request)
        onMessage("Downloading update on this tablet…")
    }

    override fun onDestroy() {
        if (receiverRegistered) runCatching { unregisterReceiver(receiver) }
        receiverRegistered = false
        super.onDestroy()
    }
}

object KitchenAlertChannels {
    const val LOW = "kitchen_refill_low_v2"
    const val EMPTY = "kitchen_refill_empty_v2"
    const val UPDATE = "app_updates"

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val alarmUri = Settings.System.DEFAULT_ALARM_ALERT_URI
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val low = NotificationChannel(LOW, "Kitchen refill · LOW", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Persistent LOW buffet refill requests"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 280, 130, 280, 130, 420)
            setSound(alarmUri, attributes)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val empty = NotificationChannel(EMPTY, "Kitchen refill · EMPTY URGENT", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Urgent EMPTY buffet refill requests"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 350, 120, 350, 120, 650, 160, 650)
            setSound(alarmUri, attributes)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val update = NotificationChannel(UPDATE, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannels(listOf(low, empty, update))
    }

    fun notifyKitchen(context: Context, urgent: Boolean, dishes: List<String> = emptyList()) {
        ensure(context)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val launch = Intent(context, KitchenBoardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, if (urgent) 22002 else 22001, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val channel = if (urgent) EMPTY else LOW
        val names = dishes.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val first = names.firstOrNull()
        val title = when {
            urgent && first != null -> "URGENT · EMPTY · $first"
            urgent -> "URGENT · EMPTY DISH"
            first != null -> "LOW · REFILL · $first"
            else -> "LOW · REFILL REQUEST"
        }
        val text = when {
            names.size > 1 -> "${names.size} dishes waiting · ${names.take(3).joinToString(", ")}"
            urgent -> "Buffet tray is EMPTY · start preparing now"
            else -> "Buffet tray is LOW · prepare the next tray"
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, channel) else Notification.Builder(context)
        builder.setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(if (urgent) 22102 else 22101, builder.build())
    }

    fun clearKitchen(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(22101)
        manager.cancel(22102)
    }
}
