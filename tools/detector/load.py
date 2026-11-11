import regex as re, glob, json
SRC = glob.glob('/mnt/user-data/attachments/*/76749113-claude-chapters-710-809.txt')[0]
QUOTE = re.compile(r'"([^"]+)"|“([^”]+)”|(?<![\p{L}\p{N}])‘(.+?)’(?![\p{L}\p{N}])')

def load_book():
    chapters = {}
    cur = None
    for line in open(SRC, encoding='utf-8').read().split('\n'):
        m = re.match(r'=== CHAPTER (\d+) ', line)
        if m:
            cur = int(m.group(1)); chapters[cur] = {}; continue
        if cur is None: continue
        m = re.match(r'\[(\d+)\] (.*)$', line)
        if m:
            chapters[cur][int(m.group(1))] = m.group(2)
    return chapters

def load_gt(path):
    gt = {}; cur = None
    for line in open(path, encoding='utf-8').read().split('\n')[1:]:
        if line.startswith('#'):
            cur = int(line[1:]); gt[cur] = {}
        elif ':' in line and cur is not None:
            p, rest = line.split(':', 1)
            gt[cur][int(p)] = [x.strip() for x in rest.split('|')]
    return gt

if __name__ == '__main__':
    b = load_book(); g = load_gt('/tmp/claude-0/work/epubvoice-ai.txt')
    bad = 0; tot = 0
    for ch in g:
        for p, names in g[ch].items():
            n = len(list(QUOTE.finditer(b[ch][p])))
            tot += 1
            if n != len(names):
                bad += 1; print('mismatch', ch, p, n, len(names), b[ch][p][:80])
    print(len(b), 'chapters', tot, 'labeled paras', bad, 'count mismatches')
