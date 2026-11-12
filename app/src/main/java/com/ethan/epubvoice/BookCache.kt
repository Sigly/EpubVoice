package com.ethan.epubvoice

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.stream.IntStream

/**
 * Saves a parsed book and its offline speaker detection to a file so the next launch can skip both.
 *
 * The file is not compressed and is split into one block per chapter, so all cores can decode
 * chapters at the same time when it is read back.
 *
 * Bump [VERSION] whenever EpubParser or SpeakerDetector change what they produce, so old caches are
 * ignored and rebuilt.
 */
object BookCache {
    private const val MAGIC = 0x45504243
    const val VERSION = 2

    private class Decoded(val chapter: Chapter, val segments: List<List<Segment>>)

    fun load(f: File): Pair<Book, Detected>? {
        if (!f.exists()) return null
        return try {
            val bytes = f.readBytes()
            val bb = ByteBuffer.wrap(bytes)
            if (bb.getInt() != MAGIC || bb.getInt() != VERSION) return null
            val title = readStr(bb)
            val names = Array(bb.getInt()) { readStr(bb) }
            val aliases = HashMap<String, String>()
            repeat(bb.getInt()) {
                val k = readStr(bb)
                aliases[k] = readStr(bb)
            }
            val nCh = bb.getInt()
            val sizes = IntArray(nCh) { bb.getInt() }
            val offsets = IntArray(nCh)
            var pos = bb.position()
            for (c in 0 until nCh) {
                offsets[c] = pos
                pos += sizes[c]
            }
            if (pos + 4 > bytes.size || ByteBuffer.wrap(bytes, pos, 4).getInt() != MAGIC) return null

            val out = arrayOfNulls<Decoded>(nCh)
            IntStream.range(0, nCh).parallel().forEach { c ->
                out[c] = decode(ByteBuffer.wrap(bytes, offsets[c], sizes[c]), names)
            }
            val chapters = ArrayList<Chapter>(nCh)
            val segs = ArrayList<List<List<Segment>>>(nCh)
            for (d in out) {
                chapters.add(d!!.chapter)
                segs.add(d.segments)
            }
            Book(title, chapters) to Detected(segs, aliases)
        } catch (_: Throwable) {
            null
        }
    }

    private fun decode(b: ByteBuffer, names: Array<String>): Decoded {
        val title = readStr(b)
        val nP = b.getInt()
        val paras = ArrayList<String>(nP)
        val chSegs = ArrayList<List<Segment>>(nP)
        for (p in 0 until nP) {
            val text = readStr(b)
            paras.add(text)
            val nS = b.getInt()
            val list = ArrayList<Segment>(nS)
            var pos = 0
            for (s in 0 until nS) {
                val len = b.getInt()
                val flags = b.get().toInt()
                val sp = b.getInt()
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
        return Decoded(Chapter(title, paras), chSegs)
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

            val blocks = ArrayList<ByteArray>(book.chapters.size)
            for (c in book.chapters.indices) {
                val bo = ByteArrayOutputStream()
                DataOutputStream(bo).use { o ->
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
                blocks.add(bo.toByteArray())
            }

            val tmp = File(f.parentFile, f.name + ".tmp")
            DataOutputStream(FileOutputStream(tmp).buffered(256 * 1024)).use { o ->
                o.writeInt(MAGIC)
                o.writeInt(VERSION)
                writeStr(o, book.title)
                o.writeInt(names.size)
                for (n in names.keys) writeStr(o, n)
                o.writeInt(det.autoAliases.size)
                for ((k, v) in det.autoAliases) {
                    writeStr(o, k)
                    writeStr(o, v)
                }
                o.writeInt(blocks.size)
                for (bl in blocks) o.writeInt(bl.size)
                for (bl in blocks) o.write(bl)
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

    private fun readStr(b: ByteBuffer): String {
        val n = b.getInt()
        val arr = ByteArray(n)
        b.get(arr)
        return String(arr, Charsets.UTF_8)
    }
}
