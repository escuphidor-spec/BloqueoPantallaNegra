package com.example.blackscreenblocker

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import java.util.Calendar

class BlackScreenService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var blackOverlay: View? = null
    private var isOverlayShown = false
    private var currentForegroundPackage: String = ""

    private val handler = Handler(Looper.getMainLooper())

    private val scheduleCheckRunnable = object : Runnable {
        override fun run() {
            evaluateOverlayState(currentForegroundPackage)
            handler.postDelayed(this, 10_000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        handler.post(scheduleCheckRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkgName = event.packageName?.toString() ?: return

        if (isIgnoredSystemPackage(pkgName)) return

        currentForegroundPackage = pkgName
        evaluateOverlayState(pkgName)
    }

    private fun evaluateOverlayState(pkgName: String) {
        if (pkgName.isEmpty()) return

        val prefs = getSharedPreferences("blocker_prefs", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("enabled", true)
        if (!isEnabled) {
            hideBlackScreen()
            return
        }

        val blockedApps = prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()
        val startHour = prefs.getInt("start_hour", 22)
        val startMin = prefs.getInt("start_min", 0)
        val endHour = prefs.getInt("end_hour", 6)
        val endMin = prefs.getInt("end_min", 0)

        val shouldBlock = blockedApps.contains(pkgName) &&
                isWithinSchedule(startHour, startMin, endHour, endMin)

        if (shouldBlock) {
            showBlackScreen()
        } else {
            hideBlackScreen()
        }
    }

    private fun isWithinSchedule(startH: Int, startM: Int, endH: Int, endM: Int): Boolean {
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = startH * 60 + startM
        val endMinutes = endH * 60 + endM

        return if (startMinutes <= endMinutes) {
            currentMinutes in startMinutes..endMinutes
        } else {
            currentMinutes >= startMinutes || currentMinutes <= endMinutes
        }
    }

    private fun isIgnoredSystemPackage(pkg: String): Boolean {
        return pkg == packageName ||
                pkg == "com.android.systemui" ||
                pkg.contains("inputmethod") ||
                pkg.contains("honeyboard") ||
                pkg.contains("keyboard")
    }

    private fun showBlackScreen() {
        if (isOverlayShown) return

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            setOnTouchListener { _, _ -> true }
            isClickable = true
            isFocusable = false
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager?.addView(overlay, params)
            blackOverlay = overlay
            isOverlayShown = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hideBlackScreen() {
        if (!isOverlayShown) return
        try {
            blackOverlay?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            blackOverlay = null
            isOverlayShown = false
        }
    }

    override fun onInterrupt() {
        hideBlackScreen()
    }

    override fun onDestroy() {
        handler.removeCallbacks(scheduleCheckRunnable)
        hideBlackScreen()
        super.onDestroy()
    }
}
