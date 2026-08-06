package com.duckpet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Calendar

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var webView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private val handler = Handler(Looper.getMainLooper())
    private var appCheckRunnable: Runnable? = null

    private val density by lazy { resources.displayMetrics.density }
    private val screenWidth by lazy { resources.displayMetrics.widthPixels }
    private val screenHeight by lazy { resources.displayMetrics.heightPixels }

    private val overlayWidth = (80 * density).toInt()
    private val overlayHeight = (96 * density).toInt()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = createNotification()
            startForeground(1, notification)
            if (webView == null) {
                createOverlay()
            }
            startAppDetection()
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        appCheckRunnable?.let { handler.removeCallbacks(it) }
        try {
            webView?.let { v ->
                if (v.isAttachedToWindow) {
                    windowManager.removeView(v)
                }
            }
        } catch (_: Exception) {}
        webView = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                "duck_pet_channel",
                "涂鸦叽",
                NotificationManager.IMPORTANCE_LOW
            )
            ch.setShowBadge(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun createNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, "duck_pet_channel")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("涂鸦叽")
            .setContentText("涂鸦叽正在屏幕上陪着你呢~")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startAppDetection() {
        appCheckRunnable = object : Runnable {
            override fun run() {
                detectForegroundApp()
                handler.postDelayed(this, 2000)
            }
        }
        handler.post(appCheckRunnable!!)
    }

    private fun detectForegroundApp() {
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val cal = Calendar.getInstance()
            cal.add(Calendar.MINUTE, -1)
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                cal.timeInMillis,
                System.currentTimeMillis()
            )
            if (stats.isNullOrEmpty()) return

            var topApp = ""
            var topTime = 0L
            for (s in stats) {
                if (s.lastTimeUsed > topTime) {
                    topTime = s.lastTimeUsed
                    topApp = s.packageName
                }
            }
            if (topApp.isEmpty() || topApp == packageName) return

            val appName = try {
                val pm = packageManager
                val ai = pm.getApplicationInfo(topApp, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (_: Exception) {
                topApp
            }

            val safeName = appName.replace("'", "\\'")
            webView?.evaluateJavascript(
                "javascript:setAppLabel('$safeName')",
                null
            )
        } catch (_: Exception) {}
    }

    inner class DuckInterface {
        @JavascriptInterface
        fun onTap() {}
    }

    private fun createOverlay() {
        params = WindowManager.LayoutParams(
            overlayWidth,
            overlayHeight,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = (screenWidth - overlayWidth) / 2
            y = screenHeight / 4
        }

        val wv = WebView(this).apply {
            setBackgroundColor(0x00000000)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(DuckInterface(), "DuckInterface")
            setOnTouchListener(overlayTouchListener)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                }
            }
            loadUrl("file:///android_asset/pet.html")
        }

        try {
            windowManager.addView(wv, params)
            webView = wv
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private val overlayTouchListener = View.OnTouchListener { v, event ->
        val p = params ?: return@OnTouchListener false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = p.x
                initialY = p.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                    isDragging = true
                    p.x = initialX + dx
                    p.y = initialY + dy
                    try {
                        windowManager.updateViewLayout(v, p)
                    } catch (_: Exception) {}
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    webView?.evaluateJavascript("javascript:handleTap()", null)
                } else {
                    snapToEdge(p)
                }
                true
            }
            else -> false
        }
    }

    private fun snapToEdge(p: WindowManager.LayoutParams) {
        val snapThreshold = (80 * density).toInt()
        val distLeft = p.x
        val distRight = screenWidth - (p.x + overlayWidth)
        val distTop = p.y
        val distBottom = screenHeight - (p.y + overlayHeight)

        val minDist = minOf(distLeft, distRight, distTop, distBottom)
        if (minDist > snapThreshold) return

        when (minDist) {
            distLeft -> p.x = 0
            distRight -> p.x = screenWidth - overlayWidth
            distTop -> p.y = 0
            distBottom -> p.y = screenHeight - overlayHeight
        }
        try {
            windowManager.updateViewLayout(webView, p)
        } catch (_: Exception) {}
    }
}