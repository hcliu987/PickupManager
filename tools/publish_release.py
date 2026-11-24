#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
码上取 PickupManager —— 同步代码并发布正式 Release（GitHub + Gitee）

做一件事：把「git push 两个远端 → 为当前版本创建正式 Release → 上传签名 APK → 下载校验」串成一条命令。
幂等：同一版本已存在 Release 时只补缺失的附件，不重复创建、不覆盖 latest 直链。

用法：
    python3 tools/publish_release.py                       # 按 version.json 版本发布
    python3 tools/publish_release.py --version 1.2         # 显式指定版本名（需与 version.json 一致）
    python3 tools/publish_release.py --apk /path/app.apk   # 指定 APK（默认桌面同名文件）
    python3 tools/publish_release.py --no-push             # 只发 Release，不推代码
    python3 tools/publish_release.py --dry-run             # 只预览，不做任何改动

前置条件：
    - GitHub / Gitee 令牌在 macOS 钥匙串（security find-internet-password -s github.com/gitee.com -w）
    - GitHub Push 走系统代理 7897；api.github.com 走代理；uploads.github.com 直连
    - 工作区干净（--no-push 时无此要求）

隐私：令牌只从钥匙串读取，不写入任何文件、不打印明文。
"""
import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

ROOT = pathlib.Path(__file__).resolve().parent.parent
VERSION_JSON = ROOT / 'version.json'
GH_OWNER, GH_REPO = 'hcliu987', 'PickupManager'
GT_OWNER, GT_REPO = '884024720', 'pickup-manager'
PROXY = os.environ.get('PICKUP_GITHUB_PROXY', 'http://127.0.0.1:7897')


def run(cmd, *, capture=False, check=True, env=None):
    printable = ' '.join(str(x) for x in cmd)
    print(f'  $ {printable}')
    r = subprocess.run([str(x) for x in cmd], cwd=ROOT, capture_output=capture,
                       text=True, env=env)
    if check and r.returncode != 0:
        if capture:
            print(r.stdout)
            print(r.stderr, file=sys.stderr)
        raise SystemExit(f'命令失败（退出码 {r.returncode}）：{printable}')
    return r.stdout.strip() if capture else r


def token(service):
    r = subprocess.run(['security', 'find-internet-password', '-s', service, '-w'],
                       capture_output=True, text=True)
    if r.returncode != 0 or not r.stdout.strip():
        raise SystemExit(f'无法从钥匙串读取 {service} 令牌')
    return r.stdout.strip()


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def opener(use_proxy=False):
    return urllib.request.build_opener(
        urllib.request.ProxyHandler({'https': PROXY} if use_proxy else {}))


def api_json(url, *, tok, method='GET', payload=None, use_proxy=False, timeout=60):
    headers = {'Authorization': f'Bearer {tok}', 'Accept': 'application/json',
               'User-Agent': 'PickupRelease'}
    body = None
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode()
        headers['Content-Type'] = 'application/json;charset=UTF-8'
    req = urllib.request.Request(url, data=body, method=method, headers=headers)
    with opener(use_proxy).open(req, timeout=timeout) as resp:
        raw = resp.read()
        return json.loads(raw) if raw else None


def release_body(changelog):
    return (f'码上取 {changelog.splitlines()[0] if changelog else ""}\n\n'
            '更新内容：\n' + ''.join(line if line.startswith('· ') else '· ' + line
                                   for line in changelog.splitlines() if line) + '\n\n'
            '安装：下载 APK 直接安装；同一签名可覆盖升级，取件记录不丢。\n'
            '隐私：取件数据仅保存在本机；INTERNET 权限仅用于启动时检查更新。\n\n'
            f'源码：https://github.com/{GH_OWNER}/{GH_REPO}\n')


def ensure_github(version, apk, body, tok, *, dry_run):
    tag = 'v' + version.lstrip('v')
    url = f'https://api.github.com/repos/{GH_OWNER}/{GH_REPO}/releases/tags/{tag}'
    try:
        rel = api_json(url, tok=tok, use_proxy=True)
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
        rel = None
    asset_name = f'pickupmanager-{tag}.apk'
    if rel and rel.get('id'):
        print(f'  ✓ GitHub Release {tag} 已存在（id={rel["id"]}）')
        assets = rel.get('assets', [])
        if any(a['name'] == asset_name for a in assets):
            print(f'  ✓ 附件 {asset_name} 已存在，跳过上传')
            return rel['html_url']
        if dry_run:
            print(f'  [dry-run] 将补传附件 {asset_name}')
            return rel['html_url']
        print(f'  → 补传附件 {asset_name}')
    else:
        if dry_run:
            print(f'  [dry-run] 将创建 GitHub Release {tag} 并上传 {asset_name}')
            return None
        rel = api_json(f'https://api.github.com/repos/{GH_OWNER}/{GH_REPO}/releases',
                       tok=tok, method='POST', use_proxy=True, payload={
                           'tag_name': tag, 'target_commitish': 'main',
                           'name': f'码上取 {tag}', 'body': body,
                           'draft': False, 'prerelease': False})
        print(f'  ✓ 已创建 GitHub Release {tag}（id={rel["id"]}）')

    # uploads.github.com 走代理 SSL 失败，必须直连；用 curl 上传大文件更稳。
    upload_url = (f'https://uploads.github.com/repos/{GH_OWNER}/{GH_REPO}/'
                  f'releases/{rel["id"]}/assets?name={asset_name}')
    env = {k: v for k, v in os.environ.items()
           if k.lower() not in ('http_proxy', 'https_proxy', 'all_proxy')}
    cmd = ['curl', '-sS', '--max-time', '300', '--noproxy', '*', '-X', 'POST',
           '-H', f'Authorization: Bearer {tok}',
           '-H', 'Accept: application/vnd.github+json',
           '-H', 'Content-Type: application/vnd.android.package-archive',
           '-H', 'User-Agent: PickupRelease', '--data-binary', f'@{apk}', upload_url]
    r = subprocess.run(cmd, capture_output=True, text=True, env=env)
    if r.returncode != 0:
        raise SystemExit(f'GitHub 附件上传失败：{r.stderr[:300]}')
    up = json.loads(r.stdout)
    print(f'  ✓ 已上传 {up.get("name")}（{up.get("size")} 字节）')
    return rel['html_url']


def ensure_gitee(version, apk, body, tok, *, dry_run):
    tag = 'v' + version.lstrip('v')
    url = f'https://gitee.com/api/v5/repos/{GT_OWNER}/{GT_REPO}/releases/tags/{tag}'
    try:
        rel = api_json(url, tok=tok)
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
        rel = None
    asset_name = f'pickupmanager-{tag}.apk'
    if rel and rel.get('id'):
        print(f'  ✓ Gitee Release {tag} 已存在（id={rel["id"]}）')
        if any(a['name'] == asset_name for a in rel.get('assets', [])):
            print(f'  ✓ 附件 {asset_name} 已存在，跳过上传')
            return rel.get('html_url')
        if dry_run:
            print(f'  [dry-run] 将补传附件 {asset_name}')
            return rel.get('html_url')
    else:
        if dry_run:
            print(f'  [dry-run] 将创建 Gitee Release {tag} 并上传 {asset_name}')
            return None
        rel = api_json(f'https://gitee.com/api/v5/repos/{GT_OWNER}/{GT_REPO}/releases',
                       tok=tok, method='POST', payload={
                           'access_token': tok, 'tag_name': tag,
                           'target_commitish': 'main', 'name': f'码上取 {tag}',
                           'body': body, 'prerelease': False})
        print(f'  ✓ 已创建 Gitee Release {tag}（id={rel["id"]}）')

    boundary = '----PickupReleaseBoundary'
    parts = [f'--{boundary}\r\n'.encode(),
             (f'Content-Disposition: form-data; name="file"; filename="{asset_name}"\r\n'
              'Content-Type: application/vnd.android.package-archive\r\n\r\n').encode(),
             apk.read_bytes(), f'\r\n--{boundary}\r\n'.encode(),
             f'Content-Disposition: form-data; name="access_token"\r\n\r\n{tok}\r\n'.encode(),
             f'--{boundary}--\r\n'.encode()]
    req = urllib.request.Request(
        f'https://gitee.com/api/v5/repos/{GT_OWNER}/{GT_REPO}/releases/{rel["id"]}/attach_files',
        data=b''.join(parts), method='POST',
        headers={'Content-Type': f'multipart/form-data; boundary={boundary}',
                 'Accept': 'application/json'})
    with opener().open(req, timeout=180) as resp:
        up = json.load(resp)
    print(f'  ✓ 已上传 {up.get("name")}（id={up.get("id")}）')
    return rel.get('html_url')


def verify(url, expected, *, use_proxy):
    env = {k: v for k, v in os.environ.items()
           if k.lower() not in ('http_proxy', 'https_proxy', 'all_proxy')}
    cmd = ['curl', '-sSL', '--max-time', '300']
    if use_proxy:
        cmd += ['--http1.1', '--proxy', PROXY]
    else:
        cmd += ['--noproxy', '*']
    cmd += ['-o', '/tmp/pickup_release_verify.apk', url]
    r = subprocess.run(cmd, stdout=subprocess.DEVNULL,
                       stderr=subprocess.PIPE, text=True, env=env)
    if r.returncode != 0:
        raise SystemExit(f'下载校验失败：{r.stderr[:300]}')
    got = sha256('/tmp/pickup_release_verify.apk')
    if got != expected:
        raise SystemExit(f'下载校验失败：{got} != {expected}')
    print(f'  ✓ 下载一致：{got}')


def main():
    ap = argparse.ArgumentParser(description='同步代码并发布 GitHub/Gitee 正式 Release')
    ap.add_argument('--version', help='版本名，如 1.2；默认读 version.json')
    ap.add_argument('--apk', help='APK 路径；默认 ~/Desktop/码上取-v{版本}-release.apk')
    ap.add_argument('--no-push', action='store_true', help='只发 Release，不推代码')
    ap.add_argument('--dry-run', action='store_true', help='只预览，不做任何改动')
    args = ap.parse_args()

    data = json.loads(VERSION_JSON.read_text(encoding='utf-8'))
    version = (args.version or data['versionName']).lstrip('v')
    if version != data['versionName']:
        raise SystemExit(f'版本不一致：--version={version} 但 version.json={data["versionName"]}')
    apk = pathlib.Path(args.apk or pathlib.Path.home() / f'Desktop/码上取-v{version}-release.apk')
    if not apk.exists():
        raise SystemExit(f'未找到 APK：{apk}')
    digest = sha256(apk)
    body = release_body(data['changelog'])
    tag = 'v' + version

    print('=== 发布预览 ===')
    print(f'  版本：{tag}（versionCode {data["versionCode"]}）')
    print(f'  APK：{apk}（{apk.stat().st_size} 字节，SHA256 {digest}）')
    print(f'  Push：{"跳过" if args.no_push else "GitHub + Gitee"}')

    if not args.no_push and not args.dry_run:
        print('\n=== 1. 同步代码 ===')
        dirty = run(['git', 'status', '--porcelain'], capture=True)
        if dirty:
            raise SystemExit('工作区不干净，请先提交：' + dirty[:400])
        run(['git', '-c', 'http.proxy=' + PROXY, '-c', 'https.proxy=' + PROXY,
             'push', 'origin', 'main'])
        run(['git', 'push', 'gitee', 'main'])

    print('\n=== 2. GitHub Release ===')
    gh_url = ensure_github(version, apk, body, token('github.com'), dry_run=args.dry_run)
    print('\n=== 3. Gitee Release ===')
    gt_url = ensure_gitee(version, apk, body, token('gitee.com'), dry_run=args.dry_run)

    if not args.dry_run:
        print('\n=== 4. 下载校验 ===')
        asset_name = f'pickupmanager-{tag}.apk'
        verify(f'https://github.com/{GH_OWNER}/{GH_REPO}/releases/download/{tag}/{asset_name}',
               digest, use_proxy=True)
        verify(f'https://gitee.com/{GT_OWNER}/{GT_REPO}/releases/download/{tag}/{asset_name}',
               digest, use_proxy=False)
        print('\n=== 发布完成 ===')
        print(f'  GitHub：{gh_url}')
        print(f'  Gitee：{gt_url or f"https://gitee.com/{GT_OWNER}/{GT_REPO}/releases/tag/{tag}"}')
    else:
        print('\n[dry-run] 未做任何改动。')


if __name__ == '__main__':
    main()
