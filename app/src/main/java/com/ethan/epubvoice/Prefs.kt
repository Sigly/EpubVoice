package com.ethan.epubvoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("epubvoice", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        val j = try {
            JSONObject(sp.getString("settings", "{}") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        // Saved text colors from before the black, white and blue look are replaced once by the new defaults (light grey base).
        val fresh = j.optInt("themeV", 0) < 3
        return Settings(
            background = if (fresh) d.background else j.optInt("background", d.background),
            narration = if (fresh) d.narration else j.optInt("narration", d.narration),
            dialogue = if (fresh) d.dialogue else j.optInt("dialogue", d.dialogue),
            highlight = if (fresh) d.highlight else j.optInt("highlight", d.highlight),
            chapterTitle = if (fresh) d.chapterTitle else j.optInt("chapterTitle", d.chapterTitle),
            fontSize = j.optDouble("fontSize", d.fontSize.toDouble()).toFloat(),
            engineId = j.optString("engineId", d.engineId),
            voice = j.optString("voice", d.voice),
            rate = j.optDouble("rate", d.rate.toDouble()).toFloat(),
            pitch = j.optDouble("pitch", d.pitch.toDouble()).toFloat(),
            autoColors = j.optBoolean("autoColors", d.autoColors),
            lookColors = j.optBoolean("lookColors", d.lookColors),
            speakerColors = intMap(j.optJSONObject("speakerColors")),
            aliases = strMap(j.optJSONObject("aliases")),
            overrides = strMap(j.optJSONObject("overrides")),
            providers = providers(j.optJSONArray("providers")),
            aiKind = j.optString("aiKind", d.aiKind),
            aiBaseUrl = j.optString("aiBaseUrl", d.aiBaseUrl),
            aiKey = j.optString("aiKey", d.aiKey),
            aiModel = j.optString("aiModel", d.aiModel),
            aiAuto = j.optBoolean("aiAuto", d.aiAuto),
            aiParallel = j.optInt("aiParallel", d.aiParallel),
            aiDelay = j.optInt("aiDelay", d.aiDelay),
            lastUri = j.optString("lastUri", ""),
            lastBookId = j.optString("lastBookId", ""),
            lastChapter = j.optInt("lastChapter", 0),
            lastParagraph = j.optInt("lastParagraph", 0)
        )
    }

    fun save(s: Settings) {
        val j = JSONObject()
        j.put("background", s.background)
        j.put("narration", s.narration)
        j.put("dialogue", s.dialogue)
        j.put("highlight", s.highlight)
        j.put("chapterTitle", s.chapterTitle)
        j.put("fontSize", s.fontSize.toDouble())
        j.put("engineId", s.engineId)
        j.put("voice", s.voice)
        j.put("rate", s.rate.toDouble())
        j.put("pitch", s.pitch.toDouble())
        j.put("autoColors", s.autoColors)
        j.put("lookColors", s.lookColors)
        j.put("speakerColors", JSONObject().also { o -> s.speakerColors.forEach { (k, v) -> o.put(k, v) } })
        j.put("aliases", JSONObject().also { o -> s.aliases.forEach { (k, v) -> o.put(k, v) } })
        j.put("overrides", JSONObject().also { o -> s.overrides.forEach { (k, v) -> o.put(k, v) } })
        j.put("providers", JSONArray().also { arr ->
            s.providers.forEach { p ->
                arr.put(JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("url", p.url)
                    put("method", p.method)
                    put("headers", p.headers)
                    put("bodyTemplate", p.bodyTemplate)
                    put("voice", p.voice)
                })
            }
        })
        j.put("aiKind", s.aiKind)
        j.put("aiBaseUrl", s.aiBaseUrl)
        j.put("aiKey", s.aiKey)
        j.put("aiModel", s.aiModel)
        j.put("aiAuto", s.aiAuto)
        j.put("aiParallel", s.aiParallel)
        j.put("aiDelay", s.aiDelay)
        j.put("themeV", 3)
        j.put("lastUri", s.lastUri)
        j.put("lastBookId", s.lastBookId)
        j.put("lastChapter", s.lastChapter)
        j.put("lastParagraph", s.lastParagraph)
        sp.edit().putString("settings", j.toString()).apply()
    }

    private fun intMap(o: JSONObject?): Map<String, Int> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (k in o.keys()) out[k] = o.optInt(k)
        return out
    }

    private fun strMap(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (k in o.keys()) out[k] = o.optString(k)
        return out
    }

    private fun providers(a: JSONArray?): List<HttpProvider> {
        if (a == null) return emptyList()
        val out = ArrayList<HttpProvider>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out += HttpProvider(
                id = o.optString("id"),
                name = o.optString("name"),
                url = o.optString("url"),
                method = o.optString("method", "POST"),
                headers = o.optString("headers"),
                bodyTemplate = o.optString("bodyTemplate"),
                voice = o.optString("voice")
            )
        }
        return out
    }
}
