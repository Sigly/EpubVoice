import numpy as np, pickle
from scipy.optimize import minimize
S = pickle.load(open('samples.pkl','rb'))
NF = S[0][1].shape[1]
def nll(w, data, l2):
    tot = 0; grad = np.zeros_like(w)
    for ch, X, ti, meta in data:
        if ti < 0: continue
        s = X @ w; s -= s.max(); e = np.exp(s); P = e / e.sum()
        tot -= np.log(P[ti] + 1e-12)
        grad -= X[ti] - P @ X
    return tot + l2 * w @ w, grad + 2 * l2 * w
def fit(data, l2=1.0):
    r = minimize(nll, np.zeros(NF), args=(data, l2), jac=True, method='L-BFGS-B')
    return r.x
def acc(w, data):
    ok = 0
    for ch, X, ti, meta in data:
        if ti >= 0 and np.argmax(X @ w) == ti: ok += 1
    return ok / len(data)
chs = sorted(set(s[0] for s in S)); folds = [chs[i::5] for i in range(5)]
for l2 in [0.3, 1.0, 3.0]:
    accs = []
    for f in folds:
        tr = [s for s in S if s[0] not in f]; te = [s for s in S if s[0] in f]
        w = fit(tr, l2); accs.append(acc(w, te))
    print('l2', l2, 'cv acc %.3f' % np.mean(accs), [round(a, 2) for a in accs])
w = fit(S, 1.0); print('train acc %.3f' % acc(w, S))
np.set_printoptions(precision=2, suppress=True); print(w)
pickle.dump(w, open('w.pkl','wb'))
