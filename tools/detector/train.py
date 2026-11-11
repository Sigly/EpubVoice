import numpy as np, pickle, sys
from collections import Counter
from scipy.optimize import minimize
from load import *; from det import *; from feat import *
b = load_book(); g = load_gt('/tmp/claude-0/work/epubvoice-ai.txt')
det = Detector(b)
GEN = {'Soldiers','Crowd','Player','Vampire','Red Knight','Maid','Villager','Jackson'}
gfreq = Counter()
for ch in b.values():
    for t in ch.values():
        for _,_,n in det.names_in(t): gfreq[n] += 1

def build_samples():
    S = []  # list of (chapter, [feature matrix], true index or -1, meta)
    for ch in sorted(g):
        ctx = Ctx(det, ch)
        hist = []
        for p in ctx.keys:
            ms = ctx.q[p]
            if not ms: continue
            for qi in range(len(ms)):
                before, after, inner, thought = quote_info(ctx, p, qi)
                label = g[ch].get(p, [None]*len(ms))[qi] if qi < len(g[ch].get(p, [])) else None
                if det.is_scare(before, after, inner, thought, qi, 0) or ctx.paras[p].lstrip().startswith('[') or label == '-':
                    continue
                cands = candidates(ctx, p, qi, hist)
                F, th, bf, af, inn = features(ctx, p, qi, hist, cands, gfreq)
                spk = None
                if label and label not in GEN:
                    spk = next((c for c in cands if c == label or c == label.split()[-1]), None)
                    sp_hist = spk if spk else label.split()[-1]
                else:
                    sp_hist = None
                if cands:
                    X = np.array([F[c] for c in cands])
                    ti = cands.index(spk) if spk in cands else -1
                    if label and label not in GEN:
                        S.append((ch, X, ti, (ch, p, qi, label, cands)))
                hist.append(dict(p=p, qi=qi, spk=sp_hist, thought=th, scene=ctx.scene[p]))
                hist = hist[-10:]
    return S

if __name__ == '__main__':
    S = build_samples()
    print(len(S), 'samples', sum(1 for s in S if s[2] >= 0), 'with true cand in set')
    pickle.dump(S, open('samples.pkl', 'wb'))
