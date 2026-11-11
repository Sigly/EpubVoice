import numpy as np, pickle
from collections import Counter
from load import *; from det import *; from feat import *
import train as T
b, g, det, gfreq, GEN = T.b, T.g, T.det, T.gfreq, T.GEN

def infer_chapter(ch, w, MINP=0.0):
    ctx = Ctx(det, ch); hist = []; out = {}
    for p in ctx.keys:
        ms = ctx.q[p]
        if not ms: continue
        row = []
        for qi in range(len(ms)):
            before, after, inner, thought = quote_info(ctx, p, qi)
            if ctx.paras[p].lstrip().startswith('[') or det.is_scare(before, after, inner, thought, qi, 0):
                row.append(('-', 1.0)); continue
            cands = candidates(ctx, p, qi, hist)
            if not cands: row.append((None, 0)); hist.append(dict(p=p, qi=qi, spk=None, thought=thought, scene=ctx.scene[p])); continue
            F, th, *_ = features(ctx, p, qi, hist, cands, gfreq)
            X = np.array([F[c] for c in cands]); s = X @ w; s -= s.max(); e = np.exp(s); P = e / e.sum()
            k = int(P.argmax()); row.append((cands[k], float(P[k])))
            hist.append(dict(p=p, qi=qi, spk=cands[k], thought=th, scene=ctx.scene[p])); hist = hist[-10:]
        out[p] = row
    return out

def evaluate(wfor):
    chs = sorted(g); folds = [chs[i::5] for i in range(5)]
    ok = tot = 0; byconf = Counter(); byconf_ok = Counter()
    for f in folds:
        w = wfor(f)
        for ch in f:
            o = infer_chapter(ch, w)
            for p, names in g[ch].items():
                for i, n in enumerate(names):
                    if n in GEN or n == '-': continue
                    tot += 1
                    pr = o[p][i] if p in o and i < len(o[p]) else (None, 0)
                    hit = pr[0] == n or (pr[0] and n.split()[-1] == pr[0])
                    ok += hit
                    bkt = min(int(pr[1] * 5), 4); byconf[bkt] += 1; byconf_ok[bkt] += hit
    print('sequential CV acc %.3f (%d/%d)' % (ok / tot, ok, tot))
    for k in sorted(byconf): print(' conf bucket', k / 5, byconf[k], '%.2f' % (byconf_ok[k] / byconf[k]))

if __name__ == '__main__':
    from fit import fit
    S = pickle.load(open('samples.pkl', 'rb'))
    evaluate(lambda f: fit([s for s in S if s[0] not in f], 1.0))
