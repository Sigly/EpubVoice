package com.ethan.epubvoice

import android.graphics.Color as AColor

/** What the text says a character looks like, e.g. label "silver hair". [key] is the color family. */
data class Look(val label: String, val key: String)

/**
 * Finds hair and eye colors in the book ("Lauel's silver hair", "the black-haired Yura", "his purple eyes")
 * and turns them into dialogue colors that stay readable on the current background.
 */
object Appearance {
    private val WORDS = mapOf(
        "black" to "black", "brown" to "brown", "chestnut" to "brown",
        "blond" to "blond", "blonde" to "blond", "golden" to "blond", "gold" to "blond", "platinum" to "blond",
        "silver" to "silver", "ash" to "silver", "white" to "white", "gray" to "grey", "grey" to "grey",
        "red" to "red", "crimson" to "red", "scarlet" to "red", "auburn" to "auburn", "orange" to "orange",
        "pink" to "pink", "blue" to "blue", "azure" to "blue", "green" to "green", "emerald" to "green",
        "purple" to "purple", "violet" to "purple", "lilac" to "purple", "cyan" to "cyan"
    )
    private val COLOR = WORDS.keys.joinToString("|", "(", ")")
    private val SENT = Regex("(?<=[.!?])\\s+")
    private val CASE = RegexOption.IGNORE_CASE

    /** "his silver hair" with the character named earlier in the same sentence. */
    private val PRONOUN = Regex(
        "\\b(?:his|her)\\s+(?:[A-Za-z-]+\\s+){0,2}?$COLOR(?:\\s+[A-Za-z-]+){0,2}?\\s+(hair|eyes)\\b", CASE
    )

    private class Rules(name: String) {
        private val n = "(?-i:" + Regex.escape(name) + ")"
        val possessive = Regex(
            "\\b" + n + "['’]s?\\s+(?:[A-Za-z-]+\\s+){0,2}?" + COLOR + "(?:\\s+[A-Za-z-]+){0,2}?\\s+(hair|eyes)\\b", CASE
        )
        val haired = Regex("\\b" + COLOR + "[- ]haired\\s+(?:[A-Za-z]+\\s+){0,2}?" + n + "\\b", CASE)
        val eyed = Regex("\\b" + COLOR + "[- ]eyed\\s+(?:[A-Za-z]+\\s+){0,2}?" + n + "\\b", CASE)
    }

    /** Blocking and fairly quick: only sentences that mention hair or eyes are examined. */
    fun scan(book: Book, names: List<String>): Map<String, Look> {
        if (names.isEmpty()) return emptyMap()
        val rules = HashMap<String, Rules>()
        val hair = HashMap<String, HashMap<String, Int>>()
        val eyes = HashMap<String, HashMap<String, Int>>()

        fun vote(name: String, word: String, part: String) {
            val key = WORDS[word.lowercase()] ?: return
            val m = if (part.equals("eyes", true)) eyes else hair
            val votes = m.getOrPut(name) { HashMap() }
            votes[key] = (votes[key] ?: 0) + 1
        }

        for (ch in book.chapters) for (p in ch.paragraphs) {
            if (!p.contains("hair", true) && !p.contains("eyes", true) &&
                !p.contains("haired", true) && !p.contains("eyed", true)
            ) continue
            for (sentence in p.split(SENT)) {
                val present = names.filter { sentence.contains(it) }
                if (present.isEmpty()) continue
                for (name in present) {
                    val r = rules.getOrPut(name) { Rules(name) }
                    for (m in r.possessive.findAll(sentence)) vote(name, m.groupValues[1], m.groupValues[2])
                    for (m in r.haired.findAll(sentence)) vote(name, m.groupValues[1], "hair")
                    for (m in r.eyed.findAll(sentence)) vote(name, m.groupValues[1], "eyes")
                }
                if (present.size == 1) {
                    val name = present[0]
                    val at = sentence.indexOf(name)
                    for (m in PRONOUN.findAll(sentence)) {
                        if (m.range.first > at) vote(name, m.groupValues[1], m.groupValues[2])
                    }
                }
            }
        }

        val out = HashMap<String, Look>()
        for (name in names) {
            val h = hair[name]?.maxByOrNull { it.value }
            val e = eyes[name]?.maxByOrNull { it.value }
            // A single mention is too easy to misread, so at least two are needed.
            when {
                h != null && h.value >= 2 -> out[name] = Look(h.key + " hair", h.key)
                e != null && e.value >= 2 -> out[name] = Look(e.key + " eyes", e.key)
            }
        }
        return out
    }

    // dark background color, light background color
    private val TABLE: Map<String, Pair<Int, Int>> = mapOf(
        "black" to (0xFF9FB3CC.toInt() to 0xFF3B4558.toInt()),
        "brown" to (0xFFC8956B.toInt() to 0xFF7A4A21.toInt()),
        "blond" to (0xFFFFD866.toInt() to 0xFF9A6B00.toInt()),
        "silver" to (0xFFC9D6E8.toInt() to 0xFF52657A.toInt()),
        "white" to (0xFFEAF4FF.toInt() to 0xFF6C7A89.toInt()),
        "grey" to (0xFFAEB6BF.toInt() to 0xFF59626B.toInt()),
        "red" to (0xFFFF6B6B.toInt() to 0xFFC62828.toInt()),
        "auburn" to (0xFFE08A5F.toInt() to 0xFFA0481C.toInt()),
        "orange" to (0xFFFFA040.toInt() to 0xFFD35400.toInt()),
        "pink" to (0xFFFF8FB8.toInt() to 0xFFC2185B.toInt()),
        "blue" to (0xFF6AA9FF.toInt() to 0xFF1565C0.toInt()),
        "green" to (0xFF6FD58D.toInt() to 0xFF2E7D32.toInt()),
        "purple" to (0xFFC08BFF.toInt() to 0xFF7B1FA2.toInt()),
        "cyan" to (0xFF5FD6E6.toInt() to 0xFF00838F.toInt())
    )

    // hue shift in degrees and brightness shift, for characters that share a color
    private val OFFSETS = listOf(
        12f to 0.10f, -12f to -0.10f, 24f to 0.20f, -24f to -0.20f, 36f to 0.0f, -36f to 0.0f
    )

    /** Final dialogue color per character. Characters with the same hair color get slightly different shades. */
    fun finalColors(looks: Map<String, Look>, order: List<String>, dark: Boolean): Map<String, Int> {
        val used = HashMap<String, Int>()
        val out = HashMap<String, Int>()
        for (name in order) {
            val look = looks[name] ?: continue
            val base = TABLE[look.key] ?: continue
            val n = used[look.key] ?: 0
            used[look.key] = n + 1
            out[name] = variant(if (dark) base.first else base.second, n, dark)
        }
        return out
    }

    private fun variant(color: Int, n: Int, dark: Boolean): Int {
        if (n == 0) return color
        val shift = OFFSETS[(n - 1) % OFFSETS.size]
        val hsv = FloatArray(3)
        AColor.colorToHSV(color, hsv)
        hsv[0] = (hsv[0] + shift.first + 360f) % 360f
        hsv[2] = (hsv[2] + (if (dark) shift.second else -shift.second)).coerceIn(0.25f, 1f)
        return AColor.HSVToColor(hsv)
    }
}
