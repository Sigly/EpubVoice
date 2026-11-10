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
        return Settings(
            background = j.optInt("background", d.background),
            narration = j.optInt("narration", d.narration),
            dialogue = j.optInt("dialogue", d.dialogue),
            highlight = j.optInt("highlight", d.highlight),
            chapterTitle = j.optInt("chapterTitle", d.chapterTitle),
            fontSize = j.optDouble("fontSize", d.fontSize.toDouble()).toFloat(),
            engineId = j.optString("engineId", d.engineId),
            voice = j.optString("voice", d.voice),
            rate = j.optDouble("rate", d.rate.toDouble()).toFloat(),
            pitch = j.optDouble("pitch", d.pitch.toDouble()).toFloat(),
            autoColors = j.optBoolean("autoColors", d.autoColors),
            speakerColors = intMap(j.optJSONObject("speakerColors")),
            aliases = strMap(j.optJSONObject("aliases")),
            overrides = strMap(j.optJSONObject("overrides")),
            providers = providers(j.optJSONArray("providers")),
            lastUri = j.optString("lastUri", ""),
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
        j.put("lastUri", s.lastUri)
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
