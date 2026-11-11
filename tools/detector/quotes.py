import regex as re

_OLD_Q = re.compile(r'"([^"]+)"|“([^”]+)”|(?<![\p{L}\p{N}])‘(.+?)’(?![\p{L}\p{N}])')
_MIXED_Q = re.compile(r'["“]([^"“”]+)["”]')

class _QM:
    def __init__(self, s, e, text): self.s, self.e, self.text = s, e, text
    def start(self): return self.s
    def end(self): return self.e
    def span(self): return (self.s, self.e)
    def groups(self): return (self.text,)
    def group(self, i=0): return self.text

class _Quotes:
    """Old QUOTE matches first, then mixed-mark quotes ("text” or “text") found only in the gaps.
    Mirrors SpeakerDetector.findQuotes in the app."""
    def finditer(self, t):
        out = []; pos = 0
        def gap(a, b):
            for m in _MIXED_Q.finditer(t, a, b): out.append(_QM(m.start(), m.end(), m.group(1)))
        for m in _OLD_Q.finditer(t):
            gap(pos, m.start())
            out.append(_QM(m.start(), m.end(), next(g for g in m.groups() if g)))
            pos = m.end()
        gap(pos, len(t))
        return out

QUOTE = _Quotes()
