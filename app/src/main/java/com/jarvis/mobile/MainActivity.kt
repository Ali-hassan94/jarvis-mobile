package com.jarvis.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var phone: PhoneActions
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var statusView: TextView
    private lateinit var input: EditText
    private lateinit var micBtn: Button
    private lateinit var callBtn: Button
    private lateinit var settingsBox: LinearLayout
    private lateinit var baseEt: EditText
    private lateinit var pinEt: EditText
    private lateinit var langBtn: Button

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var sr: SpeechRecognizer? = null
    private var callOn = false
    private var busy = false
    private var listening = false
    private var pending = 0
    private var streamDone = true
    private var speakGen = 0
    private val history = ArrayList<Pair<String, String>>()

    private val watchdog = object : Runnable {
        override fun run() {
            if (callOn && !busy && !listening && streamDone) {
                if (pending > 0 && tts?.isSpeaking != true) pending = 0
                if (pending == 0) startListening()
            }
            main.postDelayed(this, 2500)
        }
    }

    // ---------------------------------------------------------------- UI helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radius: Int, stroke: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radius).toFloat()
        if (stroke != 0) d.setStroke(dp(1), stroke)
        return d
    }

    private fun pill(text: String, color: Int): Button {
        val b = Button(this)
        b.text = text
        b.setTextColor(Color.WHITE)
        b.setAllCaps(false)
        b.textSize = 14f
        b.background = bg(color, 22)
        b.setPadding(dp(14), dp(8), dp(14), dp(8))
        return b
    }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            r.addView(v, lp)
        }
        return r
    }

    private fun bubble(text: String, user: Boolean): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 15f
        tv.setTextColor(Color.WHITE)
        tv.setPadding(dp(14), dp(10), dp(14), dp(10))
        tv.background = if (user) bg(Color.parseColor("#4F46E5"), 18)
        else bg(Color.parseColor("#1FFFFFFF"), 18, Color.parseColor("#22FFFFFF"))
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.setMargins(0, dp(4), 0, dp(4))
        lp.gravity = if (user) Gravity.END else Gravity.START
        chat.addView(tv, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    private fun setStatus(s: String) {
        statusView.text = s
    }

    private fun updateButtons() {
        callBtn.text = if (callOn) "❌ Call khatam" else "📞 Call"
        callBtn.background = bg(if (callOn) Color.parseColor("#DC2626") else Color.parseColor("#0891B2"), 22)
        micBtn.text = if (listening && !callOn) "⏹ Ruko" else "🎤 Bolo"
    }

    private fun buildUi() {
        val outer = FrameLayout(this)
        outer.setBackgroundColor(Color.parseColor("#05060D"))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(18), dp(14), dp(10))
        val colLp = FrameLayout.LayoutParams(MATCH, MATCH)
        val maxW = dp(640)
        if (resources.displayMetrics.widthPixels > maxW) {
            colLp.width = maxW
            colLp.gravity = Gravity.CENTER_HORIZONTAL
        }
        outer.addView(col, colLp)

        val title = TextView(this)
        title.text = "NOVA"
        title.textSize = 30f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(Color.parseColor("#67E8F9"))
        title.letterSpacing = 0.25f
        title.gravity = Gravity.CENTER
        col.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))

        val chip = TextView(this)
        chip.text = "✦ Founder · Ali Hassan"
        chip.textSize = 11f
        chip.setTextColor(Color.parseColor("#DDD6FE"))
        chip.setPadding(dp(12), dp(4), dp(12), dp(4))
        chip.background = bg(Color.parseColor("#1F8B5CF6"), 14, Color.parseColor("#66A78BFA"))
        val chipLp = LinearLayout.LayoutParams(WRAP, WRAP)
        chipLp.gravity = Gravity.CENTER_HORIZONTAL
        chipLp.topMargin = dp(6)
        col.addView(chip, chipLp)

        statusView = TextView(this)
        statusView.textSize = 13f
        statusView.gravity = Gravity.CENTER
        statusView.setTextColor(Color.parseColor("#9CA3AF"))
        statusView.setPadding(0, dp(8), 0, dp(6))
        col.addView(statusView, LinearLayout.LayoutParams(MATCH, WRAP))

        // ---- Settings box ----
        settingsBox = LinearLayout(this)
        settingsBox.orientation = LinearLayout.VERTICAL
        settingsBox.setPadding(dp(12), dp(10), dp(12), dp(10))
        settingsBox.background = bg(Color.parseColor("#14FFFFFF"), 16, Color.parseColor("#22FFFFFF"))
        settingsBox.visibility = View.GONE

        val hint = TextView(this)
        hint.text = "Server ka address (Tailscale wala https link) aur PIN. Phone par Tailscale ON hona zaroori hai."
        hint.textSize = 12f
        hint.setTextColor(Color.parseColor("#9CA3AF"))
        settingsBox.addView(hint, LinearLayout.LayoutParams(MATCH, WRAP))

        baseEt = EditText(this)
        baseEt.hint = "https://desktop-xxxx.tailxxxx.ts.net"
        baseEt.setHintTextColor(Color.parseColor("#6B7280"))
        baseEt.setTextColor(Color.WHITE)
        baseEt.textSize = 14f
        baseEt.setSingleLine(true)
        baseEt.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        baseEt.setText(prefs.getString("base", ""))
        settingsBox.addView(baseEt, LinearLayout.LayoutParams(MATCH, WRAP))

        pinEt = EditText(this)
        pinEt.hint = "PIN"
        pinEt.setHintTextColor(Color.parseColor("#6B7280"))
        pinEt.setTextColor(Color.WHITE)
        pinEt.textSize = 14f
        pinEt.setSingleLine(true)
        pinEt.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        pinEt.setText(prefs.getString("pin", ""))
        settingsBox.addView(pinEt, LinearLayout.LayoutParams(MATCH, WRAP))

        langBtn = pill(langLabel(), Color.parseColor("#374151"))
        langBtn.setOnClickListener {
            val next = when (langMode()) {
                "roman" -> "ur"
                "ur" -> "en"
                else -> "roman"
            }
            prefs.edit().putString("lang", next).apply()
            langBtn.text = langLabel()
            applyVoice()
        }
        val saveBtn = pill("✔ Save karo", Color.parseColor("#7C3AED"))
        saveBtn.setOnClickListener {
            prefs.edit()
                .putString("base", baseEt.text.toString().trim())
                .putString("pin", pinEt.text.toString().trim())
                .apply()
            settingsBox.visibility = View.GONE
            testConnection()
        }
        settingsBox.addView(row(langBtn, saveBtn), LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(settingsBox, LinearLayout.LayoutParams(MATCH, WRAP))

        // ---- Chat ----
        scroll = ScrollView(this)
        chat = LinearLayout(this)
        chat.orientation = LinearLayout.VERTICAL
        scroll.addView(chat, ViewGroup.LayoutParams(MATCH, WRAP))
        col.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // ---- Buttons ----
        micBtn = pill("🎤 Bolo", Color.parseColor("#374151"))
        callBtn = pill("📞 Call", Color.parseColor("#0891B2"))
        val setBtn = pill("⚙ Settings", Color.parseColor("#374151"))
        micBtn.setOnClickListener {
            if (listening) {
                stopListening()
            } else {
                if (!hasMic()) {
                    askPermissions()
                } else {
                    startListening()
                }
            }
        }
        callBtn.setOnClickListener { toggleCall() }
        setBtn.setOnClickListener { toggleSettings() }
        col.addView(row(micBtn, callBtn, setBtn), LinearLayout.LayoutParams(MATCH, WRAP))

        // ---- Input ----
        input = EditText(this)
        input.hint = "Likhein ya bolein..."
        input.setHintTextColor(Color.parseColor("#6B7280"))
        input.setTextColor(Color.WHITE)
        input.textSize = 15f
        input.setSingleLine(true)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setPadding(dp(14), dp(10), dp(14), dp(10))
        input.background = bg(Color.parseColor("#14FFFFFF"), 22, Color.parseColor("#22FFFFFF"))
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendFromInput()
                true
            } else {
                false
            }
        }
        val sendBtn = pill("Send", Color.parseColor("#7C3AED"))
        sendBtn.setOnClickListener { sendFromInput() }

        val inRow = LinearLayout(this)
        inRow.orientation = LinearLayout.HORIZONTAL
        inRow.gravity = Gravity.CENTER_VERTICAL
        val inLp = LinearLayout.LayoutParams(0, WRAP, 1f)
        inLp.setMargins(dp(3), dp(3), dp(6), dp(3))
        inRow.addView(input, inLp)
        inRow.addView(sendBtn, LinearLayout.LayoutParams(WRAP, WRAP))
        col.addView(inRow, LinearLayout.LayoutParams(MATCH, WRAP))

        val foot = TextView(this)
        foot.text = "Made with ❤ by Ali Hassan · Founder of Nova"
        foot.textSize = 10f
        foot.gravity = Gravity.CENTER
        foot.setTextColor(Color.parseColor("#66FFFFFF"))
        foot.setPadding(0, dp(6), 0, 0)
        col.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))

        setContentView(outer)
    }

    private fun toggleSettings() {
        settingsBox.visibility = if (settingsBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun sendFromInput() {
        val t = input.text.toString()
        input.setText("")
        send(t)
    }

    // ---------------------------------------------------------------- Settings

    private fun langMode(): String = prefs.getString("lang", "roman") ?: "roman"

    private fun langLabel(): String = when (langMode()) {
        "ur" -> "🌐 Urdu script"
        "en" -> "🌐 English"
        else -> "🌐 Roman Urdu"
    }

    private fun apiBase(): String {
        var b = (prefs.getString("base", "") ?: "").trim().trimEnd('/')
        if (b.isEmpty()) return ""
        if (!b.startsWith("http")) b = "https://$b"
        if (!b.endsWith("/api") && !b.endsWith(":5298")) b += "/api"
        return b
    }

    private fun testConnection() {
        val base = apiBase()
        if (base.isEmpty()) {
            setStatus("Pehle server ka address likhein")
            return
        }
        setStatus("Server check ho raha hai...")
        val pin = prefs.getString("pin", "") ?: ""
        Thread {
            var msg: String
            try {
                val c = URL("$base/auth-check").openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 10000
                c.setRequestProperty("X-Jarvis-Token", pin)
                val code = c.responseCode
                msg = when {
                    code in 200..299 -> "✅ Server aur PIN theek hain"
                    code == 401 || code == 429 -> "PIN galat hai (ya bohat koshishein ho gayi)"
                    else -> "Server ne jawab diya: $code"
                }
            } catch (e: Exception) {
                msg = "Server tak nahi pahunch saki. Tailscale ON hai? Address sahi hai?"
            }
            runOnUiThread { setStatus(msg) }
        }.start()
    }

    // ---------------------------------------------------------------- Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("nova", MODE_PRIVATE)
        phone = PhoneActions(this)
        buildUi()
        updateButtons()
        tts = TextToSpeech(this) { st ->
            runOnUiThread {
                if (st == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts?.setPitch(1.1f)
                    applyVoice()
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            runOnUiThread { speechFinished(utteranceId) }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            runOnUiThread { speechFinished(utteranceId) }
                        }
                    })
                }
            }
        }
        bubble("Assalam o Alaikum! Main Nova hoon. Mujhe Ali Hassan ne banaya hai. Likhein, 🎤 bolein, ya 📞 call karein.", false)
        askPermissions()
        if (apiBase().isEmpty()) {
            settingsBox.visibility = View.VISIBLE
            setStatus("Pehle server ka address aur PIN likhein")
        } else {
            setStatus("Tayyar")
            testConnection()
        }
        main.postDelayed(watchdog, 2500)
    }

    override fun onResume() {
        super.onResume()
        if (callOn) main.postDelayed({ startListening() }, 500)
    }

    override fun onPause() {
        super.onPause()
        stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        sr?.destroy()
        tts?.stop()
        tts?.shutdown()
    }

    // ---------------------------------------------------------------- Permissions

    private fun hasMic(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val need = ArrayList<String>()
        if (!hasMic()) need.add(Manifest.permission.RECORD_AUDIO)
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.READ_CONTACTS)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 11)
    }

    // ---------------------------------------------------------------- Voice out (TTS)

    private fun applyVoice() {
        val t = tts ?: return
        if (!ttsReady) return
        val cands = when (langMode()) {
            "ur" -> listOf(Locale("ur", "PK"), Locale("ur"), Locale("en", "IN"), Locale.UK)
            "en" -> listOf(Locale.US, Locale.UK)
            else -> listOf(Locale("en", "IN"), Locale.UK, Locale.US)
        }
        for (l in cands) {
            val r = t.setLanguage(l)
            if (r >= TextToSpeech.LANG_AVAILABLE) return
        }
    }

    private fun speak(text: String) {
        val clean = text.trim()
        if (!ttsReady || clean.isEmpty() || clean.all { it == '.' || it == '…' }) return
        pending++
        val id = "n" + speakGen + "_" + System.nanoTime()
        tts?.speak(clean, TextToSpeech.QUEUE_ADD, null, id)
    }

    private fun speechFinished(uid: String?) {
        val gen = uid?.removePrefix("n")?.substringBefore("_")?.toIntOrNull() ?: return
        if (gen != speakGen) return
        pending = if (pending > 0) pending - 1 else 0
        afterSpeech()
    }

    private fun afterSpeech() {
        if (streamDone && pending == 0) {
            if (callOn) {
                setStatus("👂 Sun rahi hoon...")
                main.postDelayed({
                    if (callOn && !busy && pending == 0) startListening()
                }, 400)
            } else {
                setStatus("Tayyar")
            }
        }
    }

    // ---------------------------------------------------------------- Voice in (mic)

    private fun speechLocale(): String = if (langMode() == "en") "en-US" else "ur-PK"

    private val recListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            listening = false
            updateButtons()
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                callOn = false
                updateButtons()
                setStatus("Mic ki permission do (Phone Settings, Apps, Nova, Permissions)")
                return
            }
            if (!callOn) {
                setStatus("Tayyar")
                return
            }
            val wait = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200L else 700L
            main.postDelayed({
                if (callOn && !busy && pending == 0) startListening()
            }, wait)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            updateButtons()
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = if (list != null && list.isNotEmpty()) list[0] else ""
            input.setText("")
            if (text.isNotBlank()) {
                send(text)
            } else if (callOn) {
                main.postDelayed({ startListening() }, 500)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (list != null && list.isNotEmpty()) input.setText(list[0])
        }
    }

    private fun startListening() {
        if (listening || busy) return
        if (!hasMic()) {
            askPermissions()
            callOn = false
            updateButtons()
            setStatus("Mic ki permission do")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            callOn = false
            updateButtons()
            setStatus("Is phone par Google ki speech service nahi hai. Play Store se 'Speech Recognition & Synthesis' update karein.")
            return
        }
        try {
            sr?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            sr = r
            r.setRecognitionListener(recListener)
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLocale())
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            listening = true
            updateButtons()
            setStatus("👂 Sun rahi hoon...")
            r.startListening(i)
        } catch (e: Exception) {
            listening = false
            updateButtons()
            setStatus("Mic shuru nahi hua: " + (e.message ?: ""))
        }
    }

    private fun stopListening() {
        try {
            sr?.cancel()
        } catch (e: Exception) {
        }
        listening = false
        updateButtons()
    }

    // ---------------------------------------------------------------- Call mode

    private fun toggleCall() {
        if (callOn) {
            callOn = false
            stopListening()
            tts?.stop()
            speakGen++
            pending = 0
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            updateButtons()
            setStatus("Call khatam")
            return
        }
        if (apiBase().isEmpty()) {
            settingsBox.visibility = View.VISIBLE
            setStatus("Pehle server ka address aur PIN likhein")
            return
        }
        if (!hasMic()) {
            askPermissions()
            setStatus("Pehle mic ki permission dein, phir Call dabayein")
            return
        }
        callOn = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateButtons()
        applyVoice()
        streamDone = true
        speak("Hello! Main sun rahi hoon. Boliye.")
        if (pending == 0) startListening()
    }

    // ---------------------------------------------------------------- Server baat-cheet

    private fun send(text: String) {
        val t = text.trim()
        if (t.length < 2 || busy) return
        val base = apiBase()
        if (base.isEmpty()) {
            settingsBox.visibility = View.VISIBLE
            setStatus("Pehle server ka address aur PIN likhein")
            return
        }
        stopListening()
        tts?.stop()
        speakGen++
        pending = 0
        busy = true
        streamDone = false
        bubble(t, true)
        val botView = bubble("…", false)
        setStatus("🤔 Soch rahi hoon...")

        val pin = prefs.getString("pin", "") ?: ""
        val hist = JSONArray()
        for (h in history.takeLast(16)) {
            hist.put(JSONObject().put("role", h.first).put("text", h.second))
        }
        val body = JSONObject()
            .put("message", t)
            .put("history", hist)
            .put("lang", langMode())
            .put("device", "phone")
            .toString()

        val full = StringBuilder()
        val err = StringBuilder()

        Thread {
            var problem: String? = null
            try {
                val conn = URL("$base/talk").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 120000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("X-Jarvis-Token", pin)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code == 401 || code == 429) {
                    problem = "PIN galat hai ya bohat koshishein ho gayi hain. Settings mein PIN theek karein."
                } else if (code !in 200..299) {
                    problem = "Server ne error diya ($code)"
                } else {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                    reader.forEachLine { line ->
                        val s = line.trim()
                        if (s.isNotEmpty()) {
                            val ev: JSONObject? = try {
                                JSONObject(s)
                            } catch (e: Exception) {
                                null
                            }
                            if (ev != null) {
                                when (ev.optString("t")) {
                                    "s" -> {
                                        val tx = ev.optString("text")
                                        val dots = tx.trim().all { it == '.' || it == '…' }
                                        if (tx.isNotBlank() && !dots) {
                                            if (full.isNotEmpty()) full.append(" ")
                                            full.append(tx)
                                            val snap = full.toString()
                                            runOnUiThread {
                                                botView.text = snap
                                                speak(tx)
                                                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                                            }
                                        }
                                    }
                                    "a" -> {
                                        val nm = ev.optString("name")
                                        runOnUiThread { setStatus("⚙️ $nm") }
                                    }
                                    "phone" -> {
                                        val nm = ev.optString("name")
                                        val args = ev.optJSONObject("args") ?: JSONObject()
                                        runOnUiThread {
                                            val m = phone.run(nm, args)
                                            if (m.isNotEmpty()) setStatus("📱 $m")
                                        }
                                    }
                                    "err" -> err.append(ev.optString("text"))
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                problem = "Server tak nahi pahunch saki. Tailscale ON hai? (" + (e.message ?: "") + ")"
            }
            if (problem == null && err.isNotEmpty() && full.isEmpty()) problem = "Error: $err"
            val fp = problem
            runOnUiThread { endTurn(t, botView, full.toString(), fp) }
        }.start()
    }

    private fun endTurn(userText: String, botView: TextView, reply: String, problem: String?) {
        busy = false
        streamDone = true
        if (problem != null) {
            botView.text = problem
            botView.setTextColor(Color.parseColor("#FCA5A5"))
            setStatus("Error")
            callOn = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            updateButtons()
            return
        }
        if (reply.isBlank()) {
            chat.removeView(botView)
            setStatus("Tayyar")
        } else {
            history.add(Pair("user", userText))
            history.add(Pair("jarvis", reply))
        }
        if (pending == 0) afterSpeech()
    }
}
