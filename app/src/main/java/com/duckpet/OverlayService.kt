package com.duckpet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
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
    private var isSnapped = false
    private var snapEdge = "none"
    private val handler = Handler(Looper.getMainLooper())
    private var appCheckRunnable: Runnable? = null

    private val density by lazy { resources.displayMetrics.density }
    private val screenWidth by lazy { resources.displayMetrics.widthPixels }
    private val screenHeight by lazy { resources.displayMetrics.heightPixels }

    // 悬浮窗大小 - 像素风小鸭变小了
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
            if (webView == null) createOverlay()
            startAppDetection()
        } catch (e: Exception) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        appCheckRunnable?.let { handler.removeCallbacks(it) }
        try {
            webView?.let { v ->
                if (Build.VERSION.SDK_INT < 19 || v.isAttachedToWindow)
                    windowManager.removeView(v)
            }
        } catch (_: Exception) {}
        webView = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("duck_pet_channel", "小黄鸭桌宠",
                NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun createNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, "duck_pet_channel")
                .setContentTitle("小黄鸭桌宠").setContentText("小黄鸭正在屏幕上陪着你")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pi).setOngoing(true).build()
        else
            Notification.Builder(this)
                .setContentTitle("小黄鸭桌宠").setContentText("小黄鸭正在屏幕上陪着你")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pi).setOngoing(true).build()
    }

    // ========== APP检测 ==========
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
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, cal.timeInMillis, System.currentTimeMillis())
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
            } catch (_: Exception) { topApp }

            webView?.evaluateJavascript("javascript:setAppLabel('$appName')", null)
        } catch (_: Exception) {}
    }

    // ========== JavaScript 接口 ==========
    inner class DuckInterface {
        @JavascriptInterface
        fun onTap() {}
    }

    // ========== 创建悬浮窗 ==========
    private fun createOverlay() {
        try {
            params = WindowManager.LayoutParams(
                overlayWidth, overlayHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.START or Gravity.TOP
                x = 50; y = screenHeight / 3
            }

            val wv = WebView(this).apply {
                setBackgroundColor(0x00000000)
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled = false
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.domStorageEnabled = true
                settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                setOnTouchListener(overlayTouchListener)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.setBackgroundColor(0x00000000)
                    }
                    override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
                        // 静默处理加载错误
                    }
                }
                addJavascriptInterface(DuckInterface(), "DuckInterface")
                loadUrl("file:///android_asset/pet.html")
            }

            windowManager.addView(wv, params)
            webView = wv
        } catch (e: Exception) {
            stopSelf()
        }
    }

    // ========== 触摸 + 边缘吸附 ==========
    private val overlayTouchListener = View.OnTouchListener { v, event ->
        val p = params ?: return@OnTouchListener false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = p.x; initialY = p.y
                initialTouchX = event.rawX; initialTouchY = event.rawY
                isDragging = false
                false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                    isDragging = true
                    isSnapped = false
                    p.x = initialX + dx
                    p.y = initialY + dy
                    try { windowManager.updateViewLayout(v, p) }
                    catch (_: Exception) {}
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    // 点击 -> 通知WebView
                    webView?.evaluateJavascript("javascript:handleTap()", null)
                } else {
                    // 拖拽结束 -> 边缘吸附
                    snapToEdge(p)
                }
                true
            }
            else -> false
        }
    }

    private fun snapToEdge(p: WindowManager.LayoutParams) {
        val snapThreshold = (60 * density).toInt()
        val halfW = overlayWidth / 2
        val halfH = overlayHeight / 2

        // 检测距离哪个边缘最近
        val distLeft = p.x
        val distRight = screenWidth - (p.x + overlayWidth)
        val distTop = p.y
        val distBottom = screenHeight - (p.y + overlayHeight)

        val minDist = minOf(distLeft, distRight, distTop, distBottom)
        if (minDist > snapThreshold) return // 不在边缘附近

        isSnapped = true
        when (minDist) {
            distLeft -> {
                p.x = 0
                snapEdge = "left"
            }
            distRight -> {
                p.x = screenWidth - overlayWidth
                snapEdge = "right"
            }
            distTop -> {
                p.y = 0
                snapEdge = "top"
            }
            distBottom -> {
                p.y = screenHeight - overlayHeight
                snapEdge = "bottom"
            }
        }
        try { windowManager.updateViewLayout(webView, p) }
        catch (_: Exception) {}
    }
}