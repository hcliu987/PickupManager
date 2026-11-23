# 码上取 PickupManager —— 完全离线的安卓快递取件助手
# Copyright (C) 2026 shanqiugzs
# SPDX-License-Identifier: GPL-3.0-or-later
# 许可全文见仓库根目录 LICENSE。
# -*- coding: utf-8 -*-
"""工作树脱敏扫描器：文本规则 + 二进制/图片元数据。

用法:
    python3 tools/audit/scan_sensitive.py [仓库根=.]
    python3 tools/audit/test_scan_sensitive.py        # 本扫描器自己的回归测试

只报告「形状 + 位置」，不把完整命中内容原样打全（避免把命中的敏感串再复制一份到日志里）。

为什么需要它：本仓库以"逐条可核对"为卖点，而"仓库里没有真实数据"这句话必须由一条
可执行的命令来支撑，不能靠人工看一眼。本脚本扫的是**工作树**；历史对象由
scan_history.py 负责 —— 两者缺一不可（删掉一行只是新增一个提交，旧对象仍可寻址）。

判定纪律（R6 加固；逐条对应 docs/audit/05-security-and-license.md §15 的 M-25 / L-23）：
  * 「这一条命中算不算 PII」的**唯一输入是命中的那个值本身**。判定函数
    `placeholder_of(value)` 只接收一个字符串 —— 拿不到文件、行号、前后文，
    所以**不可能**写出"这句话里提到了示例，所以豁免"这种判定。同一个值在任何
    文件、任何一行都得到同一个结论。
  * 允许豁免的只有三类：RFC/规范保留值；"值本身不含任何可识别信息"的形态
    （例如只有一个门牌号）；以及仓库自己那一个规范化夹具号。明细见
    PLACEHOLDER_VALUES，每条都带一个**见证值**，test_scan_sensitive.py 会机械核对
    "正文则能命中见证值"且"见证值真的会由规则表产出"。
  * 二进制按**内容**判别（前 4KB 含 NUL，git 用的同一条启发式），不再有
    "文件大于 4MB 就当二进制跳过"这一条 —— 那会让一个 4MB 的文本文件整段逃过扫描。
"""
import argparse
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

# ---------------------------------------------------------------- 规则表
# (规则名, 严重度, 正则, 说明)
RULES_TEXT = [
    # --- 凭据 / 密钥 ---
    ("PRIVATE_KEY", "BLOCK",
     r"-----BEGIN [A-Z ]*PRIVATE KEY-----", "私钥 PEM 块"),
    ("KEYSTORE_PASS", "BLOCK",
     r"(storePassword|keyPassword|storePass|keyPass)\s*[=:]\s*[\"'][^\"'\s]{3,}[\"']",
     "签名口令明文"),
    ("SECRET_ASSIGN", "HIGH",
     r"(?i)\b(api[_-]?key|apikey|access[_-]?token|secret[_-]?key|client[_-]?secret|auth[_-]?token|bearer)\b\s*[=:]\s*[\"'][^\"'\s]{8,}[\"']",
     "疑似 API key / token"),
    ("CREDENTIAL_URL", "HIGH",
     r"\b[a-z][a-z0-9+.\-]*://[^/\s:@]{1,64}:[^/\s:@]{1,64}@", "URL 内嵌用户名口令"),
    ("JDBC", "HIGH", r"jdbc:[a-z]+://[^\s\"']+", "JDBC 连接串"),
    ("BASIC_AUTH", "HIGH",
     r"(?i)authorization\s*:\s*(basic|bearer)\s+\S+", "HTTP 认证头"),
    ("GOOGLE_API_KEY", "HIGH", r"\bAIza[0-9A-Za-z_\-]{35}\b", "Google API key"),
    ("AWS_KEY", "HIGH", r"\bAKIA[0-9A-Z]{16}\b", "AWS Access Key ID"),
    ("GITHUB_TOKEN", "HIGH",
     r"\b(gh[pousr]_[0-9A-Za-z]{30,}|github_pat_[0-9A-Za-z_]{50,})\b", "GitHub Token"),
    ("SLACK_TOKEN", "HIGH", r"\bxox[baprs]-[0-9A-Za-z\-]{10,}\b", "Slack Token"),

    # --- 个人绝对路径 ---
    ("WIN_USER_PATH", "HIGH",
     r"[A-Za-z]:[\\/](?:Users|用户)[\\/][^\\/\s\"'<>|]{1,40}", "含用户名的 Windows 绝对路径"),
    ("UNIX_HOME", "HIGH", r"/(?:home|Users)/[A-Za-z0-9_.\-]{1,40}", "Unix 家目录绝对路径"),
    ("TILDE_HOME", "LOW", r"~/[A-Za-z0-9_./\-]{2,}", "~ 家目录路径"),

    # --- 中国个人身份信息 ---
    ("CN_MOBILE", "BLOCK", r"(?<!\d)1[3-9]\d{9}(?!\d)", "中国大陆手机号"),
    ("CN_IDCARD", "BLOCK", r"(?<!\d)\d{17}[\dXx](?!\d)", "身份证号"),
    ("CN_TEL_AREA", "HIGH", r"(?<!\d)0\d{2,3}-\d{7,8}(?!\d)", "固定电话（带区号）"),
    ("CN_400", "MED", r"(?<!\d)400-?\d{3}-?\d{4}(?!\d)", "400 客服号"),
    ("EMAIL", "MED", r"\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b", "邮箱地址"),
    ("IPV4", "MED",
     r"(?<![\d.])(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?![\d.])",
     "IPv4 地址"),
    ("MAC", "MED", r"\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b", "MAC 地址"),
    ("BANKCARD", "HIGH", r"(?<!\d)\d{16,19}(?!\d)", "疑似银行卡号"),

    # --- 中文地址 / 真实地点 ---
    ("CN_ADDR_DOOR", "HIGH",
     r"[\u4e00-\u9fa5]{0,8}\d{1,4}\s*(?:号楼|栋|幢|单元|门|室|层)", "含门牌号的地址片段"),
    ("CN_ADDR_COMPOUND", "HIGH",
     r"[\u4e00-\u9fa5]{2,5}(?:小区|花园|家园|新城|公寓|广场|大厦|华庭|雅苑|名邸|国际城|新村|别墅|佳苑|丽景)",
     "疑似真实居住地名（通名）"),
    ("CN_ADDR_ROAD", "HIGH",
     r"[\u4e00-\u9fa5]{2,6}(?:路|街|道|巷)\s*\d{1,4}\s*号", "含门牌的道路地址"),
    ("CN_ADDR_FIELD", "MED",
     r"(?:收件人|收货人|取件人|联系电话|联系号码|收件地址)\s*[:：]\s*\S{2,}",
     "短信式联系字段（冒号后带内容）"),

    # --- 取件码形态（用于发现"注释里写了真实码"）---
    ("PICKUP_CODE_COMPOUND", "MED",
     r"(?<![\dA-Za-z])[A-Z]?\d{1,3}-\d{1,3}-\d{3,6}(?![\dA-Za-z])", "复合取件码形态 a-b-ccccc"),

    # --- 个人标识 ---
    ("QQ_WECHAT", "MED",
     r"(?<!\d)(?:QQ|qq|微信|wechat|wx)[:：\s]{0,3}\d{5,12}(?!\d)", "QQ/微信号"),
]

# ---------------------------------------------------------------- 占位值表
# (条目名, 全串正则（re.fullmatch 语义）, 理由, 见证值)
#
# 关于取舍（R6 实测，见 05-security-and-license.md §15）：
#   旧版这里是一张"值里含某个子串就算示例"的正则列表，带来两类问题：
#     a) 与实际规则表脱节 —— `12345678901` 的第二位不在 [3-9]，CN_MOBILE 的形状
#        决定它**永远不可能**被产出；`1[3-9]x{4,}` 连它自己想要的掩码写法
#        （`138xxxx1234`）都匹配不上；`localhost` / `F5FAFA` / `FF[0-9A-F]{6}`
#        在本仓库的规则表里没有任何一条能产出它们。共 7 条从未生效过。
#     b) 过宽 —— `foo` / `bar` / `test@` / `your` 这类短子串会把**真实数据**判成示例：
#        `test@163.com` 是真实可注册邮箱，却因为含 `test@` 被豁免。
#   所以改成"全串匹配 + 见证值"。宁可有误报（人来复核），不可有漏报。
#
# 注意"故意不豁免"的一项：`PICKUP_CODE_COMPOUND`（`A2-1-3456` 这类）**不设豁免** ——
# 真实取件码与夹具码的形状完全一致，按值无法区分，正是这个规则存在的意义（提示人工复核）。
PLACEHOLDER_VALUES = [
    # —— 值本身不含任何可识别信息：只有一个门牌 token，前面没有地名
    ("NO_NAME_DOOR", r"\d{1,4}\s*(?:号楼|栋|幢|单元|门|室|层)",
     "只有门牌号、没有地名，无法指向任何具体住所", "3号楼"),

    # —— 仓库自己的规范化夹具值（不是"看起来像假的"，是"审计时已逐条确认过是夹具"）
    ("REPO_FIXTURE_MOBILE", r"1(?:3800138000|8712345678)",
     "13800138000 是中文文档惯用示例号；18712345678 是本仓库夹具统一使用的规范化假号"
     "（R1 脱敏时选定，13 处全部位于测试与注释的模式说明里）。**只有这两个值被豁免**，"
     "换任何一个别的号码都会让本脚本以退出码 1 失败",
     "13800138000"),
    ("REPO_FIXTURE_400", r"4001234567",
     "仓库夹具使用的规范化假 400 号，与上一条同源", "4001234567"),

    # —— RFC / 规范保留值：按定义不属于任何人
    ("LOOPBACK_V4", r"127(?:\.\d{1,3}){3}", "IPv4 回环地址段（RFC 5735）", "127.0.0.1"),
    ("UNSPECIFIED_V4", r"0\.0\.0\.0", "IPv4 未指定地址（RFC 5735）", "0.0.0.0"),
    ("BROADCAST_V4", r"255\.255\.255\.255", "IPv4 受限广播地址（RFC 919）", "255.255.255.255"),
    ("PRIVATE_V4",
     r"(?:10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}"
     r"|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})",
     "RFC 1918 私网地址，不指向公网主机", "192.168.1.1"),
    ("DOC_V4", r"(?:192\.0\.2|198\.51\.100|203\.0\.113)\.\d{1,3}",
     "RFC 5737 文档专用地址段，按规范永不分配", "203.0.113.9"),
    ("RESERVED_EMAIL",
     r"[^@\s]+@(?:[A-Za-z0-9\-]+\.)*(?:example\.(?:com|org|net)|invalid|test|localhost)",
     "RFC 2606 / 6761 保留域名，按规范不可注册", "author@example.invalid"),

    # —— 仓库内夹具/文档的统一写法：**以「占位词 + 通名」结尾**，不是"包含"。
    # 为什么必须结尾锚定：CN_ADDR_COMPOUND / CN_ADDR_DOOR 的匹配里会带上前文
    # （实测有 `裹已到示例小区`、`达12号楼`、`件码和真实小区`），而 `示例阳光小区`
    # 这种"占位词后面又跟了真名"的写法可能正是被截断过的真实小区名，不能豁免。
    ("PLACEHOLDER_PLACE",
     r".*(?:示例|样例|占位)"
     r"(?:小区|花园|家园|新城|公寓|广场|大厦|华庭|雅苑|名邸|国际城|新村|别墅|佳苑|丽景)",
     "以「占位词+通名」结尾的地名（仓库夹具与文档的统一写法）", "示例小区"),
    ("PLACEHOLDER_PLACE_DOOR",
     r".*(?:示例|样例|占位)"
     r"(?:小区|花园|家园|新城|公寓|广场|大厦|华庭|雅苑|名邸|国际城|新村|别墅|佳苑|丽景)"
     r"\s*\d{1,4}\s*(?:号楼|栋|幢|单元|门|室|层)",
     "占位地名 + 门牌", "示例小区3号楼"),
]

BINARY_EXT = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".jar", ".keystore",
              ".jks", ".p12", ".pfx", ".so", ".a", ".zip", ".apk", ".aab", ".ttf", ".otf"}

BINARY_META_PATTERNS = [
    (b"tEXt", "PNG tEXt 文本块"), (b"iTXt", "PNG iTXt 国际化文本块"),
    (b"zTXt", "PNG zTXt 压缩文本块"), (b"eXIf", "PNG eXIf EXIF 块"),
    (b"Exif\x00\x00", "EXIF 段"), (b"<x:xmpmeta", "XMP 元数据"),
    (b"http://ns.adobe.com", "XMP/Adobe 元数据"),
    (b"GPSLatitude", "EXIF GPS 纬度"), (b"GPSLongitude", "EXIF GPS 经度"),
    (b"C:/Users", "二进制内嵌 Windows 用户路径"),
    (b"C:\\Users", "二进制内嵌 Windows 用户路径"),
    # 这里曾有一条 `b"E:/PickupReview"`。它是错的，两个理由，都记下来免得再犯：
    #   ① 一个**公开**工具把作者私有目录名写成检测特征，等于把那个名字本身公开出去；
    #   ② 它只能抓到"这一个字符串"，换个盘符、换个目录名就完全失效 —— 也就是假阴性。
    # 通用形态交给下面 DRIVE_SELF_PATH_RE；"作者私有目录名"本身不该在仓库里出现。
]

# 通用的「盘符:\Users\名字」形态。与上表里几条硬编码路径不同，这条不限定盘符与用户名。
DRIVE_USER_PATH_RE = re.compile(r"[A-Za-z]:[\\/](?:Users|用户)[\\/][^\\/\x00]{1,40}".encode("utf-8"))

# 通用的「盘符:\某目录\...」形态（不要求是 Users）：DEX / arsc / jar 里内嵌的构建机绝对路径
# 长这样。要求盘符后至少有 2 层、总长 ≥8，压掉随机字节撞车的噪声。
DRIVE_SELF_PATH_RE = re.compile(
    rb"[A-Za-z]:[\\/][A-Za-z0-9_.\-]{2,}[\\/][A-Za-z0-9_.\-\\/]{4,}")

SEVERITY_ORDER = ["BLOCK", "HIGH", "MED", "LOW"]

# 二进制按内容判别：前 4KB 含 NUL 即视为二进制（git 判文本/二进制用的同一条启发式）
HEAD_BYTES = 4096
# 二进制文件仍整体读入内存做元数据统计；超过此上限**报出来**，而不是静默跳过
BINARY_HARD_LIMIT = 64 * 1024 * 1024


def placeholder_of(value):
    """只凭 value 判定是否占位；返回命中的条目名，非占位返回 None。

    这个函数的签名本身就是约束 —— 它拿不到文件、行号、上下文，所以没法写出
    "这一行提到了示例所以豁免"这类判定。要加豁免，只能加值。
    """
    for name, pat, _why, _witness in PLACEHOLDER_VALUES:
        if re.fullmatch(pat, value):
            return name
    return None


def scan_text(text):
    """扫描一段文本，返回 [(rule, sev, lineno, snippet, desc, placeholder_name_or_None)]"""
    hits = []
    for lineno, line in enumerate(text.splitlines(), 1):
        for rule, sev, pat, desc in RULES_TEXT:
            for m in re.finditer(pat, line):
                s = m.group(0)
                hits.append((rule, sev, lineno, s, desc, placeholder_of(s)))
    return hits


def looks_binary(head):
    """按内容判别二进制。不用扩展名，也不看文件大小。"""
    return b"\x00" in head


def binary_meta_hits(path):
    """统计一个二进制文件里的元数据标记，返回 [(说明, 次数)]。"""
    size = os.path.getsize(path)
    if size > BINARY_HARD_LIMIT:
        return [("文件超过 %d MB，未扫描（**这是一条待处理的发现，不是跳过**）"
                 % (BINARY_HARD_LIMIT >> 20), 1)]
    with open(path, "rb") as fh:
        data = fh.read()
    out = []
    for pat, desc in BINARY_META_PATTERNS:
        n = data.count(pat)
        if n:
            out.append((desc, n))
    n = len(DRIVE_USER_PATH_RE.findall(data))
    if n:
        out.append(("二进制内嵌 Windows 用户路径（通用形态）", n))
    n = len(DRIVE_SELF_PATH_RE.findall(data))
    if n:
        out.append(("二进制内嵌构建机绝对路径（不限于 Users）", n))
    return out


def _ignored_paths(repo):
    """git 认为被忽略的路径 —— 它们不可能被提交，因此不属于"会被公开的内容"。

    不加这一步会有一个很难看的自指问题：跑测试本身会生成 `__pycache__/*.pyc`，
    而这些字节码里嵌着本机绝对路径，于是"仓库是否干净"这条断言在**任何跑过工具的人
    的机器上**都恒为假 —— 一个永远绿不了的门等于没有门，而且下一次有人会去把它注掉。
    """
    try:
        out = subprocess.run(
            ["git", "-C", repo, "ls-files", "--others", "--ignored",
             "--exclude-standard", "-z"],
            capture_output=True, text=True, encoding="utf-8", errors="ignore")
    except OSError:
        return {"__pycache__"}
    if out.returncode != 0:
        return {"__pycache__"}
    return {os.path.normpath(p) for p in out.stdout.split("\0") if p}


def walk_tree(repo):
    ignored = _ignored_paths(repo)
    for root, dirs, files in os.walk(repo):
        dirs[:] = [d for d in dirs if d not in (".git", "build", ".gradle", ".kotlin")]
        for f in files:
            p = os.path.join(root, f)
            rel = os.path.normpath(os.path.relpath(p, repo))
            if rel in ignored or "__pycache__" in rel:
                continue
            yield p


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("repo", nargs="?", default=".")
    ap.add_argument("--max-snippet", type=int, default=60)
    ap.add_argument("--show", type=int, default=8, help="每规则最多打印几条")
    args = ap.parse_args()
    repo = os.path.abspath(args.repo)

    print("=" * 78)
    print(f"A. 工作树文本扫描  repo={repo}")
    print("=" * 78)

    per_rule = defaultdict(list)
    binary_findings = []
    text_files = 0
    for p in sorted(walk_tree(repo)):
        rel = os.path.relpath(p, repo).replace("\\", "/")
        ext = os.path.splitext(p)[1].lower()
        try:
            with open(p, "rb") as fh:
                head = fh.read(HEAD_BYTES)
        except OSError:
            continue
        if ext in BINARY_EXT or looks_binary(head):
            # 二进制不静默跳过：仍然统计元数据标记
            try:
                for desc, n in binary_meta_hits(p):
                    binary_findings.append((rel, desc, n))
            except OSError:
                continue
            continue
        try:
            with open(p, encoding="utf-8", errors="replace") as fh:
                text = fh.read()
        except OSError:
            continue
        text_files += 1
        for rule, sev, lineno, s, desc, ph in scan_text(text):
            per_rule[(rule, sev, desc)].append((rel, lineno, s, ph))

    if not per_rule:
        print("  (无命中)")
    for (rule, sev, desc), items in sorted(
            per_rule.items(), key=lambda kv: (SEVERITY_ORDER.index(kv[0][1]), kv[0][0])):
        ph_n = sum(1 for i in items if i[3])
        print(f"\n  [{sev}] {rule} — {desc}  命中 {len(items)} 处（其中 {ph_n} 处按值判为占位）")
        for rel, lineno, s, ph in items[:args.show]:
            snip = s if len(s) <= args.max_snippet else s[:args.max_snippet] + "…"
            print(f"      {rel}:{lineno}  «{snip}»{f'  ⚠占位={ph}' if ph else ''}")
        if len(items) > args.show:
            print(f"      … 另有 {len(items) - args.show} 处")

    print(f"\n  文本文件 {text_files} 个；豁免判定只看值本身（PLACEHOLDER_VALUES 共 "
          f"{len(PLACEHOLDER_VALUES)} 条，条目标注在每行末尾）")

    print("\n" + "=" * 78)
    print("B. 二进制 / 图片元数据")
    print("=" * 78)
    if not binary_findings:
        print("  (无元数据块)")
    for rel, desc, n in binary_findings:
        print(f"  {rel}  →  {desc} × {n}")

    # 退出码：出现 BLOCK 级命中即失败，可直接接 CI
    blocking = [k for k in per_rule if k[1] == "BLOCK"
                and any(i[3] is None for i in per_rule[k])]
    blk_bin = [b for b in binary_findings if "路径" in b[1] or "未扫描" in b[1]]
    if blocking or blk_bin:
        print("\n❌ 存在 BLOCK 级命中（未在占位值表内），请人工确认后清理。")
        return 1
    print("\n✓ 未发现 BLOCK 级命中。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
