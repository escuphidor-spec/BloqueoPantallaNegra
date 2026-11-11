package com.example.blackscreenblocker

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.Toast
import java.util.Calendar

class BlackScreenService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var blackOverlay: View? = null
    private var isOverlayShown = false
    private var currentForegroundPackage: String = ""

    // Guarda la app desbloqueada por emergencia hasta que el usuario salga de ella
    private var bypassedPackage: String? = null

    // Combinacion secreta: Subir, Subir, Bajar, Bajar
    private val secretCombo = listOf(
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_DOWN
    )
    private val currentKeyInputs = mutableListOf<Int>()
    private var lastKeyEventTime = 0L

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

        // Si el usuario cambio de aplicacion o salio al inicio, se reinicia el desbloqueo de emergencia
        if (pkgName != bypassedPackage) {
            bypassedPackage = null
        }

        currentForegroundPackage = pkgName
        evaluateOverlayState(pkgName)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val isVolumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

        // Si la pantalla negra NO esta activa, dejar que los botones de volumen funcionen normal
        if (!isOverlayShown || !isVolumeKey) {
            return false
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            // Si pasan mas de 3 segundos entre pulsaciones, reiniciar la secuencia
            if (now - lastKeyEventTime > 3000L) {
                currentKeyInputs.clear()
            }
            lastKeyEventTime = now

            currentKeyInputs.add(keyCode)
            if (currentKeyInputs.size > secretCombo.size) {
                currentKeyInputs.removeAt(0)
            }

            if (currentKeyInputs == secretCombo) {
                currentKeyInputs.clear()
                bypassedPackage = currentForegroundPackage
                hideBlackScreen()
                Toast.makeText(
                    this,
                    "Emergencia: App desbloqueada hasta que salgas de ella",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Consumir el evento mientras la pantalla negra esta puesta para no alterar el volumen
        return true
    }

    private fun evaluateOverlayState(pkgName: String) {
        if (pkgName.isEmpty()) return

        // Si esta app fue desbloqueada con los botones de volumen en esta sesion, no bloquear
        if (pkgName == bypassedPackage) {
            hideBlackScreen()
            return
        }

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
                pkg == "android" ||
                pkg == "com.android.systemui" ||
                pkg.contains("permissioncontroller") ||
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
            currentKeyInputs.clear()
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
            currentKeyInputs.clear()
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
