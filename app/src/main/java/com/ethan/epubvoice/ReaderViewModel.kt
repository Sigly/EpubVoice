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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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

    /** Bumped whenever speaker data changes so paragraphs re-render. */
    var dataVersion by mutableIntStateOf(0)
        private set

    var aiBusy by mutableStateOf(false)
        private set
    var aiStatus by mutableStateOf<String?>(null)
        private set

    private var raw: List<List<List<Segment>>> = emptyList()
    private var autoAliases: Map<String, String> = emptyMap()

    /** "chapter:paragraph:segment" -> speaker from the AI pass ("" = not dialogue) */
    private var aiMap: Map<String, String> = emptyMap()
    private var aiDone: Set<Int> = emptySet()
    private var aiJob: Job? = null
    private var computeJob: Job? = null
    private var autoChapter = -1

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

    /** Priority: your manual fix, then the AI, then the offline rules. */
    fun effectiveSegments(ch: Int, p: Int): List<Segment> {
        val segs = raw.getOrNull(ch)?.getOrNull(p) ?: return emptyList()
        return segs.mapIndexed { i, seg ->
            if (!seg.isQuote) {
                seg
            } else {
                val key = "$ch:$p:$i"
                val o = settings.overrides[key] ?: aiMap[key]
                val base = when {
                    o == null -> seg.speaker
                    o.isEmpty() -> null
                    else -> o
                }
                seg.copy(speaker = base?.let { canonical(it) }, guess = if (o != null) false else seg.guess)
            }
        }
    }

    private fun computeCharacters() {
        val ov = settings.overrides
        val ai = aiMap
        val aliases = settings.aliases
        val snap = raw
        computeJob?.cancel()
        computeJob = viewModelScope.launch(Dispatchers.Default) {
            val rawCounts = HashMap<String, Int>()
            for (c in snap.indices) {
                ensureActive()
                for (p in snap[c].indices) {
                    val segs = snap[c][p]
                    for (i in segs.indices) {
                        val seg = segs[i]
                        if (!seg.isQuote) continue
                        val key = "$c:$p:$i"
                        val o = ov[key] ?: ai[key]
                        val sp = when {
                            o == null -> seg.speaker
                            o.isEmpty() -> null
                            else -> o
                        }
                        if (sp != null) rawCounts[sp] = (rawCounts[sp] ?: 0) + 1
                    }
                }
            }
            val auto = SpeakerDetector.autoAlias(rawCounts.keys)
            val counts = LinkedHashMap<String, Int>()
            for ((name, n) in rawCounts) {
                val canon = aliases[name] ?: auto[name] ?: name
                counts[canon] = (counts[canon] ?: 0) + n
            }
            val sorted = counts.entries.sortedByDescending { it.value }
            withContext(Dispatchers.Main) {
                autoAliases = auto
                characters = sorted.map { it.key to it.value }
                rank = sorted.mapIndexed { i, e -> e.key to i }.toMap()
                dataVersion++
            }
        }
    }

    // ---------- AI attribution ----------

    private fun aiFile(): File = File(app.filesDir, "ai_${settings.lastUri.hashCode()}.json")

    private fun loadAi() {
        aiMap = emptyMap()
        aiDone = emptySet()
        try {
            val f = aiFile()
            if (!f.exists()) return
            val j = JSONObject(f.readText())
            val map = HashMap<String, String>()
            j.optJSONObject("map")?.let { m -> for (k in m.keys()) map[k] = m.optString(k) }
            val done = HashSet<Int>()
            j.optJSONArray("done")?.let { a -> for (i in 0 until a.length()) done.add(a.optInt(i)) }
            aiMap = map
            aiDone = done
        } catch (_: Exception) {
        }
    }

    private fun saveAi() {
        try {
            val j = JSONObject()
            j.put("map", JSONObject().also { o -> aiMap.forEach { (k, v) -> o.put(k, v) } })
            j.put("done", JSONArray().also { a -> aiDone.forEach { a.put(it) } })
            aiFile().writeText(j.toString())
        } catch (_: Exception) {
        }
    }

    fun aiReady(): Boolean = settings.aiKey.isNotBlank() && settings.aiModel.isNotBlank()

    fun aiChapterDone(ch: Int): Boolean = ch in aiDone

    fun cancelAi() {
        aiJob?.cancel()
    }

    /** Ask the AI who speaks each quote in chapters [from]..[to]. */
    fun analyzeRange(from: Int, to: Int, force: Boolean = false) {
        val b = book ?: return
        if (aiBusy) return
        if (!aiReady()) {
            error = "Add your AI API key and model in Settings first"
            return
        }
        val cfg = AiConfig(settings.aiKind, settings.aiBaseUrl, settings.aiKey, settings.aiModel)
        val chapters = (from..to).filter { it in b.chapters.indices && (force || it !in aiDone) }
        if (chapters.isEmpty()) {
            aiStatus = "Already analyzed"
            return
        }
        aiBusy = true
        error = null
        updateService()
        aiJob = viewModelScope.launch {
            try {
                val par = settings.aiParallel.coerceIn(1, 8)
                var finished = 0
                for (batch in chapters.chunked(par)) {
                    aiStatus = "Analyzing chapter ${batch.first() + 1} ($finished of ${chapters.size} done)..."
                    updateService()
                    val results = coroutineScope {
                        batch.map { ch -> async { ch to analyzeOne(cfg, b, ch) } }.awaitAll()
                    }
                    for ((ch, add) in results) {
                        aiMap = aiMap + add
                        aiDone = aiDone + ch
                    }
                    finished += batch.size
                    saveAi()
                    computeCharacters()
                    if (settings.aiDelay > 0) delay(settings.aiDelay * 1000L)
                }
                aiStatus = "Done. Analyzed ${chapters.size} chapter(s)."
            } catch (c: CancellationException) {
                aiStatus = "Stopped. Progress is saved; press the button again to resume."
                throw c
            } catch (t: Throwable) {
                error = "AI analysis stopped: ${t.message}. Progress is saved; press the button again to resume."
                aiStatus = null
            } finally {
                aiBusy = false
                updateService()
            }
        }
    }

    private suspend fun analyzeOne(cfg: AiConfig, b: Book, ch: Int): Map<String, String> {
        val paras = b.chapters[ch].paragraphs
        val quotes = paras.indices.map { p -> raw[ch][p].filter { it.isQuote }.map { it.text } }
        val known = characters.take(40).map { it.first }
        val res = AiAttributor.analyzeChapter(cfg, b.chapters[ch].title, paras, quotes, known)
        val add = HashMap<String, String>()
        for ((key, sp) in res) {
            val parts = key.split(":")
            val p = parts[0].toInt()
            val q = parts[1].toInt()
            val segIdx = raw[ch][p].withIndex().filter { it.value.isQuote }.map { it.index }.getOrNull(q - 1)
                ?: continue
            add["$ch:$p:$segIdx"] = sp ?: ""
        }
        return add
    }

    /** Chapters that still have no AI result. */
    fun aiRemaining(): Int = (book?.chapters?.size ?: 0) - aiDone.size

    fun analyzeWholeBook() {
        val n = book?.chapters?.size ?: return
        analyzeRange(0, n - 1)
    }

    // ---------- backup ----------

    private fun mapOfStrings(o: JSONObject?): Map<String, String> {
        val out = HashMap<String, String>()
        if (o != null) for (k in o.keys()) out[k] = o.optString(k)
        return out
    }

    fun exportBackup(uri: Uri) {
        val ai = aiMap
        val done = aiDone
        val s = settings
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val j = JSONObject()
                j.put("app", "EpubVoice")
                j.put("version", 1)
                j.put("ai", JSONObject().also { o -> ai.forEach { (k, v) -> o.put(k, v) } })
                j.put("done", JSONArray().also { a -> done.forEach { a.put(it) } })
                j.put("overrides", JSONObject().also { o -> s.overrides.forEach { (k, v) -> o.put(k, v) } })
                j.put("aliases", JSONObject().also { o -> s.aliases.forEach { (k, v) -> o.put(k, v) } })
                j.put("speakerColors", JSONObject().also { o -> s.speakerColors.forEach { (k, v) -> o.put(k, v) } })
                app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(j.toString().toByteArray(Charsets.UTF_8)) }
                    ?: throw IllegalStateException("Cannot write the file")
                withContext(Dispatchers.Main) { aiStatus = "Backup saved" }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { error = "Backup failed: ${t.message}" }
            }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("Cannot read the file")
                val j = JSONObject(text)
                if (j.optString("app") != "EpubVoice") throw IllegalStateException("Not an EpubVoice backup")
                val ai = mapOfStrings(j.optJSONObject("ai"))
                val ov = mapOfStrings(j.optJSONObject("overrides"))
                val al = mapOfStrings(j.optJSONObject("aliases"))
                val sc = HashMap<String, Int>()
                j.optJSONObject("speakerColors")?.let { o -> for (k in o.keys()) sc[k] = o.optInt(k) }
                val done = HashSet<Int>()
                j.optJSONArray("done")?.let { a -> for (i in 0 until a.length()) done.add(a.optInt(i)) }
                withContext(Dispatchers.Main) {
                    aiMap = aiMap + ai
                    aiDone = aiDone + done
                    update { copy(overrides = overrides + ov, aliases = aliases + al, speakerColors = speakerColors + sc) }
                    saveAi()
                    computeCharacters()
                    aiStatus = "Backup restored"
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { error = "Restore failed: ${t.message}" }
            }
        }
    }

    // ---------- background service ----------

    private fun updateService() {
        val i = Intent(app, PlaybackService::class.java)
        if (playing || aiBusy) {
            i.putExtra("text", if (aiBusy) (aiStatus ?: "Analyzing dialogue") else "Reading aloud")
            ContextCompat.startForegroundService(app, i)
        } else {
            app.stopService(i)
        }
    }

    private fun maybeAutoAnalyze() {
        if (!settings.aiAuto || !aiReady() || aiBusy || autoChapter == chapterIndex) return
        autoChapter = chapterIndex
        analyzeRange(chapterIndex, chapterIndex + 1)
    }

    // ---------- book loading ----------

    fun restoreLast() {
        if (book != null || settings.lastUri.isEmpty()) return
        openBook(Uri.parse(settings.lastUri), restore = true)
    }

    fun openBook(uri: Uri, restore: Boolean = false) {
        pause()
        aiJob?.cancel()
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
                autoChapter = -1
                update { copy(lastUri = uri.toString(), lastChapter = ch, lastParagraph = pi) }
                loadAi()
                book = b
                aiStatus = null
                computeCharacters()
                maybeAutoAnalyze()
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
        maybeAutoAnalyze()
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
        updateService()
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
                            maybeAutoAnalyze()
                            continue
                        }
                        paragraphIndex = ch.paragraphs.size - 1
                        break
                    }
                    maybeAutoAnalyze()
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
                    updateService()
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
            updateService()
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
