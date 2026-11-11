package com.ethan.epubvoice

data class Detected(
    /** chapter -> paragraph -> segments */
    val chapters: List<List<List<Segment>>>,
    /** short name -> full name, e.g. "Harry" -> "Harry Potter" */
    val autoAliases: Map<String, String>
)

/**
 * Offline dialogue attribution. In order of confidence:
 *  1. "said Name", "Name said", "Name:" next to the quote
 *  2. a quote that continues the same sentence as the previous one
 *  3. action beats ("Fine." Harry shrugged.) and conversation alternation (marked as guesses)
 *  4. the character most mentioned in the surrounding paragraphs (marked as a guess)
 * Anything wrong can be fixed by hand or by the AI pass.
 */
object SpeakerDetector {
    private const val VERBS =
        "said|asked|replied|answered|whispered|shouted|yelled|muttered|murmured|cried|called|added|" +
            "continued|exclaimed|snapped|laughed|sighed|growled|demanded|suggested|explained|declared|" +
            "insisted|offered|responded|remarked|protested|stammered|breathed|hissed|barked|groaned|" +
            "sneered|chuckled|interrupted|warned|urged|admitted|agreed|began|says|asks|replies|thought|wondered"

    private const val NAME =
        "(?:(?:Mr|Mrs|Ms|Dr|Lord|Lady|Sir|Captain|Professor|Master)\\.?\\s+)?" +
            "[A-Z][\\p{L}'’-]+(?:\\s+[A-Z][\\p{L}'’-]+)?"

    // "double", “curly double” and ‘single curly’ (thoughts). The single-quote form only matches
    // when the quotes are not inside a word, so apostrophes in "wouldn’t" are left alone.
    private val QUOTE = Regex(
        "\"([^\"]+)\"|“([^”]+)”|(?<![\\p{L}\\p{N}])‘(.+?)’(?![\\p{L}\\p{N}])"
    )
    private val AFTER = Regex("^\\s*,?\\s*(?:($NAME)\\s+(?:$VERBS)\\b|(?:$VERBS)\\s+($NAME))")
    private val BEFORE = Regex("($NAME)\\s+(?:$VERBS)(?:\\s+\\p{L}+ly)?\\s*[,:]?\\s*\$")
    private val COLON = Regex("($NAME)\\s*:\\s*\$")
    private val AFTER_BEAT = Regex("^\\s*($NAME)\\s+\\p{Ll}")
    private val BEFORE_BEAT = Regex("^($NAME)\\s+\\p{Ll}")
    private val VERB_ANY = Regex("\\b(?:$VERBS)\\b")
    private val END_PUNCT = Regex("[.!?]")
    private val SPACES = Regex("\\s+")
    private val CAP_MID = Regex("(?<=[\\p{Ll},;] )\\p{Lu}\\p{L}{2,}")
    private val LOWER = Regex("(?<!\\p{L})\\p{Ll}\\p{L}{2,}")
    private val CAPWORD = Regex("\\p{Lu}\\p{L}{2,}")

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
        val names = bookNames(book)
        val counts = HashMap<String, Int>()
        val chapters = book.chapters.map { ch ->
            val recent = ArrayList<String?>()
            val paras: MutableList<List<Segment>> = ch.paragraphs.map { analyze(it, recent, names) }.toMutableList()
            contextPass(paras, names)
            for (segs in paras) for (s in segs) s.speaker?.let { counts[it] = (counts[it] ?: 0) + 1 }
            paras
        }
        return Detected(chapters, autoAlias(counts.keys))
    }

    /** "Harry" -> "Harry Potter" when exactly one longer name contains that word. */
    fun autoAlias(names: Collection<String>): Map<String, String> {
        val multi = names.filter { it.contains(' ') }
        val auto = HashMap<String, String>()
        for (single in names.filter { !it.contains(' ') }) {
            val candidates = multi.filter { m -> m.split(' ').any { it.equals(single, ignoreCase = true) } }
            if (candidates.size == 1) auto[single] = candidates[0]
        }
        return auto
    }

    /**
     * Words that look like character names: capitalized in the middle of sentences far more often
     * than they appear lowercase anywhere in the book.
     */
    private fun bookNames(book: Book): Set<String> {
        val cap = HashMap<String, Int>()
        val low = HashMap<String, Int>()
        for (ch in book.chapters) for (p in ch.paragraphs) {
            for (m in CAP_MID.findAll(p)) cap[m.value] = (cap[m.value] ?: 0) + 1
            for (m in LOWER.findAll(p)) low[m.value] = (low[m.value] ?: 0) + 1
        }
        val out = HashSet<String>()
        for ((w, c) in cap) {
            if (c >= 3 && c > 2 * (low[w.lowercase()] ?: 0) && w.lowercase() !in STOP) out.add(w)
        }
        return out
    }

    private fun analyze(text: String, recent: MutableList<String?>, names: Set<String>): List<Segment> {
        val ms = QUOTE.findAll(text).toList()
        if (ms.isEmpty()) {
            recent.clear()
            return listOf(Segment(text, false))
        }
        val n = ms.size
        val before = Array(n) { "" }
        val spk = arrayOfNulls<String>(n)
        val guess = BooleanArray(n)

        for (i in 0 until n) {
            val prevEnd = if (i == 0) 0 else ms[i - 1].range.last + 1
            before[i] = text.substring(prevEnd, ms[i].range.first)
            val nextStart = if (i == n - 1) text.length else ms[i + 1].range.first
            val after = text.substring(ms[i].range.last + 1, nextStart).take(100)

            val explicit = AFTER.find(after)?.let { firstGroup(it) }?.let { clean(it) }
                ?: BEFORE.find(before[i])?.let { firstGroup(it) }?.let { clean(it) }
                ?: COLON.find(before[i])?.let { firstGroup(it) }?.let { clean(it) }
            if (explicit != null) {
                spk[i] = explicit
            } else {
                val beat = AFTER_BEAT.find(after)?.let { firstGroup(it) }?.let { clean(it) }?.takeIf { known(it, names) }
                    ?: BEFORE_BEAT.find(lastSentence(before[i]))?.let { firstGroup(it) }?.let { clean(it) }
                        ?.takeIf { known(it, names) }
                if (beat != null) {
                    spk[i] = beat
                    guess[i] = true
                }
            }
        }

        // "Hello," he said, "how are you?" -> both quotes share a speaker
        for (i in 1 until n) {
            val gap = before[i]
            if (gap.length < 60 && VERB_ANY.containsMatchIn(gap) && !END_PUNCT.containsMatchIn(gap)) {
                if (spk[i] == null) {
                    spk[i] = spk[i - 1]
                    guess[i] = guess[i - 1]
                } else if (spk[i - 1] == null) {
                    spk[i - 1] = spk[i]
                    guess[i - 1] = guess[i]
                }
            }
        }

        // Paragraph level fallback: reuse a named speaker, or alternate A, B, A, B
        var para: String? = spk.firstOrNull { it != null }
        if (para == null && recent.size >= 2) {
            val a = recent[recent.size - 1]
            val b = recent[recent.size - 2]
            if (a != null && b != null && a != b) para = b
        }
        for (i in 0 until n) {
            if (spk[i] == null && para != null) {
                spk[i] = para
                guess[i] = true
            }
        }
        recent.add(spk[0])
        while (recent.size > 4) recent.removeAt(0)

        val segs = ArrayList<Segment>()
        var cursor = 0
        for (i in 0 until n) {
            val r = ms[i].range
            if (r.first > cursor) segs += Segment(text.substring(cursor, r.first), false)
            segs += Segment(text.substring(r.first, r.last + 1), true, spk[i], guess[i])
            cursor = r.last + 1
        }
        if (cursor < text.length) segs += Segment(text.substring(cursor), false)
        return segs
    }

    /**
     * For quotes still without a speaker, pick the character mentioned most in the nearby
     * narration (this paragraph, then the neighbors, then two away).
     */
    private fun contextPass(paras: MutableList<List<Segment>>, names: Set<String>) {
        val n = paras.size
        val pn = paras.map { segs ->
            segs.filter { !it.isQuote }.flatMap { s ->
                CAPWORD.findAll(s.text).map { it.value }.filter { it in names }.toList()
            }
        }
        val freq = HashMap<String, Int>()
        for (l in pn) for (nm in l) freq[nm] = (freq[nm] ?: 0) + 1

        for (p in 0 until n) {
            val segs = paras[p]
            if (segs.none { it.isQuote && it.speaker == null }) continue
            val score = HashMap<String, Int>()
            for (off in -2..2) {
                val q = p + off
                if (q < 0 || q >= n) continue
                val w = when (kotlin.math.abs(off)) {
                    0 -> 4
                    1 -> 3
                    else -> 1
                }
                for (nm in pn[q]) if ((freq[nm] ?: 0) >= 2) score[nm] = (score[nm] ?: 0) + w
            }
            val sorted = score.entries.sortedByDescending { it.value }
            val best = sorted.firstOrNull() ?: continue
            val second = sorted.getOrNull(1)?.value ?: 0
            if (best.value >= 3 && best.value > second) {
                paras[p] = segs.map { s ->
                    if (s.isQuote && s.speaker == null) s.copy(speaker = best.key, guess = true) else s
                }
            }
        }
    }

    private fun known(name: String, names: Set<String>): Boolean = name.split(' ').any { it in names }

    private fun lastSentence(before: String): String {
        val t = before.trim()
        val idx = maxOf(t.lastIndexOf(". "), t.lastIndexOf("! "), t.lastIndexOf("? "))
        return if (idx >= 0) t.substring(idx + 2) else t
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
