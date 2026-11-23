# 码上取 PickupManager —— 完全离线的安卓快递取件助手
# Copyright (C) 2026 shanqiugzs
# SPDX-License-Identifier: GPL-3.0-or-later
# 许可全文见仓库根目录 LICENSE。
# -*- coding: utf-8 -*-
"""kotlin_lexer.py 的回归测试。

用法:
    python3 tools/audit/test_kotlin_lexer.py

为什么规格要写死在这些用例里：这个模块的产出会被两个检查器信任，
而它的错误方向是**静默的** —— 多屏蔽一点会让"死 import"漏报，
少屏蔽一点会让注释里的字面量被当成真代码。两种都不会让构建红。
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

from kotlin_lexer import count_chinese_literals, strip  # noqa: E402


class TestStripMaskBoth(unittest.TestCase):
    """keep_strings=False：注释与字符串都屏蔽。"""

    def test_line_comment(self):
        self.assertNotIn("Star", strip("val a = 1 // Icons.Filled.Star\n"))

    def test_block_comment(self):
        self.assertNotIn("Star", strip("/* Icons.Filled.Star */val a = 1\n"))

    def test_nested_block_comment(self):
        out = strip("/* a /* Icons.Filled.Star */ b */val a = 1\n")
        self.assertNotIn("Star", out)
        self.assertIn("val a = 1", out)

    def test_string_masked(self):
        self.assertNotIn("Icons.Filled.Star", strip('val u = "https://x/Icons.Filled.Star"\n'))

    def test_url_in_string_does_not_eat_rest_of_line(self):
        """字符串里的 // 不是行注释。"""
        src = 'val u = "https://a" + Icons.Filled.List\n'
        self.assertIn("Icons.Filled.List", strip(src))

    def test_escaped_quote_does_not_end_string_early(self):
        src = 'val s = "a\\"b" // tail\n'
        self.assertNotIn("tail", strip(src))

    def test_line_count_and_length_preserved(self):
        src = "a\n// x\nb /* c */\n"
        out = strip(src)
        self.assertEqual(len(src), len(out))
        self.assertEqual(src.count("\n"), out.count("\n"))

    def test_real_code_survives(self):
        self.assertIn("Icons.Filled.List", strip("val v = Icons.Filled.List\n"))

    def test_char_literal_slash_does_not_start_a_comment(self):
        """`'/'` 是字符字面量，不是注释开头 —— 否则它会把整行剩余部分吃掉。"""
        src = "val c = '/' ; val y = 1 // tail\n"
        out = strip(src)
        self.assertIn("val y = 1", out)
        self.assertNotIn("tail", out)


class TestStripKeepStrings(unittest.TestCase):
    """keep_strings=True：只屏蔽注释，字符串原样保留。"""

    def test_string_content_kept(self):
        self.assertIn("你好", strip('val s = "你好"\n', keep_strings=True))

    def test_comment_still_masked(self):
        self.assertNotIn("你好", strip('// 你好\nval a = 1\n', keep_strings=True))

    def test_raw_string_kept(self):
        self.assertIn("世界", strip('val s = """世界"""\n', keep_strings=True))


class TestCountChineseLiterals(unittest.TestCase):
    def test_counts_only_literals(self):
        src = 'val a = "中文一"\nval b = "ascii"\nval c = "中文二"\n'
        self.assertEqual(2, count_chinese_literals(src))

    def test_comment_chinese_not_counted(self):
        src = '// "中文"\nval a = "x"\n'
        self.assertEqual(0, count_chinese_literals(src))

    def test_concatenation_counts_each_literal(self):
        src = 'val a = "甲" + "乙"\n'
        self.assertEqual(2, count_chinese_literals(src))

    def test_kotlin_template_in_literal_counted_once(self):
        src = 'val a = "共 ${n} 条"\n'
        self.assertEqual(1, count_chinese_literals(src))

    def test_cjk_punctuation_alone_not_counted(self):
        """只有标点（，。）不算中文文案 —— 判定依据是中日韩汉字/扩展区。"""
        src = 'val a = "，。"\n'
        self.assertEqual(0, count_chinese_literals(src))


if __name__ == "__main__":
    unittest.main(verbosity=2)
