package com.ethan.epubvoice

import kotlin.math.ln
import kotlin.math.min

data class Detected(
    /** chapter -> paragraph -> segments */
    val chapters: List<List<List<Segment>>>,
    /** short name -> full name, e.g. "Harry" -> "Harry Potter" */
    val autoAliases: Map<String, String>
)

/**
 * Offline dialogue attribution.
 *
 * 1. Find the book's characters: capitalized words that act like people (they do things, and are
 *    rarely written in lowercase).
 * 2. For every quote, list candidate speakers from the nearby text and score each one with a small
 *    learned model (weights below) over cues such as "Name said", a beat like "Lorex roared.",
 *    who was just addressed, who spoke last, whose scene it is, and who the thoughts belong to.
 * 3. Quotes are processed in order, so turn taking (A, B, A, B) feeds into the next decision.
 * Quotes that are scare quotes or system text are marked so they are not colored as speech.
 * Low-confidence calls are flagged as guesses (shown in italics). Fix anything by hand or with the AI.
 */
object SpeakerDetector {
    private const val SPEECH =
        "said|asked|replied|answered|whispered|shouted|yelled|muttered|murmured|cried|called|added|" +
            "continued|exclaimed|snapped|laughed|sighed|growled|demanded|suggested|explained|declared|" +
            "insisted|offered|responded|remarked|protested|stammered|breathed|hissed|barked|groaned|" +
            "sneered|chuckled|interrupted|warned|urged|admitted|agreed|began|says|asks|replies|thought|" +
            "wondered|roared|screamed|questioned|reported|ordered|commanded|retorted|grumbled|bellowed|" +
            "smiled|spoke|told|stated|announced|mumbled|cheered|praised|apologized|finished|nodded|" +
            "cursed|reminded|corrected|noted|scolded"

    private const val STRICT =
        "said|asked|replied|answered|whispered|shouted|yelled|muttered|murmured|cried|exclaimed|demanded|" +
            "suggested|explained|declared|insisted|responded|remarked|protested|stammered|hissed|barked|" +
            "retorted|growled|snapped|sneered|bellowed|roared|screamed|says|asks|replies|added|continued|" +
            "interrupted|warned|questioned"

    private const val PRE = "(?:\\p{Lu}\\p{Ll}+ ){0,3}"

    private val STOPNAMES: Set<String> = (
        "The A An And But Then So As When While After Before Suddenly Finally Now There This That His Her Their " +
            "My Our Me Him Them Us What Who How Why If Or Yet At In On With By Just Well Oh Yes No Maybe Perhaps Once " +
            "Again Still Even Soon Later Meanwhile However Though Because Since Until One Each Everyone Someone Nobody " +
            "Somebody All Both Some Another Other Which Where Here Do Did Not Never Always Only Also Too Unlike " +
            "Although Giving Had Didn Don Isn Wasn Overgeared Satisfy Kingdom Empire National Competition King Earl " +
            "Majesty Sir Lord Lady Captain Knight Knights Saintess Prince Princess Emperor Duke Sword Master Professor " +
            "Mr Mrs Ms Dr God Church Guild World Hall Fame Dragon Red Black White Blue Great Third Fifth First Second " +
            "Fourth Sixth Seventh Eighth Ninth Tenth Hello Hey Okay Please Thank Thanks Korea Korean Japan Japanese " +
            "China Chinese Russia Russian States United Canada East West South North Valhalla Reinhardt Reidan Titan " +
            "Lubana Behen Archipelago Liberon Forest Hell Heaven Level Skill Quest Fighting Linked Kill Wave Pinnacle " +
            "Basic Attack Hero Legend Legends Undefeated Descendant Hmm Huh Kuk Eh Ah Ugh Hiik Kuaaaak " +
            "She They You We Her Him His Its Our Your Their Those These Mister Missus Miss Madam"
        ).split(" ").toSet()

    // "double", “curly double” and ‘single curly’ (thoughts). The single-quote form only matches
    // when the quotes are not inside a word, so apostrophes in "wouldn’t" are left alone.
    private val QUOTE = Regex(
        "\"([^\"]+)\"|“([^”]+)”|(?<![\\p{L}\\p{N}])‘(.+?)’(?![\\p{L}\\p{N}])"
    )
    private val TOKEN = Regex("\\p{Lu}\\p{L}+")
    private val CAP3 = Regex("\\p{Lu}\\p{L}{2,}")
    private val LOW3 = Regex("(?<![\\p{L}])\\p{Ll}\\p{L}{2,}")
    private val VERBRE = Regex(
        "^\\s+(?:$SPEECH|\\p{Ll}+ed|ran|stood|sat|took|gave|saw|felt|knew|looked|stepped|moved|turned|" +
            "laughed|shook|left|came|went|made|got|fell|rose)\\b"
    )
    private val SCENE_BREAK = Regex("\\s*(\\*\\s*){3,}")
    private val SENT_SPLIT = Regex("(?<=[.!?…])\\s+(?=[\\p{Lu}“\"‘\\[])")
    private val PREP = Regex("(?:to|at|toward|towards|of|with|from|for|by|than)$")
    private val WS = Regex("\\s+")
    private val ENDS_LOWER = Regex("[\\p{Ll}\\p{N}]$")
    private val ENDS_SPEECH = Regex("(?:$SPEECH)$")
    private val ENDS_CONJ = Regex("\\b(?:thought|that|and|but|then)$")
    private val SENT_END = Regex("[.!?…]\\s*$")

    private const val NF = 29

    /** Learned weights (see tools notes in README). Index = feature number in [features]. */
    private val W = doubleArrayOf(
        0.27, 0.14, 1.16, 0.77, 0.49, -1.23, 0.0, 0.48, 0.95, 0.18,
        -0.12, 0.28, 0.43, 0.07, 0.36, 0.35, 0.27, 1.49, 0.1, -0.16,
        0.43, 1.85, 0.28, -0.29, 1.34, 0.39, 0.65, 4.0, 4.0
    )

    private const val MIN_P = 0.35
    private const val SURE_P = 0.75

    private class Hit(val start: Int, val end: Int, val name: String)

    private class Hist(val p: Int, val qi: Int, val spk: String?, val thought: Boolean, val scene: Int)

    private class Rx(names: String) {
        val a1 = Regex("^\\s*[,.]?\\s*(?:$PRE)?($names)\\s+(?:$SPEECH)\\b")
        val a2 = Regex("^\\s*[,.]?\\s*(?:$SPEECH)\\s+(?:$PRE)?($names)\\b")
        val b1 = Regex("($names)\\s+(?:$SPEECH)(?:\\s+\\p{L}+ly)?\\s*[,:]?\\s*$")
        val b2 = Regex("($names)\\s*:\\s*$")
        val beat = Regex("^\\s*(?:$PRE)?($names)\\b")
        val sa1 = Regex("^\\s*[,.]?\\s*(?:$PRE)?($names)\\s+(?:$STRICT)\\b")
        val sa2 = Regex("^\\s*[,.]?\\s*(?:$STRICT)\\s+(?:$PRE)?($names)\\b")
        val sb1 = Regex("($names)\\s+(?:$STRICT)(?:\\s+\\p{L}+ly)?\\s*[,:]?\\s*$")
    }

    private class Chap(val paras: List<String>, val persons: Set<String>) {
        val n = paras.size
        val q: List<List<MatchResult>> = paras.map { QUOTE.findAll(it).toList() }
        val narr: List<String> = paras.mapIndexed { p, t ->
            if (q[p].isEmpty()) t else {
                val sb = StringBuilder(t)
                for (m in q[p]) for (i in m.range) sb.setCharAt(i, ' ')
                sb.toString()
            }
        }
        val scene = IntArray(n)
        val nm: List<List<Hit>>
        val qnm: List<List<List<String>>>
        lateinit var focusSlow: List<Map<String, Double>>
        lateinit var focusFast: List<Map<String, Double>>

        init {
            var s = 0
            for (p in 0 until n) {
                scene[p] = s
                if (SCENE_BREAK.matches(paras[p])) s++
            }
            nm = narr.map { namesIn(it, persons) }
            qnm = (0 until n).map { p ->
                q[p].map { m -> namesIn(quoteText(m), persons).map { it.name } }
            }
            focusSlow = focus(0.85)
            focusFast = focus(0.5)
        }

        private fun focus(decay: Double): List<Map<String, Double>> {
            val out = ArrayList<Map<String, Double>>(n)
            var cur = HashMap<String, Double>()
            var last = -1
            for (p in 0 until n) {
                if (last != -1 && scene[p] != last) {
                    val nx = HashMap<String, Double>()
                    for ((k, v) in cur) nx[k] = v * 0.3
                    cur = nx
                }
                last = scene[p]
                for (k in cur.keys.toList()) cur[k] = cur[k]!! * decay
                val t = paras[p]
                for (h in nm[p]) {
                    val subj = h.start == 0 || SENT_END.containsMatchIn(t.substring(0, h.start))
                    cur[h.name] = (cur[h.name] ?: 0.0) + if (subj) 1.5 else 0.6
                }
                out.add(if (q[p].isEmpty()) emptyMap() else HashMap(cur))
            }
            return out
        }
    }

    private fun quoteText(m: MatchResult): String = m.groups.drop(1).firstNotNullOfOrNull { it?.value } ?: ""

    private fun namesIn(text: String, persons: Set<String>): List<Hit> {
        val out = ArrayList<Hit>()
        for (m in TOKEN.findAll(text)) if (m.value in persons) out.add(Hit(m.range.first, m.range.last + 1, m.value))
        return out
    }

    fun detect(book: Book): Detected {
        val persons = findPersons(book)
        val gfreq = HashMap<String, Int>()
        for (ch in book.chapters) for (t in ch.paragraphs) {
            for (h in namesIn(t, persons)) gfreq[h.name] = (gfreq[h.name] ?: 0) + 1
        }
        val gmax = maxOf(1, gfreq.values.maxOrNull() ?: 1)
        val rx = if (persons.isEmpty()) null else Rx(
            persons.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        )
        val counts = HashMap<String, Int>()
        val chapters = book.chapters.map { ch ->
            val paras = detectChapter(ch.paragraphs, persons, rx, gfreq, gmax)
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
     * People are capitalized words that are rarely seen in lowercase and often followed by an action
     * verb ("Lorex roared", "Mia looked"). Places, skills and titles mostly fail that test.
     */
    private fun findPersons(book: Book): Set<String> {
        val total = HashMap<String, Int>()
        val low = HashMap<String, Int>()
        val agent = HashMap<String, Int>()
        for (ch in book.chapters) for (t in ch.paragraphs) {
            for (m in CAP3.findAll(t)) {
                val w = m.value
                total[w] = (total[w] ?: 0) + 1
                val tail = t.substring(m.range.last + 1, min(t.length, m.range.last + 1 + 25))
                if (VERBRE.containsMatchIn(tail)) agent[w] = (agent[w] ?: 0) + 1
            }
            for (m in LOW3.findAll(t)) low[m.value] = (low[m.value] ?: 0) + 1
        }
        val out = HashSet<String>()
        for ((w, c) in total) {
            if (c < 4 || w in STOPNAMES) continue
            if ((low[w.lowercase()] ?: 0) > c * 0.3) continue
            val a = agent[w] ?: 0
            if (a >= 2 && a >= 0.04 * c) out.add(w)
        }
        return out
    }

    private fun isScare(before: String, inner: String, qi: Int): Boolean {
        val words = inner.trim().split(WS).filter { it.isNotEmpty() }
        val endc = inner.trimEnd().lastOrNull()
        if (endc != null && endc !in ".!?…,~-—" && words.size <= 5) return true
        val b = before.trimEnd()
        if (qi == 0 && before.isBlank()) return false
        if (b.isNotEmpty() && inner.firstOrNull()?.isLowerCase() == true && ENDS_LOWER.containsMatchIn(b) &&
            !ENDS_SPEECH.containsMatchIn(b) && words.size <= 5 && !ENDS_CONJ.containsMatchIn(b)
        ) {
            if (before == b + " " || before == b) return true
        }
        return false
    }

    private fun detectChapter(
        paras: List<String>, persons: Set<String>, rx: Rx?, gfreq: Map<String, Int>, gmax: Int
    ): List<List<Segment>> {
        val out = ArrayList<List<Segment>>(paras.size)
        if (rx == null) {
            for ((p, t) in paras.withIndex()) {
                val ms = QUOTE.findAll(t).toList()
                out.add(buildSegments(t, ms, arrayOfNulls(ms.size), BooleanArray(ms.size), BooleanArray(ms.size)))
            }
            return out
        }
        val ctx = Chap(paras, persons)
        var hist = ArrayList<Hist>()
        for (p in 0 until ctx.n) {
            val ms = ctx.q[p]
            val t = paras[p]
            if (ms.isEmpty()) {
                out.add(listOf(Segment(t, false)))
                continue
            }
            val spk = arrayOfNulls<String>(ms.size)
            val guess = BooleanArray(ms.size)
            val scare = BooleanArray(ms.size)
            val sys = t.trimStart().startsWith("[")
            for (qi in ms.indices) {
                val m = ms[qi]
                val prevEnd = if (qi > 0) ms[qi - 1].range.last + 1 else 0
                val nextStart = if (qi + 1 < ms.size) ms[qi + 1].range.first else t.length
                val before = t.substring(prevEnd, m.range.first)
                val after = t.substring(m.range.last + 1, nextStart)
                val inn = quoteText(m)
                val thought = t[m.range.first] == '‘'
                if (sys || isScare(before, inn, qi)) {
                    scare[qi] = true
                    continue
                }
                val cands = candidates(ctx, p, hist)
                if (cands.isEmpty()) {
                    hist.add(Hist(p, qi, null, thought, ctx.scene[p]))
                    continue
                }
                val feats = features(ctx, rx, p, qi, hist, cands, gfreq, gmax, before, after, thought)
                val scores = DoubleArray(cands.size) { i ->
                    var s = 0.0
                    for (k in 0 until NF) s += feats[i][k] * W[k]
                    s
                }
                val mx = scores.max()
                var z = 0.0
                for (i in scores.indices) {
                    scores[i] = Math.exp(scores[i] - mx)
                    z += scores[i]
                }
                var best = 0
                for (i in scores.indices) if (scores[i] > scores[best]) best = i
                val prob = scores[best] / z
                val chosen = cands[best]
                val explicit = feats[best][27] > 0 || feats[best][28] > 0
                if (prob >= MIN_P) {
                    spk[qi] = chosen
                    guess[qi] = prob < SURE_P && !explicit
                }
                hist.add(Hist(p, qi, chosen, thought, ctx.scene[p]))
                if (hist.size > 10) hist = ArrayList(hist.subList(hist.size - 10, hist.size))
            }
            out.add(buildSegments(t, ms, spk, guess, scare))
        }
        return out
    }

    private fun buildSegments(
        text: String, ms: List<MatchResult>, spk: Array<String?>, guess: BooleanArray, scare: BooleanArray
    ): List<Segment> {
        if (ms.isEmpty()) return listOf(Segment(text, false))
        val segs = ArrayList<Segment>()
        var cursor = 0
        for (i in ms.indices) {
            val r = ms[i].range
            if (r.first > cursor) segs += Segment(text.substring(cursor, r.first), false)
            segs += Segment(text.substring(r.first, r.last + 1), true, spk[i], guess[i], scare[i])
            cursor = r.last + 1
        }
        if (cursor < text.length) segs += Segment(text.substring(cursor), false)
        return segs
    }

    private fun candidates(ctx: Chap, p: Int, hist: List<Hist>): List<String> {
        val c = java.util.TreeSet<String>()
        for (off in -4..2) {
            val j = p + off
            if (j < 0 || j >= ctx.n) continue
            if (ctx.scene[j] != ctx.scene[p] && off != 0) continue
            for (h in ctx.nm[j]) c.add(h.name)
        }
        for (off in -2..2) {
            val j = p + off
            if (j < 0 || j >= ctx.n) continue
            for (l in ctx.qnm[j]) c.addAll(l)
        }
        for (h in hist.takeLast(6)) h.spk?.let { c.add(it) }
        return c.toList()
    }

    private fun features(
        ctx: Chap, rx: Rx, p: Int, qi: Int, hist: List<Hist>, cands: List<String>,
        gfreq: Map<String, Int>, gmax: Int, before: String, after: String, thought: Boolean
    ): Array<DoubleArray> {
        val idx = HashMap<String, Int>()
        for ((i, c) in cands.withIndex()) idx[c] = i
        val f = Array(cands.size) { DoubleArray(NF) }
        fun add(name: String?, k: Int, v: Double = 1.0) {
            val i = idx[name ?: return] ?: return
            f[i][k] += v
        }

        rx.a1.find(after)?.let { add(it.groupValues[1], 0) }
        rx.a2.find(after)?.let { add(it.groupValues[1], 0) }
        rx.b1.find(before)?.let { add(it.groupValues[1], 1) }
        rx.b2.find(before)?.let { add(it.groupValues[1], 1) }
        rx.beat.find(after)?.let { add(it.groupValues[1], 2) }
        rx.sa1.find(after)?.let { add(it.groupValues[1], 27) }
        rx.sa2.find(after)?.let { add(it.groupValues[1], 27) }
        rx.sb1.find(before)?.let { add(it.groupValues[1], 28) }
        rx.b2.find(before)?.let { add(it.groupValues[1], 28) }

        val b = before.trim()
        if (b.isNotEmpty()) {
            val last = SENT_SPLIT.split(b).last()
            val nms = namesIn(last, ctx.persons)
            if (nms.isNotEmpty()) {
                val h0 = nms[0]
                val pref = last.substring(0, h0.start).trimEnd()
                val poss = last.substring(h0.end, min(last.length, h0.end + 2))
                if (!PREP.containsMatchIn(pref) && poss != "'s" && poss != "’s") add(h0.name, 3)
                for (h in nms) add(h.name, 4)
            }
        }
        for (n in ctx.qnm[p][qi]) add(n, 5)

        val prev = hist.lastOrNull()
        if (prev != null && prev.scene == ctx.scene[p] && p - prev.p <= 2) {
            for (n in ctx.qnm[prev.p][prev.qi]) add(n, 6)
            if (prev.spk != null) {
                add(prev.spk, if (prev.p == p) 8 else 7)
                val others = hist.dropLast(1).filter { it.spk != null && it.spk != prev.spk && !it.thought }
                if (others.isNotEmpty()) add(others.last().spk, 9)
            }
        }

        var nxtP = -1
        var nxtQ = 0
        if (qi + 1 < ctx.q[p].size) {
            nxtP = p; nxtQ = qi + 1
        } else {
            for (j in p + 1 until min(p + 3, ctx.n)) {
                if (ctx.q[j].isNotEmpty()) { nxtP = j; nxtQ = 0; break }
            }
        }
        if (nxtP >= 0) for (n in ctx.qnm[nxtP][nxtQ]) add(n, 10)

        val fs = ctx.focusSlow[p]
        val ff = ctx.focusFast[p]
        val msx = maxOf(fs.values.maxOrNull() ?: 1.0, 1e-9)
        val mfx = maxOf(ff.values.maxOrNull() ?: 1.0, 1e-9)
        val gl = ln(1.0 + gmax)
        val last6 = hist.takeLast(6)
        val thoughtSpk = hist.takeLast(4).filter { it.thought && it.spk != null }.lastOrNull()?.spk
        val prevNm = if (p > 0 && ctx.scene[p - 1] == ctx.scene[p]) ctx.nm[p - 1] else emptyList()
        val nextNm = if (p + 1 < ctx.n && ctx.scene[p + 1] == ctx.scene[p]) ctx.nm[p + 1] else emptyList()
        val afterNames = namesIn(after, ctx.persons).map { it.name }
        val distinct = ctx.nm[p].map { it.name }.toSet()
        val isLast = qi + 1 == ctx.q[p].size
        var nextSubj: String? = null
        if (isLast && p + 1 < ctx.n && ctx.scene[p + 1] == ctx.scene[p]) {
            val t2 = ctx.paras[p + 1]
            val nm2 = ctx.nm[p + 1]
            if (nm2.isNotEmpty() && nm2[0].start == 0) {
                val s2 = t2.trimStart()
                if (!(s2.startsWith("\"") || s2.startsWith("“") || s2.startsWith("‘"))) nextSubj = nm2[0].name
            }
        }
        for ((i, c) in cands.withIndex()) {
            val a = (fs[c] ?: 0.0) / msx
            val bb = (ff[c] ?: 0.0) / mfx
            f[i][11] = a
            f[i][12] = bb
            if (thought) { f[i][13] = a; f[i][14] = bb }
            val cnt = ctx.nm[p].count { it.name == c }
            f[i][15] = min(cnt, 3) / 3.0
            if (prevNm.any { it.name == c }) f[i][16] = 1.0
            if (nextNm.any { it.name == c }) f[i][17] = 1.0
            val g = ln(1.0 + (gfreq[c] ?: 0)) / gl
            f[i][18] = g
            if (thought) f[i][19] = g
            if (last6.any { it.spk == c }) f[i][20] = 1.0
            if (thought && thoughtSpk == c) f[i][21] = 1.0
            if (qi == 0 && prevNm.isNotEmpty() && prevNm.last().name == c) f[i][22] = 1.0
            if (before.isBlank() && qi == 0 && !thought) {
                val h = hist.lastOrNull()
                if (h != null && h.spk == c && h.p != p) f[i][23] = 1.0
            }
            if (nextSubj == c) f[i][24] = 1.0
            if (distinct.size == 1 && c in distinct) f[i][25] = 1.0
            if (c in afterNames) f[i][26] = 1.0
        }
        return f
    }
}
