package app.couchtv.launcher

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * A short spoken line through the TV's own text to speech voice (Google's on most Android TVs), for
 * "Welcome, Giel" when the person watching changes (home.say_welcome in config.json, app.js greet()).
 * A line can come in parts, each in its own language, so a Dutch name in an English home is said by
 * the Dutch voice (a person's "name_lang"). A language the TV has no voice for uses its default voice.
 * The engine starts the first time it is needed and stays for the life of the app. A TV without an
 * engine simply says nothing. Nothing leaves the TV: the engine speaks on the TV itself.
 */
object Voice {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var waiting: List<Pair<String, String>>? = null   // asked for before the engine was up

    @Synchronized
    fun say(c: Context, parts: List<Pair<String, String>>) {
        val clean = parts.map { (text, lang) -> text.trim().take(200) to lang }
            .filter { (text, _) -> text.any { it.isLetterOrDigit() } }
        if (clean.isEmpty()) return
        val engine = tts
        if (engine == null) {
            waiting = clean
            tts = TextToSpeech(c.applicationContext) { status -> started(status) }
            return
        }
        if (!ready) { waiting = clean; return }
        speak(engine, clean)
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
        waiting?.let { speak(engine, it) }
        waiting = null
    }

    private fun speak(engine: TextToSpeech, parts: List<Pair<String, String>>) {
        runCatching {
            val fallback = engine.defaultVoice?.locale ?: Locale.getDefault()
            parts.forEachIndexed { i, (line, lang) ->
                val locale = Locale.forLanguageTag(lang.ifBlank { "en" })
                // the language goes with each request, so every part keeps its own voice
                engine.language = if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) locale else fallback
                engine.speak(line, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "couchtv-welcome-$i")
            }
        }.onFailure { Log.w(TAG, "could not speak: ${it.javaClass.simpleName}") }
    }

    private const val TAG = "couchtv-voice"
}
