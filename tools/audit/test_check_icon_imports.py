# 码上取 PickupManager —— 完全离线的安卓快递取件助手
# Copyright (C) 2026 shanqiugzs
# SPDX-License-Identifier: GPL-3.0-or-later
# 许可全文见仓库根目录 LICENSE。
# -*- coding: utf-8 -*-
"""check_icon_imports.py 的回归测试。

用法:
    python3 tools/audit/test_check_icon_imports.py

为什么一个"检查器"也要有自己的测试：
它给出的结论会被写进 CI 门禁、也会被引用到文档里。而它自己的正确性并不显然 ——
R5 第一次写出来时就有两个可复现的漏报（一句注释里写了 `Icons.Filled.Star`，
于是真死的 `filled.Star` 被判成"已使用"；以及 `Icons.Filled.Star` 被子串匹配到
`Icons.Filled.StarBorder` 上）。两条都固化成了下面的用例。

注意：判定函数是**直接复用工具里的那一个**，不是复制一份 ——
否则测试会在"自己的复制品"上通过，却测不到真实工具（R5 已吃过一次这个亏）。
"""
import sys

# Windows 控制台默认 GBK，« ✓ ❌ 与中文断言消息会抛 UnicodeEncodeError，
# 于是「门跑不了」看起来像「门查出了问题」。所有审计脚本统一先切 UTF-8。
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_icon_imports import find_dead_icon_imports, group_of  # noqa: E402

PREFIX = "androidx.compose.material.icons."


def dead_imports(src):
    return find_dead_icon_imports(src)


class TestGroupOf(unittest.TestCase):
    def test_plain_group(self):
        self.assertEqual("Filled", group_of(PREFIX + "filled.List"))
        self.assertEqual("Rounded", group_of(PREFIX + "rounded.HelpOutline"))
        self.assertEqual("Outlined", group_of(PREFIX + "outlined.Info"))

    def test_automirrored_group(self):
        self.assertEqual("AutoMirrored.Filled", group_of(PREFIX + "automirrored.filled.List"))
        self.assertEqual("AutoMirrored.Rounded", group_of(PREFIX + "automirrored.rounded.Message"))

    def test_root_import_is_ignored(self):
        # `androidx.compose.material.icons.Icons` 是根对象 import，没有 group 段
        self.assertEqual("", group_of(PREFIX + "Icons"))


class TestDeadDetection(unittest.TestCase):
    def test_genuinely_dead_is_reported(self):
        src = (
            "import androidx.compose.material.icons.Icons\n"
            "import androidx.compose.material.icons.filled.StarBorder\n"
            "import androidx.compose.material.icons.filled.Star\n"
            "val a = Icons.Filled.StarBorder\n"
        )
        self.assertEqual([PREFIX + "filled.Star"], dead_imports(src))

    def test_comment_only_usage_does_not_hide_dead_import(self):
        """R5 复现过的漏报：注释里写到同一个表达式，不能算"用过"。"""
        src = (
            "// 说明里提到 Icons.Filled.Star\n"
            "import androidx.compose.material.icons.Icons\n"
            "import androidx.compose.material.icons.filled.Star\n"
            "val a = 1\n"
        )
        self.assertEqual([PREFIX + "filled.Star"], dead_imports(src))

    def test_prefix_collision_does_not_hide_dead_import(self):
        """`Icons.Filled.Star` 不能被子串匹配到 `Icons.Filled.StarBorder` 上。"""
        src = (
            "import androidx.compose.material.icons.Icons\n"
            "import androidx.compose.material.icons.filled.Star\n"
            "import androidx.compose.material.icons.filled.StarBorder\n"
            "val a = Icons.Filled.StarBorder\n"
        )
        self.assertEqual([PREFIX + "filled.Star"], dead_imports(src))

    def test_used_import_not_reported(self):
        src = (
            "import androidx.compose.material.icons.Icons\n"
            "import androidx.compose.material.icons.filled.List\n"
            "val a = Icons.Filled.List\n"
        )
        self.assertEqual([], dead_imports(src))

    def test_automirrored_usage_counts(self):
        src = (
            "import androidx.compose.material.icons.Icons\n"
            "import androidx.compose.material.icons.automirrored.filled.List\n"
            "val a = Icons.AutoMirrored.Filled.List\n"
        )
        self.assertEqual([], dead_imports(src))

    def test_commented_out_import_is_not_reported(self):
        """注释掉的 import 不是 import，不该被当成死引用报出来。"""
        src = (
            "// import androidx.compose.material.icons.filled.Star\n"
            "import androidx.compose.material.icons.Icons\n"
            "val a = 1\n"
        )
        self.assertEqual([], dead_imports(src))


if __name__ == "__main__":
    unittest.main(verbosity=2)
