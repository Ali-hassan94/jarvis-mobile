package com.jarvis.mobile

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import org.json.JSONObject
import java.util.Locale

/** Server (Nova) ke "phone_*" commands yahan phone par chalte hain. */
class PhoneActions(private val act: Activity) {

    fun run(name: String, a: JSONObject): String {
        return try {
            when (name) {
                "phone_open_app" -> openApp(a.optString("name"))
                "phone_open_url" -> openUrl(a.optString("url"))
                "phone_call" -> call(a.optString("who"))
                "phone_sms" -> sms(a.optString("who"), a.optString("text"))
                "phone_flashlight" -> torch(a.optBoolean("on", true))
                "phone_alarm" -> alarm(a.optInt("hour"), a.optInt("minute"), a.optString("label"))
                "phone_timer" -> timer(a.optInt("seconds", 60))
                "phone_volume" -> volume(a.optString("action"))
                "phone_navigate" -> navigate(a.optString("query"))
                "phone_settings" -> settings(a.optString("page"))
                else -> "Ye phone command samajh nahi aayi: $name"
            }
        } catch (e: Exception) {
            "Phone command nahi chali: " + (e.message ?: name)
        }
    }

    private fun go(i: Intent): Boolean {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            act.startActivity(i)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun openApp(query: String): String {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return "App ka naam nahi mila"
        val pm = act.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        var best: ResolveInfo? = null
        for (ri in apps) {
            val label = ri.loadLabel(pm).toString().lowercase(Locale.ROOT)
            if (label == q) {
                best = ri
                break
            }
            if (best == null && (label.contains(q) || (label.length >= 3 && q.contains(label)))) {
                best = ri
            }
        }
        if (best == null) return "Phone mein '$query' naam ki app nahi mili"
        val launch = pm.getLaunchIntentForPackage(best.activityInfo.packageName)
            ?: return "'$query' khul nahi saki"
        return if (go(launch)) "$query khul gayi" else "'$query' khul nahi saki"
    }

    private fun openUrl(url: String): String {
        var u = url.trim()
        if (u.isEmpty()) return "Link nahi mila"
        if (!u.startsWith("http")) u = "https://$u"
        return if (go(Intent(Intent.ACTION_VIEW, Uri.parse(u)))) "Link khul gaya" else "Link nahi khula"
    }

    private fun resolveNumber(who: String): String? {
        val w = who.trim()
        if (w.isEmpty()) return null
        val looksLikeNumber = w.any { it.isDigit() } && w.none { it.isLetter() }
        if (looksLikeNumber) return w.filter { it.isDigit() || it == '+' }
        if (act.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        var number: String? = null
        val cur = act.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            ),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$w%"),
            null
        )
        cur?.use {
            if (it.moveToFirst()) number = it.getString(0)
        }
        return number
    }

    private fun call(who: String): String {
        val number = resolveNumber(who)
        return if (number != null) {
            go(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
            "Dialer khul gaya, hara call button khud dabayein"
        } else {
            go(Intent(Intent.ACTION_DIAL))
            "'$who' ka number nahi mila, dialer khol diya"
        }
    }

    private fun sms(who: String, text: String): String {
        val number = resolveNumber(who) ?: ""
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
        i.putExtra("sms_body", text)
        return if (go(i)) "Message tayyar hai, send khud dabayein" else "Messages app nahi khuli"
    }

    private fun torch(on: Boolean): String {
        val cm = act.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var id: String? = null
        for (cid in cm.cameraIdList) {
            val flash = cm.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
            if (flash == true) {
                id = cid
                break
            }
        }
        if (id == null) return "Is phone mein torch nahi mili"
        cm.setTorchMode(id, on)
        return if (on) "Torch on" else "Torch off"
    }

    private fun alarm(hour: Int, minute: Int, label: String): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
        i.putExtra(AlarmClock.EXTRA_HOUR, hour)
        i.putExtra(AlarmClock.EXTRA_MINUTES, minute)
        i.putExtra(AlarmClock.EXTRA_MESSAGE, if (label.isBlank()) "Nova" else label)
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (go(i)) "Alarm laga diya: " + String.format(Locale.US, "%02d:%02d", hour, minute) else "Clock app nahi mili"
    }

    private fun timer(seconds: Int): String {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
        i.putExtra(AlarmClock.EXTRA_LENGTH, seconds)
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (go(i)) "Timer shuru: $seconds second" else "Clock app nahi mili"
    }

    private fun volume(action: String): String {
        val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (action.lowercase(Locale.ROOT)) {
            "up" -> {
                for (n in 0 until 3) {
                    am.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE,
                        if (n == 2) AudioManager.FLAG_SHOW_UI else 0
                    )
                }
                return "Awaaz barha di"
            }
            "down" -> {
                for (n in 0 until 3) {
                    am.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER,
                        if (n == 2) AudioManager.FLAG_SHOW_UI else 0
                    )
                }
                return "Awaaz kam kar di"
            }
            else -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                return "Awaaz band"
            }
        }
    }

    private fun navigate(query: String): String {
        val q = query.trim()
        if (q.isEmpty()) return "Jagah ka naam nahi mila"
        if (go(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))) return "Map khul gaya"
        val web = "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(q)
        return if (go(Intent(Intent.ACTION_VIEW, Uri.parse(web)))) "Map khul gaya" else "Map nahi khula"
    }

    private fun settings(page: String): String {
        val action = when (page.trim().lowercase(Locale.ROOT)) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return if (go(Intent(action))) "Settings khul gayi" else "Settings nahi khuli"
    }
}
