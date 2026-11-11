package com.example.blackscreenblocker

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
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

    // Nueva combinacion secreta: Abajo, Abajo, Arriba, Arriba
    private val secretCombo = listOf(
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_UP
    )
    private val currentKeyInputs = mutableListOf<Int>()
    private var lastKeyEventTime = 0L

    private val handler = Handler(Looper.getMainLooper())

    private val scheduleCheckRunnable = object : Runnable {
        override fun run() {
            verifyActiveWindowAndEvaluate()
            handler.postDelayed(this, 5_000L)
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

        // Si la pantalla negra esta activa y el usuario intenta bajar la barra de estado, cerrarla al instante
        if (pkgName == "com.android.systemui") {
            if (isOverlayShown) {
                collapseStatusBar()
            }
            return
        }

        // Ignorar paneles del sistema, teclados y subprocesos de la barra de notificaciones
        if (isIgnoredSystemPackage(pkgName)) return

        // Si el usuario sale al Launcher (Inicio / Recientes), desbloquear de inmediato
        if (isLauncherPackage(pkgName)) {
            bypassedPackage = null
            currentForegroundPackage = pkgName
            hideBlackScreen()
            return
        }

        // Ignorar paquetes que no sean aplicaciones instaladas abribles
        if (!isLaunchableApp(pkgName)) return

        // Si la pantalla negra ya esta activa y entra un evento de otra app que no es el Launcher,
        // verificar con rootInActiveWindow que no sea un widget dentro de la barra de estado
        if (isOverlayShown && pkgName != currentForegroundPackage) {
            handler.postDelayed({
                verifyActiveWindowAndEvaluate()
            }, 200L)
            return
        }

        if (pkgName != bypassedPackage) {
            bypassedPackage = null
        }

        currentForegroundPackage = pkgName
        evaluateOverlayState(pkgName)
    }

    private fun verifyActiveWindowAndEvaluate() {
        val activePkg = rootInActiveWindow?.packageName?.toString()
        if (!activePkg.isNullOrEmpty() && activePkg != "com.android.systemui" && !isIgnoredSystemPackage(activePkg)) {
            if (isLauncherPackage(activePkg) || isLaunchableApp(activePkg)) {
                if (activePkg != bypassedPackage) {
                    bypassedPackage = null
                }
                currentForegroundPackage = activePkg
            }
        }
        evaluateOverlayState(currentForegroundPackage)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val isVolumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

        // Si la pantalla negra NO esta activa, los botones de volumen funcionan normal
        if (!isOverlayShown || !isVolumeKey) {
            return false
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val now = System.currentTimeMillis()
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

        return true
    }

    private fun evaluateOverlayState(pkgName: String) {
        if (pkgName.isEmpty()) return

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

    private fun collapseStatusBar() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            } else {
                @Suppress("DEPRECATION")
                sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isLauncherPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("launcher") || p.contains("home") || p == "com.sec.android.app.launcher"
    }

    private fun isLaunchableApp(pkg: String): Boolean {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(pkg)
        }
        val list = packageManager.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        return list.isNotEmpty()
    }

    private fun isIgnoredSystemPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return pkg == packageName ||
                pkg == "android" ||
                pkg == "com.android.systemui" ||
                p.contains("systemui") ||
                p.contains("quickboard") ||       // Panel de medios/dispositivos en barra de estado Samsung
                p.contains("cocktailbar") ||      // Panel Edge de Samsung
                p.contains("oneconnect") ||       // Control de dispositivos en barra de notificaciones
                p.contains("permissioncontroller") ||
                p.contains("inputmethod") ||
                p.contains("honeyboard") ||
                p.contains("keyboard") ||
                p.contains("biometrics")
    }

    private fun showBlackScreen() {
        if (isOverlayShown) return

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            setOnTouchListener { _, _ ->
                collapseStatusBar()
                true
            }
            isClickable = true
            isFocusable = false
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Extiende la pantalla negra hasta la franja superior de la camara/barra de estado
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            windowManager?.addView(overlay, params)
            blackOverlay = overlay
            isOverlayShown = true
            currentKeyInputs.clear()
            collapseStatusBar()
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
