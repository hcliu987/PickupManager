# -*- coding: utf-8 -*-
# 码上取 PickupManager —— 完全离线的安卓快递取件助手
# Copyright (C) 2026 shanqiugzs
# SPDX-License-Identifier: GPL-3.0-or-later
# 许可全文见仓库根目录 LICENSE。
"""`git filter-branch --tree-filter` 用的历史脱敏脚本（R1 / B-01）。

用法（在仓库根、filter-branch 提供的临时工作树里执行）：

    git filter-branch --force --tree-filter \
        'python tools/audit/redact_history.py' --tag-name-filter cat -- --all

映射不写在代码里，而是从一个**本机文件**读：

    tools/audit/redact_map.local.txt        # 被 .gitignore 的 *.txt 挡住，永不入库
    tools/audit/redact_map.example.txt      # 仓库里只有这一份，内容全是哨兵值

原因：本脚本的目的是把真实值从历史里挖掉；若它把真实值硬编码在自己身上，它自己
就成了那份真实值的新载体 —— 而"修复动作本身变成新的泄露"是本项目犯过两次的错。

"""
import pathlib
import sys

# Windows 控制台默认 GBK，« ✓ ❌ 与中文断言消息会抛 UnicodeEncodeError，
# 于是「门跑不了」看起来像「门查出了问题」。所有审计脚本统一先切 UTF-8。
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

TAB = chr(9)

def load_replacements():
    """从本机映射文件读"真实值 -> 假值"；仓库里不放任何真实值。

    每行 `真实值<TAB>假值`，默认 tools/audit/redact_map.local.txt，
    也可用 REDACT_MAP 环境变量指定别的路径。
    """
    import os
    path = pathlib.Path(os.environ.get("REDACT_MAP", "tools/audit/redact_map.local.txt"))
    if not path.is_file():
        msg = ("缺少映射文件 %s（含真实数据、被 .gitignore 挡住不入库）；"
               "请按 redact_map.example.txt 的格式在本机准备，或用 REDACT_MAP 指定。")
        raise SystemExit(msg % path)
    pairs = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or TAB not in line:
            continue
        a, b = line.split(TAB, 1)
        pairs.append((a.strip(), b.strip()))
    if not pairs:
        raise SystemExit("映射文件为空 —— 拒绝在没有任何替换规则的情况下重写历史。")
    # 先长后短：否则长串会被短串先吃掉一半，产出预期之外的怪值
    return sorted(pairs, key=lambda x: -len(x[0]))

TEXT_SUFFIXES = {".kt", ".kts", ".java", ".md", ".xml", ".py", ".txt",
                 ".json", ".pro", ".toml", ".properties", ".yml",
                 ".gradle", ".bat", ".sh", ".gitignore", ".gitattributes", ""}


def main() -> int:
    changed = 0
    for p in pathlib.Path(".").rglob("*"):
        if not p.is_file() or ".git" in p.parts:
            continue
        if p.suffix.lower() not in TEXT_SUFFIXES and p.name not in (
                ".gitignore", ".gitattributes"):
            continue
        try:
            raw = p.read_bytes()
        except OSError:
            continue
        if not raw or b"\x00" in raw[:4096]:
            continue
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            continue
        original = text
        for a, b in load_replacements():
            text = text.replace(a, b)
        if text != original:
            # 关键：newline="" —— 不做任何换行翻译，原样写回
            with open(p, "w", encoding="utf-8", newline="") as fh:
                fh.write(text)
            changed += 1
            print(f"[redact] {p.as_posix()} 已脱敏", file=sys.stderr)
    if changed == 0:
        print("[redact] 本棵树无可替换内容", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())