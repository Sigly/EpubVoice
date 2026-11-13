package com.ethan.epubvoice

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val build: Int, val url: String)

/**
 * Looks for a newer build among the GitHub releases and hands it to Android's installer.
 * Android always asks you to confirm an install, so this can't be fully silent.
 */
object Updater {
    private const val REPO = "Sigly/EpubVoice"

    fun installedBuild(ctx: Context): Int {
        return try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Blocking. Asks the plain github.com "latest release" page first (no API limits), then the API.
     * Throws the first problem it ran into when neither works.
     */
    fun latest(): UpdateInfo? {
        var firstError: Throwable? = null
        try {
            redirectLatest()?.let { return it }
        } catch (t: Throwable) {
            firstError = t
        }
        try {
            apiLatest()?.let { return it }
        } catch (t: Throwable) {
            if (firstError == null) firstError = t
        }
        if (firstError != null) throw firstError
        return null
    }

    private fun redirectLatest(): UpdateInfo? {
        val c = URL("https://github.com/$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.requestMethod = "HEAD"
            c.useCaches = false
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "EpubVoice-updater")
            val code = c.responseCode
            if (code !in 300..399) return null
            val location = c.getHeaderField("Location") ?: return null
            val tag = location.substringAfter("/tag/", "")
            val build = tag.removePrefix("build-").toIntOrNull() ?: return null
            return UpdateInfo(build, "https://github.com/$REPO/releases/download/$tag/EpubVoice.apk")
        } finally {
            c.disconnect()
        }
    }

    /** Blocking. Returns the highest-numbered release that has an APK, or null when the list cannot be read. */
    private fun apiLatest(): UpdateInfo? {
        val c = URL("https://api.github.com/repos/$REPO/releases?per_page=10&t=" + System.currentTimeMillis())
            .openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.useCaches = false
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("Cache-Control", "no-cache")
            c.setRequestProperty("User-Agent", "EpubVoice-updater")
            if (c.responseCode != 200) return null
            val list = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            var best: UpdateInfo? = null
            for (r in 0 until list.length()) {
                val j = list.optJSONObject(r) ?: continue
                if (j.optBoolean("draft") || j.optBoolean("prerelease")) continue
                val build = j.optString("tag_name").removePrefix("build-").toIntOrNull() ?: continue
                val assets = j.optJSONArray("assets") ?: continue
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name").endsWith(".apk") && (best == null || build > best.build)) {
                        best = UpdateInfo(build, a.optString("browser_download_url"))
                    }
                }
            }
            return best
        } finally {
            c.disconnect()
        }
    }

    /** Blocking. Downloads the APK into the cache folder and reports whole-percent progress. */
    fun download(ctx: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        val out = File(dir, "EpubVoice.apk")
        val c = URL(info.url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "EpubVoice-updater")
            if (c.responseCode != 200) error("Download failed (HTTP ${c.responseCode})")
            val total = c.contentLengthLong
            var done = 0L
            var lastPct = -1
            c.inputStream.use { i ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            if (out.length() < 1_000_000L) error("The downloaded file is too small to be the app")
        } finally {
            c.disconnect()
        }
        return out
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
