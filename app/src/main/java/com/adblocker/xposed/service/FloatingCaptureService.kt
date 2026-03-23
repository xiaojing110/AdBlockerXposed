package com.adblocker.xposed.service

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
import android.widget.*
import kotlinx.coroutines.*
import com.adblocker.xposed.App
import com.adblocker.xposed.data.model.CaptureLog
import java.text.SimpleDateFormat
import java.util.*

/**
 * Floating window service that displays real-time packet capture results.
 * Shows a small draggable overlay on top of any app.
 */
class FloatingCaptureService : Service() {

    companion object {
        const val ACTION_ADD_CAPTURE = "action_add_capture"
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_BLOCKED = "extra_blocked"

        private var instance: FloatingCaptureService? = null

        fun isRunning(): Boolean = instance != null

        /**
         * Push a capture event from the hook (called from AdBlockHook).
         */
        fun pushCapture(packageName: String, url: String, host: String, blocked: Boolean) {
            instance?.addCapture(packageName, url, host, blocked)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private var captureCount = 0
    private val recentCaptures = mutableListOf<String>()
    private val maxRecentItems = 50

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createFloatingWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ADD_CAPTURE -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: ""
                val url = intent.getStringExtra(EXTRA_URL) ?: ""
                val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: ""
                val blocked = intent.getBooleanExtra(EXTRA_BLOCKED, false)
                addCapture(pkg, url, host, blocked)
            }
        }
        return START_STICKY
    }

    private fun createFloatingWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Main floating container
        floatingView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xDD222222.toInt())
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            alpha = 0.95f
        }

        // Title bar with close button
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(this).apply {
            text = "📡 抓包 0"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            id = android.R.id.title
        }

        val closeBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(0xFFFFFFFF.toInt())
            setPadding(dpToPx(8), 0, 0, 0)
            setOnClickListener { stopSelf() }
        }

        val minBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.arrow_down_float)
            setColorFilter(0xFFFFFFFF.toInt())
            setPadding(dpToPx(8), 0, 0, 0)
            setOnClickListener { toggleMinimize() }
        }

        titleBar.addView(titleText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleBar.addView(minBtn)
        titleBar.addView(closeBtn)

        // Capture list
        val scrollView = object : ScrollView(this) {
            private val maxH = dpToPx(300)
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val hSpec = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                    MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
                } else heightMeasureSpec
                super.onMeasure(widthMeasureSpec, hSpec)
            }
        }.apply {
            id = android.R.id.content
        }

        val captureList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            id = android.R.id.text1
        }

        val emptyText = TextView(this).apply {
            text = "等待抓包数据..."
            setTextColor(0xAAFFFFFF.toInt())
            textSize = 11f
            id = android.R.id.empty
        }
        captureList.addView(emptyText)

        scrollView.addView(captureList)

        (floatingView as LinearLayout).addView(titleBar)
        (floatingView as LinearLayout).addView(scrollView)

        // Layout params
        val layoutParams = WindowManager.LayoutParams(
            dpToPx(320),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(16)
            y = dpToPx(80)
        }

        windowManager.addView(floatingView, layoutParams)

        // Draggable
        setupDrag(floatingView, layoutParams)
    }

    private var isMinimized = false
    private fun toggleMinimize() {
        isMinimized = !isMinimized
        val scrollView = floatingView.findViewById<ScrollView>(android.R.id.content)
        scrollView?.visibility = if (isMinimized) View.GONE else View.VISIBLE
    }

    private fun addCapture(packageName: String, url: String, host: String, blocked: Boolean) {
        captureCount++

        // Update title
        val titleText = floatingView.findViewById<TextView>(android.R.id.title)
        titleText?.text = "📡 抓包 $captureCount"

        // Add to list
        val captureList = floatingView.findViewById<LinearLayout>(android.R.id.text1) ?: return
        val emptyView = captureList.findViewById<TextView>(android.R.id.empty)
        if (emptyView != null) captureList.removeView(emptyView)

        val icon = if (blocked) "🚫" else "✅"
        val shortPkg = packageName.substringAfterLast(".")
        val shortHost = if (host.length > 25) host.take(25) + "…" else host
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        val entry = TextView(this).apply {
            text = "$icon [$time] $shortPkg → $shortHost"
            setTextColor(if (blocked) 0xFFFF6666.toInt() else 0xFFFFFFFF.toInt())
            textSize = 11f
            setPadding(0, dpToPx(2), 0, dpToPx(2))
            setOnClickListener {
                // Show full details
                val details = "App: $packageName\nHost: $host\nURL: $url\n${if (blocked) "🚫 已拦截" else "✅ 放行"}"
                Toast.makeText(this@FloatingCaptureService, details, Toast.LENGTH_LONG).show()
            }
        }

        captureList.addView(entry, 0) // Add at top

        // Limit items
        while (captureList.childCount > maxRecentItems) {
            captureList.removeViewAt(captureList.childCount - 1)
        }

        // Also save to database
        try {
            val dao = App.instance.database.captureLogDao()
            CoroutineScope(Dispatchers.IO).launch {
                dao.insert(CaptureLog(
                    packageName = packageName,
                    url = url,
                    host = host,
                    method = "HOOK",
                    statusCode = if (blocked) 0 else 200,
                    contentType = "",
                    contentLength = 0,
                    headers = "",
                    requestBody = "",
                    responseBody = "",
                    isBlocked = blocked,
                    blockReason = if (blocked) "Ad blocked" else ""
                ))
            }
        } catch (_: Throwable) {}
    }

    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        instance = null
        try {
            windowManager.removeView(floatingView)
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
