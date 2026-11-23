#!/usr/bin/env python3
# 码上取 PickupManager —— 完全离线的安卓快递取件助手
# Copyright (C) 2026 shanqiugzs
# SPDX-License-Identifier: GPL-3.0-or-later
# 许可全文见仓库根目录 LICENSE。
"""校验 README.md 里的每一处代码引用是否仍然成立。

背景：本仓库把所有文档合并进单一 README，而 README 里密布 `文件:行号`、
`类.方法()`、`R.string.xxx` 这类硬引用。代码一改，这些引用就会静默失效 ——
而"文档比代码乐观"是一个以隐私为卖点的开源项目最不该有的缺陷。

用法：
    python3 tools/check_doc_refs.py            # 人工/CI 均可，失败时退出码非 0

检查五类引用：
  1. `xxx.kt:123` / `xxx.kt:12-34`  —— 文件必须存在，且行号不超过文件长度
  2. `AndroidManifest.xml:12`       —— 同上
  3. 反引号内的 Kotlin 标识符 `Foo.bar()` / `Foo()` / `CONSTANT` —— 符号必须仍能在源码里找到
  4. 反引号内不带扩展名的裸类名行号 `PickupSmsParserV2:1000/1019` —— 按 `<类名>.kt` 解析并校验行号
  5. 「包职责」表的文件数列 —— 与磁盘上各包的 .kt 数量逐行比对
"""
import pathlib
import re
import sys

# Windows 控制台默认 GBK，输出 ✓ / 中文会抛 UnicodeEncodeError；这里强制 UTF-8。
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = pathlib.Path(__file__).resolve().parent.parent
README = ROOT / 'README.md'   # 默认目标；自检测试会传入一份被改坏的副本

# 注释/字符串的屏蔽只有一份实现，和图标检查器共用（见 tools/audit/kotlin_lexer.py 的说明）
sys.path.insert(0, str(ROOT / 'tools/audit'))
from kotlin_lexer import count_chinese_literals  # noqa: E402

FILE_REF = re.compile(
    r'([A-Za-z0-9_./-]+\.(?:kt|xml|pro|kts|json))[:\s]+'
    r'(\d{1,4}(?:\s*(?:[-–/,])\s*\d{1,4})*)')
# 反引号里的 Kotlin 符号引用：Foo.bar() / Foo() / Foo.bar(arg, …)
# 带参数的一起查：`dao.markExpiredItems(PickupItem.STATUS_EXPIRED, ...)` 这类写法
# 上一版要求紧跟 ()，于是整批漏检，而 markExpiredItems 其实早已被删。
SYMBOL = re.compile(r'`([A-Za-z_][A-Za-z0-9_]*(?:\.[a-zA-Z_][A-Za-z0-9_]*)?)\s*\(')
BACKTICK = re.compile(r'`([^`\n]+)`')
# 反引号内容整体形如 `Foo.kt:12` / `Foo:12-34` / `Foo:1000/1019`
BARE_REF = re.compile(r'^([A-Za-z][A-Za-z0-9_.\-/]*?)[:：]\s*(.+)$')


def expand_numspec(spec):
    """把行号规格拆成 [(lo, hi_or_None), ...]。

    `12`          -> [(12, None)]
    `12-34`       -> [(12, 34)]
    `594, 1137`   -> [(594, None), (1137, None)]     ← 此前整段只取到第一个数，
    `1000/1019`   -> [(1000, None), (1019, None)]       后面的数字**从未被校验**
    """
    out = []
    for chunk in re.split(r'[,/]', spec):
        chunk = chunk.strip()
        if not chunk:
            continue
        m = re.match(r'^(\d{1,4})\s*[-–]\s*(\d{1,4})$', chunk)
        if m:
            out.append((int(m.group(1)), int(m.group(2))))
        elif chunk.isdigit():
            out.append((int(chunk), None))
    return out

# 「包职责」表的一行： | `widget/` | 15 | ... |
PKG_ROW = re.compile(r'^\|\s*`([a-z]+/)`\s*\|\s*(\d+)\s*\|', re.M)
# 句子里常见但**不能当锚点**的词：文件名成分、Kotlin/Android 通用词。
# 拿它们当锚会把"行号漂移"误判成"没问题"。
STOP_WORDS = {
    'fun', 'val', 'var', 'override', 'private', 'internal', 'suspend', 'class', 'object',
    'import', 'package', 'android', 'kotlin', 'true', 'false', 'null', 'this', 'com',
    'kt', 'kts', 'xml', 'json', 'pro', 'README', 'main', 'app', 'src', 'res', 'values',
    'https', 'http', 'file', 'line', 'name', 'data', 'ui', 'util', 'widget', 'viewmodel',
    'db', 'repository', 'receiver', 'model', 'service', 'components', 'screens', 'theme',
    'Composable', 'Modifier', 'remember', 'State', 'Flow', 'List', 'String', 'Int', 'Boolean',
}


def _lines_of(lines, word):
    pat = re.compile(r'\b' + re.escape(word) + r'\b')
    hits = [i + 1 for i, l in enumerate(lines) if pat.search(l)]
    if not hits:
        return 0
    if len(hits) == 1:
        return f'第 {hits[0]} 行'
    return '第 ' + ', '.join(str(h) for h in hits[:6]) + ' 行'
PKG_ROOT = pathlib.Path(__file__).resolve().parent.parent / 'app/src/main/java/com/shanqiu/pickupmanager'
RES_ROOT = ROOT / 'app/src/main/res'
TEST_ROOT = ROOT / 'app/src/test'

CJK = re.compile(r'[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]')
# XML 属性值里的中文（实测本仓库命中的 28 处全部是 android:text）
XML_ATTR = re.compile(r'(\w[\w:]*)\s*=\s*"([^"]*)"')
XML_STRING = re.compile(r'<string name="([^"]+)"')


def gather_numbers():
    """把 README 里"可机械复核的数字"全部重算一遍。

    为什么要有这一段：README 用"逐条可核对""机械校验"支撑读者对它全部数字的信任，
    而**这个强度此前超出了工具的能力** —— 工具只查 `文件:行号` 与包文件数，
    于是 819 / 24 / 0 / 34 这些数字写错了也没人发现（H-14）。
    这里把能算的都算出来，算不出的（历史基线、语料相关）就不让它进正文。
    """
    main_kts = sorted(PKG_ROOT.rglob('*.kt'))
    strings_xml = RES_ROOT / 'values/strings.xml'
    entries = XML_STRING.findall(strings_xml.read_text(encoding='utf-8')) if strings_xml.exists() else []

    res_cjk = 0
    for p in RES_ROOT.rglob('*.xml'):
        for _, val in XML_ATTR.findall(p.read_text(encoding='utf-8', errors='ignore')):
            if CJK.search(val):
                res_cjk += 1

    kt_cjk = sum(count_chinese_literals(p.read_text(encoding='utf-8', errors='ignore'))
                 for p in main_kts)

    test_src = '\n'.join(p.read_text(encoding='utf-8', errors='ignore') for p in TEST_ROOT.rglob('*.kt'))
    # 静态可数：@Test 注解个数与"环境变量门控"的断言个数。
    # 与实跑结果一致（83 / 2），所以不必依赖构建产物。
    tests = len(re.findall(r'^\s*@Test\b', test_src, re.M))
    gated = len(re.findall(r'Assume\.assumeTrue', test_src))

    # 审计工具自己的测试（tools/audit/test_*.py）。
    # 为什么要把它纳入复算：R7 给 scanner 加了 2 个负向用例，48 这个数字出现在 6 个文件里，
    # 全靠人工同步 —— 那正是 H-14 那类"声明与实现不符"的温床。
    audit_tests = sum(len(re.findall(r'^\s*def test_', p.read_text(encoding='utf-8', errors='ignore'), re.M))
                      for p in (ROOT / 'tools' / 'audit').glob('test_*.py'))

    return {
        'main_files': len(main_kts),
        'main_lines': sum(len(p.read_text(encoding='utf-8', errors='ignore').splitlines()) for p in main_kts),
        'strings_entries': len(entries),
        'res_cjk': res_cjk,
        'kt_cjk': kt_cjk,
        'tests': tests,
        'gated': gated,
        'audit_tests': audit_tests,
    }


# (说明, 在 README 里找声明的正则, 取期望值的函数)
# 规则：**声明必须存在且数字正确**。删掉那句数字并不等于通过 —— 会报"找不到这项声明"。
NUM_CLAIMS = [
    ('包职责节的「主源文件数与行数」',
     re.compile(r'主源 `\.kt` 共 \*\*(\d+) 个文件、(\d+) 行\*\*'),
     lambda n: (n['main_files'], n['main_lines'])),
    ('构建注意事项里的「106→N 个 / 28877→M 行」',
     re.compile(r'主源 Kotlin 文件 106→(\d+) 个（28877 行 → (\d+) 行'),
     lambda n: (n['main_files'], n['main_lines'])),
    ('问题 8 的「含中文的字符串字面量」',
     re.compile(r'Kotlin 里有 \*\*(\d+) 处\*\*含中文的字符串字面量'),
     lambda n: (n['kt_cjk'],)),
    ('问题 8 的「strings.xml 条数」',
     re.compile(r'`res/values/strings\.xml` 有 \*\*(\d+)\*\* 条'),
     lambda n: (n['strings_entries'],)),
    ('问题 8 的「XML 硬编码中文文案数」',
     re.compile(r'布局与资源 XML 里的硬编码中文文案有 \*\*(\d+) 处\*\*'),
     lambda n: (n['res_cjk'],)),
    ('问题 9 的「用例数 / 门控跳过数」',
     re.compile(r'当前共 \*\*(\d+) 个用例\*\*，其中 \*\*(\d+) 个\*\*由环境变量门控'),
     lambda n: (n['tests'], n['gated'])),
    ('测试命令注释里的「用例数」',
     re.compile(r'testDebugUnitTest\s+#\s*(\d+) 个用例'),
     lambda n: (n['tests'],)),
    ('构建注意事项里的「单测从 16 增至 N 个」',
     re.compile(r'其间单测从 16 增至 (\d+) 个用例'),
     lambda n: (n['tests'],)),
    ('门禁一节的「审计工具自测用例数」',
     re.compile(r'`tools/audit/test_\*\.py` 共 \*\*(\d+)\*\* 个用例'),
     lambda n: (n['audit_tests'],)),
]


ISSUE_REF = re.compile(r'问题\s*#?\s*(\d{1,2})')
ISSUE_REF2 = re.compile(r'修复状态」\s*#(\d{1,2})')
ISSUE_HEAD = re.compile(r'^###\s*(\d{1,2})\.\s', re.M)


def check_issue_refs(errors, readme):
    """校验「问题 N」这类**内部交叉引用**指向真实存在的条目。

    为什么要有这一条：本文件的 `文件:行号` 校验只管代码锚点，没人管
    "README 自己引用的那个条目号还在不在"。R7 就在 §3 的权限表里发现过
    `第 4 节问题 35`，而清单只到 34 —— 引用指向不存在的对象，
    与 H-14 那两处 +8 漂移属于同一类缺陷，只是换了个方向（指向文档自己）。
    README 与 `docs/audit/*.md`、`CROSS-VERIFICATION.md` 一并扫。
    """
    ki = companion(readme)
    src = ki if ki is not None else readme
    valid = {int(m.group(1)) for m in ISSUE_HEAD.finditer(src.read_text(encoding='utf-8'))}
    # README 的状态表也列了同一批编号，一并纳入（表格行形如 `| 12 | ... |`）
    valid |= {int(m.group(1)) for m in re.finditer(
        r'^\|\s*(\d{1,2})\s*\|', readme.read_text(encoding='utf-8'), re.M)}
    targets = [readme] + sorted((ROOT / 'docs' / 'audit').glob('*.md'))
    cv = ROOT / 'docs' / 'audit' / 'CROSS-VERIFICATION.md'
    if cv.exists():
        targets.append(cv)
    n = 0
    for p in targets:
        text = p.read_text(encoding='utf-8')
        refs = {int(m.group(1)) for m in ISSUE_REF.finditer(text)}
        refs |= {int(m.group(1)) for m in ISSUE_REF2.finditer(text)}
        for r in sorted(refs):
            n += 1
            if r not in valid:
                errors.append(f'{p.name}  引用了「问题 {r}」，但 README 里没有这个条目'
                              f'（现有 1–{max(valid) if valid else 0}）')
    return n


def check_doc_numbers(errors, readme):
    """复算每一项"计数类"声明。返回校验条数。

    搜索范围 = README + docs/KNOWN_ISSUES.md：数字声明可以住在任一份里
    （"声明必须存在且数字正确"这条规则不变，只是不再限定必须写在 README）。
    """
    nums = gather_numbers()
    ki = companion(readme)
    text = readme.read_text(encoding='utf-8')
    if ki is not None:
        text += chr(10) + ki.read_text(encoding='utf-8')
    checked = 0
    for what, pat, expect in NUM_CLAIMS:
        checked += 1
        m = pat.search(text)
        if not m:
            errors.append(f'README  找不到这项数字声明（不要靠删掉数字来让它通过）: {what}')
            continue
        got = tuple(int(g) for g in m.groups())
        want = tuple(expect(nums))
        if got != want:
            errors.append(f'README  {what}: 文档写 {got}，实测 {want}')
    return checked, nums


def load_sources():
    exts = ('*.kt', '*.xml', '*.pro', '*.kts', '*.json')
    out = {}
    for pat in exts:
        for p in ROOT.glob('app/**/' + pat):
            if 'build' in p.parts:
                continue
            out[p] = p.read_text(encoding='utf-8', errors='ignore')
    for p in [ROOT / 'app/build.gradle.kts', ROOT / 'gradle/libs.versions.toml', ROOT / '.gitignore']:
        if p.exists():
            out[p] = p.read_text(encoding='utf-8', errors='ignore')
    # 仓库自带的工具脚本也要进符号池：README 从 R6 起会引用 tools/ 下的函数名
    # （例如脱敏扫描器的判定函数）。只把 .py 加进符号池与 by_name，
    # 不影响「包职责表」那条按目录数 .kt 的规则，也不影响 gather_numbers()。
    for p in ROOT.glob('tools/**/*.py'):
        if '__pycache__' in p.parts:
            continue
        out[p] = p.read_text(encoding='utf-8', errors='ignore')
    return out

def companion(readme):
    """返回与 readme 配套的明细文档（docs/KNOWN_ISSUES.md），没有则 None。

    为什么按**被校验文件**的位置找，而不是写死仓库根：本文件的自检测试会把
    README 复制到临时目录、只改副本再校验。若这里硬编码 ROOT，测试的"突变"
    会被真实仓库里那份明细文档里的原文救活 —— 9 个突变用例一起失效，
    而门看起来还是绿的。配套文档必须跟着被校验对象走。
    """
    sibling = readme.parent / 'docs' / 'KNOWN_ISSUES.md'
    if sibling.exists():
        return sibling
    # 只有在校验**仓库里那份真的 README** 时才允许回退到仓库根，
    # 否则自检测试（临时副本）会意外读到真文档，突变用例集体失效。
    if readme.resolve() == README.resolve():
        root_doc = ROOT / 'docs' / 'KNOWN_ISSUES.md'
        if root_doc.exists():
            return root_doc
    return None


def main(readme_path=None):
    readme = pathlib.Path(readme_path) if readme_path else README
    srcs = load_sources()
    by_name = {}
    for p, t in srcs.items():
        by_name.setdefault(p.name, []).append((p, t))
    all_code = '\n'.join(srcs.values())

    def line_count(fname, path, ln):
        cands = by_name.get(fname)
        if not cands:
            return f'文件不存在: {path}:{ln}'
        if any(len(t.splitlines()) >= int(ln) for _, t in cands):
            return None
        maxlen = max(len(t.splitlines()) for _, t in cands)
        return f'行号越界: {path}:{ln} （该文件仅 {maxlen} 行）'

    def anchor_err(fname, ln, ln2, line):
        """行号不仅要在范围内，还要**仍然指着它声称的那个东西**。

        上一版只判"没越过文件末尾"，于是给 82 个文件各加 7 行许可头之后，
        README 里所有行号整体漂移，工具照样输出"全部成立" —— 这正是本项目
        定义为最严重的那类缺陷（文档比代码乐观），由校验器自己犯了一次。

        规则（尽量少误报）：
          a) 区间引用就整段判；单行引用判 ±4 行窗口；
          b) 窗口内是空行 / 只有括号 / import 行 → 一定是漂移；
          c) 句子里出现的、目标文件里确实存在的标识符，只要有一个落在窗口内就算通过。
        """
        cands = by_name.get(fname)
        if not cands:
            return None
        lo = int(ln)
        hi = int(ln2) if ln2 else lo
        idents = [w for w in re.findall(r'[A-Za-z_][A-Za-z0-9_]{3,}', line)
                  if w not in STOP_WORDS]
        # 目标文件自己的类名几乎不会出现在自己文件里（除类声明那一行），
        # 留着它当锚会把"引用某行注释"这类合法引用一律判成漂移。
        stem = fname.rsplit('.', 1)[0]
        idents = [w for w in idents if w.lower() != stem.lower()]
        for _, text in cands:
            lines = text.splitlines()
            if hi > len(lines):
                continue
            # 锚点窗口：**单行引用收窄到 ±2**。
            # 上一版是 ±4/5 且"句中任一标识符命中即通过"，于是 4~5 行的漂移量
            # 正好落在窗口内、被判成"没问题"（H-14 的两处漂移就是这样漏掉的）。
            # 收窄到 ±2 之后，±3 以上的漂移会稳定地报出来。
            pad_lo, pad_hi = 2, 2
            window_idx = range(max(0, lo - 1 - pad_lo), min(len(lines), hi + pad_hi))
            window = '\n'.join(lines[i] for i in window_idx)
            usable = [w for w in idents if re.search(r'\b' + re.escape(w) + r'\b', text)]
            if any(re.search(r'\b' + re.escape(w) + r'\b', window) for w in usable):
                return None
            # "整段都是空行/括号/import" 用更宽的窗口判（这是个很强的信号，不怕宽）
            stripped = [l.strip() for l in lines[max(0, lo - 5):hi + 4]]
            if all(s in ('', '}', '{', '})', ')', '},') or s.startswith(('import ', 'package '))
                   for s in stripped):
                where = next((f'`{w}` 的引用点在 {_lines_of(lines, w)}'
                              for w in usable if _lines_of(lines, w)), '')
                return (f'行号已漂移: {fname}:{ln}{("-" + ln2) if ln2 else ""} 整段都是空行/括号/导入行'
                        + (f'，而 {where}' if where else ''))
            if not usable:
                return None      # 句子里没有可锚定的标识符，不判（避免误报）
            return (f'行号对不上: {fname}:{ln}{("-" + ln2) if ln2 else ""} 窗口(±2)内找不到该句提到的'
                    f'任何标识符（候选 {usable[:4]}）')
        return None

    def nearest(lines, word):
        pat = re.compile(r'\b' + re.escape(word) + r'\b')
        hits = [i + 1 for i, l in enumerate(lines) if pat.search(l)]
        return hits[0] if hits else 0

    errors, checked = [], 0
    # 明细搬到 docs/KNOWN_ISSUES.md 之后，那些 `文件:行号` 锚点**必须继续被校验**，
    # 否则"搬走"等于"免检"。两份文档各自按自己的文件名报错。
    doc_paths = [readme]
    _ki = companion(readme)
    if _ki is not None:
        doc_paths.append(_ki)
    doc_lines = [(d.name, ln, line)
                 for d in doc_paths
                 for ln, line in enumerate(d.read_text(encoding='utf-8').splitlines(), 1)]
    for docname, lineno, line in doc_lines:
        for path, numspec in FILE_REF.findall(line):
            base = path.split('/')[-1]
            for lo, hi in expand_numspec(numspec):
                checked += 1
                err = line_count(base, path, hi or lo)
                if err:
                    errors.append(f'{docname}:{lineno}  {err}')
                    continue
                err = anchor_err(base, str(lo), str(hi) if hi else None, line)
                if err:
                    errors.append(f'{docname}:{lineno}  {err}')

        for sym in SYMBOL.findall(line):
            checked += 1
            tail = sym.split('.')[-1]
            if tail not in all_code:
                errors.append(f'{docname}:{lineno}  符号已不存在: {sym}()')

        # 反引号里的裸**类名**引用：`PickupSmsParserV2:1000/1019`
        # 注意：带扩展名的（`Foo.kt:12`）不在这里处理 —— FILE_REF 分支已经扫过整行
        # （包括反引号内部），在这里再查一遍只会把同一个问题报两遍，
        # 而且报错文案会变成 `Foo.kt:12, 34:34` 这种读不懂的形状。
        for span in BACKTICK.findall(line):
            m = BARE_REF.match(span)
            if not m:
                continue
            name, nums = m.group(1), re.findall(r'\d{1,4}', m.group(2))
            if not nums or '.' in name:
                continue
            fname = name + '.kt'
            if fname.split('/')[-1] not in by_name:
                continue          # 不是文件名（常量名、散文里的冒号），跳过
            checked += 1
            base = fname.split('/')[-1]
            lo, hi = min(int(x) for x in nums), max(int(x) for x in nums)
            err = line_count(base, base, hi)
            if err:
                errors.append(f'{docname}:{lineno}  {err}')
                continue
            # M-05 的第一个缺口：**裸类名引用此前只判越界、从不校验锚点**，
            # 于是 `Foo:1000/1019` 这类引用漂到哪都不会被发现。
            # 这里把多个行号当一个区间看（它们是"同一处引用的多个位置"），
            # 因此窗口按 lo..hi 展开 —— 但**至少**不再是"只判越界"。
            err = anchor_err(base, str(lo), str(hi) if hi != lo else None, line)
            if err:
                errors.append(f'{docname}:{lineno}  {err}')

    # 「包职责」表的文件数列：README 里唯一能整列机械核对的数字
    for pkg, claimed in PKG_ROW.findall(readme.read_text(encoding='utf-8')):
        checked += 1
        d = PKG_ROOT / pkg.rstrip('/')
        if not d.is_dir():
            errors.append(f'README  包不存在: {pkg}')
            continue
        actual = len(list(d.rglob('*.kt')))
        if str(actual) != claimed:
            errors.append(f'README  文件数不符: {pkg} 表中 {claimed} 实际 {actual}')

    # 内部交叉引用：`问题 N` / `修复状态」 #N` 必须指向 README 里真实存在的条目。
    # 加这一条的原因：R7 实测在 §3 权限表里发现 `第 4 节问题 35`，而清单只到 34 ——
    # 这是"引用指向不存在的对象"，与 H-14 里那两处 +7 漂移同一类，此前无人校验。
    checked += check_issue_refs(errors, readme)

    # 可机械复核的"计数类"声明（H-14：README 的数字此前没被任何工具复算过）
    num_checked, nums = check_doc_numbers(errors, readme)
    checked += num_checked

    print(f'共校验 {checked} 处引用')
    print('  其中计数类声明 {} 项：主源 {} 文件 / {} 行，中文串 {}，'
          'XML 硬编码中文 {}，strings.xml {} 条，单测 {} 个（门控 {} 个），'
          '审计工具自测 {} 个'.format(
              num_checked, nums['main_files'], nums['main_lines'], nums['kt_cjk'],
              nums['res_cjk'], nums['strings_entries'], nums['tests'], nums['gated'],
              nums['audit_tests']))
    if errors:
        print(f'!! {len(errors)} 处失效：')
        for e in errors:
            print('   ' + e)
        return 1
    print('✓ README 中全部代码引用仍然成立')
    return 0

if __name__ == '__main__':
    # 可选参数：要校验的 Markdown 文件（默认 README.md；自检测试传副本）
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else None))
