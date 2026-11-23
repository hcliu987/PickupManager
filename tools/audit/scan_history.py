# 码上取 PickupManager —— 完全离线的安卓快递取件助手
# Copyright (C) 2026 shanqiugzs
# SPDX-License-Identifier: GPL-3.0-or-later
# 许可全文见仓库根目录 LICENSE。
# -*- coding: utf-8 -*-
"""git 全对象脱敏扫描器 —— 扫的是**历史**，不是工作树。

用法:
    python3 tools/audit/scan_history.py [仓库根=.] [--denylist 清单路径]

它回答的问题是：请把仓库推到公开托管之后，随便哪个陌生人执行
    git clone <url> && git log -p --all
能拿到什么。工作树干净不代表历史干净 —— 删掉一行只是新增一个提交，旧对象永久可寻址。

## 两条判据，缺一不可（这一条是交叉验证时翻出来的）

1. **形状判据**（`RULES_TEXT`）："有没有**长得像**手机号 / 取件码 / 小区名的东西"。
   这个问题对开源仓库没有可靠答案 —— 夹具、正则模式说明、负向测试用例本来就长得像。
   所以 `PICKUP_CODE_COMPOUND`（`字母?+数字-数字-5位数字` 这种复合码形态）与
   `CN_ADDR_COMPOUND` 只能是 MED / HIGH，**不参与退出码**。后果实测过：本仓库有
   8 个提交携带作者本人的真实取件码与真实小区名，而形状判据对它们输出的是
   `✓ 历史对象里没有 BLOCK 级命中`。**只看这一条 = 假绿。**
2. **精确值判据**（`--denylist`）："有没有**等于我真实值**的东西"。
   判据是全串相等，不是形状，所以不存在"像不像夹具"的争议 —— 清单由本机私有语料
   派生，仓库里的假号假码不可能恰好在其中。清单**不入库**，未提供时本条**显式打
   SKIPPED 并说明"这是发布前本机门，不能拿 CI 绿当它绿"**，绝不静默跳过。

形状判据能发现"清单之外新冒出来的真实值"，精确值判据能分辨"形状规则永远分辨不了
的那一类"（真实取件码与假取件码形状完全一致）。两条都要过才算历史干净。

实现说明（这一条是踩过坑的）：
  **不要**用 `git grep -E` 来扫。POSIX ERE 既不支持 (?<!…) / (?=…) 断言，
  也不认 \\uXXXX 转义，用它扫手机号会得到**看起来正常的假阴性**。
  本脚本改用「枚举所有提交的 tree → 取唯一 blob → Python 全正则扫内容」。
"""
import os
import re
import subprocess
import sys

# Windows 控制台默认 GBK，« ✓ ❌ 与中文断言消息会抛 UnicodeEncodeError，
# 于是「门跑不了」看起来像「门查出了问题」。所有审计脚本统一先切 UTF-8。
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from scan_sensitive import (  # noqa: E402
    RULES_TEXT, placeholder_of, BINARY_EXT, BINARY_META_PATTERNS, SEVERITY_ORDER,
    DRIVE_SELF_PATH_RE,
)


def git(repo, *args, binary=False):
    p = subprocess.run(["git", "-C", repo, *args], capture_output=True)
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


def read_blobs(repo, shas, expect_sizes=None):
    """用**一次** `git cat-file --batch` 读回所有 blob，返回 {sha: bytes}。

    为什么要批量化：逐个 `git cat-file blob <sha>` 在 Windows 上一次约 0.2s，
    246 个唯一 blob 就要 50s 以上 —— 足够把 CI 的一步拖到超时，
    而超时会让人误以为"扫描通过了"。

    三条**不许省**的加固（每一条都对应真实踩过的坑）：
    1. `--batch` 遇到取不到的 object 会输出 `<sha> missing`（只有 2 个字段）。
       老版本在这里 `break`，于是**后面所有 blob 全部静默丢失**、扫描却报"通过"。
       现在改成跳过这一条并记账，最后以非零退出码报出来。
    2. 内容片段长度必须与 `<sha> blob <size>` 里的 size 逐条相等（expect_sizes
       用 `--batch-check` 独立取一遍做交叉核对）。不等即视为解析失同步，
       直接失败 —— 宁可报错，也不要"看起来扫过了"。
    3. 绝不能 break 到"提前收尾"：只有 read 到 out 末尾才算正常结束。
    """
    if not shas:
        return {}, []
    proc = subprocess.Popen(["git", "-C", repo, "cat-file", "--batch"],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    out, _ = proc.communicate(("\n".join(shas) + "\n").encode())
    result = {}
    anomalies = []
    pos = 0
    while pos < len(out):
        nl = out.find(b"\n", pos)
        if nl < 0:
            anomalies.append("输出在 header 中途截断（剩余 %d 字节）" % (len(out) - pos))
            break
        header = out[pos:nl].decode("utf-8", "replace")
        pos = nl + 1
        parts = header.split()
        if len(parts) >= 2 and parts[1] in ("missing", "ambiguous"):
            anomalies.append("object 不可读: %s (%s)" % (parts[0][:8], parts[1]))
            continue
        if len(parts) < 3:
            anomalies.append("无法解析的 header: %r" % header[:120])
            break
        try:
            size = int(parts[2])
        except ValueError:
            anomalies.append("size 不是整数: %r" % header[:120])
            break
        chunk = out[pos:pos + size]
        if len(chunk) != size:
            anomalies.append("blob %s 内容长度 %d != 声明 %d（解析失同步）"
                             % (parts[0][:8], len(chunk), size))
            break
        result[parts[0]] = chunk
        pos += size + 1  # 跳过内容后面的那个换行
    if expect_sizes:
        for sha, sz in expect_sizes.items():
            got = result.get(sha)
            if got is None:
                anomalies.append("blob %s 未读回（声明长度 %d）" % (sha[:8], sz))
            elif len(got) != sz:
                anomalies.append("blob %s 交叉核对长度不符：%d != %d" % (sha[:8], len(got), sz))
    return result, anomalies


def blob_sizes(repo, shas):
    """用 `--batch-check` 独立取一遍 sizes，供 read_blobs 交叉核对。"""
    if not shas:
        return {}
    proc = subprocess.Popen(["git", "-C", repo, "cat-file", "--batch-check"],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    out, _ = proc.communicate(("\n".join(shas) + "\n").encode())
    sizes = {}
    for line in out.decode("utf-8", "replace").splitlines():
        f = line.split()
        if len(f) >= 3 and f[1] == "blob":
            try:
                sizes[f[0]] = int(f[2])
            except ValueError:
                pass
    return sizes


def iter_numbered_lines(text):
    """按**编辑器口径**逐行编号：只认 \\n，并且把行尾的 CR 剥掉。

    为什么不用 str.splitlines()：它会额外在**孤立 \\r** 处断行，而 `wc -l`、
    VS Code、IDE 的"第 N 行"都不这么算。本仓库历史里确实存在过
    `\\r\\r\\n` 的被破坏 blob（v1.1 早期某次 CRLF 二次转换），
    用 splitlines() 会把 905 行的文件算成 1810 行、报出根本不存在的
    "RuleBasedPickupParser.kt:1689" —— 行号错了，人就无法按图索骥去修，
    审查记录会立刻失去可信度。
    """
    for i, line in enumerate(text.split("\n"), 1):
        yield i, line.rstrip("\r")


def load_denylist(path):
    """读本机的"真实值清单"。清单里每一行是一个**精确**真实值，不是正则、不是子串模板。"""
    with open(path, encoding="utf-8") as fh:
        vals = [ln.strip() for ln in fh if ln.strip() and not ln.startswith("#")]
    # 空串会让 `v in text` 恒真，等于把整棵仓库判成泄漏；单字符值同理不可信。
    short = [v for v in vals if len(v) < 4]
    return [v for v in vals if len(v) >= 4], short


def exact_scan(blobs, vals):
    """返回 {sha: [命中值]}。子串匹配，不做归一化 —— 清单里的值就是原样写进注释的。"""
    hit = {}
    for sha, data in blobs.items():
        text = data.decode("utf-8", "ignore")
        got = [v for v in vals if v in text]
        if got:
            hit[sha] = got
    return hit


def main():
    argv = sys.argv[1:]

    def take(name):
        if name in argv:
            i = argv.index(name)
            if i + 1 >= len(argv):
                raise SystemExit(f"❌ {name} 后面要跟一个路径")
            v = argv[i + 1]
            del argv[i:i + 2]
            return v
        return None

    denylist = take("--denylist")
    advisory = take("--advisory")
    repo = os.path.abspath(argv[0] if argv else ".")
    revs = git(repo, "rev-list", "--all", "--reverse").split()
    print(f"提交数={len(revs)}  {[r[:8] for r in revs]}")

    rev_path_blob = {}
    path_seen = defaultdict(set)
    for rev in revs:
        for line in git(repo, "ls-tree", "-r", "-z", rev).split("\0"):
            if not line:
                continue
            meta, _, path = line.partition("\t")
            parts = meta.split()
            if len(parts) < 3 or parts[1] != "blob":
                continue
            rev_path_blob[(rev, path)] = parts[2]
            path_seen[path].add(rev)

    uniq = {}
    for (rev, path), sha in rev_path_blob.items():
        uniq.setdefault(sha, path)
    print(f"唯一 blob 数={len(uniq)}")

    print("\n### 历史唯一 blob 内容扫描（Python 全正则）")
    per_rule = defaultdict(list)
    bin_findings = []
    blobs, anomalies = read_blobs(repo, list(uniq.keys()), blob_sizes(repo, list(uniq.keys())))
    for sha, sample_path in uniq.items():
        data = blobs.get(sha)
        if data is None:
            continue
        ext = os.path.splitext(sample_path)[1].lower()
        if ext in BINARY_EXT or b"\x00" in data[:4096]:
            for pat, desc in BINARY_META_PATTERNS:
                n = data.count(pat)
                if n:
                    bin_findings.append((sample_path, sha[:8], desc, n))
            for _m in re.finditer(rb"[A-Za-z]:[\\/]Users[\\/][^\\/\x00]{1,40}", data):
                bin_findings.append((sample_path, sha[:8], "二进制内嵌用户路径", 1))
            # 与工作树扫描器共用同一条通用判据（DRIVE_SELF_PATH_RE）：
            # 原先这里只有上面那条"必须含 Users"的硬编码形态，于是 DEX/arsc 里内嵌的
            # **构建机绝对路径**（不含 Users，例如 SDK、工程目录）在历史里查不出来。
            for _m in DRIVE_SELF_PATH_RE.finditer(data):
                bin_findings.append((sample_path, sha[:8], "二进制内嵌构建机绝对路径", 1))
            continue
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError:
            text = data.decode("utf-8", "replace")
        for lineno, line in iter_numbered_lines(text):
            for rule, sev, pat, desc in RULES_TEXT:
                for m in re.finditer(pat, line):
                    s = m.group(0)
                    # 与工作树扫描器共用同一个判定函数 —— 不再抄第二份实现（R6，M-25）。
                    # 「一个值算不算占位」只取决于值本身，历史与工作树必须给出同一结论。
                    ph = placeholder_of(s)
                    per_rule[(rule, sev, desc)].append((sample_path, sha, lineno, s, ph))

    if not per_rule:
        print("  (无命中)")
    for (rule, sev, desc), items in sorted(
            per_rule.items(), key=lambda kv: (SEVERITY_ORDER.index(kv[0][1]), kv[0][0])):
        hit_blobs = {i[1] for i in items}
        revs_hit = {rev[:8] for (rev, _p), s2 in rev_path_blob.items() if s2 in hit_blobs}
        paths = {i[0] for i in items}
        ph_n = sum(1 for i in items if i[4])
        print(f"\n  [{sev}] {rule} — {desc}  命中 {len(items)} 处 / "
              f"{len(paths)} 文件 / {len(revs_hit)} 提交（其中 {ph_n} 处按值判为占位）")
        for path, sha, lineno, s, ph in items[:10]:
            snip = s if len(s) <= 70 else s[:70] + "…"
            print(f"      blob:{sha[:8]}  {path}:{lineno}  «{snip}»"
                  f"{f'  ⚠占位={ph}' if ph else ''}")
        if len(items) > 10:
            print(f"      … 另有 {len(items) - 10} 处")

    print("\n### 二进制元数据")
    if not bin_findings:
        print("  (无)")
    for path, sha, desc, n in bin_findings:
        print(f"  {path}  blob:{sha}  → {desc} × {n}")

    print("\n### 历史中出现过、但当前树已不存在的文件（『删了但没删干净』的信号）")
    head_paths = set()
    for line in git(repo, "ls-tree", "-r", "-z", "HEAD").split("\0"):
        if line:
            head_paths.add(line.partition("\t")[2])
    gone = sorted(set(path_seen) - head_paths)
    if not gone:
        print("  (无)")
    for p in gone:
        print(f"  {p}    (曾在 {len(path_seen[p])} 个提交)")

    # ---------------- 精确值判据（第二条判据，见模块 docstring） ----------------
    print("\n### 精确值反查（拿本机私有真实值清单比对全部 git 对象）")
    exact_bad = False
    if not denylist:
        print("  SKIPPED —— 未给 --denylist。这是**发布前本机门**：清单由私有语料派生，"
              "公开 CI 上没有。")
        print("  ⇒ 上面那条 ✓ 只代表「形状判据过了」，**不代表「没有我的真实值」**。"
              "推送前必须在本机带 --denylist 再跑一次。")
    else:
        for label, path, fatal in (("A 级（不可歧义：手机号 / 居住地专名）", denylist, True),
                                   ("B 级（取件码形状：与夹具同形同值，只作复核线索）",
                                    advisory, False)):
            if not path:
                if fatal:
                    continue
                print(f"\n  {label}: 未提供 --advisory，跳过")
                continue
            if not os.path.isfile(path):
                print(f"  ❌ 清单文件不存在：{path}")
                return 2
            vals, too_short = load_denylist(path)
            note = f"（已忽略 {len(too_short)} 行短于 4 字符：{too_short[:5]}）" if too_short else ""
            print(f"\n  {label}: {len(vals)} 个精确值 {note}")
            hit = exact_scan(blobs, vals)
            # 按提交报，不按 blob 报：只知道"历史里有"没法定位到要重写哪个提交。
            leak_revs = 0
            for rev in revs:
                found = sorted({p: hit[s] for (r, p), s in rev_path_blob.items()
                                if r == rev and s in hit}.items())
                if not found:
                    continue
                leak_revs += 1
                # 两档都把**文件与命中值**列出来：A 级是要照着删的，
                # B 级自称"仅供人工逐条确认"——不给逐条内容就无法确认。
                print(f"  {rev[:8]}  {'LEAK' if fatal else 'ADVISORY'}  {len(found)} 文件 / "
                      f"{sum(len(v) for _, v in found)} 个值")
                for path2, vs in found[:8]:
                    print(f"        {path2} → {vs[:5]}{' …' if len(vs) > 5 else ''}")
            if fatal:
                # 工作树单独判：未跟踪文件会被 `git add -A` 带进下一个提交。
                wt = subprocess.run(["git", "-C", repo, "ls-files", "-z", "--cached",
                                     "--others", "--exclude-standard"], capture_output=True)
                wt_bad = []
                for rel in wt.stdout.decode("utf-8").split("\0"):
                    if not rel:
                        continue
                    fp = os.path.join(repo, rel)
                    if not os.path.isfile(fp):
                        continue
                    with open(fp, "rb") as fh:
                        data = fh.read()
                    got = [v for v in vals if v in data.decode("utf-8", "ignore")]
                    if got:
                        wt_bad.append((rel, got))
                for rel, got in wt_bad[:10]:
                    print(f"  !! [工作树] {rel} → {len(got)} 个真实值")
                if leak_revs or wt_bad:
                    exact_bad = True
                    print(f"\n❌ A 级判据：{leak_revs}/{len(revs)} 个提交携带真实值"
                          f"（工作树另有 {len(wt_bad)} 个文件）。这些提交不可发布。")
                else:
                    print(f"\n✓ A 级判据：{len(blobs)} 个 blob + 工作树里没有任何值命中清单。")
            else:
                print(f"  命中提交 {leak_revs}/{len(revs)} —— **不进退出码**，"
                      f"仅供人工逐条确认（真实取件码与假码同形同值，机器分不开）。")

    # 退出码：历史里出现 BLOCK 级未豁免命中即失败
    blocking = [k for k in per_rule if k[1] == "BLOCK"
                and any(i[4] is None for i in per_rule[k])]
    blk_bin = [b for b in bin_findings if "用户路径" in b[2]]
    print()
    if anomalies:
        print("❌ 读取历史对象时出现 %d 处异常，本次扫描结果**不可信**：" % len(anomalies))
        for a in anomalies[:20]:
            print("   -", a)
        return 2
    if blocking or blk_bin or exact_bad:
        print("❌ 历史对象未过门（BLOCK 级形状命中 / 内嵌用户路径 / 精确真实值命中）。")
        print("   修法见 docs/audit/01-desensitization.md：git filter-branch --tree-filter + gc --prune=now")
        return 1
    if not denylist:
        print("⚠ 本次只过了形状判据，精确值判据是 SKIPPED —— 结论**不足以支撑公开发布**。")
    print("✓ 历史对象里没有 BLOCK 级命中（%d 个唯一 blob 全部读回并交叉核对长度）。" % len(blobs))
    return 0


if __name__ == "__main__":
    sys.exit(main())
