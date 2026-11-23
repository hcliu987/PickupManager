# 码上取 PickupManager —— 完全离线的安卓快递取件助手
# Copyright (C) 2026 shanqiugzs
# SPDX-License-Identifier: GPL-3.0-or-later
# 许可全文见仓库根目录 LICENSE。
# -*- coding: utf-8 -*-
"""图标 import 死引用检查器。

用法:
    python3 tools/audit/check_icon_imports.py [仓库根=.]
    python3 tools/audit/test_check_icon_imports.py     # 检查器自身的回归测试

退出码: 0 = 未发现死引用; 1 = 有死引用（CI 可据此失败）; 2 = 参数错误。

为什么需要它
------------
**Kotlin 编译器不对未使用的 import 报警告。** 也就是说"构建日志干净"
永远不能证明"没有死 import"。而本项目以"逐条可核对"为卖点，这类只能靠
机械检查发现的问题就必须有对应的检查器，不能靠人眼。
（R5 实测：全量重编译后构建日志 0 条相关告警，工作树里实际存在 14 处。）

为什么只查 `androidx.compose.material.icons.*`
--------------------------------------------
因为只有这一类可以**只用词法就判定**：图标 import 的用法必然是
`Icons.<Group>.<Name>` 这个固定形式，`Group` 由包路径唯一决定，
所以判据只依赖被检查的值本身。其余 import（`fadeIn` / `List` / `Add`）
会被普通标识符撞名，"名字出现在正文里" 会产生大量假阴性。
宁可少查一类，也不要给出一个会自相矛盾的结论。

两个必须做对的地方（都曾写错过，现已固化成回归测试）
--------------------------------------------------
1. **先剥注释与字符串再判定。** 否则一句注释里写了 `Icons.Filled.Star`
   就足以让真死的 `filled.Star` 被判成"已使用"。
2. **匹配必须带词边界。** 否则 `Icons.Filled.Star` 会被
   `Icons.Filled.StarBorder` 这类同前缀的用法吞掉。
"""
import argparse
import re
import sys

# Windows 控制台默认 GBK，« ✓ ❌ 与中文断言消息会抛 UnicodeEncodeError，
# 于是「门跑不了」看起来像「门查出了问题」。所有审计脚本统一先切 UTF-8。
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from kotlin_lexer import strip as _strip   # noqa: E402  （注释/字符串屏蔽只有一份实现）

ICON_PREFIX = "androidx.compose.material.icons."


# 注释/字符串屏蔽由 kotlin_lexer.strip 提供（单一实现）。

def group_of(fqcn):
    """androidx.compose.material.icons.<group...>.<Name> -> 'Filled' / 'AutoMirrored.Rounded'"""
    if not fqcn.startswith(ICON_PREFIX):
        return ""
    tail = fqcn[len(ICON_PREFIX):].split(".")
    if len(tail) < 2:
        return ""                      # 根对象 import（...icons.Icons）没有 group 段
    if tail[0] == "automirrored":
        if len(tail) < 3:
            return ""
        return "AutoMirrored." + tail[1].capitalize()
    return tail[0].capitalize()


def find_dead_icon_imports(src):
    """返回一份源码文本里"声明了、但代码中从未出现"的图标 import 全限定名。

    工具与回归测试共用这一个实现 —— 判定逻辑只存在一处，
    否则测试会在自己那份复制品上通过，却测不到真实工具。
    """
    code = _strip(src, keep_strings=False)
    declared, body_lines = [], []
    for line in code.splitlines():
        s = line.strip()
        if s.startswith("import "):
            declared.append(s[len("import "):].strip())
        else:
            body_lines.append(line)
    body = "\n".join(body_lines)

    dead = []
    for fqcn in declared:
        if not fqcn.startswith(ICON_PREFIX):
            continue
        grp = group_of(fqcn)
        if not grp:
            continue
        name = fqcn.rsplit(".", 1)[-1]
        expected = "Icons.{}.{}".format(grp, name)
        # 词边界不能省：否则同前缀用法会把它吞掉
        if not re.search(re.escape(expected) + r"\b", body):
            dead.append(fqcn)
    return dead


def scan(root):
    hits = []
    for kt in sorted(root.rglob("*.kt")):
        posix = kt.as_posix()
        if "/build/" in posix or "/.git/" in posix:
            continue
        for fqcn in find_dead_icon_imports(kt.read_text(encoding="utf-8", errors="replace")):
            hits.append((kt.relative_to(root).as_posix(), fqcn))
    return hits


def main():
    ap = argparse.ArgumentParser(description="检查 Compose 图标 import 中的死引用")
    ap.add_argument("root", nargs="?", default=".", help="仓库根目录（默认当前目录）")
    args = ap.parse_args()

    root = Path(args.root).resolve()
    if not root.is_dir():
        print("不是目录: {}".format(root))
        return 2

    hits = scan(root)
    if not hits:
        print("OK: 未发现死图标 import（扫描 {}）".format(root))
        return 0

    print("发现 {} 处死图标 import：\n".format(len(hits)))
    for rel, fqcn in hits:
        grp = group_of(fqcn)
        name = fqcn.rsplit(".", 1)[-1]
        print("  {}".format(rel))
        print("    - {}   （代码里从未出现 Icons.{}.{}）".format(fqcn, grp, name))
    print("\n修法：删掉该 import；若其实是要改用 AutoMirrored 版本，")
    print("      则把用法与 import 一起换成 automirrored.<group>.<Name>。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
