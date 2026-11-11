package com.example.blackscreenblocker

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import java.util.Locale

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("blocker_prefs", Context.MODE_PRIVATE)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val btnAccessibility = Button(this).apply {
            text = "1. Activar Permiso de Accesibilidad"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        rootLayout.addView(btnAccessibility)

        val switchEnable = Switch(this).apply {
            text = "Activar bloqueo por horario"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 30, 0, 30)
            isChecked = prefs.getBoolean("enabled", true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("enabled", isChecked).apply()
            }
        }
        rootLayout.addView(switchEnable)

        val btnStartTime = Button(this)
        val btnEndTime = Button(this)

        fun updateTimeButtons() {
            val sh = prefs.getInt("start_hour", 22)
            val sm = prefs.getInt("start_min", 0)
            val eh = prefs.getInt("end_hour", 6)
            val em = prefs.getInt("end_min", 0)
            btnStartTime.text = String.format(Locale.getDefault(), "Hora Inicio: %02d:%02d", sh, sm)
            btnEndTime.text = String.format(Locale.getDefault(), "Hora Fin: %02d:%02d", eh, em)
        }
        updateTimeButtons()

        btnStartTime.setOnClickListener {
            val sh = prefs.getInt("start_hour", 22)
            val sm = prefs.getInt("start_min", 0)
            TimePickerDialog(this, { _, hourOfDay, minute ->
                prefs.edit().putInt("start_hour", hourOfDay).putInt("start_min", minute).apply()
                updateTimeButtons()
            }, sh, sm, true).show()
        }

        btnEndTime.setOnClickListener {
            val eh = prefs.getInt("end_hour", 6)
            val em = prefs.getInt("end_min", 0)
            TimePickerDialog(this, { _, hourOfDay, minute ->
                prefs.edit().putInt("end_hour", hourOfDay).putInt("end_min", minute).apply()
                updateTimeButtons()
            }, eh, em, true).show()
        }

        rootLayout.addView(btnStartTime)
        rootLayout.addView(btnEndTime)

        val titleApps = TextView(this).apply {
            text = "Selecciona las aplicaciones a bloquear:"
            setTextColor(Color.LTGRAY)
            textSize = 15f
            setPadding(0, 30, 0, 15)
        }
        rootLayout.addView(titleApps)

        val scrollView = ScrollView(this)
        val appsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        val selectedApps = prefs.getStringSet("blocked_apps", emptySet())?.toMutableSet() ?: mutableSetOf()

        val appList = resolveInfos
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != packageName }
            .sortedBy { it.second.lowercase() }

        for ((pkg, label) in appList) {
            val checkBox = CheckBox(this).apply {
                text = "$label ($pkg)"
                setTextColor(Color.WHITE)
                isChecked = selectedApps.contains(pkg)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedApps.add(pkg) else selectedApps.remove(pkg)
                    prefs.edit().putStringSet("blocked_apps", HashSet(selectedApps)).apply()
                }
            }
            appsContainer.addView(checkBox)
        }

        scrollView.addView(appsContainer)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)
    }
}
