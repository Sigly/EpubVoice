package com.ethan.epubvoice

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PALETTE = intArrayOf(
    0xFFFFD54F.toInt(), 0xFF81C784.toInt(), 0xFF64B5F6.toInt(), 0xFFE57373.toInt(),
    0xFFBA68C8.toInt(), 0xFF4DD0E1.toInt(), 0xFFFFB74D.toInt(), 0xFFF06292.toInt(),
    0xFFAED581.toInt(), 0xFF90A4AE.toInt()
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val app: Application get() = getApplication()
    private val prefs = Prefs(app)

    var settings by mutableStateOf(prefs.load())
        private set
    var book by mutableStateOf<Book?>(null)
        private set
    var chapterIndex by mutableIntStateOf(0)
        private set
    var paragraphIndex by mutableIntStateOf(0)
        private set
    var playing by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var voices by mutableStateOf<List<String>>(emptyList())
        private set
    var characters by mutableStateOf<List<Pair<String, Int>>>(emptyList())
        private set
    private var rank by mutableStateOf<Map<String, Int>>(emptyMap())

    private var raw: List<List<List<Segment>>> = emptyList()
    private var autoAliases: Map<String, String> = emptyMap()
    private var engine: SpeechEngine? = null
    private var job: Job? = null
    private var playGen = 0

    private val systemEngines: List<EngineOption> = run {
        val pm = app.packageManager
        try {
            pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).map {
                EngineOption("system:" + it.serviceInfo.packageName, it.loadLabel(pm).toString())
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------- settings ----------

    fun update(f: Settings.() -> Settings) {
        settings = settings.f()
        prefs.save(settings)
    }

    fun setColor(key: String, color: Int) = update {
        when (key) {
            "background" -> copy(background = color)
            "narration" -> copy(narration = color)
            "dialogue" -> copy(dialogue = color)
            "highlight" -> copy(highlight = color)
            "title" -> copy(chapterTitle = color)
            else -> this
        }
    }

    fun setSpeakerColor(name: String, color: Int?) = update {
        copy(speakerColors = if (color == null) speakerColors - name else speakerColors + (name to color))
    }

    fun setAlias(name: String, target: String) {
        update { copy(aliases = if (target.isBlank() || target == name) aliases - name else aliases + (name to target.trim())) }
        computeCharacters()
    }

    fun setOverride(ch: Int, p: Int, seg: Int, value: String?) {
        val key = "$ch:$p:$seg"
        update { copy(overrides = if (value == null) overrides - key else overrides + (key to value)) }
        computeCharacters()
    }

    fun colorFor(speaker: String?): Int {
        if (speaker == null) return settings.dialogue
        settings.speakerColors[speaker]?.let { return it }
        if (settings.autoColors) rank[speaker]?.let { return PALETTE[it % PALETTE.size] }
        return settings.dialogue
    }

    // ---------- speaker data ----------

    private fun canonical(n: String): String = settings.aliases[n] ?: autoAliases[n] ?: n

    fun effectiveSegments(ch: Int, p: Int): List<Segment> {
        val segs = raw.getOrNull(ch)?.getOrNull(p) ?: return emptyList()
        return segs.mapIndexed { i, seg ->
            if (!seg.isQuote) {
                seg
            } else {
                val o = settings.overrides["$ch:$p:$i"]
                val base = when {
                    o == null -> seg.speaker
                    o.isEmpty() -> null
                    else -> o
                }
                seg.copy(speaker = base?.let { canonical(it) })
            }
        }
    }

    private fun computeCharacters() {
        val counts = LinkedHashMap<String, Int>()
        for (c in raw.indices) for (p in raw[c].indices) {
            for (seg in effectiveSegments(c, p)) {
                if (seg.isQuote && seg.speaker != null) counts[seg.speaker] = (counts[seg.speaker] ?: 0) + 1
            }
        }
        val sorted = counts.entries.sortedByDescending { it.value }
        characters = sorted.map { it.key to it.value }
        rank = sorted.mapIndexed { i, e -> e.key to i }.toMap()
    }

    // ---------- book loading ----------

    fun restoreLast() {
        if (book != null || settings.lastUri.isEmpty()) return
        openBook(Uri.parse(settings.lastUri), restore = true)
    }

    fun openBook(uri: Uri, restore: Boolean = false) {
        pause()
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val (b, det) = withContext(Dispatchers.IO) {
                    val bk = EpubParser.parse(app, uri)
                    bk to SpeakerDetector.detect(bk)
                }
                if (b.chapters.isEmpty()) throw IllegalStateException("No readable text found in this EPUB")
                val resume = restore || settings.lastUri == uri.toString()
                val ch = if (resume) settings.lastChapter.coerceIn(0, b.chapters.size - 1) else 0
                val pi = if (resume) settings.lastParagraph.coerceIn(0, b.chapters[ch].paragraphs.size - 1) else 0
                raw = det.chapters
                autoAliases = det.autoAliases
                chapterIndex = ch
                paragraphIndex = pi
                book = b
                update { copy(lastUri = uri.toString(), lastChapter = ch, lastParagraph = pi) }
                computeCharacters()
            } catch (t: Throwable) {
                if (!restore) error = "Could not open the EPUB: ${t.message}"
            } finally {
                loading = false
            }
        }
    }

    // ---------- navigation ----------

    private fun saveProgress() = update { copy(lastChapter = chapterIndex, lastParagraph = paragraphIndex) }

    fun jumpTo(c: Int, p: Int) {
        val b = book ?: return
        val was = playing
        pause()
        chapterIndex = c.coerceIn(0, b.chapters.size - 1)
        paragraphIndex = p.coerceIn(0, b.chapters[chapterIndex].paragraphs.size - 1)
        saveProgress()
        if (was) play()
    }

    fun step(delta: Int) {
        val b = book ?: return
        var c = chapterIndex
        var p = paragraphIndex + delta
        if (p < 0) {
            if (c > 0) {
                c--
                p = b.chapters[c].paragraphs.size - 1
            } else {
                p = 0
            }
        } else if (p >= b.chapters[c].paragraphs.size) {
            if (c + 1 < b.chapters.size) {
                c++
                p = 0
            } else {
                p = b.chapters[c].paragraphs.size - 1
            }
        }
        jumpTo(c, p)
    }

    fun stepChapter(delta: Int) = jumpTo(chapterIndex + delta, 0)

    // ---------- playback ----------

    fun togglePlay() = if (playing) pause() else play()

    fun play() {
        val b = book ?: return
        if (playing) return
        playing = true
        error = null
        ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java))
        val gen = ++playGen
        job = viewModelScope.launch {
            try {
                val e = ensureEngine()
                while (true) {
                    val ch = b.chapters[chapterIndex]
                    if (paragraphIndex >= ch.paragraphs.size) {
                        if (chapterIndex + 1 < b.chapters.size) {
                            chapterIndex++
                            paragraphIndex = 0
                            continue
                        }
                        paragraphIndex = ch.paragraphs.size - 1
                        break
                    }
                    val text = ch.paragraphs[paragraphIndex]
                    if (text.none { it.isLetterOrDigit() }) {
                        paragraphIndex++
                        continue
                    }
                    nextText(b)?.let { e.prefetch(it) }
                    e.rate = settings.rate
                    e.pitch = settings.pitch
                    e.voice = if (e is SystemTtsEngine) settings.voice.ifEmpty { null } else null
                    e.speak(text)
                    paragraphIndex++
                    saveProgress()
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                error = t.message ?: t.toString()
            } finally {
                if (gen == playGen) {
                    playing = false
                    app.stopService(Intent(app, PlaybackService::class.java))
                }
            }
        }
    }

    private fun nextText(b: Book): String? {
        val ch = b.chapters[chapterIndex]
        val i = paragraphIndex + 1
        return if (i < ch.paragraphs.size) ch.paragraphs[i]
        else b.chapters.getOrNull(chapterIndex + 1)?.paragraphs?.firstOrNull()
    }

    fun pause() {
        playGen++
        job?.cancel()
        job = null
        if (playing) {
            playing = false
            app.stopService(Intent(app, PlaybackService::class.java))
            saveProgress()
        }
    }

    fun testVoice() {
        pause()
        error = null
        val gen = ++playGen
        job = viewModelScope.launch {
            try {
                val e = ensureEngine()
                e.rate = settings.rate
                e.pitch = settings.pitch
                e.voice = if (e is SystemTtsEngine) settings.voice.ifEmpty { null } else null
                e.speak("This is how the selected voice sounds when reading your book.")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (gen == playGen) error = t.message ?: t.toString()
            }
        }
    }

    // ---------- engines ----------

    fun engineOptions(): List<EngineOption> {
        val list = mutableListOf(EngineOption("system:", "Phone default"))
        list += systemEngines
        list += settings.providers.map { EngineOption("http:${it.id}", "Custom: ${it.name}") }
        return list
    }

    private fun resetEngine() {
        pause()
        engine?.shutdown()
        engine = null
    }

    fun selectEngine(id: String) {
        resetEngine()
        update { copy(engineId = id, voice = "") }
        voices = emptyList()
    }

    fun saveProvider(p: HttpProvider) {
        resetEngine()
        update {
            val exists = providers.any { it.id == p.id }
            copy(providers = if (exists) providers.map { if (it.id == p.id) p else it } else providers + p)
        }
    }

    fun deleteProvider(id: String) {
        resetEngine()
        update {
            copy(
                providers = providers.filter { it.id != id },
                engineId = if (engineId == "http:$id") "system:" else engineId
            )
        }
    }

    private suspend fun ensureEngine(): SpeechEngine {
        if (engine == null) {
            val id = settings.engineId
            engine = if (id.startsWith("http:")) {
                val p = settings.providers.firstOrNull { "http:${it.id}" == id }
                if (p != null) HttpTtsEngine(app.cacheDir, p, viewModelScope)
                else SystemTtsEngine(app, null)
            } else {
                SystemTtsEngine(app, id.removePrefix("system:").ifEmpty { null })
            }
        }
        val e = engine!!
        if (e is SystemTtsEngine) e.awaitReady()
        return e
    }

    fun refreshVoices() {
        viewModelScope.launch {
            voices = try {
                val e = ensureEngine()
                if (e is SystemTtsEngine) e.listVoices() else emptyList()
            } catch (t: Throwable) {
                emptyList()
            }
        }
    }

    override fun onCleared() {
        engine?.shutdown()
        super.onCleared()
    }
}
