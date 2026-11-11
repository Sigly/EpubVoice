import regex as re
from collections import Counter, defaultdict

SPEECH = ("said|asked|replied|answered|whispered|shouted|yelled|muttered|murmured|cried|called|added|"
 "continued|exclaimed|snapped|laughed|sighed|growled|demanded|suggested|explained|declared|insisted|"
 "offered|responded|remarked|protested|stammered|breathed|hissed|barked|groaned|sneered|chuckled|"
 "interrupted|warned|urged|admitted|agreed|began|says|asks|replies|thought|wondered|roared|screamed|"
 "questioned|reported|ordered|commanded|retorted|grumbled|bellowed|smiled|spoke|told|stated|announced|"
 "mumbled|cheered|praised|apologized|answered|finished|nodded|cursed|reminded|corrected|noted|scolded")
STOPNAMES = set("""The A An And But Then So As When While After Before Suddenly Finally Now There This That His Her Their
My Our Me Him Them Us What Who How Why If Or Yet At In On With By Just Well Oh Yes No Maybe Perhaps Once Again Still
Even Soon Later Meanwhile However Though Because Since Until One Each Everyone Someone Nobody Somebody All Both Some
Another Other Which Where Here Do Did Not Never Always Only Also Too Not Unlike Although Giving Had Didn Don Isn Wasn
Overgeared Satisfy Kingdom Empire National Competition King Earl Majesty Sir Lord Lady Captain Knight Knights Saintess
Prince Princess Emperor Duke Sword Master Professor Mr Mrs Ms Dr God Church Guild World Hall Fame Dragon Red Black
White Blue Great Third Fifth First Second Fourth Sixth Seventh Eighth Ninth Tenth Hello Hey Okay Please Thank Thanks
Korea Korean Japan Japanese China Chinese Russia Russian States United Canada East West South North Valhalla Reinhardt
Reidan Titan Lubana Behen Archipelago Liberon Forest Hell Heaven Level Skill Quest Fighting Linked Kill Wave Pinnacle
Pagma's Swordsmanship Basic Attack Hero Legend Legends Undefeated Descendant Hmm Huh Kuk Eh Ah Ugh Hiik Kuaaaak""".split())

def sent_split(t):
    return re.split(r'(?<=[.!?…])\s+(?=[\p{Lu}“"‘\[])', t.strip())

class Detector:
    def __init__(self, chapters):
        self.chapters = chapters
        self.build_lexicon()

    # ---------- lexicon ----------
    def build_lexicon(self):
        CAPM = re.compile(r'(?<=[\p{Ll},;] )\p{Lu}[\p{L}]{2,}')
        LOW = re.compile(r'(?<![\p{L}])\p{Ll}[\p{L}]{2,}')
        capc = Counter(); lowc = Counter(); agent = Counter(); total = Counter()
        VERBRE = re.compile(r'^\s+(?:' + SPEECH + r'|\p{Ll}+ed|ran|stood|sat|took|gave|saw|felt|knew|looked|stepped|moved|turned|laughed|shook|left|came|went|made|got|fell|rose)\b')
        for ch in self.chapters.values():
            for t in ch.values():
                for m in CAPM.finditer(t): capc[m.group()] += 1
                for m in LOW.finditer(t): lowc[m.group()] += 1
                for m in re.finditer(r'\p{Lu}[\p{L}]{2,}', t):
                    total[m.group()] += 1
                    if VERBRE.match(t[m.end():m.end()+25]): agent[m.group()] += 1
        pers = set()
        for w, c in total.items():
            if w in STOPNAMES: continue
            if c < 4: continue
            if lowc[w.lower()] > c * 0.3: continue
            if agent[w] >= 2 and agent[w] >= 0.04 * c: pers.add(w)
        self.persons = pers
        # bigram merge: "Yang Fei", "Pope Damian"
        big = Counter(); first = Counter()
        for ch in self.chapters.values():
            for t in ch.values():
                for m in re.finditer(r'(\p{Lu}[\p{L}]+) (\p{Lu}[\p{L}]+)', t):
                    big[(m.group(1), m.group(2))] += 1
        self.bigrams = {}
        for (a, b2), c in big.items():
            if c >= 3 and b2 in pers and a not in STOPNAMES and total[a] > 0 and c >= 0.6 * total[a] and a in pers:
                self.bigrams[(a, b2)] = a + ' ' + b2
        if hasattr(self, 'debug'): pass

    def names_in(self, text):
        """list of (start, end, name) for persons, merging known bigrams"""
        out = []
        toks = list(re.finditer(r'\p{Lu}[\p{L}]+', text))
        i = 0
        while i < len(toks):
            m = toks[i]
            w = m.group()
            if i + 1 < len(toks) and (w, toks[i+1].group()) in self.bigrams and text[m.end():toks[i+1].start()] == ' ':
                out.append((m.start(), toks[i+1].end(), self.bigrams[(w, toks[i+1].group())])); i += 2; continue
            if w in self.persons:
                out.append((m.start(), m.end(), w))
            i += 1
        return out

    # ---------- per-chapter detection ----------
    from quotes import QUOTE  # mixed-mark aware, mirrors the app
    SPV = re.compile(r'\b(?:' + SPEECH + r')\b')

    W_POV = 4.0
    W_POV_T = 7.0
    DECAY = 0.8
    def detect_chapter(self, ch):
        paras = self.chapters[ch]
        keys = sorted(paras)
        # parse
        info = {}
        for p in keys:
            t = paras[p]
            ms = list(self.QUOTE.finditer(t))
            info[p] = (t, ms)
        # narration names per paragraph (excluding quotes)
        narr = {}
        for p in keys:
            t, ms = info[p]
            nt = t
            for m in reversed(ms): nt = nt[:m.start()] + ' ' * (m.end() - m.start()) + nt[m.end():]
            narr[p] = nt
        scene = {}
        s = 0
        for p in keys:
            scene[p] = s
            if re.fullmatch(r'\s*(\*\s*){3,}|\s*\*\*\*\s*', paras[p]): s += 1
        res = {}
        hist = []  # (p, speaker, addressees, thought, scene)
        nm_cache = {p: self.names_in(narr[p]) for p in keys}
        self._focus = self.build_focus(keys, nm_cache, scene, narr)
        for idx, p in enumerate(keys):
            t, ms = info[p]
            if not ms: continue
            out = []
            sysp = t.lstrip().startswith('[')
            for qi, m in enumerate(ms):
                prevend = ms[qi-1].end() if qi else 0
                nxt = ms[qi+1].start() if qi + 1 < len(ms) else len(t)
                before = t[prevend:m.start()]
                after = t[m.end():nxt]
                inner = next(g for g in m.groups() if g)
                thought = t[m.start()] == '‘'
                if sysp:
                    out.append(('-', 9)); continue
                if self.is_scare(before, after, inner, thought, qi, prevend):
                    out.append(('-', 9)); continue
                sc = defaultdict(float)
                self.explicit(sc, before, after, inner, thought)
                self.pov(sc, p, idx, keys, nm_cache, scene, thought, qi, ms, before)
                self.turns(sc, hist, p, scene[p], thought, qi)
                best = sorted(sc.items(), key=lambda kv: -kv[1])
                spk = None; conf = 0
                if best and best[0][1] >= 2.0:
                    spk = best[0][0]; conf = best[0][1] - (best[1][1] if len(best) > 1 else 0)
                out.append((spk, conf))
                adds = [n for _, _, n in self.names_in(inner)]
                to = [n for _, _, n in self.names_in(re.sub(r'.*\b(?:to|at|asked|told|toward|towards)\s+', '', before))]
                hist.append((p, spk, adds + to, thought, scene[p]))
                hist = [h for h in hist if h[4] == scene[p]][-8:]
            res[p] = out
        return res

    def is_scare(self, before, after, inner, thought, qi, prevend):
        words = inner.split()
        endc = inner.rstrip()[-1:]
        if endc not in '.!?…,~-—' and len(words) <= 5: return True
        b = before.rstrip()
        if qi == 0 and before.strip() == '': return False
        if b and inner[:1].islower() and re.search(r'[\p{Ll}\p{N}]$', b) and not re.search(r'(?:' + SPEECH + r')$', b) and len(words) <= 5 and not re.search(r'\b(?:thought|that|and|but|then)$', b):
            if before == b + ' ' or before == b: return True
        return False

    def explicit(self, sc, before, after, inner, thought):
        NAMES = '|'.join(sorted(map(re.escape, self.persons), key=len, reverse=True))
        if not NAMES: return
        pre = r'(?:(?:[A-Z][a-z]+) ){0,3}'
        m = re.match(r'^\s*[,.]?\s*(?:' + pre + r')?(' + NAMES + r')\s+(?:' + SPEECH + r')\b', after)
        if m: sc[m.group(1)] += 10
        m = re.match(r'^\s*[,.]?\s*(?:' + SPEECH + r')\s+(?:' + pre + r')?(' + NAMES + r')\b', after)
        if m: sc[m.group(1)] += 10
        m = re.search(r'(' + NAMES + r')\s+(?:' + SPEECH + r')(?:\s+\p{L}+ly)?\s*[,:]?\s*$', before)
        if m: sc[m.group(1)] += 10
        m = re.search(r'(' + NAMES + r')\s*:\s*$', before)
        if m: sc[m.group(1)] += 10
        # beat after: sentence starts with a name
        m = re.match(r'^\s*(?:(?:[A-Z][a-z]+) ){0,3}(' + NAMES + r')\b', after)
        if m and not sc: sc[m.group(1)] += 5
        # beat before: last sentence of before; first name is subject
        b = before.strip()
        if b:
            ss = sent_split(b)
            last = ss[-1] if ss else ''
            nm = self.names_in(last)
            if nm and not nm[0][2] in sc:
                # subject = first name unless it is preceded by 'to|at|asked|of' or followed by possessive
                s0, e0, n0 = nm[0]
                pref = last[:s0].rstrip()
                if not re.search(r'(?:to|at|toward|towards|of|with|from|for)$', pref) and not last[e0:e0+2] in ("'s", '’s'):
                    sc[n0] += 4
        # vocative: names inside the quote are not the speaker
        for _, _, n in self.names_in(inner): sc[n] -= 6

    def pov(self, sc, p, idx, keys, nm_cache, scene, thought, qi, ms, before):
        f = self._focus[p]
        tot = sum(f.values()) or 1.0
        top = max(f.values()) if f else 0
        for n, v in f.items():
            sc[n] += (self.W_POV_T if thought else self.W_POV) * v / max(top, 1e-9) * min(1.0, top / 1.5)

    def build_focus(self, keys, nm_cache, scene, paras):
        foc = {}
        cur = defaultdict(float)
        lastscene = None
        for p in keys:
            if lastscene is not None and scene[p] != lastscene:
                cur = defaultdict(float, {n: v * 0.35 for n, v in cur.items()})
            lastscene = scene[p]
            for n in list(cur): cur[n] *= self.DECAY
            t = paras[p]
            for (s0, e0, n) in nm_cache[p]:
                subj = s0 == 0 or re.search(r'[.!?…]\s*$', t[:s0]) is not None
                cur[n] += 1.5 if subj else 0.6
            foc[p] = defaultdict(float, dict(cur))
        return foc

    def chapters_text(self, q):
        return self._cur[q]

    def turns(self, sc, hist, p, sc_id, thought, qi):
        if thought: return
        if not hist: return
        last = hist[-1]
        if last[3]: 
            pass
        lp, lspk, ladd, lth, ls = last
        if lspk and not lth:
            if p - lp <= 2 and not (lp == p):
                sc[lspk] -= 3.0
            if lp == p:
                sc[lspk] -= 1.0
        for a in ladd:
            if a != lspk and p - lp <= 2: sc[a] += 3.5
        # speaker before the previous one
        prev = [h for h in hist[:-1] if not h[3] and h[1] and h[1] != lspk]
        if prev and p - lp <= 2: sc[prev[-1][1]] += 2.5
