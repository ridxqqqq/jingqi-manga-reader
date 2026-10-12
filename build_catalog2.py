# -*- coding: utf-8 -*-
# 重建 catalog.json：
# 1. DOM 顺序解析（新→旧）→ 按章节号去重（重复组验证图片可用性，保留主列表可用版）
# 2. 反转 + 按章节号稳定排序 → 旧→新
# 3. 无编号条目：最早 26 话按 DOM 反转序排最前；插在中间的特别篇锚定在相邻章节号之间
import re, io, sys, base64, json, html
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
import urllib.request

UA = 'Mozilla/5.0 (Linux; Android 13) Mobile Safari/537.36'

def fetch(url):
    req = urllib.request.Request(url, headers={
        'User-Agent': UA, 'Referer': 'http://m.gugu5.cc/'})
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.read()

def first_img_works(ch):
    """检查该章节第一张图能否下载"""
    try:
        page = fetch(ch['u']).decode('gbk', 'ignore')
        m = re.search(r'qTcms_S_m_murl_e\s*=\s*"([^"]*)"', page)
        if not m:
            return False
        urls = base64.b64decode(m.group(1)).decode('utf-8', 'ignore').split('$qingtiandy$')
        urls = [u for u in urls if u.startswith('http')]
        if not urls:
            return False
        data = fetch(urls[0])
        return len(data) > 10000
    except Exception:
        return False

def cnum(t):
    m = re.match(r'^(\d+)', t)
    if m:
        return int(m.group(1))
    m = re.search(r'第\s*(\d+)\s*话', t)
    if m:
        return int(m.group(1))
    return None

def norm_title(t):
    return re.sub(r'\s+', '', t).replace('罈', '坛')

raw = open('m_page.html', 'rb').read().decode('utf-8', 'ignore')
pairs = re.findall(r"history_in\('((?:\\'|[^'])*)'\s*,\s*'((?:\\'|[^'])*)'\)", raw)
items, seen = [], set()
for t, u in pairs:
    t = html.unescape(t).replace("\\'", "'").strip()
    if '/p/34726/' not in u:
        continue
    pid = int(re.search(r'/(\d+)\.html', u).group(1))
    if pid in seen:
        continue
    seen.add(pid)
    items.append({'p': pid, 't': t, 'u': u})
print('parsed unique:', len(items))

# ---- 分组去重 ----
groups = {}   # key -> [(dom_idx, item)]
order = []    # keys in first-appearance order
for idx, it in enumerate(items):
    n = cnum(it['t'])
    key = ('n', n) if n is not None else ('t', norm_title(it['t']))
    if key not in groups:
        groups[key] = []
        order.append(key)
    groups[key].append((idx, it))

dup_keys = [k for k in order if len(groups[k]) > 1]
print('duplicate groups:', len(dup_keys))
kept = {}  # key -> item
for k in order:
    cand = groups[k]
    if len(cand) == 1:
        kept[k] = cand[0][1]
        continue
    # 重复组：按 DOM 顺序（主列表在前），保留第一个图片可用的
    print(f'  group {k}:')
    chosen = None
    for idx, it in cand:
        ok = first_img_works(it)
        print(f"    pid {it['p']} [{it['t']}] imgs_ok={ok}")
        if ok and chosen is None:
            chosen = it
    if chosen is None:
        chosen = cand[0][1]
        print(f"    -> none verified, keep first pid {chosen['p']}")
    else:
        print(f"    -> keep pid {chosen['p']}")
    kept[k] = chosen

# ---- 旧→新排序 ----
# 反转 DOM（去重后），得到大致旧→新；再用章节号稳定排序修正局部乱序
rev = [kept[k] for k in reversed(order)]
# 无编号条目按“前一个已分配号 + 0.5”锚定
keys = []
last_assigned = 0.0
for it in rev:
    n = cnum(it['t'])
    if n is not None:
        keys.append(float(n))
        last_assigned = float(n)
    else:
        keys.append(last_assigned + 0.5)
        last_assigned = keys[-1]
paired = list(zip(keys, rev))
paired.sort(key=lambda x: x[0])  # 稳定排序
final = [it for _, it in paired]

print('final chapters:', len(final))
print('head-6:')
for it in final[:6]:
    print('   ', it['t'])
print('tail-4:')
for it in final[-4:]:
    print('   ', it['t'])

# 局部乱序抽查：1150 拆迁队 应在 1149 与 1155 之间（按号排序后应在 1150 位置）
def find(t):
    for i, it in enumerate(final):
        if it['t'] == t:
            return i
    return -1
i1150 = find('1150 拆迁队')
print('1150 拆迁队 index:', i1150, 'of', len(final))

# 1800 双条目检查
c1800 = [it for it in final if cnum(it['t']) == 1800]
print('ch1800 entries:', [(it['p'], it['t']) for it in c1800])

json.dump(final, open('apk/assets/catalog.json', 'w', encoding='utf-8'),
          ensure_ascii=False, separators=(',', ':'))
print('apk/assets/catalog.json written')
