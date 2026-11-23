# 码上取 PickupManager —— 完全离线的安卓快递取件助手
# Copyright (C) 2026 shanqiugzs
# SPDX-License-Identifier: GPL-3.0-or-later
# 许可全文见仓库根目录 LICENSE。
# -*- coding: utf-8 -*-
"""提交元数据脱敏探针。

回答两个工作树扫描器**永远看不到**的问题：

1. **提交者姓名 / 邮箱**。`git log --format='%an <%ae>'` 里的真名真邮箱会随
   `git clone` 一起被所有人拿到，而且它不在任何文件里 —— 只存在于 commit 对象。
   发布提交使用 `shanqiugzs <shanqiugzs@users.noreply.github.com>`（GitHub noreply 邮箱），
   审计过程中的提交使用两个占位身份。任何**不在白名单里的身份**
   都会被判为 BLOCK。

2. **时区偏移**。`%ad` 里的 `+0800` 会暴露作者所在时区。这一条不是"泄露个人信息"
   那么严重，但它是可机械核验的：一个宣称"不含任何位置信息"的仓库不该同时
   在每个提交里写着 `+0800`。本脚本把它作为 INFO 报出，交由作者决定是否统一成 `+0000`。

3. **`.git/config` 残留**。`url = file:///E:/...` 这类本地路径 remote 会
   把作者的目录结构和盘符一起公开。本仓库已把 origin 删除；脚本做回归断言。

退出码：0 = 通过；1 = 有未白名单身份；2 = 读不到 git 元数据（不可信）。
"""
import os
import re
import subprocess
import sys

# Windows 控制台默认 GBK，« ✓ ❌ 与中文断言消息会抛 UnicodeEncodeError，
# 于是「门跑不了」看起来像「门查出了问题」。所有审计脚本统一先切 UTF-8。
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

ALLOWED_IDENTITIES = {
    # 发布提交使用的作者身份：GitHub 的 noreply 邮箱，不暴露真实邮箱地址
    "shanqiugzs <shanqiugzs@users.noreply.github.com>",
    # 审计过程中的占位身份（保持占位，不写真实邮箱）
    "PickupManager Author <author@example.invalid>",
    "PickupManager Audit <audit@example.invalid>",
}

# 允许出现的占位邮箱域名（RFC 2606 保留域）
PLACEHOLDER_DOMAINS = {"example.invalid", "example.com", "example.org", "localhost", "users.noreply.github.com"}

# remote 里出现这些就说明是本地路径 / 私有地址
BAD_REMOTE = re.compile(r"^(file://|[a-zA-Z]:[\\/]|\\\\|/|https?://(localhost|127\.|192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.))", re.I)


def git(repo, *args):
    p = subprocess.run(["git", "-C", repo, *args], capture_output=True)
    if p.returncode != 0:
        return None
    return p.stdout.decode("utf-8", "replace")


def main():
    repo = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")

    raw = git(repo, "log", "--all", "--format=%h\t%an\t%ae\t%cn\t%ce\t%aI\t%cI")
    if raw is None:
        print("❌ 读不到 git 日志，无法判断（结果不可信）")
        return 2

    authors, committers, identities, tzs = set(), set(), set(), set()
    for line in raw.strip().splitlines():
        f = line.split("\t")
        if len(f) < 7:
            continue
        _h, an, ae, cn, ce, ai, ci = f[:7]
        authors.add(f"{an} <{ae}>")
        committers.add(f"{cn} <{ce}>")
        identities.add(f"{an} <{ae}>")
        identities.add(f"{cn} <{ce}>")
        m = re.search(r"([+-]\d{2}):?(\d{2})$", ci)
        if m:
            tzs.add(m.group(1) + m.group(2))

    print(f"提交数={len(raw.strip().splitlines())}")
    print(f"作者身份={len(authors)}  提交者身份={len(committers)}")

    bad = []
    for ident in sorted(identities):
        if ident in ALLOWED_IDENTITIES:
            continue
        m = re.search(r"<(.+?)>", ident)
        domain = m.group(1).split("@")[-1].lower() if m and "@" in m.group(1) else ""
        if domain in PLACEHOLDER_DOMAINS:
            bad.append((ident, "占位域名但不在白名单（建议补进白名单或改成约定值）"))
        else:
            bad.append((ident, f"**非占位邮箱域名 {domain!r}** —— 极可能是真实身份"))
    for ident, why in bad:
        print(f"  [BLOCK] {ident}   → {why}")
    if not bad:
        print("  ✓ 全部提交身份都在白名单内")

    print(f"出现过的提交时区偏移：{sorted(tzs) if tzs else '（无）'}")
    if tzs - {"+0000", "-0000"}:
        print("  [INFO] 时区偏移可推断作者地域。若要求完全位置中立，"
              "可用 `GIT_AUTHOR_DATE`/`GIT_COMMITTER_DATE` 统一成 +0000 后重写历史。")

    conf = git(repo, "config", "--local", "--list") or ""
    remotes = []
    for line in conf.splitlines():
        if line.strip().startswith("remote.") and ".url=" in line:
            remotes.append(line.strip())
    print("本地配置里的 remote：")
    if not remotes:
        print("  ✓ 未配置任何 remote（不会误推到备份目录）")
    for r in remotes:
        url = r.split("=", 1)[1]
        if BAD_REMOTE.search(url):
            print(f"  [BLOCK] {r}   → 指向本地路径 / 私有地址，会暴露作者的目录结构")
        else:
            print(f"  {r}")

    print()
    if bad or any(BAD_REMOTE.search(r.split("=", 1)[1]) for r in remotes):
        print("❌ 提交元数据未脱敏。")
        return 1
    print("✓ 提交元数据通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
