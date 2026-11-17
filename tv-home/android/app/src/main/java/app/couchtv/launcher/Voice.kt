package app.couchtv.launcher

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice as TtsVoice
import android.util.Log
import java.util.Locale

/**
 * A short spoken line through the TV's own text to speech voice (Google's on most Android TVs), for
 * "Welcome, Giel" when the person watching changes (home.say_welcome in config.json, app.js greet()).
 * A line can come in parts, each in its own language, so a Dutch name in an English home is said by
 * the Dutch voice (a person's "name_lang"). The language can be a region ("en-US", home.voice), and
 * then the most natural voice the TV has stored for it is used; a full voice name (as logged under
 * "couchtv-voice") picks that one. A language the TV has no voice for uses its default voice.
 * The engine starts when the home opens and stays for the life of the app, and the voice chosen for
 * a language is remembered, so a line starts quickly. A TV without an engine simply says nothing.
 */
object Voice {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var waiting: List<Pair<String, String>>? = null   // asked for before the engine was up
    private val chosen = HashMap<String, TtsVoice?>()        // language or voice name -> voice (null: default)
    private var current: String? = null                      // what the engine is set to now

    @Synchronized
    fun say(c: Context, parts: List<Pair<String, String>>) {
        val clean = parts.map { (text, lang) -> text.trim().take(200) to lang }
            .filter { (text, _) -> text.any { it.isLetterOrDigit() } }
        val engine = tts
        if (clean.isEmpty()) {   // nothing to say: only start the engine, so the first real line is quick
            if (engine == null) tts = TextToSpeech(c.applicationContext) { status -> started(status) }
            return
        }
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
        runCatching {
            val us = engine.voices.orEmpty().filter { it.locale.language == "en" }.sortedBy { it.name }
            Log.i(TAG, "english voices: " + us.joinToString { "${it.name} q${it.quality}${if (it.isNetworkConnectionRequired) " net" else ""}${if ("notInstalled" in it.features) " not-installed" else ""}" })
        }
        waiting?.let { speak(engine, it) }
        waiting = null
    }

    /** The voice for a language ("en-US": the most natural one stored on the TV) or a voice name. */
    private fun pick(engine: TextToSpeech, lang: String): TtsVoice? = chosen.getOrPut(lang) {
        val all = engine.voices.orEmpty().filter { "notInstalled" !in it.features }
        all.firstOrNull { it.name.equals(lang, ignoreCase = true) } ?: run {
            val want = Locale.forLanguageTag(lang.ifBlank { "en" })
            val same = all.filter { it.locale.language == want.language &&
                (want.country.isEmpty() || it.locale.country.equals(want.country, ignoreCase = true)) }
            // on the TV itself first (no wait for the network), then the best quality, then a steady order
            same.sortedWith(compareBy<TtsVoice>({ it.isNetworkConnectionRequired }, { -it.quality }, { it.name })).firstOrNull()
        }.also { Log.i(TAG, "voice for $lang: ${it?.name ?: "the default"}") }
    }

    private fun speak(engine: TextToSpeech, parts: List<Pair<String, String>>) {
        runCatching {
            parts.forEachIndexed { i, (line, lang) ->
                // set only when it changes: each change asks the engine, which takes a moment
                if (current != lang) {
                    val v = pick(engine, lang)
                    if (v != null) engine.voice = v else engine.language = Locale.getDefault()
                    current = lang
                }
                engine.speak(line, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "couchtv-welcome-$i")
            }
        }.onFailure { Log.w(TAG, "could not speak: ${it.javaClass.simpleName}") }
    }

    private const val TAG = "couchtv-voice"
}
