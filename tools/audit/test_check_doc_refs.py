# 码上取 PickupManager —— 完全离线的安卓快递取件助手
# Copyright (C) 2026 shanqiugzs
# SPDX-License-Identifier: GPL-3.0-or-later
# 许可全文见仓库根目录 LICENSE。
# -*- coding: utf-8 -*-
"""check_doc_refs.py 的自检测试。

用法:
    python3 tools/audit/test_check_doc_refs.py

做法：把真实的 README.md 复制一份到临时目录、**只改副本**，再让校验器去查副本。
所以测试永不触碰仓库里的 README —— 这也顺便证明"校验器是可测的"，
而不是一个只能靠人相信的黑盒。

每个用例都是"曾经真的漏过"或"工具曾经真的没有能力发现"的那一类：
  - 行号漂移 ±4（旧窗口 ±4/5 且任一标识符命中即通过，正好放过去）
  - 裸类名引用的漂移（旧实现只判越界、**从不校验锚点**）
  - 逗号分隔列表里的**第二个**行号（旧正则根本没把它捕进来）
  - README 里那些"可机械复核"的计数（旧实现只查包文件数，其余全靠人）
  - 把整句数字声明删掉（必须报错，不许靠"删掉数字"通过）
"""
import contextlib
import io
import re
import shutil
import sys

# Windows 控制台默认 GBK，« ✓ ❌ 与中文断言消息会抛 UnicodeEncodeError，
# 于是「门跑不了」看起来像「门查出了问题」。所有审计脚本统一先切 UTF-8。
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT / 'tools'))

import check_doc_refs  # noqa: E402

REAL_README = ROOT / 'README.md'
REAL_DETAIL = ROOT / 'docs' / 'KNOWN_ISSUES.md'

# (说明, 原文, 换成, 期望在报错里出现的关键词, 作用于哪份文档)
# 为什么要第五列：README 精简后，明细（含大部分行号引用与计数声明）搬到了
# docs/KNOWN_ISSUES.md。突变必须打在**文本真正所在的那一份**上，否则
# `original.count(old) == 0`，用例会静默退化成"什么都没测"。
#
# 计数类用例不要写死数字（1518/123 这类会随代码漂移，写死后 replace 变成
# 空操作、用例静默失效）——它们在 test_mutations_are_caught 里用
# check_doc_refs.NUM_CLAIMS 的正则动态定位，数字怎么变都测的是同一件事。
CASES = [
    ('行号漂移（±3，单行引用窗口已收窄到 ±2）',
     '`ComposeMainActivity.kt:177` 也调了一次', '`ComposeMainActivity.kt:180` 也调了一次', '行号对不上', 'DETAIL'),
    ('裸类名引用带扩展名形式的漂移',
     '因为 `ComposeMainActivity.kt:177` 也调了一次',
     '因为 `ComposeMainActivity.kt:135` 也调了一次', '行号', 'DETAIL'),
    ('逗号分隔列表里的第二个行号',
     '`ui/components/RuleMatchingScreen.kt:624, 1137`',
     '`ui/components/RuleMatchingScreen.kt:624, 9999`', '行号越界', 'DETAIL'),
    ('行号漂移（指到无关行）',
     '`PickupItem.kt:72` 已有该逻辑', '`PickupItem.kt:67` 已有该逻辑', '行号对不上', 'DETAIL'),
    ('行号指到 import 区（整段没有可执行代码）',
     '`ui/components/RuleMatchingScreen.kt:624, 1137`',
     '`ui/components/RuleMatchingScreen.kt:50, 1137`', '整段都是空行', 'DETAIL'),
    ('README 侧的行号引用（两份文档都要被覆盖）',
     '`ShareUtils.kt:18`（SmsForwarder', '`ShareUtils.kt:9999`（SmsForwarder', '行号', 'README'),
    ('计数类：包文件数',
     '| `util/` | 24 |', '| `util/` | 21 |', '文件数不符', 'README'),
]


def run_on(text, which='README'):
    """把两份文档复制到临时目录，只替换其中一份的内容，再跑校验器。

    必须**同时放两份**：校验器会去找与被校验文件配套的 docs/KNOWN_ISSUES.md。
    只放 README 会让配套文档解析成"不存在"（那校验器就少查一半），
    而写死仓库根又会让突变被真文档救活 —— 两种都会让这组用例失去意义。
    """
    with tempfile.TemporaryDirectory() as d:
        (Path(d) / 'docs').mkdir()
        shutil.copy2(REAL_README, Path(d) / 'README.md')
        shutil.copy2(REAL_DETAIL, Path(d) / 'docs' / 'KNOWN_ISSUES.md')
        target = (Path(d) / 'README.md') if which == 'README' else (Path(d) / 'docs' / 'KNOWN_ISSUES.md')
        target.write_text(text, encoding='utf-8')
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = check_doc_refs.main(str(Path(d) / 'README.md'))
    return code, buf.getvalue()


class TestCheckDocRefs(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original = REAL_README.read_text(encoding='utf-8')
        cls.detail = REAL_DETAIL.read_text(encoding='utf-8')
        # 保底：测试期间若中途抛异常，也不能留下被改过的 README
        shutil.copy2(REAL_README, str(REAL_README) + '.testbak')

    @classmethod
    def tearDownClass(cls):
        bak = Path(str(REAL_README) + '.testbak')
        if bak.exists():
            bak.unlink()

    def test_baseline_passes(self):
        code, out = run_on(self.original)
        self.assertEqual(0, code, out)

    def test_mutations_are_caught(self):
        for name, old, new, keyword, which in CASES:
            with self.subTest(name):
                src = self.original if which == 'README' else self.detail
                self.assertEqual(1, src.count(old),
                                 f'{name}: {which} 里应恰好出现一次，实际 {src.count(old)}')
                code, out = run_on(src.replace(old, new), which)
                self.assertNotEqual(0, code, f'{name}: 改坏之后校验器仍然通过\n{out}')
                self.assertIn(keyword, out, f'{name}: 报错里没有出现 {keyword}\n{out}')

    def test_numeric_claims_are_caught_dynamically(self):
        """每个"计数类"声明：把真实数字改坏，校验器必须报出来。

        动态定位而不是写死原文（1518/123 这类数字会随代码漂移）：
        用校验器自己的 NUM_CLAIMS 正则切出声明的真实跨度，再整体替换 ——
        这样数字怎么变，测的都是同一件事。这同时覆盖了"删掉数字也不许过"
        的反面：声明存在但数字错，同样必须红。
        """
        for what, pat, _ in check_doc_refs.NUM_CLAIMS:
            with self.subTest(name=what):
                for which, src in (('README', self.original), ('DETAIL', self.detail)):
                    m = pat.search(src)
                    if not m:
                        continue
                    # 把所有捕获组数字统统 +111，保证与实测值不同
                    changed = src[:m.start()] + re.sub(
                        r'\d+', lambda mm: str(int(mm.group()) + 111), m.group()
                    ) + src[m.end():]
                    self.assertNotEqual(src, changed, f'{what}: {which} 里改数字是空操作')
                    code, out = run_on(changed, which)
                    self.assertNotEqual(0, code, f'{what}: 数字改坏后校验器仍然通过\n{out}')
                    self.assertIn(what, out, f'{what}: 报错里没有点出这项声明\n{out}')

    def test_deleting_a_claim_does_not_pass(self):
        """把数字声明整句删掉也必须失败 —— 否则"删掉数字"就成了过关心法。

        这里**不写死那句原文**：声明里的数字会随代码漂移（R6 就被自家门禁从
        21527 改成 21533），写死会让 replace 变成空操作、用例静默退化成
        `original == changed`（2026-09-20 实测就是这么红的）。改为用校验器
        自己的正则切出该声明的真实跨度，再整段删掉 —— 数字怎么变都测的是同一件事。
        """
        what, pat, _expect = check_doc_refs.NUM_CLAIMS[0]
        m = pat.search(self.original)
        self.assertIsNotNone(m, f'README 里已找不到这项数字声明，用例已失效: {what}')
        changed = self.original[:m.start()] + '主源若干文件' + self.original[m.end():]
        self.assertNotEqual(self.original, changed)
        code, out = run_on(changed)
        self.assertNotEqual(0, code)
        self.assertIn('找不到这项数字声明', out)

    def test_readme_untouched(self):
        self.assertEqual(self.original, REAL_README.read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
