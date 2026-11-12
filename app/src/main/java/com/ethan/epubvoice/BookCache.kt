package com.ethan.epubvoice

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Saves a parsed book and its offline speaker detection to a file so the next launch can skip both.
 *
 * Bump [VERSION] whenever EpubParser or SpeakerDetector change what they produce, so old caches are
 * ignored and rebuilt.
 */
object BookCache {
    private const val MAGIC = 0x45504243
    const val VERSION = 1

    fun load(f: File): Pair<Book, Detected>? {
        if (!f.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(GZIPInputStream(FileInputStream(f), 64 * 1024), 64 * 1024)).use { i ->
                if (i.readInt() != MAGIC || i.readInt() != VERSION) return null
                val title = readStr(i)
                val names = Array(i.readInt()) { readStr(i) }
                val nCh = i.readInt()
                val chapters = ArrayList<Chapter>(nCh)
                val segs = ArrayList<List<List<Segment>>>(nCh)
                for (c in 0 until nCh) {
                    val chTitle = readStr(i)
                    val nP = i.readInt()
                    val paras = ArrayList<String>(nP)
                    val chSegs = ArrayList<List<Segment>>(nP)
                    for (p in 0 until nP) {
                        val text = readStr(i)
                        paras.add(text)
                        val nS = i.readInt()
                        val list = ArrayList<Segment>(nS)
                        var pos = 0
                        for (s in 0 until nS) {
                            val len = i.readInt()
                            val flags = i.readByte().toInt()
                            val sp = i.readInt()
                            list.add(
                                Segment(
                                    text = text.substring(pos, pos + len),
                                    isQuote = flags and 1 != 0,
                                    speaker = if (sp >= 0) names[sp] else null,
                                    guess = flags and 2 != 0,
                                    scare = flags and 4 != 0
                                )
                            )
                            pos += len
                        }
                        chSegs.add(list)
                    }
                    chapters.add(Chapter(chTitle, paras))
                    segs.add(chSegs)
                }
                val nA = i.readInt()
                val aliases = HashMap<String, String>()
                for (a in 0 until nA) {
                    val k = readStr(i)
                    aliases[k] = readStr(i)
                }
                if (i.readInt() != MAGIC) return null
                Book(title, chapters) to Detected(segs, aliases)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun save(f: File, book: Book, det: Detected) {
        try {
            // Segments must add up to their paragraph, since only their lengths are stored.
            for (c in book.chapters.indices) for (p in book.chapters[c].paragraphs.indices) {
                val joined = det.chapters[c][p].joinToString("") { it.text }
                if (joined != book.chapters[c].paragraphs[p]) return
            }
            val names = LinkedHashMap<String, Int>()
            for (ch in det.chapters) for (para in ch) for (s in para) s.speaker?.let { names.getOrPut(it) { names.size } }

            val tmp = File(f.parentFile, f.name + ".tmp")
            DataOutputStream(BufferedOutputStream(GZIPOutputStream(FileOutputStream(tmp), 64 * 1024), 64 * 1024)).use { o ->
                o.writeInt(MAGIC)
                o.writeInt(VERSION)
                writeStr(o, book.title)
                o.writeInt(names.size)
                for (n in names.keys) writeStr(o, n)
                o.writeInt(book.chapters.size)
                for (c in book.chapters.indices) {
                    writeStr(o, book.chapters[c].title)
                    val paras = book.chapters[c].paragraphs
                    o.writeInt(paras.size)
                    for (p in paras.indices) {
                        writeStr(o, paras[p])
                        val segs = det.chapters[c][p]
                        o.writeInt(segs.size)
                        for (s in segs) {
                            o.writeInt(s.text.length)
                            var flags = 0
                            if (s.isQuote) flags = flags or 1
                            if (s.guess) flags = flags or 2
                            if (s.scare) flags = flags or 4
                            o.writeByte(flags)
                            o.writeInt(s.speaker?.let { names[it] } ?: -1)
                        }
                    }
                }
                o.writeInt(det.autoAliases.size)
                for ((k, v) in det.autoAliases) {
                    writeStr(o, k)
                    writeStr(o, v)
                }
                o.writeInt(MAGIC)
            }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (_: Throwable) {
            try {
                File(f.parentFile, f.name + ".tmp").delete()
            } catch (_: Throwable) {
            }
        }
    }

    private fun writeStr(o: DataOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        o.writeInt(b.size)
        o.write(b)
    }

    private fun readStr(i: DataInputStream): String {
        val n = i.readInt()
        val b = ByteArray(n)
        i.readFully(b)
        return String(b, Charsets.UTF_8)
    }
}
