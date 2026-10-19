package com.jarvis.mobile

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var urlBox: EditText
    private lateinit var pinBox: EditText
    private lateinit var wakeBox: CheckBox
    private lateinit var urduBox: CheckBox
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private var permStep = 0

    private val prefs by lazy { getSharedPreferences("jarvis", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(24), dp(16), dp(12))
        }

        fun add(v: View) {
            root.addView(v, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }

        fun label(t: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
        }

        fun field(hintText: String, pass: Boolean) = EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF1A1A1A.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isSingleLine = true
            inputType = if (pass)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }

        fun button(t: String, color: Int, onClick: () -> Unit) = Button(this).apply {
            text = t
            setTextColor(Color.WHITE)
            setBackgroundColor(color)
            setOnClickListener { onClick() }
        }

        add(label("JARVIS Mobile", 28f))
        add(label("PC wali JARVIS API ka address (jaise 192.168.1.9) aur PIN likhein.", 13f, Color.GRAY))

        urlBox = field("PC ka address, jaise 192.168.1.9", false)
        pinBox = field("PIN", true)
        add(urlBox)
        add(pinBox)

        wakeBox = CheckBox(this).apply { text = "Wake word \"Jarvis\" zaroori"; setTextColor(Color.WHITE) }
        urduBox = CheckBox(this).apply { text = "اردو (Urdu mein baat)"; setTextColor(Color.WHITE) }
        add(wakeBox)
        add(urduBox)

        add(button("🔌 Connection test", 0xFF333333.toInt()) { testConnection() })
        add(button("🔓 Background aur pop-up allow (bar bar dabayein)", 0xFF333333.toInt()) { permissionStep() })
        add(button("▶️ JARVIS shuru karo", 0xFF1B7F3B.toInt()) { start() })
        add(button("⏹️ Band karo", 0xFF8B1A1A.toInt()) {
            stopService(Intent(this, JarvisService::class.java))
        })

        statusView = label("Band hai", 16f, 0xFF4DD0E1.toInt())
        add(statusView)

        logView = label("", 12f, Color.LTGRAY)
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(8) })

        setContentView(root)

        urlBox.setText(prefs.getString("url", ""))
        pinBox.setText(prefs.getString("pin", ""))
        wakeBox.isChecked = prefs.getBoolean("wake", true)
        urduBox.isChecked = prefs.getBoolean("urdu", false)
    }

    override fun onResume() {
        super.onResume()
        JarvisService.onStatus = { s -> statusView.text = s }
        JarvisService.onLog = { l -> appendLog(l) }
        statusView.text = JarvisService.status
        logView.text = synchronized(JarvisService.logBuf) { JarvisService.logBuf.joinToString("\n") }
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onPause() {
        super.onPause()
        JarvisService.onStatus = null
        JarvisService.onLog = null
    }

    private fun appendLog(line: String) {
        logView.append((if (logView.text.isEmpty()) "" else "\n") + line)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun save() {
        prefs.edit()
            .putString("url", urlBox.text.toString().trim())
            .putString("pin", pinBox.text.toString())
            .putBoolean("wake", wakeBox.isChecked)
            .putBoolean("urdu", urduBox.isChecked)
            .apply()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun testConnection() {
        save()
        val base = normBase(urlBox.text.toString())
        val pin = pinBox.text.toString()
        if (base.isEmpty()) { toast("Pehle PC ka address likhein"); return }
        Thread {
            val msg = try {
                val c = URL("$base/auth-check").openConnection() as HttpURLConnection
                c.connectTimeout = 6000
                c.readTimeout = 6000
                c.setRequestProperty("X-Jarvis-Token", pin)
                val code = c.responseCode
                c.disconnect()
                when (code) {
                    200 -> "✅ Connected! PIN sahi hai"
                    401 -> "⚠️ Server mila, lekin PIN galat hai"
                    else -> "⚠️ Server ne $code diya"
                }
            } catch (e: Exception) {
                "❌ Server tak nahi pahunch paya. Phone aur PC ek hi WiFi par hon, aur address check karein"
            }
            runOnUiThread { toast(msg) }
        }.start()
    }

    private fun permissionStep() {
        when (permStep % 3) {
            0 -> {
                if (!Settings.canDrawOverlays(this)) {
                    toast("JARVIS ke saamne ON karein: \"Display over other apps\"")
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else toast("✅ Pop-up permission mil chuki hai. Dobara dabayein")
            }
            1 -> {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } else toast("✅ Battery permission mil chuki hai. Dobara dabayein")
            }
            else -> {
                toast("Yahan: Autostart ON, Battery \"No restrictions\", aur \"Display pop-up windows while running in the background\" ON karein")
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        permStep++
    }

    private fun start() {
        save()
        if (normBase(urlBox.text.toString()).isEmpty()) { toast("Pehle PC ka address likhein"); return }

        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO)
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 1)
            return
        }
        launchService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) launchService()
        else toast("Mic ki permission ke bagair JARVIS nahi sun sakta")
    }

    private fun launchService() {
        val i = Intent(this, JarvisService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        toast("JARVIS shuru. Ab Home dabayein aur kuch bhi kholein.")
    }
}