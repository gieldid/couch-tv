package app.couchtv.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Two remote buttons that some TVs never hand to the right place. An accessibility service sees keys
 * before Android's global-key table, so this one catches only these and passes every other key on
 * untouched. It reads no screen content.
 *
 * The gear (Settings) button: on some TVs (seen on an ONVO / MediaTek Google TV) it never reaches
 * any app: Android hands it to the maker's hot-key agent or to the Google TV home, and with the
 * Google TV home switched off nothing happens. Here it opens couch-tv's own gear menu.
 *
 * Volume up, down and mute, only when this TV has an amplifier in config.json (the bridge says so in
 * /api/tvs): they go to the bridge's /api/volume, which sets the amplifier (bridge/amplifier.py), and
 * the TV's own volume stays where it is. A held button is sent as a few steps at a time, never more
 * than one call in flight, so the amplifier never falls behind. Without an amplifier, or without a
 * bridge (option A), the volume keys are not touched.
 *
 * Some TVs handle volume up and down before any accessibility service sees them (seen on a Philips
 * TPM191E, Android 12: mute reaches onKeyEvent, volume up and down never do). There the TV's own
 * volume moves, and Android says so with VOLUME_CHANGED_ACTION: each step it moved goes to the
 * amplifier. Once the amplifier has caught up, the TV's own volume is set to the amplifier's level
 * (both 0 to 100 steps of 1 on a Denon; the TV's top caps it), so the TV's volume bar shows the
 * amplifier's number. That suits a TV whose speakers are off and whose sound goes to the amplifier.
 * From the first such change on, couch-tv's own note is left out, except when the amplifier does not answer.
 * On such a TV this service also stops filtering keys: while it does, Android makes no repeats of a
 * held key, so holding volume up or down moved only one step (Philips TPM191E). The mute key then
 * stays with the TV too, and the TV's mute is followed (STREAM_MUTE_CHANGED_ACTION): the amplifier
 * mutes and unmutes with it. The gear button is not caught on such a TV.
 *
 * Off until the person at the TV switches it on: Settings, System (or Device Preferences),
 * Accessibility, "Home gear button and amplifier volume". Android switches it off again whenever
 * the app is reinstalled. undo.sh takes it away with couch-tv's other accessibility services.
 */
class KeyWatch : AccessibilityService() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pending = 0          // volume steps pressed but not sent yet (+ up, - down)
    private var mutes = 0            // mute presses not sent yet
    private var muteTo: Boolean? = null   // the TV's own mute, to copy to the amplifier
    private var sending = false
    private var checkedAt = 0L
    private var toast: Toast? = null
    private var audio: AudioManager? = null
    private var echo = -1            // the level of our own setStreamVolume, whose broadcast is skipped

    /** The TV moved its own volume (the keys never reached [onKeyEvent]): hand the step on. */
    private val volumeChanged = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getIntExtra(EXTRA_STREAM, -1) != AudioManager.STREAM_MUSIC) return
            val value = i.getIntExtra(EXTRA_VALUE, -1)
            val prev = i.getIntExtra(EXTRA_PREV, -1)
            if (value < 0 || prev < 0 || value == prev) return
            if (echo >= 0 && value == echo) { echo = -1; return }
            if (!ampOn()) return
            // this TV draws its own volume bar, which now shows the amplifier's level: no note of our own
            if (!prefs().getBoolean(TV_BAR_PREF, false)) { prefs().edit().putBoolean(TV_BAR_PREF, true).apply(); keysOff() }
            pending += value - prev
            Log.i(TAG, "tv volume $prev -> $value")
            pump()
        }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {}
    override fun onInterrupt() {}

    /** The TV's own mute changed (on a TV whose volume keys never reach us): the amplifier follows. */
    private val muteChanged = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getIntExtra(EXTRA_STREAM, -1) != AudioManager.STREAM_MUSIC) return
            if (!i.hasExtra(EXTRA_MUTED) || !prefs().getBoolean(TV_BAR_PREF, false) || !ampOn()) return
            muteTo = i.getBooleanExtra(EXTRA_MUTED, false)
            Log.i(TAG, "tv mute $muteTo")
            pump()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        io.execute { checkAmp() }
        audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        runCatching {
            for ((r, f) in listOf(volumeChanged to IntentFilter(VOLUME_CHANGED), muteChanged to IntentFilter(MUTE_CHANGED))) {
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
                else registerReceiver(r, f)
            }
        }
        if (prefs().getBoolean(TV_BAR_PREF, false)) keysOff()
    }

    /** Stop filtering keys (see the class comment): held volume keys repeat again on this TV. */
    private fun keysOff() {
        runCatching {
            val info = serviceInfo ?: return
            if (info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS == 0) return
            info.flags = info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
            serviceInfo = info
            Log.i(TAG, "key filter off: this TV keeps its volume keys")
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(volumeChanged) }
        runCatching { unregisterReceiver(muteChanged) }
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (isVolume(event)) return volume(event)
        if (!isGear(event)) return false   // not ours: Android gets it as usual
        Log.i(TAG, "gear keyCode=${event.keyCode} scan=${event.scanCode} action=${event.action}")
        if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
            runCatching {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("gear", true))
            }
        }
        return true   // both the press and the release, so the old (dead) route never sees it
    }

    // ---------------------------------------------------------------- amplifier volume

    private fun prefs() = getSharedPreferences("home", Context.MODE_PRIVATE)

    /** A volume key: true (taken) when this TV's volume belongs to an amplifier. */
    private fun volume(event: KeyEvent): Boolean {
        val now = System.currentTimeMillis()
        if (!ampOn()) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> pending += 1
                KeyEvent.KEYCODE_VOLUME_DOWN -> pending -= 1
                KeyEvent.KEYCODE_VOLUME_MUTE -> if (event.repeatCount == 0) mutes += 1
            }
            pump()
        }
        return true   // the press and the release: the TV's own volume never moves
    }

    /** Whether this TV's volume belongs to an amplifier (asks the bridge again now and then). */
    private fun ampOn(): Boolean {
        val now = System.currentTimeMillis()
        if (now - checkedAt > RECHECK_MS) { checkedAt = now; io.execute { checkAmp() } }
        return prefs().getBoolean(AMP_PREF, false) && prefs().getString("bridge", null) != null
    }

    /** Send what has been pressed, one call at a time (runs on the main thread). */
    private fun pump() {
        if (sending || (pending == 0 && mutes == 0 && muteTo == null)) return
        val body = JSONObject()
        val m = muteTo
        if (m != null) {
            muteTo = null
            body.put("action", "mute").put("muted", m)
        } else if (mutes > 0) {
            mutes -= 1
            body.put("action", "mute")
        } else {
            val steps = minOf(Math.abs(pending), MAX_STEPS)
            body.put("action", if (pending > 0) "up" else "down").put("steps", steps)
            pending -= if (pending > 0) steps else -steps
        }
        sending = true
        io.execute {
            val reply = call("POST", "/api/volume", body)
            main.post {
                sending = false
                show(reply)
                pump()
                if (!sending) matchTv(reply)
            }
        }
    }

    /** Everything pressed has reached the amplifier: set the TV's own volume to its level, so the TV's
     *  bar shows the same number. Never the TV's top, where volume up would no longer register. */
    private fun matchTv(reply: JSONObject?) {
        val am = audio ?: return
        val v = reply?.optJSONObject("volume") ?: return
        if (v.isNull("level")) return
        val target = v.optInt("level").coerceIn(0, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) - 1)
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == target) return
        echo = target
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
    }

    /** The amplifier's level for a moment over whatever is on screen. */
    private fun show(reply: JSONObject?) {
        val v = reply?.optJSONObject("volume")
        val text = when {
            reply == null || v == null -> getString(R.string.amp_unreachable)
            prefs().getBoolean(TV_BAR_PREF, false) -> return   // the TV's own bar shows it (see volumeChanged)
            v.optBoolean("muted") -> getString(R.string.amp_muted, ampName())
            v.has("level") && !v.isNull("level") -> getString(R.string.amp_level, ampName(), v.optInt("level"))
            else -> return
        }
        toast?.cancel()
        toast = Toast.makeText(this, text, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun ampName() = prefs().getString(AMP_NAME_PREF, null) ?: getString(R.string.amp_default_name)

    /** Ask the bridge whether this TV has an amplifier, and remember it (runs on [io]). */
    private fun checkAmp() {
        if (prefs().getString("bridge", null) == null) { prefs().edit().putBoolean(AMP_PREF, false).apply(); return }
        val r = call("GET", "/api/tvs", null) ?: return   // no answer: keep what was known
        val list = r.optJSONArray("tvs") ?: return
        val mine = prefs().getString("tv", null) ?: r.optString("default")
        var amp: JSONObject? = null
        for (i in 0 until list.length()) {
            val row = list.optJSONObject(i) ?: continue
            if (row.optString("id") == mine) amp = row.optJSONObject("amplifier")
        }
        prefs().edit().putBoolean(AMP_PREF, amp != null).putString(AMP_NAME_PREF, amp?.optString("name")?.takeIf { it.isNotBlank() }).apply()
        Log.i(TAG, "amplifier volume ${if (amp != null) "on" else "off"} for ${mine}")
    }

    /** One call to the bridge, with this TV's id and the pairing key. The reply, or null. */
    private fun call(method: String, path: String, body: JSONObject?): JSONObject? {
        val base = prefs().getString("bridge", null) ?: return null
        val tv = prefs().getString("tv", null)
        val key = prefs().getString("pairing_key", null)
        var c: HttpURLConnection? = null
        return try {
            val url = if (method == "GET" && !tv.isNullOrEmpty()) "$base$path?tv=" + URLEncoder.encode(tv, "UTF-8") else base + path
            c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 3000
            c.readTimeout = 6000
            c.requestMethod = method
            if (!key.isNullOrEmpty()) c.setRequestProperty("Authorization", "Bearer $key")
            if (body != null) {
                if (!tv.isNullOrEmpty()) body.put("tv", tv)
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            if (c.responseCode !in 200..299) { Log.w(TAG, "$path answered ${c.responseCode}"); return null }
            JSONObject(c.inputStream.bufferedReader().use { it.readText().take(64 * 1024) })
        } catch (e: Exception) {
            Log.w(TAG, "$path failed: ${e.javaClass.simpleName}")
            null
        } finally {
            c?.disconnect()
        }
    }

    companion object {
        private const val TAG = "couchtv-keywatch"
        private const val AMP_PREF = "amp_volume"
        private const val AMP_NAME_PREF = "amp_name"
        private const val TV_BAR_PREF = "amp_tv_bar"
        private const val RECHECK_MS = 5 * 60_000L
        private const val MAX_STEPS = 10
        // AudioManager's own names for these are hidden from apps; the broadcast itself is public.
        private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
        private const val MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"
        private const val EXTRA_MUTED = "android.media.EXTRA_STREAM_VOLUME_MUTED"

        /** The ONVO remote's gear: KEYCODE_BUTTON_14 (201), raw Linux KEY_TAPE (0x180 = 384), which
         *  the global-key table hands to MediaTek's hot-key agent. 176 is Android's own Settings key,
         *  kept for remotes that send it. */
        const val GEAR_SCAN = 384

        fun isGear(e: KeyEvent): Boolean = e.keyCode == KeyEvent.KEYCODE_BUTTON_14 ||
            e.scanCode == GEAR_SCAN || e.keyCode == KeyEvent.KEYCODE_SETTINGS

        fun isVolume(e: KeyEvent): Boolean = e.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || e.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
    }
}
