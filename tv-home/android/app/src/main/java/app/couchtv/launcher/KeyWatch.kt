package app.couchtv.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
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
 * Off until the person at the TV switches it on: Settings, System (or Device Preferences),
 * Accessibility, "Home gear button and amplifier volume". Android switches it off again whenever
 * the app is reinstalled. undo.sh takes it away with couch-tv's other accessibility services.
 */
class KeyWatch : AccessibilityService() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pending = 0          // volume steps pressed but not sent yet (+ up, - down)
    private var mutes = 0            // mute presses not sent yet
    private var sending = false
    private var checkedAt = 0L
    private var toast: Toast? = null

    override fun onAccessibilityEvent(e: AccessibilityEvent) {}
    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        io.execute { checkAmp() }
    }

    override fun onDestroy() {
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
        if (now - checkedAt > RECHECK_MS) { checkedAt = now; io.execute { checkAmp() } }
        if (!prefs().getBoolean(AMP_PREF, false) || prefs().getString("bridge", null) == null) return false
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

    /** Send what has been pressed, one call at a time (runs on the main thread). */
    private fun pump() {
        if (sending || (pending == 0 && mutes == 0)) return
        val body = JSONObject()
        if (mutes > 0) {
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
            }
        }
    }

    /** The amplifier's level for a moment over whatever is on screen. */
    private fun show(reply: JSONObject?) {
        val v = reply?.optJSONObject("volume")
        val text = when {
            reply == null || v == null -> getString(R.string.amp_unreachable)
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
        private const val RECHECK_MS = 5 * 60_000L
        private const val MAX_STEPS = 10

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
