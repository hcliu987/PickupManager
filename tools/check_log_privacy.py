#!/usr/bin/env python3
# 码上取 PickupManager —— 完全离线的安卓快递取件助手
# Copyright (C) 2026 shanqiugzs
# SPDX-License-Identifier: GPL-3.0-or-later
# 许可全文见仓库根目录 LICENSE。
"""检查 release 里真能留下的日志，有没有带出用户的包裹凭证。

为什么单独要有这个工具：`app/proguard-rules.pro` 的 -assumenosideeffects 只列了
Log.v / Log.d / Log.i 与 isLoggable，**Log.w 与 Log.e 在 release 包里原样保留**。
所以"日志脱敏"的边界不能按"当时想到了哪几类字段"来划 —— 本项目就因此漏过三次
（三处 Log.w/Log.e 直接打印取件码，而取件码是唯一"泄露即可去驿站冒领"的字段）。

用法： python3 tools/check_log_privacy.py     # 失败时退出码非 0，已挂进 CI
"""
import pathlib
import re
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / 'app/src/main/java'

# 只有 Log.w / Log.e（以及 println / printStackTrace 带参）会活到 release
SURVIVES = re.compile(r'\bLog\.(?:w|e)\s*\(')
# 视为"用户的包裹凭证 / 住址 / 号码"的字段名
SENSITIVE = re.compile(
    r'\$\{?\s*(?:[A-Za-z_][\w.]*\.)?'
    r'(pickupCode|rawSms|senderPhone|phoneNumber|addressTag|address|body|messageBody|smsText|'
    r'trackingNumber|correctedAddress|predictedAddress|displayName|originalSms)'
    r'\b[^}]*\}?|"\s*\+?\s*\w*\.(?:messageBody|senderAddress)\b'
)
# 已知的安全包装：privacyRef(...) / .length / size / take(...)
SAFE_WRAPPED = re.compile(r'privacyRef\s*\(|\.length\b|\.size\b|\bsize\b')


def main():
    bad = []
    for p in SRC.rglob('*.kt'):
        for lineno, line in enumerate(p.read_text(encoding='utf-8', errors='ignore').splitlines(), 1):
            if not SURVIVES.search(line):
                continue
            if not SENSITIVE.search(line):
                continue
            # 整行里所有被插值的敏感字段都必须已经被 privacyRef/长度化包装
            stripped = SAFE_WRAPPED.sub('', line)
            if SENSITIVE.search(stripped):
                bad.append((p.relative_to(ROOT), lineno, line.strip()[:130]))

    print(f'扫描 {sum(1 for _ in SRC.rglob("*.kt"))} 个源文件里活到 release 的日志调用')
    if bad:
        print(f'!! {len(bad)} 处 Log.w/Log.e 会带出用户数据（R8 不剥离 w/e）：')
        for f, ln, s in bad:
            print(f'   {f}:{ln}  {s}')
        print('   修法：换成 util/PrivacyLog.kt 的 privacyRef(x)，或只打数量/长度。')
        return 1
    print('✓ 活到 release 的日志里没有裸露的取件码 / 住址 / 号码 / 短信正文')
    return 0


if __name__ == '__main__':
    sys.exit(main())
