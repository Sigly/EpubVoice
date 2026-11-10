package com.ethan.epubvoice

import android.content.Context
import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

object EpubParser {
    private const val BLOCKS = "p, h1, h2, h3, h4, h5, h6, li, blockquote"

    fun parse(context: Context, uri: Uri): Book {
        val tmp = File(context.cacheDir, "current.epub")
        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open the file")
        input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }

        return ZipFile(tmp).use { zip ->
            fun read(path: String): String? {
                val entry = zip.getEntry(path)
                    ?: zip.getEntry(URLDecoder.decode(path, "UTF-8"))
                    ?: return null
                return zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }

            val container = read("META-INF/container.xml") ?: error("Not a valid EPUB (no container.xml)")
            val opfPath = Jsoup.parse(container, "", Parser.xmlParser())
                .selectFirst("rootfile")?.attr("full-path")
                ?: error("Not a valid EPUB (no package file)")
            val opf = Jsoup.parse(read(opfPath) ?: error("Package file is missing"), "", Parser.xmlParser())
            val baseDir = opfPath.substringBeforeLast('/', "")

            val title = opf.getElementsByTag("dc:title").firstOrNull()?.text()?.takeIf { it.isNotBlank() } ?: "Untitled"
            val manifest = opf.select("manifest > item").associate { it.attr("id") to it.attr("href") }

            val chapters = mutableListOf<Chapter>()
            for (ref in opf.select("spine > itemref")) {
                val href = manifest[ref.attr("idref")] ?: continue
                val html = read(resolve(baseDir, href)) ?: continue
                val body = Jsoup.parse(html).body()

                val paras = body.select(BLOCKS)
                    .filter { el -> el.select(BLOCKS).size == 1 }
                    .filter { el -> el.parents().none { it.tagName() == "nav" } }
                    .map { it.text().trim() }
                    .filter { it.isNotEmpty() }
                    .ifEmpty {
                        body.wholeText().split(Regex("\\n\\s*\\n"))
                            .map { it.trim().replace(Regex("\\s+"), " ") }
                            .filter { it.isNotEmpty() }
                    }
                if (paras.isEmpty()) continue

                val heading = body.selectFirst("h1, h2, h3")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                chapters += Chapter(heading ?: "Section ${chapters.size + 1}", paras)
            }
            Book(title, chapters)
        }
    }

    private fun resolve(base: String, href: String): String {
        val clean = href.substringBefore('#')
        val full = if (base.isEmpty()) clean else "$base/$clean"
        val parts = mutableListOf<String>()
        for (p in full.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(p)
            }
        }
        return parts.joinToString("/")
    }
}
