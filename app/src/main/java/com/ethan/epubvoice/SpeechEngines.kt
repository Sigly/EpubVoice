package com.ethan.epubvoice

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Anything that can read a piece of text aloud. Cancelling the coroutine stops playback. */
interface SpeechEngine {
    var rate: Float
    var pitch: Float
    var voice: String?

    /** Returns when the text has finished playing. */
    suspend fun speak(text: String)

    /** Optional hint so network engines can download the next paragraph early. */
    fun prefetch(text: String) {}

    fun shutdown()
}

fun chunkText(text: String, max: Int): List<String> {
    if (text.length <= max) return listOf(text)
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    for (s in text.split(Regex("(?<=[.!?…])\\s+"))) {
        if (sb.isNotEmpty() && sb.length + s.length + 1 > max) {
            out += sb.toString()
            sb.setLength(0)
        }
        if (s.length > max) {
            s.chunked(max).forEach { out += it }
        } else {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(s)
        }
    }
    if (sb.isNotEmpty()) out += sb.toString()
    return out
}

/**
 * Uses any text-to-speech engine installed on the phone (Google, Samsung, RHVoice,
 * Sherpa/Piper apps, and so on). Pass the engine's package name, or null for the default.
 */
class SystemTtsEngine(context: Context, enginePackage: String?) : SpeechEngine {
    override var rate = 1f
    override var pitch = 1f
    override var voice: String? = null

    private val ready = CompletableDeferred<Boolean>()
    private val tts: TextToSpeech = TextToSpeech(
        context.applicationContext,
        { status -> ready.complete(status == TextToSpeech.SUCCESS) },
        enginePackage
    )

    suspend fun awaitReady(): Boolean = ready.await()

    suspend fun listVoices(): List<String> {
        if (!awaitReady()) return emptyList()
        return try {
            tts.voices?.map { it.name }?.sorted() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun speak(text: String) {
        if (!awaitReady()) throw IllegalStateException("That text-to-speech engine failed to start")
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
        val wanted = voice
        if (wanted != null) {
            try {
                tts.voices?.firstOrNull { it.name == wanted }?.let { tts.setVoice(it) }
            } catch (_: Exception) {
            }
        }
        val max = minOf(3500, TextToSpeech.getMaxSpeechInputLength())
        for (chunk in chunkText(text, max)) {
            suspendCancellableCoroutine<Unit> { cont ->
                val id = UUID.randomUUID().toString()
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id && cont.isActive) cont.resume(Unit)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id && cont.isActive) {
                            cont.resumeWithException(IOException("Text-to-speech error"))
                        }
                    }
                })
                cont.invokeOnCancellation { tts.stop() }
                val r = tts.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, id)
                if (r != TextToSpeech.SUCCESS && cont.isActive) {
                    cont.resumeWithException(IOException("Text-to-speech refused the text"))
                }
            }
        }
    }

    override fun shutdown() {
        try {
            tts.stop()
            tts.shutdown()
        } catch (_: Exception) {
        }
    }
}

/**
 * Calls any HTTP service that returns audio. {{text}} and {{voice}} are replaced in the
 * URL (URL-encoded) and in the body (JSON-escaped).
 */
class HttpTtsEngine(
    private val cacheDir: File,
    private val provider: HttpProvider,
    private val scope: CoroutineScope
) : SpeechEngine {
    override var rate = 1f
    override var pitch = 1f
    override var voice: String? = null

    private var player: MediaPlayer? = null
    private val pending = ConcurrentHashMap<String, Deferred<File>>()

    private fun jsonEscape(s: String): String = JSONObject.quote(s).let { it.substring(1, it.length - 1) }

    private fun fetch(text: String): File {
        val v = voice ?: provider.voice
        val url = provider.url
            .replace("{{text}}", URLEncoder.encode(text, "UTF-8"))
            .replace("{{voice}}", URLEncoder.encode(v, "UTF-8"))
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 90000
            conn.requestMethod = provider.method
            provider.headers.lines().map { it.trim() }.filter { it.contains(":") }.forEach {
                conn.setRequestProperty(it.substringBefore(":").trim(), it.substringAfter(":").trim())
            }
            if (provider.method == "POST") {
                conn.doOutput = true
                if (conn.getRequestProperty("Content-Type") == null) {
                    conn.setRequestProperty("Content-Type", "application/json")
                }
                val body = provider.bodyTemplate
                    .replace("{{text}}", jsonEscape(text))
                    .replace("{{voice}}", jsonEscape(v))
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (conn.responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(300)
                throw IOException("HTTP ${conn.responseCode}: $err")
            }
            val f = File.createTempFile("tts", ".audio", cacheDir)
            conn.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            return f
        } finally {
            conn.disconnect()
        }
    }

    private fun request(text: String): Deferred<File> =
        pending.getOrPut(text) { scope.async(Dispatchers.IO) { fetch(text) } }

    override fun prefetch(text: String) {
        request(text)
    }

    override suspend fun speak(text: String) {
        val job = request(text)
        val file = try {
            job.await()
        } finally {
            pending.remove(text)
        }
        try {
            play(file)
        } finally {
            file.delete()
        }
    }

    private suspend fun play(file: File) = suspendCancellableCoroutine<Unit> { cont ->
        val mp = MediaPlayer()
        player = mp
        mp.setDataSource(file.absolutePath)
        mp.setOnCompletionListener {
            it.release()
            if (player === it) player = null
            if (cont.isActive) cont.resume(Unit)
        }
        mp.setOnErrorListener { m, what, extra ->
            m.release()
            if (cont.isActive) cont.resumeWithException(IOException("Audio playback error $what/$extra"))
            true
        }
        mp.setOnPreparedListener {
            it.start()
            try {
                it.playbackParams = it.playbackParams.setSpeed(rate)
            } catch (_: Exception) {
            }
        }
        cont.invokeOnCancellation {
            try {
                mp.stop()
            } catch (_: Exception) {
            }
            mp.release()
        }
        mp.prepareAsync()
    }

    override fun shutdown() {
        pending.values.forEach { it.cancel() }
        pending.clear()
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }
}
