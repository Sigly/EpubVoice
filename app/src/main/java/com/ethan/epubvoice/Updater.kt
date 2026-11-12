package com.ethan.epubvoice

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
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

    /** Blocking. Returns the newest release, or null when it cannot be read. */
    fun latest(): UpdateInfo? {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "EpubVoice-updater")
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val build = j.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return null
            val assets = j.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk")) {
                    return UpdateInfo(build, a.optString("browser_download_url"))
                }
            }
            return null
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
