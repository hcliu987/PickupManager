# 码上取 PickupManager —— 完全离线的安卓快递取件助手
# Copyright (C) 2026 shanqiugzs
# SPDX-License-Identifier: GPL-3.0-or-later
# 许可全文见仓库根目录 LICENSE。
# -*- coding: utf-8 -*-
"""`.gitignore` 纵深探针 —— 回归 H-05。

用法:
    python3 tools/audit/check_gitignore_depth.py [仓库根=.]

背景（这就是这个脚本存在的理由）：
  `.gitignore` 里带**前导斜杠**的模式（`/*.json`）只与仓库根比对，子目录一律不匹配。
  于是"把一条规则写进 .gitignore"和"这条规则真的能挡住子目录里的文件"是两件事。
  本仓库曾经踩过：兜底规则写成 `/*.json`、`/*.txt`，威胁模型写在注释里是
  "换一个文件名的短信导出照样能被 add"，而解法恰好对子目录无效 —— 一次拖拽即可绕过。

本脚本是一张**表**，不是模糊检查：
  MUST_IGNORE     —— 必须被忽略（漏一个就是一条未修复的缺陷）
  MUST_TRACK      —— 必须**不能**被忽略（Room schema 这类构建契约被挡掉就是回归）
"""
import os
import subprocess
import sys

# Windows 控制台默认 GBK，« ✓ ❌ 与中文断言消息会抛 UnicodeEncodeError，
# 于是「门跑不了」看起来像「门查出了问题」。所有审计脚本统一先切 UTF-8。
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

MUST_IGNORE = [
    # 短信导出改名 + 换目录（H-05 的原始威胁模型）
    "docs/export.json",
    "app/src/main/assets/sms.json",
    "app/notes/data.txt",
    "tools/语料.txt",
    "app/sms_2026.txt",
    "docs/my_sms.txt",
    "app/dump.db",
    "docs/records.csv",
    "backup/pickup.db",
    "docs/raw.txt",
    "sms_dump.json",
    "app/src/main/assets/whatever.txt",
    "a/b/c/deep/data.json",
    # 日志与备份残留
    "debug.log",
    "old.bak",
    "src/main/kotlin/x.java.orig",
    # 凭据
    "secrets.env",
    "config/secrets.env",
    ".env",
    "app/.env.local",
]

MUST_TRACK = [
    # Room 导出的 schema 是**必须跟踪**的构建契约：被 *.json 挡掉会让新克隆无法编译校验
    "app/schemas/com.shanqiu.pickupmanager.data.db.PickupDatabase/1.json",
    "app/schemas/com.shanqiu.pickupmanager.data.db.PickupDatabase/9.json",
    "app/schemas/com.shanqiu.pickupmanager.data.db.PickupDatabase/10.json",
    # 脱敏映射的格式样例（全哨兵值）：redact_history.py 的报错直接指名它，
    # 被 *.txt 兜底挡掉 = 让读者去参考一个仓库里不存在的文件（R7 / L-24）
    "tools/audit/redact_map.example.txt",
    # 关键构建与元数据文件不能被误挡
    "gradle/wrapper/gradle-wrapper.jar",
    "gradle/libs.versions.toml",
    "app/proguard-rules.pro",
    "README.md",
    "LICENSE",
]


def _bulk_verdict(repo, paths):
    """一次 git 调用判定所有路径，返回 {path: (是否真被忽略, 命中的规则)}。

    为什么要批量：每个路径单独 spawn 一次 git，在 Windows 上一次约 0.2s，
    200 个路径就是 40s 以上。用 `--stdin` 可以把整张表压成一次调用。

    为什么必须加 `-z`：在 Windows 上 `git check-ignore --stdin` 用**换行**分隔时
    会静默失败（rc=1、stdout 为空、stderr 也为空 —— 看起来像"没有任何路径被忽略"，
    实际是它一个路径都没读到）。`-z` 走 NUL 分隔，输出是
    `<source>\\0<lineno>\\0<pattern>\\0<pathname>\\0` 的四元组序列。
    """
    proc = subprocess.run(["git", "-C", repo, "check-ignore", "--stdin", "-z", "-v"],
                          input="\0".join(paths) + "\0",
                          capture_output=True, text=True, encoding="utf-8")
    fields = proc.stdout.split("\0")
    if fields and fields[-1] == "":
        fields.pop()
    matched = {}
    for i in range(0, len(fields) - 3, 4):
        source, lineno, pattern, path = fields[i], fields[i + 1], fields[i + 2], fields[i + 3]
        # pattern 以 `!` 开头是负向白名单 ⇒ 该路径并未被忽略
        matched[path] = (not pattern.startswith("!"), f"{source}:{lineno}:{pattern}")
    return {p: matched.get(p, (False, "")) for p in paths}


def main():
    repo = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")
    failed = []

    print("=" * 78)
    print("A. MUST_IGNORE —— 必须被 .gitignore 挡住")
    print("=" * 78)
    verdict = _bulk_verdict(repo, MUST_IGNORE)
    for p in MUST_IGNORE:
        ignored, rule = verdict[p]
        if ignored:
            print(f"  ✓ {p}    <= {rule.split(chr(9))[0]}")
        else:
            print(f"  ✗ 未忽略：{p}")
            failed.append(("MUST_IGNORE", p))

    print("\n" + "=" * 78)
    print("B. MUST_TRACK —— 必须**不能**被 .gitignore 挡住")
    print("=" * 78)
    verdict = _bulk_verdict(repo, MUST_TRACK)
    for p in MUST_TRACK:
        ignored, rule = verdict[p]
        if not ignored:
            print(f"  ✓ 未被忽略：{p}")
        else:
            print(f"  ✗ 被误挡：{p}    <= {rule}")
            failed.append(("MUST_TRACK", p))

    # C. 已受控文件里有没有被新规则误伤的（tracked 文件不受 .gitignore 影响，
    #    但这条能提前发现"以后想 add 会被挡"的雷）
    print("\n" + "=" * 78)
    print("C. 现有受控文件中会被当前规则命中的（提示，不一定算错）")
    print("=" * 78)
    ls = subprocess.run(["git", "-C", repo, "ls-files"], capture_output=True, text=True)
    tracked = ls.stdout.splitlines()
    hit = [f for f, (ig, _r) in _bulk_verdict(repo, tracked).items() if ig]
    if hit:
        for f in hit:
            print(f"  · {f}")
    else:
        print("  (无)")

    print()
    if failed:
        print(f"❌ {len(failed)} 条不通过：")
        for kind, p in failed:
            print(f"   [{kind}] {p}")
        print("   修法：.gitignore 里的模式不要带前导斜杠；"
              "schema 用 !app/schemas/**/*.json 白名单收回。")
        return 1
    print("✓ .gitignore 纵深覆盖与反向白名单全部通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
