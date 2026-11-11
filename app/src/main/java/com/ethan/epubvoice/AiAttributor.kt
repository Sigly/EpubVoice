package com.ethan.epubvoice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class AiConfig(val kind: String, val baseUrl: String, val apiKey: String, val model: String)

/**
 * Sends a chapter to a language model and asks who speaks or thinks each quoted line.
 * Works with the Anthropic API or any OpenAI-compatible chat endpoint.
 */
object AiAttributor {
    private const val SYSTEM =
        "You attribute quoted speech and thoughts in fiction to the character who says or thinks them. " +
            "You reply with JSON only."
    private const val WINDOW_CHARS = 16000

    private class RetryableException(msg: String) : IOException(msg)

    /**
     * Returns "p:q" -> speaker for every quote the model answered. p is the paragraph index,
     * q the 1-based quote number inside that paragraph. A null speaker means "not character speech".
     */
    suspend fun analyzeChapter(
        cfg: AiConfig,
        title: String,
        paragraphs: List<String>,
        quotes: List<List<String>>,
        known: List<String>
    ): Map<String, String?> = withContext(Dispatchers.IO) {
        val out = HashMap<String, String?>()
        val names = LinkedHashSet<String>(known)
        var start = 0
        while (start < paragraphs.size) {
            var end = start
            var size = 0
            while (end < paragraphs.size && (end == start || size + paragraphs[end].length < WINDOW_CHARS)) {
                size += paragraphs[end].length
                end++
            }
            val last = end - 1
            if ((start..last).any { quotes[it].isNotEmpty() }) {
                val prompt = buildPrompt(title, paragraphs, quotes, start, last, names.toList().take(40))
                val arr = parse(call(cfg, prompt))
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val p = o.optInt("p", -1)
                    val q = o.optInt("q", -1)
                    if (p < start || p > last || q < 1 || q > quotes[p].size) continue
                    val s = if (o.isNull("s")) null else o.optString("s").trim().ifEmpty { null }
                    out["$p:$q"] = s
                    if (s != null) names.add(s)
                }
            }
            start = end
        }
        out
    }

    private fun buildPrompt(
        title: String,
        paragraphs: List<String>,
        quotes: List<List<String>>,
        from: Int,
        to: Int,
        known: List<String>
    ): String {
        val sb = StringBuilder()
        sb.append("Chapter: ").append(title).append('\n')
        if (known.isNotEmpty()) {
            sb.append("Known characters (reuse these exact names when they match): ")
                .append(known.joinToString(", ")).append('\n')
        }
        sb.append(
            "Below are numbered paragraphs. Under a paragraph, lines starting with Q<n> show the quoted " +
                "spans that need a speaker.\n" +
                "For every Q, decide who speaks or silently thinks it, using the whole context. " +
                "Use the character's most complete common name, spelled the same way every time. " +
                "Use \"System\" for game or system messages. " +
                "Use null if it is not a character's speech or thought (a title, a scare quote, quoted text from a document).\n" +
                "Return ONLY a JSON array like [{\"p\":12,\"q\":1,\"s\":\"Agnus\"}]. Include every Q exactly once.\n\n"
        )
        for (p in from..to) {
            sb.append('[').append(p).append("] ").append(paragraphs[p]).append('\n')
            quotes[p].forEachIndexed { i, q ->
                sb.append("  Q").append(i + 1).append(": ").append(q.take(90)).append('\n')
            }
        }
        return sb.toString()
    }

    private fun parse(reply: String): JSONArray {
        val s = reply.indexOf('[')
        val e = reply.lastIndexOf(']')
        if (s < 0 || e <= s) throw IOException("The AI did not return a JSON list")
        return JSONArray(reply.substring(s, e + 1))
    }

    private suspend fun call(cfg: AiConfig, prompt: String): String {
        var attempt = 0
        while (true) {
            try {
                return request(cfg, prompt)
            } catch (e: RetryableException) {
                // Free tiers rate-limit hard, so back off for a while before giving up.
                attempt++
                if (attempt >= 5) throw IOException(e.message)
                delay(15000L * attempt)
            }
        }
    }

    private fun request(cfg: AiConfig, prompt: String): String {
        val anthropic = cfg.kind == "anthropic"
        val url = if (anthropic) {
            cfg.baseUrl.ifBlank { "https://api.anthropic.com" }.trimEnd('/') + "/v1/messages"
        } else {
            cfg.baseUrl.ifBlank { "https://api.openai.com/v1" }.trimEnd('/') + "/chat/completions"
        }
        val body = if (anthropic) {
            JSONObject()
                .put("model", cfg.model)
                .put("max_tokens", 4096)
                .put("system", SYSTEM)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        } else {
            JSONObject()
                .put("model", cfg.model)
                .put("temperature", 0)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", SYSTEM))
                        .put(JSONObject().put("role", "user").put("content", prompt))
                )
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20000
            conn.readTimeout = 180000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (anthropic) {
                conn.setRequestProperty("x-api-key", cfg.apiKey)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
            } else {
                conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 429 || code in 500..599) throw RetryableException("HTTP $code: ${text.take(200)}")
            if (code !in 200..299) throw IOException("HTTP $code: ${text.take(300)}")
            val json = JSONObject(text)
            return if (anthropic) {
                val c = json.getJSONArray("content")
                buildString { for (i in 0 until c.length()) append(c.getJSONObject(i).optString("text")) }
            } else {
                json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            }
        } finally {
            conn.disconnect()
        }
    }
}
