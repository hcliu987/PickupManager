# 码上取 PickupManager —— 完全离线的安卓快递取件助手
# Copyright (C) 2026 shanqiugzs
# SPDX-License-Identifier: GPL-3.0-or-later
# 许可全文见仓库根目录 LICENSE。
# -*- coding: utf-8 -*-
"""Kotlin 源码的词法级屏蔽工具（注释 / 字符串）。

存在理由：本仓库有两个检查器都需要"只看可执行代码"或"只看字符串字面量"，
而这两件事都不该各写一份 —— R5 已经吃过一次教训：
当时的图标检查器与它的回归测试各实现了一份判定，
结果测试在"自己那份复制品"上通过，却测不到真实工具。

`strip(src, keep_strings=...)`：
  keep_strings=False -> 注释与字符串内容都替换为空格（用于"这段 import 有没有被用到"）
  keep_strings=True  -> 只屏蔽注释，字符串字面量原样保留（用于"数一数字面量里有多少中文"）

两种模式都是**等长替换**（换行符原样保留），所以行号列号不变，报错位置可以直接跳转。

Kotlin 的语法细节按真实规则处理：
  - 行注释 `//`、块注释 `/* */`，且块注释**可以嵌套**；
  - 原始字符串 `\"\"\"...\"\"\"`（内部不处理转义）；
  - 普通字符串与字符字面量，含 `\\` 转义（`"a\\"b"` 不会提前结束）。
"""
import re

_CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
# 一个双引号字符串字面量（含转义），不含跨行的原始字符串
_PLAIN_STR = re.compile(r'"((?:[^"\\\n]|\\.)*)"')


def strip(src, keep_strings=False):
    """按 Kotlin 词法把注释（可选字符串）内容替换成空格。等长，换行符保留。"""
    out = []
    i, n = 0, len(src)
    state = "code"          # code | line | block | str | raw | chr
    depth = 0
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if state == "code":
            if c == "/" and nxt == "/":
                state = "line"
                out.append("  ")
                i += 2
                continue
            if c == "/" and nxt == "*":
                state, depth = "block", 1
                out.append("  ")
                i += 2
                continue
            if c == '"' and src[i:i + 3] == '"""':
                state = "raw"
                out.append("   " if not keep_strings else '"""')
                i += 3
                continue
            if c == '"':
                state = "str"
                out.append(c)
                i += 1
                continue
            if c == "'":
                state = "chr"
                out.append(c)
                i += 1
                continue
            out.append(c)
            i += 1
            continue

        if state == "line":
            if c == "\n":
                state = "code"
                out.append(c)
            else:
                out.append(" ")
            i += 1
            continue

        if state == "block":
            if c == "/" and nxt == "*":
                depth += 1
                out.append("  ")
                i += 2
                continue
            if c == "*" and nxt == "/":
                depth -= 1
                out.append("  ")
                i += 2
                if depth <= 0:
                    state = "code"
                continue
            out.append("\n" if c == "\n" else " ")
            i += 1
            continue

        if state == "raw":
            if src[i:i + 3] == '"""':
                state = "code"
                out.append('"""' if keep_strings else "   ")
                i += 3
                continue
            out.append(c if keep_strings else ("\n" if c == "\n" else " "))
            i += 1
            continue

        # str / chr
        if c == "\\":
            out.append(src[i:i + 2] if keep_strings else "  ")
            i += 2
            continue
        if state == "str" and c == '"':
            state = "code"
            out.append(c)
            i += 1
            continue
        if state == "chr" and c == "'":
            state = "code"
            out.append(c)
            i += 1
            continue
        out.append(c if keep_strings else ("\n" if c == "\n" else " "))
        i += 1
    return "".join(out)


def count_chinese_literals(src):
    """数一份源码里"含中日韩字符的双引号字面量"个数。

    口径（README 引用这个数字时必须写同样的口径）：**先剔除注释，再统计**。
    不含跨行原始字符串（`\"\"\"`）—— 本仓库的原始字符串里没有中文界面文案，
    把它算进来只会让数字口径变复杂而不增加信息量。
    """
    return sum(1 for m in _PLAIN_STR.finditer(strip(src, keep_strings=True)) if _CJK.search(m.group(1)))
