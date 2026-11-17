package app.couchtv.launcher

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * A short spoken line through the TV's own text to speech voice (Google's on most Android TVs), for
 * "Welcome, Giel" when the person watching changes (home.say_welcome in config.json, app.js greet()).
 * The engine starts the first time it is needed and stays for the life of the app. A TV without an
 * engine simply says nothing. Nothing leaves the TV: the engine speaks on the TV itself.
 */
object Voice {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var waiting: Pair<String, String>? = null   // a line asked for before the engine was up

    @Synchronized
    fun say(c: Context, text: String, lang: String) {
        val line = text.trim().take(200)
        if (line.isEmpty()) return
        val engine = tts
        if (engine == null) {
            waiting = line to lang
            tts = TextToSpeech(c.applicationContext) { status -> started(status) }
            return
        }
        if (!ready) { waiting = line to lang; return }
        speak(engine, line, lang)
    }

    @Synchronized
    private fun started(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "no text to speech on this TV ($status)")
            runCatching { engine.shutdown() }
            tts = null
            waiting = null
            return
        }
        ready = true
        waiting?.let { (line, lang) -> speak(engine, line, lang) }
        waiting = null
    }

    private fun speak(engine: TextToSpeech, line: String, lang: String) {
        runCatching {
            val locale = Locale.forLanguageTag(lang.ifBlank { "en" })
            if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) engine.language = locale
            engine.speak(line, TextToSpeech.QUEUE_FLUSH, null, "couchtv-welcome")
        }.onFailure { Log.w(TAG, "could not speak: ${it.javaClass.simpleName}") }
    }

    private const val TAG = "couchtv-voice"
}
