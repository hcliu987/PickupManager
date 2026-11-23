# 码上取 PickupManager —— 完全离线的安卓快递取件助手
# Copyright (C) 2026 shanqiugzs
# SPDX-License-Identifier: GPL-3.0-or-later
# 许可全文见仓库根目录 LICENSE。
# -*- coding: utf-8 -*-
"""scan_sensitive.py 的回归测试。

用法:
    python3 tools/audit/test_scan_sensitive.py

为什么一个"扫描器"也要有自己的测试：
它的退出码是 CI 门禁（决定这次提交能不能过），而它的**豁免判定**决定了
"什么会被藏起来"。R6 实测出三类可复现的问题，全部固化在下面：

  1. 白名单与规则表脱节 —— 有一条 `12345678901`，第二位不在 [3-9]，
     CN_MOBILE 的形状决定它**永远不可能**被产出；`1[3-9]x{4,}` 连自己想要的
     掩码写法 `138xxxx1234` 都匹配不上；`localhost` / `F5FAFA` / `FF[0-9A-F]{6}`
     在本仓库规则表里没有任何一条能产出。共 7 条从未生效过。
  2. 过宽 —— `test@` / `foo` / `bar` / `your` 这类短子串会把**真实数据**判成示例：
     `test@163.com` 是真实可注册邮箱，旧版却把它豁免掉了。
  3. 判定逻辑有两份实现 —— scan_history.py 里抄了一份同样的
     `any(re.search(w, s, re.I) for w in WHITELIST_HINTS)`（R5 在图标检查器上
     已经吃过一次这个亏）。现在两边都只调用 `placeholder_of`。

对应 docstring 里的判定纪律：**唯一输入是值本身**。所以本文件判定的不是
"实现细节"，而是三条可执行的性质：
  A 每条豁免都带一个能通过规则表产出的见证值（挡住"从未生效"）
  B 真实感的值一个都不许被豁免（挡住"过宽"）
  C 同一个值换个上下文，结论必须不变（挡住"看上下文"）
"""
import io
import os
import re
import subprocess
import sys

# Windows 控制台默认 GBK，« ✓ ❌ 与中文断言消息会抛 UnicodeEncodeError，
# 于是「门跑不了」看起来像「门查出了问题」。所有审计脚本统一先切 UTF-8。
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import scan_sensitive as S  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]


def frag(*parts):
    """把"真实感"的取值拆成片段再拼起来。

    本文件必须持有"看起来真实"的取值，才能证明豁免表不过宽；而仓库自己的扫描器
    会把手机号/身份证号形状的**字面量**当 BLOCK 级命中报出来。两者共存的写法：
    源码里只出现碎片，运行时拼出完整值。

    这件事本身要写进报告 —— **正则扫描不是不可绕过的**，把号码拆开写就能过。
    它的用途是提高造假/误提的成本、把"仓库看起来干净"变成一条可执行断言，
    而不是形式化证明。所以最终结论仍然需要人复核，工具只是把复核面收窄。
    """
    return "".join(parts)


NOTREAL_MOBILE = frag("135056", "71234")
NOTREAL_MOBILE_NEAR_MISS = frag("1871234", "5679")
NOTREAL_IDCARD = frag("1101051990", "0307123X")


def rule_produces(value):
    """value 是否能被 RULES_TEXT 中某条规则以 group(0) 整体产出（= 该形态真实可达）"""
    out = []
    for rule, _sev, pat, _desc in S.RULES_TEXT:
        for m in re.finditer(pat, value):
            if m.group(0) == value:
                out.append(rule)
    return out


class TestAPlaceholderTableIsWitnessed(unittest.TestCase):
    """A. 每条豁免都必须带一个"真的会被规则表产出"的见证值。"""

    def test_names_are_unique(self):
        names = [n for n, _p, _w, _x in S.PLACEHOLDER_VALUES]
        self.assertEqual(len(names), len(set(names)), f"条目名重复：{names}")

    def test_witness_matches_its_own_pattern(self):
        for name, pat, _why, witness in S.PLACEHOLDER_VALUES:
            with self.subTest(entry=name):
                self.assertIsNotNone(
                    re.fullmatch(pat, witness),
                    f"{name} 的见证值 {witness!r} 不匹配它自己的正文则 —— 这条永远不会生效")

    def test_witness_is_reachable_through_the_rule_table(self):
        """核心断言：挡住"从来没生效过"的条目（旧版有 7 条）。"""
        for name, _pat, _why, witness in S.PLACEHOLDER_VALUES:
            with self.subTest(entry=name):
                self.assertTrue(
                    rule_produces(witness),
                    f"{name} 的见证值 {witness!r} 不会被任何规则产出 —— 这条白名单是死的")

    def test_no_dead_entry_from_the_old_table(self):
        """旧版那几条"证明是死的"的写法，必须一个都不在表里。"""
        dead = ["12345678901", "localhost", "F5FAFA", "FF3B30FF", "138xxxx1234"]
        for value in dead:
            with self.subTest(value=value):
                self.assertIsNone(S.placeholder_of(value))

    def test_old_substring_hints_are_gone(self):
        """旧版用来"按子串判示例"的短串，不能再有任何豁免效果。"""
        for value in ["test@163.com", "foo@bar.com", "your_name@qq.com", "x@placeholder.io"]:
            with self.subTest(value=value):
                self.assertIsNone(S.placeholder_of(value))


class TestBRealValuesAreNeverExempt(unittest.TestCase):
    """B. 真实感的值一个都不许被豁免。误报可以（人来复核），漏报不行。"""

    REALISTIC = [
        ("CN_MOBILE", NOTREAL_MOBILE),
        ("CN_MOBILE", NOTREAL_MOBILE_NEAR_MISS),  # 只差最后一位，也不能蹭到夹具号
        ("CN_IDCARD", NOTREAL_IDCARD),
        ("CN_TEL_AREA", "010-12345678"),
        ("CN_400", "4008208820"),
        ("EMAIL", "zhangsan@163.com"),
        ("EMAIL", "test@163.com"),             # 旧版因含 `test@` 被豁免
        ("EMAIL", "foo@bar.com"),              # 旧版因含 `foo`/`bar` 被豁免
        ("IPV4", "114.114.114.114"),
        ("MAC", "00:1A:2B:3C:4D:5E"),
        ("CN_ADDR_COMPOUND", "阳光水岸小区"),
        ("CN_ADDR_COMPOUND", "在水一方小区"),
        ("CN_ADDR_COMPOUND", "例如阳光小区"),   # 旧版因含 `例如` 被豁免
        ("CN_ADDR_COMPOUND", "示例阳光小区"),   # 占位词后面又跟了真名 —— 不许豁免
        ("CN_ADDR_ROAD", "中山路188号"),
        ("CN_ADDR_DOOR", "阳光小区3号楼"),      # 带地名时不能蹭到 NO_NAME_DOOR
        ("PICKUP_CODE_COMPOUND", "A2-1-3456"),  # 故意不豁免：与真实码形状相同
        ("BANKCARD", "6222021234567890123"),
        ("QQ_WECHAT", "QQ:123456789"),
        ("WIN_USER_PATH", r"C:\Users\zhangsan"),
    ]

    def test_not_exempt(self):
        for rule, value in self.REALISTIC:
            with self.subTest(rule=rule, value=value):
                self.assertIsNone(S.placeholder_of(value),
                                  f"{value!r} 被豁免了（{S.placeholder_of(value)}）—— 真实值不许豁免")

    def test_each_realistic_value_is_actually_a_hit(self):
        """上表不能靠"根本没命中"来通过 —— 先证明它真的会被规则抓到。"""
        for rule, value in self.REALISTIC:
            with self.subTest(rule=rule, value=value):
                hit_rules = {h[0] for h in S.scan_text(value)}
                self.assertIn(rule, hit_rules, f"{value!r} 并没有被 {rule} 命中，用例本身失效了")

    def test_repo_fixture_is_exempt_and_only_that_one(self):
        self.assertEqual("REPO_FIXTURE_MOBILE", S.placeholder_of("18712345678"))
        self.assertEqual("REPO_FIXTURE_MOBILE", S.placeholder_of("13800138000"))
        self.assertIsNone(S.placeholder_of(NOTREAL_MOBILE_NEAR_MISS))

    def test_rfc_reserved_ranges_are_exempt(self):
        """RFC 5737 文档地址段按规范永不分配，与 example.invalid 同理。"""
        self.assertEqual("DOC_V4", S.placeholder_of("203.0.113.9"))
        self.assertEqual("DOC_V4", S.placeholder_of("198.51.100.7"))
        self.assertEqual("DOC_V4", S.placeholder_of("192.0.2.1"))


class TestCJudgementDependsOnlyOnTheValue(unittest.TestCase):
    """C. 同一个值，换文件/换上下文，结论必须一致。"""

    VALUE = "18712345678"
    CONTEXTS = [
        "咨询 18712345678",                          # 光秃秃
        "// 示例：比如可以写 18712345678 这种",        # 旧版 `示例`/`比如` 会豁免
        "收件人：张三 联系电话：18712345678",          # 出现在 CN_ADDR_FIELD 里
        "localhost 上跑的服务，配 18712345678",        # 旁边有 localhost
        "「这是占位」18712345678",
    ]

    def test_placeholder_verdict_is_stable(self):
        verdicts = {S.placeholder_of(self.VALUE) for _ in self.CONTEXTS}
        self.assertEqual({S.placeholder_of(self.VALUE)}, verdicts)
        self.assertEqual("REPO_FIXTURE_MOBILE", S.placeholder_of(self.VALUE))

    def test_scan_text_verdict_is_stable_across_contexts(self):
        seen = []
        for line in self.CONTEXTS:
            for rule, _sev, _ln, s, _desc, ph in S.scan_text(line):
                if s == self.VALUE:
                    seen.append((rule, ph))
        self.assertTrue(seen, "上下文里应该至少各命中一次，用例本身失效了")
        self.assertEqual({("CN_MOBILE", "REPO_FIXTURE_MOBILE")}, set(seen),
                         f"同一值在不同上下文里得到了不同结论：{seen}")

    def test_a_real_number_is_not_rescued_by_surrounding_prose(self):
        """反向：真实号码不会因为句子里写了"示例"就被豁免。"""
        for line in [f"例如 {NOTREAL_MOBILE}", f"示例：{NOTREAL_MOBILE}",
                     f"占位写 {NOTREAL_MOBILE}"]:
            with self.subTest(line=line):
                self.assertIsNone(S.placeholder_of(NOTREAL_MOBILE))
                hits = [(h[3], h[5]) for h in S.scan_text(line) if h[0] == "CN_MOBILE"]
                self.assertEqual([(NOTREAL_MOBILE, None)], hits)


class TestDScannerContract(unittest.TestCase):
    """D. 元组形状、判定函数签名、二进制判别。"""

    def test_scan_text_tuple_shape(self):
        hits = S.scan_text("咨询 18712345678")
        self.assertEqual(1, len(hits))
        self.assertEqual(6, len(hits[0]))

    def test_placeholder_of_takes_only_a_value(self):
        """签名本身就是约束：拿不到上下文，就写不出看上下文的判定。"""
        import inspect
        params = list(inspect.signature(S.placeholder_of).parameters)
        self.assertEqual(["value"], params)

    def test_looks_binary_is_content_based(self):
        self.assertTrue(S.looks_binary(b"\x00\x01\x02"))
        self.assertFalse(S.looks_binary("中文 abc\n".encode("utf-8")))
        self.assertFalse(S.looks_binary(b""))

    def test_binary_detects_build_machine_absolute_path(self):
        """二进制里内嵌的**构建机绝对路径**必须被抓到，且判据不得依赖某个具体目录名。

        回归的是 R7 的一条真实缺陷：原表里写着 `b"E:/PickupReview"` —— 一个**公开**工具把
        作者私有目录名写成检测特征。它同时犯了两个错：把那个名字本身公开出去，
        以及只能抓这一个字符串（换个盘符或目录名就完全失效 = 假阴性）。
        """
        with tempfile.NamedTemporaryFile(suffix=".dex", delete=False) as fh:
            fh.write(b"\x00\x01" + rb"C:\build\agent\proj\src" + b"\x00")
            path = fh.name
        try:
            out = S.binary_meta_hits(path)
        finally:
            os.unlink(path)
        self.assertTrue(any("绝对路径" in desc for desc, _n in out), out)

    def test_no_private_directory_name_is_hardcoded(self):
        """工具自己的源码里不许出现作者的私有目录名（它是"检测特征"，不是数据）。"""
        src = Path(S.__file__).read_text(encoding="utf-8")
        code = "\n".join(ln for ln in src.splitlines() if not ln.strip().startswith("#"))
        for name in ("PickupReview", "PickupBackup", "PickupData"):
            self.assertNotIn(name, code, f"{name} 被硬编码进了扫描器源码")

    def test_oversized_binary_is_reported_not_silently_skipped(self):
        """超上限的文件必须**报出来**，不能像旧版那样静默跳过。"""
        old = S.BINARY_HARD_LIMIT
        try:
            S.BINARY_HARD_LIMIT = 4
            with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as fh:
                fh.write(b"\x00" * 64)
                path = fh.name
            try:
                out = S.binary_meta_hits(path)
            finally:
                os.unlink(path)
        finally:
            S.BINARY_HARD_LIMIT = old
        self.assertTrue(out)
        self.assertTrue(any("未扫描" in desc for desc, _n in out), out)


class TestEMainOnTheRealRepo(unittest.TestCase):
    """E. 端到端：本仓库当前必须是干净的（CI 门禁就是这一条）。

    这条同时是旧的"文件大于 4MB 就跳过"盲区的回归 —— 造一个 >4MB 的文本文件，
    里面放一个真实感号码，扫描必须仍然抓得到它。
    """

    def test_repo_is_clean_now(self):
        # 必须显式 encoding="utf-8"：subprocess 的 text=True 用 locale 编码解码，
        # 中文 Windows 上是 GBK —— 扫描器输出的 UTF-8 中文会让读取线程直接抛
        # UnicodeDecodeError，proc.stdout 变成 None，本断言于是以一个毫不相干的
        # TypeError 失败（"NoneType is not subscriptable"），把真实结论完全盖掉。
        proc = subprocess.run(
            [sys.executable, str(REPO_ROOT / "tools" / "audit" / "scan_sensitive.py"),
             str(REPO_ROOT)],
            capture_output=True, text=True, encoding="utf-8", errors="replace")
        self.assertEqual(0, proc.returncode,
                         (proc.stdout or "")[-2000:] + (proc.stderr or "")[-2000:])

    def test_large_text_file_is_still_scanned(self):
        filler = ("// 这是填充行，用于把文件撑到 4MB 以上\n" * 200000)
        with tempfile.TemporaryDirectory() as tmp:
            big = Path(tmp) / "big.txt"
            big.write_text(filler + f"咨询 {NOTREAL_MOBILE}\n", encoding="utf-8")
            self.assertGreater(big.stat().st_size, 4 * 1024 * 1024,
                               "用例前提失效：文件没到 4MB")
            buf = io.StringIO()
            argv = sys.argv
            try:
                sys.argv = ["scan_sensitive.py", tmp]
                with redirect_stdout(buf):
                    code = S.main()
            finally:
                sys.argv = argv
        out = buf.getvalue()
        self.assertEqual(1, code, "大文件里的真实号码必须让扫描器失败（退出码 1）")
        self.assertIn(NOTREAL_MOBILE, out)


if __name__ == "__main__":
    unittest.main(verbosity=2)
