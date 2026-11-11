package com.ethan.epubvoice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Per-book speaker data. Chapter and paragraph numbers differ between books, so each book keeps its own. */
class BookData(
    val overrides: Map<String, String>,
    val aliases: Map<String, String>,
    val speakerColors: Map<String, Int>
)

/** The list of books, their covers, bookmarks and per-book speaker data. EPUBs are copied into app storage. */
class Library(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val booksDir = File(ctx.filesDir, "books").apply { mkdirs() }
    private val coversDir = File(ctx.filesDir, "covers").apply { mkdirs() }

    fun bookFile(id: String): File = File(booksDir, "$id.epub")

    fun load(): List<LibraryEntry> {
        val out = ArrayList<LibraryEntry>()
        try {
            val a = JSONArray(sp.getString("books", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty() || !bookFile(id).exists()) continue
                out += LibraryEntry(
                    id = id,
                    title = o.optString("title", "Untitled"),
                    cover = o.optString("cover", ""),
                    chapter = o.optInt("chapter", 0),
                    paragraph = o.optInt("paragraph", 0),
                    total = o.optInt("total", 0),
                    added = o.optLong("added", 0L)
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun save(list: List<LibraryEntry>) {
        val a = JSONArray()
        list.forEach { e ->
            a.put(JSONObject().apply {
                put("id", e.id)
                put("title", e.title)
                put("cover", e.cover)
                put("chapter", e.chapter)
                put("paragraph", e.paragraph)
                put("total", e.total)
                put("added", e.added)
            })
        }
        sp.edit().putString("books", a.toString()).apply()
    }

    /** Copies the picked EPUB into app storage. The id comes from the file content, so adding a book twice is harmless. */
    fun importUri(uri: Uri): String {
        val tmp = File(ctx.cacheDir, "import.epub")
        val md = MessageDigest.getInstance("SHA-1")
        val input = ctx.contentResolver.openInputStream(uri) ?: error("Cannot open the file")
        input.use { i ->
            tmp.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    o.write(buf, 0, n)
                }
            }
        }
        val id = md.digest().joinToString("") { "%02x".format(it) }.take(16)
        val dest = bookFile(id)
        if (!dest.exists()) tmp.copyTo(dest, overwrite = true)
        tmp.delete()
        return id
    }

    fun saveCover(id: String, bytes: ByteArray?): String {
        if (bytes == null) return ""
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 900) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (bmp == null) {
                ""
            } else {
                val f = File(coversDir, "$id.jpg")
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                f.absolutePath
            }
        } catch (_: Throwable) {
            ""
        }
    }

    fun delete(id: String) {
        bookFile(id).delete()
        File(coversDir, "$id.jpg").delete()
        File(ctx.filesDir, "ai_$id.json").delete()
        File(ctx.filesDir, "bookdata_$id.json").delete()
        sp.edit().remove("bm_$id").apply()
    }

    // ---------- bookmarks ----------

    fun bookmarks(id: String): List<Bookmark> {
        val out = ArrayList<Bookmark>()
        try {
            val a = JSONArray(sp.getString("bm_$id", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                out += Bookmark(o.optInt("c"), o.optInt("p"), o.optString("t"), o.optLong("time"))
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun saveBookmarks(id: String, list: List<Bookmark>) {
        val a = JSONArray()
        list.forEach { b ->
            a.put(JSONObject().apply {
                put("c", b.chapter)
                put("p", b.paragraph)
                put("t", b.preview)
                put("time", b.time)
            })
        }
        sp.edit().putString("bm_$id", a.toString()).apply()
    }

    // ---------- per-book speaker data ----------

    private fun dataFile(id: String) = File(ctx.filesDir, "bookdata_$id.json")

    fun loadBookData(id: String): BookData? {
        val f = dataFile(id)
        if (!f.exists()) return null
        return try {
            val j = JSONObject(f.readText())
            BookData(strMap(j.optJSONObject("overrides")), strMap(j.optJSONObject("aliases")), intMap(j.optJSONObject("speakerColors")))
        } catch (_: Exception) {
            null
        }
    }

    fun saveBookData(id: String, overrides: Map<String, String>, aliases: Map<String, String>, colors: Map<String, Int>) {
        try {
            val j = JSONObject()
            j.put("overrides", JSONObject().also { o -> overrides.forEach { (k, v) -> o.put(k, v) } })
            j.put("aliases", JSONObject().also { o -> aliases.forEach { (k, v) -> o.put(k, v) } })
            j.put("speakerColors", JSONObject().also { o -> colors.forEach { (k, v) -> o.put(k, v) } })
            dataFile(id).writeText(j.toString())
        } catch (_: Exception) {
        }
    }

    private fun strMap(o: JSONObject?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (o != null) for (k in o.keys()) out[k] = o.optString(k)
        return out
    }

    private fun intMap(o: JSONObject?): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        if (o != null) for (k in o.keys()) out[k] = o.optInt(k)
        return out
    }
}
