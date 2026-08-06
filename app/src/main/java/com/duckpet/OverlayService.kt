package com.duckpet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var webView: WebView? = null
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

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
        } catch (e: Exception) {
            stopSelf()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            webView?.let { view ->
                if (view.isAttachedToWindow || Build.VERSION.SDK_INT < 19) {
                    windowManager.removeView(view)
                }
            }
        } catch (e: Exception) {
            // Ignore remove errors
        }
        webView = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "duck_pet_channel",
                "小黄鸭桌宠",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            channel.description = "小黄鸭桌宠正在运行"
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "duck_pet_channel")
                .setContentTitle("小黄鸭桌宠")
                .setContentText("小黄鸭正在屏幕上陪着你")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("小黄鸭桌宠")
                .setContentText("小黄鸭正在屏幕上陪着你")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun createOverlay() {
        try {
            val density = resources.displayMetrics.density
            val width = (160 * density).toInt()
            val height = (180 * density).toInt()

            val params = WindowManager.LayoutParams(
                width,
                height,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )

            params.gravity = Gravity.START or Gravity.TOP
            params.x = 50
            params.y = 200

            val wv = WebView(this)
            wv.setBackgroundColor(0x00000000)
            wv.isHorizontalScrollBarEnabled = false
            wv.isVerticalScrollBarEnabled = false
            wv.settings.javaScriptEnabled = true
            wv.settings.allowFileAccess = true
            wv.settings.loadWithOverviewMode = true
            wv.settings.useWideViewPort = true
            wv.settings.domStorageEnabled = true
            wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                }
            }

            wv.loadUrl("file:///android_asset/pet.html")

            wv.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isDragging = true
                            params.x = initialX + dx
                            params.y = initialY + dy
                            try {
                                windowManager.updateViewLayout(wv, params)
                            } catch (e: Exception) {
                                // Ignore layout errors during drag
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            wv.evaluateJavascript(
                                "javascript:handleClick()", null
                            )
                        }
                        true
                    }
                    else -> false
                }
            }

            windowManager.addView(wv, params)
            webView = wv
        } catch (e: Exception) {
            stopSelf()
        }
    }
}
