package com.ethan.epubvoice

data class Detected(
    /** chapter -> paragraph -> segments */
    val chapters: List<List<List<Segment>>>,
    /** short name -> full name, e.g. "Harry" -> "Harry Potter" */
    val autoAliases: Map<String, String>
)

/**
 * Heuristic dialogue attribution. It finds quoted text and looks for "said Name",
 * "Name said", "Name:" patterns next to each quote. Quotes with no name nearby inherit
 * the speaker from the same sentence, or alternate in back-and-forth exchanges.
 * Anything it gets wrong can be fixed by long-pressing the line in the reader.
 */
object SpeakerDetector {
    private const val VERBS =
        "said|asked|replied|answered|whispered|shouted|yelled|muttered|murmured|cried|called|added|" +
            "continued|exclaimed|snapped|laughed|sighed|growled|demanded|suggested|explained|declared|" +
            "insisted|offered|responded|remarked|protested|stammered|breathed|hissed|barked|groaned|" +
            "sneered|chuckled|interrupted|warned|urged|admitted|agreed|began|says|asks|replies"

    private const val NAME =
        "(?:(?:Mr|Mrs|Ms|Dr|Lord|Lady|Sir|Captain|Professor|Master)\\.?\\s+)?" +
            "[A-Z][\\p{L}'’-]+(?:\\s+[A-Z][\\p{L}'’-]+)?"

    private val QUOTE = Regex("\"([^\"]+)\"|“([^”]+)”")
    private val AFTER = Regex("^\\s*,?\\s*(?:($NAME)\\s+(?:$VERBS)\\b|(?:$VERBS)\\s+($NAME))")
    private val BEFORE = Regex("($NAME)\\s+(?:$VERBS)(?:\\s+\\p{L}+ly)?\\s*[,:]?\\s*\$")
    private val COLON = Regex("($NAME)\\s*:\\s*\$")
    private val VERB_ANY = Regex("\\b(?:$VERBS)\\b")
    private val END_PUNCT = Regex("[.!?]")
    private val SPACES = Regex("\\s+")

    private val STOP = setOf(
        "he", "she", "they", "it", "i", "we", "you", "the", "a", "an", "and", "but", "then", "so", "as",
        "when", "while", "after", "before", "suddenly", "finally", "now", "there", "this", "that", "his",
        "her", "their", "my", "our", "me", "him", "them", "us", "what", "who", "how", "why", "if", "or",
        "yet", "at", "in", "on", "with", "by", "just", "well", "oh", "yes", "no", "maybe", "perhaps",
        "once", "again", "still", "even", "soon", "later", "meanwhile", "however", "though", "because",
        "since", "until", "one", "each", "everyone", "someone", "nobody", "somebody", "all", "both",
        "some", "another", "other", "which", "where", "here", "do", "did", "not", "never", "always",
        "only", "also", "too"
    )

    fun detect(book: Book): Detected {
        val counts = HashMap<String, Int>()
        val chapters = book.chapters.map { ch ->
            val recent = ArrayList<String?>()
            ch.paragraphs.map { text ->
                analyze(text, recent).also { segs ->
                    segs.forEach { s -> s.speaker?.let { counts[it] = (counts[it] ?: 0) + 1 } }
                }
            }
        }
        val multi = counts.keys.filter { it.contains(' ') }
        val auto = HashMap<String, String>()
        for (single in counts.keys.filter { !it.contains(' ') }) {
            val candidates = multi.filter { m -> m.split(' ').any { it.equals(single, ignoreCase = true) } }
            if (candidates.size == 1) auto[single] = candidates[0]
        }
        return Detected(chapters, auto)
    }

    private fun analyze(text: String, recent: MutableList<String?>): List<Segment> {
        val ms = QUOTE.findAll(text).toList()
        if (ms.isEmpty()) {
            recent.clear()
            return listOf(Segment(text, false))
        }
        val n = ms.size
        val before = Array(n) { "" }
        val spk = arrayOfNulls<String>(n)

        for (i in 0 until n) {
            val prevEnd = if (i == 0) 0 else ms[i - 1].range.last + 1
            before[i] = text.substring(prevEnd, ms[i].range.first)
            val nextStart = if (i == n - 1) text.length else ms[i + 1].range.first
            val after = text.substring(ms[i].range.last + 1, nextStart).take(100)
            spk[i] = AFTER.find(after)?.let { firstGroup(it) }?.let { clean(it) }
                ?: BEFORE.find(before[i])?.let { firstGroup(it) }?.let { clean(it) }
                ?: COLON.find(before[i])?.let { firstGroup(it) }?.let { clean(it) }
        }

        // "Hello," he said, "how are you?" -> both quotes share a speaker
        for (i in 1 until n) {
            val gap = before[i]
            if (gap.length < 60 && VERB_ANY.containsMatchIn(gap) && !END_PUNCT.containsMatchIn(gap)) {
                if (spk[i] == null) spk[i] = spk[i - 1] else if (spk[i - 1] == null) spk[i - 1] = spk[i]
            }
        }

        // Paragraph level fallback: reuse a named speaker, or alternate A, B, A, B
        var para: String? = spk.firstOrNull { it != null }
        if (para == null && recent.size >= 2) {
            val a = recent[recent.size - 1]
            val b = recent[recent.size - 2]
            if (a != null && b != null && a != b) para = b
        }
        for (i in 0 until n) if (spk[i] == null) spk[i] = para
        recent.add(spk[0])
        while (recent.size > 4) recent.removeAt(0)

        val segs = ArrayList<Segment>()
        var cursor = 0
        for (i in 0 until n) {
            val r = ms[i].range
            if (r.first > cursor) segs += Segment(text.substring(cursor, r.first), false)
            segs += Segment(text.substring(r.first, r.last + 1), true, spk[i])
            cursor = r.last + 1
        }
        if (cursor < text.length) segs += Segment(text.substring(cursor), false)
        return segs
    }

    private fun firstGroup(m: MatchResult): String? =
        m.groups.drop(1).firstNotNullOfOrNull { it?.value }

    private fun clean(raw: String): String? {
        val words = raw.trim().split(SPACES).toMutableList()
        while (words.isNotEmpty() && words.first().lowercase().trimEnd('.') in STOP) words.removeAt(0)
        if (words.isEmpty()) return null
        val name = words.joinToString(" ").trimEnd('\'', '’', '-')
        return if (name.length >= 2) name else null
    }
}
