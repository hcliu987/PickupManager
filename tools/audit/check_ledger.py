# 码上取 PickupManager —— 完全离线的安卓快递取件助手
# Copyright (C) 2026 shanqiugzs
# SPDX-License-Identifier: GPL-3.0-or-later
# 许可全文见仓库根目录 LICENSE。
# -*- coding: utf-8 -*-
"""缺陷总表（docs/audit/00-defect-ledger.md）的结构自检。

用法:
    python3 tools/audit/check_ledger.py [仓库根=.]

为什么需要它：
`00-defect-ledger.md` 是整个审计的**索引**，`99-final-report.md` 的统计数字直接来自它。
而它是一张人写人改的表 —— R6 实测发现 13 行的「状态」与「提交」两列**写反了**，
于是"按状态机械统计条数"这件事在此之前根本做不了：同一张表会数出两种结果。
一张不能被机械读出的索引表，和一个会骗人的检查器是同一种东西。

所以这里只做四件不需要判断力的事：
  1. 每个 ID 唯一（`M-05` 出现两次 = 一定有人改错行）；
  2. **状态列只能是图例里的四个值**（写反列、写"已修复"、写自由文本都会在这里现形）；
  3. 标为「已修」「不修」的行必须写明是哪一轮、以及引用哪份文档；
  4. 被引用的文档**必须真的存在**（这条专治"文档里引用了不存在的东西"——
     本目录的 `99-unverifiable.md` 曾被索引引用过 5 轮而文件并不存在）。

它**不**判断"这条缺陷是不是真的修好了" —— 那是人的事，而且每轮的轮次文档里有证据。
"""
import os
import re
import sys

# Windows 控制台默认 GBK，« ✓ ❌ 与中文断言消息会抛 UnicodeEncodeError，
# 于是「门跑不了」看起来像「门查出了问题」。所有审计脚本统一先切 UTF-8。
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')
from collections import defaultdict

# 图例（与 00-defect-ledger.md 里写的必须一致）
LEGEND = ["已修", "待修", "不修（附理由）", "无法验证"]
SEVERITIES = ["阻断", "高", "中", "低"]
ROW = re.compile(r"^\|\s*\*\*(B|H|M|L)-(\d+)\*\*\s*\|")
SECTION = re.compile(r"^##\s*[一二三四]、(.+?)\s*$")
ROUND_REF = re.compile(r"^(R\d+)\s*/\s*`(docs/[^`]+?)`")
DOC_REF = re.compile(r"`(docs/audit/[^`]+\.md)`")


def main():
    repo = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")
    ledger = os.path.join(repo, "docs", "audit", "00-defect-ledger.md")
    if not os.path.exists(ledger):
        print("找不到总表: " + ledger)
        return 2

    errors = []
    seen = {}
    rows = []
    section = None
    with open(ledger, encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            m = SECTION.match(line.strip())
            if m:
                section = m.group(1)
                continue
            if not ROW.match(line.strip()):
                continue
            # 按**未转义**的竖线切分：GFM 里 `\|` 是转义的普通竖线，不是分隔符。
            # （L-04 那行里写着 `\|\| true`，按裸 split('|') 会切出 7 格 —— R6 实测。）
            cells = [c.strip() for c in re.split(r"(?<!\\)\|", line.strip().strip("|"))]
            ident = cells[0].replace("*", "").strip()
            if len(cells) != 5:
                errors.append(f"  ledger:{lineno}  {ident} 列数为 {len(cells)}，应为 5"
                              f"（多半是某一列里写了裸竖线）")
                continue
            _i, _loc, _defect, status, commit = cells
            if ident in seen:
                errors.append(f"  ledger:{lineno}  重复 ID：{ident}（首次出现在 {seen[ident]} 行）")
            seen[ident] = lineno
            rows.append((ident, _severity_of(ident), status, commit, section))

            if status not in LEGEND:
                errors.append(f"  ledger:{lineno}  {ident} 状态列不是图例里的值：«{status}»"
                              f"  ← 常见原因：把「状态」和「提交」两列写反了")
            if status in ("已修", "不修（附理由）"):
                m2 = ROUND_REF.match(commit)
                if not m2:
                    errors.append(f"  ledger:{lineno}  {ident} 状态为「{status}」，但提交列"
                                  f"没写成 `RN / docs/...` 的形状：«{commit}»")
                else:
                    _round, doc = m2.group(1), m2.group(2)
                    if not os.path.exists(os.path.join(repo, doc)):
                        errors.append(f"  ledger:{lineno}  {ident} 引用了不存在的文档：{doc}")
            else:
                if commit != "—":
                    errors.append(f"  ledger:{lineno}  {ident} 状态为「{status}」，提交列应为 `—`，"
                                  f"实际是 «{commit}»")

    # 每份被引用的文档都要存在（含缺陷行之外的引用，例如索引表）
    # 带通配符的写法（`docs/audit/0N-*.md`）是在描述命名约定，不是引用，跳过。
    with open(ledger, encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            for doc in DOC_REF.findall(line):
                if "*" in doc:
                    continue
                if not os.path.exists(os.path.join(repo, doc)):
                    errors.append(f"  ledger:{lineno}  引用了不存在的文件：{doc}")

    counts = defaultdict(lambda: defaultdict(int))
    for _ident, sev, status, _commit, _sec in rows:
        counts[sev][status] += 1

    print("=" * 78)
    print("缺陷总表结构自检  {}".format(os.path.relpath(ledger, repo)))
    print("=" * 78)
    print(f"  条目数 = {len(rows)}")
    header = f"  {'严重度':<8}" + "".join(f"{s:<14}" for s in LEGEND) + "小计"
    print(header)
    for sev in SEVERITIES:
        row = counts[sev]
        total = sum(row.values())
        if not total:
            continue
        print(f"  {sev:<8}" + "".join(f"{row.get(s, 0):<14}" for s in LEGEND) + str(total))
    col = {s: sum(counts[x][s] for x in SEVERITIES) for s in LEGEND}
    print(f"  {'合计':<8}" + "".join(f"{col[s]:<14}" for s in LEGEND) + str(len(rows)))

    print()
    if errors:
        print(f"❌ {len(errors)} 处结构问题：")
        for e in errors:
            print(e)
        return 1
    print("✓ 总表结构自检通过（ID 唯一、状态列合法、每处引用都指向真实存在的文档）。")
    return 0


def _severity_of(ident):
    return {"B": "阻断", "H": "高", "M": "中", "L": "低"}[ident[0]]


if __name__ == "__main__":
    sys.exit(main())
