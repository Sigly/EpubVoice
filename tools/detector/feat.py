import math, numpy as np
import regex as re
from collections import defaultdict
from det import Detector, SPEECH, sent_split
STRICT = ("said|asked|replied|answered|whispered|shouted|yelled|muttered|murmured|cried|exclaimed|demanded|"
          "suggested|explained|declared|insisted|responded|remarked|protested|stammered|hissed|barked|retorted|"
          "growled|snapped|sneered|bellowed|roared|screamed|says|asks|replies|added|continued|interrupted|warned|questioned")

NF = 29
PREP = re.compile(r'(?:to|at|toward|towards|of|with|from|for|by|than)$')

def last_tok(n): return n.split()[-1]

class Ctx:
    def __init__(self, det, ch):
        self.det = det; self.ch = ch
        paras = det.chapters[ch]
        self.keys = sorted(paras)
        self.paras = paras
        self.q = {}
        self.narr = {}
        for p in self.keys:
            t = paras[p]
            ms = list(det.QUOTE.finditer(t))
            self.q[p] = ms
            nt = t
            for m in reversed(ms): nt = nt[:m.start()] + ' ' * (m.end() - m.start()) + nt[m.end():]
            self.narr[p] = nt
        s = 0; self.scene = {}
        for p in self.keys:
            self.scene[p] = s
            if re.fullmatch(r'\s*(\*\s*){3,}', paras[p]): s += 1
        self.nm = {p: det.names_in(self.narr[p]) for p in self.keys}
        self.qnm = {}
        for p in self.keys:
            for qi, m in enumerate(self.q[p]):
                inner = next(g for g in m.groups() if g)
                self.qnm[(p, qi)] = [n for _, _, n in det.names_in(inner)]
        self.pos = {p: i for i, p in enumerate(self.keys)}
        self.focus_slow = self._focus(0.85); self.focus_fast = self._focus(0.5)
        self.NAMES = '|'.join(sorted(map(re.escape, det.persons), key=len, reverse=True))

    def _focus(self, decay):
        foc = {}; cur = defaultdict(float); last = None
        for p in self.keys:
            if last is not None and self.scene[p] != last:
                cur = defaultdict(float, {n: v * 0.3 for n, v in cur.items()})
            last = self.scene[p]
            for n in list(cur): cur[n] *= decay
            t = self.paras[p]
            for (s0, e0, n) in self.nm[p]:
                subj = s0 == 0 or re.search(r'[.!?…]\s*$', t[:s0]) is not None
                cur[n] += 1.5 if subj else 0.6
            foc[p] = dict(cur)
        return foc

def quote_info(ctx, p, qi):
    t = ctx.paras[p]; ms = ctx.q[p]; m = ms[qi]
    prevend = ms[qi-1].end() if qi else 0
    nxt = ms[qi+1].start() if qi + 1 < len(ms) else len(t)
    before = t[prevend:m.start()]; after = t[m.end():nxt]
    inner = next(g for g in m.groups() if g)
    return before, after, inner, t[m.start()] == '‘'

def candidates(ctx, p, qi, hist):
    c = set()
    i = ctx.pos[p]
    for off in range(-4, 3):
        j = i + off
        if 0 <= j < len(ctx.keys):
            q = ctx.keys[j]
            if ctx.scene[q] != ctx.scene[p] and off != 0: continue
            for _, _, n in ctx.nm[q]: c.add(n)
    for off in range(-2, 3):
        j = i + off
        if 0 <= j < len(ctx.keys):
            q = ctx.keys[j]
            for k in range(len(ctx.q[q])): c.update(ctx.qnm[(q, k)])
    for h in hist[-6:]:
        if h['spk']: c.add(h['spk'])
    return sorted(c)

def features(ctx, p, qi, hist, cands, gfreq):
    det = ctx.det
    before, after, inner, thought = quote_info(ctx, p, qi)
    NAMES = ctx.NAMES
    F = {c: np.zeros(NF) for c in cands}
    def add(c, k, v=1.0):
        if c in F: F[c][k] += v
    pre = r'(?:(?:\p{Lu}\p{Ll}+) ){0,3}'
    if NAMES:
        m = re.match(r'^\s*[,.]?\s*(?:' + pre + r')?(' + NAMES + r')\s+(?:' + SPEECH + r')\b', after)
        if m: add(m.group(1), 0)
        m = re.match(r'^\s*[,.]?\s*(?:' + SPEECH + r')\s+(?:' + pre + r')?(' + NAMES + r')\b', after)
        if m: add(m.group(1), 0)
        m = re.search(r'(' + NAMES + r')\s+(?:' + SPEECH + r')(?:\s+\p{L}+ly)?\s*[,:]?\s*$', before)
        if m: add(m.group(1), 1)
        m = re.search(r'(' + NAMES + r')\s*:\s*$', before)
        if m: add(m.group(1), 1)
        m = re.match(r'^\s*(?:' + pre + r')?(' + NAMES + r')\b', after)
        if m: add(m.group(1), 2)
    if NAMES:
        m = re.match(r'^\s*[,.]?\s*(?:' + pre + r')?(' + NAMES + r')\s+(?:' + STRICT + r')\b', after)
        if m: add(m.group(1), 27)
        m = re.match(r'^\s*[,.]?\s*(?:' + STRICT + r')\s+(?:' + pre + r')?(' + NAMES + r')\b', after)
        if m: add(m.group(1), 27)
        m = re.search(r'(' + NAMES + r')\s+(?:' + STRICT + r')(?:\s+\p{L}+ly)?\s*[,:]?\s*$', before)
        if m: add(m.group(1), 28)
        m = re.search(r'(' + NAMES + r')\s*:\s*$', before)
        if m: add(m.group(1), 28)
    b = before.strip()
    if b:
        ss = sent_split(b); last = ss[-1]
        nm = det.names_in(last)
        if nm:
            s0, e0, n0 = nm[0]
            if not PREP.search(last[:s0].rstrip()) and last[e0:e0+2] not in ("'s", '’s'):
                add(n0, 3)
            for _, _, n in nm: add(n, 4)
    for n in ctx.qnm[(p, qi)]: add(n, 5)
    # prev / next quote vocatives
    i = ctx.pos[p]
    prevq = hist[-1] if hist else None
    if prevq and prevq['scene'] == ctx.scene[p] and ctx.pos[p] - ctx.pos[prevq['p']] <= 2:
        for n in ctx.qnm[(prevq['p'], prevq['qi'])]: add(n, 6)
        if prevq['spk']:
            same = prevq['p'] == p
            add(prevq['spk'], 8 if same else 7)
            others = [h for h in hist[:-1] if h['spk'] and h['spk'] != prevq['spk'] and not h['thought']]
            if others: add(others[-1]['spk'], 9)
    # next quote inner names
    nxt = None
    if qi + 1 < len(ctx.q[p]): nxt = (p, qi + 1)
    elif i + 1 < len(ctx.keys):
        for j in range(i + 1, min(i + 3, len(ctx.keys))):
            q = ctx.keys[j]
            if ctx.q[q]: nxt = (q, 0); break
    if nxt:
        for n in ctx.qnm[nxt]: add(n, 10)
    fs = ctx.focus_slow[p]; ff = ctx.focus_fast[p]
    ms_ = max(fs.values()) if fs else 1; mf = max(ff.values()) if ff else 1
    for c in cands:
        a = fs.get(c, 0) / max(ms_, 1e-9); bb = ff.get(c, 0) / max(mf, 1e-9)
        F[c][11] = a; F[c][12] = bb
        if thought: F[c][13] = a; F[c][14] = bb
        cnt = sum(1 for (_, _, n) in ctx.nm[p] if n == c)
        F[c][15] = min(cnt, 3) / 3
        if i > 0 and ctx.scene[ctx.keys[i-1]] == ctx.scene[p]:
            F[c][16] = 1.0 if any(n == c for _, _, n in ctx.nm[ctx.keys[i-1]]) else 0
        if i + 1 < len(ctx.keys) and ctx.scene[ctx.keys[i+1]] == ctx.scene[p]:
            F[c][17] = 1.0 if any(n == c for _, _, n in ctx.nm[ctx.keys[i+1]]) else 0
        g = math.log(1 + gfreq.get(c, 0)) / math.log(1 + max(gfreq.values()))
        F[c][18] = g
        if thought: F[c][19] = g
        if any(h['spk'] == c for h in hist[-6:]): F[c][20] = 1.0
        if thought:
            th = [h for h in hist[-4:] if h['thought'] and h['spk']]
            if th and th[-1]['spk'] == c: F[c][21] = 1.0
        # subject of last narration sentence in paragraph (possibly the one before)
        if qi == 0 and i > 0 and ctx.scene[ctx.keys[i-1]] == ctx.scene[p]:
            nmprev = ctx.nm[ctx.keys[i-1]]
            if nmprev and nmprev[-1][2] == c: F[c][22] = 1.0
        if before.strip() == '' and qi == 0 and not thought and any(h['spk'] == c and h['p'] != p for h in hist[-1:]):
            F[c][23] = 1.0
    # extra features
    after_names = [n for _, _, n in det.names_in(after)]
    is_last = qi + 1 == len(ctx.q[p])
    nextsubj = None
    if is_last and i + 1 < len(ctx.keys):
        q2 = ctx.keys[i + 1]
        if ctx.scene[q2] == ctx.scene[p]:
            t2 = ctx.paras[q2]
            nm2 = ctx.nm[q2]
            if nm2 and nm2[0][0] == 0 and not t2.lstrip().startswith(('"', '“', '‘')):
                nextsubj = nm2[0][2]
    distinct = {n for _, _, n in ctx.nm[p]}
    for c in cands:
        if nextsubj == c: F[c][24] = 1.0
        if len(distinct) == 1 and c in distinct: F[c][25] = 1.0
        if c in after_names: F[c][26] = 1.0
    return F, thought, before, after, inner
