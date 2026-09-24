package com.bbqtown.dickson.ops

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Launcher + kitchen alert monitor. Paired devices use signed credentials rather than
 * the old User-Agent bypass. Kitchen roles use the dedicated three-lane KDS.
 */
class KitchenAlertBootstrapActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KitchenAlertChannels.ensure(applicationContext)

        if (!DeviceSession.isPaired(this)) {
            startActivity(Intent(this, DevicePairingActivity::class.java))
            finish()
            return
        }

        KitchenAlertMonitor.start(applicationContext)
        PrepRecovery.start(applicationContext)
        AppUpdater.startPeriodic(applicationContext)

        lifecycleScope.launch {
            val update = AppUpdater.known(this@KitchenAlertBootstrapActivity) ?: withTimeoutOrNull(2_500L) {
                runCatching { AppUpdater.check(this@KitchenAlertBootstrapActivity) }.getOrNull()
            }
            if (update != null) {
                startActivity(Intent(this@KitchenAlertBootstrapActivity, AppUpdateActivity::class.java))
                finish()
                return@launch
            }

            val role = getSharedPreferences("bbqtown_ops_v2", Context.MODE_PRIVATE)
                .getString("role", "").orEmpty()
            val target = if (role == "s1" || role == "s2") KitchenBoardActivity::class.java else PremiumOpsActivity::class.java
            startActivity(Intent(this@KitchenAlertBootstrapActivity, target))
            finish()
        }
    }
}

object KitchenAlertMonitor {
    @Volatile private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }

        val app = context.applicationContext
        scope.launch {
            val rolePrefs = app.getSharedPreferences("bbqtown_ops_v2", Context.MODE_PRIVATE)
            val api = FastApi(app)
            var lastSection = -1
            var initialized = false
            var seenRequests = emptySet<String>()
            var lastAlertAt = 0L
            var lastBackgroundRefreshAt = 0L

            while (isActive) {
                val section = when (rolePrefs.getString("role", "")) {
                    "s1" -> 1
                    "s2" -> 2
                    else -> 0
                }

                if (!initialized) {
                    initialized = true
                    lastSection = section
                    seenRequests = if (section == 0) emptySet() else {
                        api.cachedLive(section)?.foods.orEmpty()
                            .filter { it.section == section && it.kitchen == "requested" }
                            .map { "${it.id}:${it.requestedAt}" }.toSet()
                    }
                } else if (section != lastSection) {
                    lastSection = section
                    seenRequests = emptySet()
                    lastAlertAt = 0L
                    if (section > 0 && !KitchenBoardPresence.active) {
                        app.startActivity(
                            Intent(app, KitchenBoardActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                    }
                }

                if (section == 0) {
                    seenRequests = emptySet()
                    delay(800)
                    continue
                }

                val now = System.currentTimeMillis()
                val latest = if (!KitchenBoardPresence.active && now - lastBackgroundRefreshAt >= 4_000L) {
                    lastBackgroundRefreshAt = now
                    runCatching { api.loadLive(section) }.getOrNull() ?: api.cachedLive(section)
                } else {
                    api.cachedLive(section)
                }
                val requested = latest?.foods.orEmpty()
                    .filter { it.section == section && it.kitchen == "requested" }
                    .sortedBy { it.name.lowercase() }
                val requestKeys = requested.map { "${it.id}:${it.requestedAt}" }.toSet()
                val newRequests = requested.filter { "${it.id}:${it.requestedAt}" !in seenRequests }

                when {
                    newRequests.isNotEmpty() -> {
                        playAlert(app, urgent = newRequests.any { it.status == "EMPTY" }, dishes = newRequests.map { it.name })
                        lastAlertAt = now
                    }
                    requested.isNotEmpty() && now - lastAlertAt >= if (requested.any { it.status == "EMPTY" }) 20_000L else 30_000L -> {
                        playAlert(app, urgent = requested.any { it.status == "EMPTY" }, dishes = requested.map { it.name })
                        lastAlertAt = now
                    }
                    requested.isEmpty() -> {
                        KitchenAlertChannels.clearKitchen(app)
                        lastAlertAt = 0L
                    }
                }

                seenRequests = requestKeys
                delay(700)
            }
        }
    }

    private suspend fun playAlert(context: Context, urgent: Boolean, dishes: List<String>) {
        KitchenAlertChannels.notifyKitchen(context, urgent, dishes)
        try {
            val toneType = if (urgent) ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD else ToneGenerator.TONE_PROP_BEEP2
            val repeats = if (urgent) 5 else 3
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            try {
                repeat(repeats) {
                    tone.startTone(toneType, if (urgent) 520 else 420)
                    delay(if (urgent) 680 else 560)
                }
            } finally {
                tone.release()
            }
        } catch (_: Exception) {
        }

        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val pattern = if (urgent) longArrayOf(0, 350, 120, 350, 120, 650, 160, 650) else longArrayOf(0, 280, 130, 280, 130, 420)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (_: Exception) {
        }
    }
}
