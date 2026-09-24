package com.bbqtown.dickson.ops

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var offlineMessage: TextView
    private lateinit var connectionBadge: TextView
    private lateinit var roleBadge: TextView
    private lateinit var prefs: SharedPreferences
    private lateinit var connectivityManager: ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private var toneGenerator: ToneGenerator? = null
    private var pendingKitchenTasks = 0
    private var currentRole = ""

    companion object {
        private const val OPS_URL = "https://bbqtowndickson.com/ops.html"
        private const val TABLES_URL = "https://bbqtowndickson.com/tables.html"
        private const val ALLOWED_HOST = "bbqtowndickson.com"
        private const val PREF_ROLE = "device_role"
        private const val ALERT_REPEAT_MS = 30_000L
        private val BLOCKED_PREFIXES = listOf(
            "/book",
            "/reservations",
            "/voucher",
            "/vouchers",
            "/staff"
        )
    }

    private val repeatAlert = object : Runnable {
        override fun run() {
            if ((currentRole == "s1" || currentRole == "s2") && pendingKitchenTasks > 0) {
                playKitchenAlert()
                handler.postDelayed(this, ALERT_REPEAT_MS)
            }
        }
    }

    inner class NativeOpsBridge {
        @JavascriptInterface
        fun click() {
            runOnUiThread { playClick() }
        }

        @JavascriptInterface
        fun kitchenTasks(count: Int) {
            runOnUiThread {
                val safeCount = count.coerceAtLeast(0)
                val increased = safeCount > pendingKitchenTasks
                pendingKitchenTasks = safeCount
                handler.removeCallbacks(repeatAlert)
                if (increased && safeCount > 0) playKitchenAlert()
                if ((currentRole == "s1" || currentRole == "s2") && safeCount > 0) {
                    handler.postDelayed(repeatAlert, ALERT_REPEAT_MS)
                }
            }
        }

        @JavascriptInterface
        fun chooseRole() {
            runOnUiThread { showRoleChooser(false) }
        }
    }

    private fun playClick() {
        try {
            if (toneGenerator == null) toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 90)
        } catch (_: Exception) {}
    }

    private fun playKitchenAlert() {
        try {
            if (toneGenerator == null) toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 650)
        } catch (_: Exception) {}

        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val pattern = longArrayOf(0, 180, 100, 180, 100, 260)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (_: Exception) {}
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        volumeControlStream = AudioManager.STREAM_MUSIC
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("bbqtown_ops", Context.MODE_PRIVATE)
        currentRole = prefs.getString(PREF_ROLE, "").orEmpty()

        enableImmersiveMode()

        val root = FrameLayout(this)
        webView = WebView(this)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)

        offlineMessage = TextView(this).apply {
            text = "Unable to connect to BBQ Town Ops.\nCheck Wi-Fi / internet and tap to retry."
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(245, 244, 239))
            setTextColor(Color.rgb(23, 32, 24))
            visibility = View.GONE
            setOnClickListener {
                playClick()
                webView.reload()
            }
        }

        connectionBadge = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.rgb(46, 125, 73), dp(16).toFloat())
            text = "ONLINE"
        }

        roleBadge = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.rgb(23, 32, 24), dp(10).toFloat())
            setOnClickListener {
                playClick()
                showRoleChooser(false)
            }
        }

        root.addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(progress, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(offlineMessage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        root.addView(connectionBadge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(10)
            marginEnd = dp(10)
        })
        root.addView(roleBadge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = dp(12)
            marginEnd = dp(12)
        })
        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            userAgentString = "$userAgentString BBQTownOpsAndroid/1.3"
        }

        webView.addJavascriptInterface(NativeOpsBridge(), "BBQNativeOps")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val host = uri.host?.lowercase().orEmpty()
                val path = uri.path.orEmpty()

                if (host != ALLOWED_HOST && host != "www.$ALLOWED_HOST") return true

                if (BLOCKED_PREFIXES.any { path.startsWith(it) }) {
                    loadRoleHome()
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                offlineMessage.visibility = View.GONE
                injectAppBehaviour(view)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) offlineMessage.visibility = View.VISIBLE
            }
        }

        registerNetworkStatus()
        updateRoleBadge()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else if (currentRole.isBlank()) {
            showRoleChooser(true)
        } else {
            loadRoleHome()
        }
    }

    private fun injectAppBehaviour(view: WebView) {
        val role = currentRole
        val script = """
            (function(){
              const role = ${jsString(role)};
              const hide = (el) => { if (el) el.style.setProperty('display','none','important'); };
              hide(document.getElementById('bookingLink'));
              document.querySelectorAll('a[href="/vouchers"],a[href^="/voucher"],a[href^="/reservations"],a[href^="/book"],a[href^="/staff"]').forEach(hide);

              const brand = document.getElementById('brandSub');
              if (brand) brand.textContent = 'Dickson · Ops App';

              if (location.pathname.endsWith('/ops.html') || location.pathname === '/ops.html') {
                const target = document.querySelector('[data-view="' + role + '"]');
                if (target && document.body.dataset.view !== role) target.click();

                // Lock each device to its assigned operating role. Role changes happen through
                // the native DEVICE badge, not the browser navigation.
                document.querySelectorAll('.nav [data-view]').forEach(btn => {
                  if (role && btn.getAttribute('data-view') !== role) hide(btn);
                });
              }

              if (!window.__bbqNativeClickSoundInstalled) {
                window.__bbqNativeClickSoundInstalled = true;
                document.addEventListener('click', function(event) {
                  try {
                    const target = event.target && event.target.closest
                      ? event.target.closest('button, a, [role="button"], input[type="button"], input[type="submit"], input[type="checkbox"], input[type="radio"], select, .task, .card')
                      : null;
                    if (target && window.BBQNativeOps && window.BBQNativeOps.click) window.BBQNativeOps.click();
                  } catch (_) {}
                }, true);
              }

              // Kitchen new-task monitor. Android handles loud sound, vibration and repeat alerts.
              if ((role === 's1' || role === 's2') && !window.__bbqKitchenMonitorInstalled) {
                window.__bbqKitchenMonitorInstalled = true;
                let lastCount = -1;
                const report = () => {
                  try {
                    const cols = document.querySelectorAll('.kcol');
                    const first = cols && cols.length ? cols[0] : null;
                    const count = first ? first.querySelectorAll('.task').length : document.querySelectorAll('.task').length;
                    if (count !== lastCount) {
                      lastCount = count;
                      if (window.BBQNativeOps && window.BBQNativeOps.kitchenTasks) window.BBQNativeOps.kitchenTasks(count);
                    }
                  } catch (_) {}
                };
                report();
                const observer = new MutationObserver(report);
                observer.observe(document.body, {childList:true, subtree:true, attributes:true, attributeFilter:['class']});
                setInterval(report, 5000);
              }
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    private fun jsString(value: String): String {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private fun showRoleChooser(firstRun: Boolean) {
        val dialog = AlertDialog.Builder(this).create()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(18))
            setBackgroundColor(Color.rgb(250, 250, 247))
        }

        val brand = TextView(this).apply {
            text = "BBQ TOWN"
            textSize = 24f
            setTextColor(Color.rgb(23, 32, 24))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        container.addView(brand)

        val title = TextView(this).apply {
            text = if (firstRun) "Set this tablet" else "Change device role"
            textSize = 19f
            setTextColor(Color.rgb(23, 32, 24))
            setPadding(0, dp(4), 0, 0)
        }
        container.addView(title)

        val subtitle = TextView(this).apply {
            text = "Choose what this device is used for. It will open directly to this workspace next time."
            textSize = 13f
            setTextColor(Color.rgb(101, 113, 105))
            setPadding(0, dp(6), 0, dp(16))
        }
        container.addView(subtitle)

        val choices = listOf(
            Triple("foh", "FOH FLOOR", "Food status and floor operations"),
            Triple("s1", "KITCHEN SECTION 1", "Kitchen production screen · Section 1"),
            Triple("s2", "KITCHEN SECTION 2", "Kitchen production screen · Section 2"),
            Triple("manager", "MANAGER", "Management controls and live overview"),
            Triple("tables", "TABLES", "Table status and seating screen")
        )

        choices.forEach { (role, label, description) ->
            val button = Button(this).apply {
                text = "$label\n$description"
                textSize = 14f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                isAllCaps = false
                setTextColor(Color.rgb(23, 32, 24))
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = roundedStrokeBackground(
                    fill = if (role == currentRole) Color.rgb(235, 245, 238) else Color.WHITE,
                    stroke = if (role == currentRole) Color.rgb(80, 145, 101) else Color.rgb(218, 225, 219),
                    radius = dp(10).toFloat()
                )
                setOnClickListener {
                    playClick()
                    currentRole = role
                    prefs.edit().putString(PREF_ROLE, currentRole).apply()
                    pendingKitchenTasks = 0
                    handler.removeCallbacks(repeatAlert)
                    updateRoleBadge()
                    dialog.dismiss()
                    loadRoleHome()
                }
            }
            container.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(66)).apply {
                bottomMargin = dp(8)
            })
        }

        val soundTest = Button(this).apply {
            text = "TEST DEVICE SOUND"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.rgb(23, 32, 24), dp(9).toFloat())
            setOnClickListener {
                playClick()
                handler.postDelayed({ playKitchenAlert() }, 180)
            }
        }
        container.addView(soundTest, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(4)
        })

        val hint = TextView(this).apply {
            text = "Tip: keep Media volume high on kitchen tablets. Tap the DEVICE badge at any time to change this setup."
            textSize = 11f
            setTextColor(Color.rgb(105, 115, 108))
            setPadding(0, dp(12), 0, 0)
        }
        container.addView(hint)

        dialog.setView(container)
        dialog.setCancelable(!firstRun)
        dialog.setOnShowListener {
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun loadRoleHome() {
        if (currentRole == "tables") webView.loadUrl(TABLES_URL) else webView.loadUrl(OPS_URL)
    }

    private fun updateRoleBadge() {
        val text = when (currentRole) {
            "foh" -> "DEVICE · FOH"
            "s1" -> "DEVICE · KITCHEN 1"
            "s2" -> "DEVICE · KITCHEN 2"
            "manager" -> "DEVICE · MANAGER"
            "tables" -> "DEVICE · TABLES"
            else -> "DEVICE · SET ROLE"
        }
        roleBadge.text = text
    }

    private fun registerNetworkStatus() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val update = { online: Boolean ->
            runOnUiThread {
                connectionBadge.text = if (online) "ONLINE" else "OFFLINE"
                connectionBadge.background = roundedBackground(
                    if (online) Color.rgb(46, 125, 73) else Color.rgb(190, 48, 48),
                    dp(16).toFloat()
                )
            }
        }

        update(connectivityManager.activeNetwork != null)
        connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update(true)
            override fun onLost(network: Network) = update(connectivityManager.activeNetwork != null)
        })
    }

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun roundedStrokeBackground(fill: Int, stroke: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            setStroke(dp(1), stroke)
            cornerRadius = radius
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun enableImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacks(repeatAlert)
        toneGenerator?.release()
        toneGenerator = null
        webView.destroy()
        super.onDestroy()
    }
}
