#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
码上取 PickupManager —— 一键发版脚本

做一件事：把「升版本号 → 同步 version.json → 构建签名 → 上传 Gitee latest」串成一条命令，
避免"改了功能却忘了升版本号，导致自动更新永远不提示"的坑。

用法：
    python3 tools/release.py                          # 自动递增小版本（1.1 → 1.2，code 2 → 3）
    python3 tools/release.py --version 2.0            # 指定版本名（code 自动 +1）
    python3 tools/release.py --changelog "新功能A\\n修复B"  # 指定更新说明
    python3 tools/release.py --dry-run                # 只预览要改什么，不落盘/不构建/不上传
    python3 tools/release.py --skip-upload            # 升版本 + 打包签名，但不上传 Gitee

前置条件：
    - 本机 Android SDK（local.properties 的 sdk.dir）与 build-tools
    - Gitee 私人令牌存在 macOS 钥匙串（security find-internet-password -s gitee.com -w）
    - gradlew 可执行

隐私：令牌只从钥匙串读取，不写入任何文件、不打印明文。
"""
import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / 'app' / 'build.gradle.kts'
VERSION_JSON = ROOT / 'version.json'
LOCAL_PROPS = ROOT / 'local.properties'

GITEE_API = 'https://gitee.com/api/v5'
GITEE_OWNER = '884024720'
GITEE_REPO = 'pickup-manager'
LATEST_RELEASE_TAG = 'latest'
APK_ASSET_NAME = 'pickupmanager.apk'

KEYSTORE = Path.home() / '.android' / 'debug.keystore'
KS_ALIAS = 'androiddebugkey'
KS_PASS = 'android'


def run(cmd, *, capture=False, check=True, env=None):
    """执行命令；capture=True 返回 stdout 文本。"""
    printable = ' '.join(str(x) for x in cmd)
    print(f'  $ {printable}')
    r = subprocess.run(
        [str(x) for x in cmd], cwd=ROOT, capture_output=capture, text=True, env=env
    )
    if check and r.returncode != 0:
        if capture:
            print(r.stdout)
            print(r.stderr, file=sys.stderr)
        raise SystemExit(f'命令失败（退出码 {r.returncode}）：{printable}')
    return r.stdout.strip() if capture else r


def read_versions():
    txt = GRADLE.read_text(encoding='utf-8')
    m_code = re.search(r'versionCode\s*=\s*(\d+)', txt)
    m_name = re.search(r'versionName\s*=\s*"([^"]+)"', txt)
    if not (m_code and m_name):
        raise SystemExit('无法从 build.gradle.kts 解析 versionCode / versionName')
    return int(m_code.group(1)), m_name.group(1)


def bump_version(version_code, version_name, requested):
    """返回 (新 code, 新 name)。"""
    if requested:
        parts = requested.split('.')
        if not all(p.isdigit() for p in parts):
            raise SystemExit('--version 必须是形如 1.2 或 2.0 的数字点分格式')
        return version_code + 1, requested
    # 自动：小版本 +0.1（1.1 → 1.2；1.9 → 1.10）
    parts = version_name.split('.')
    if len(parts) == 2 and parts[0].isdigit() and parts[1].isdigit():
        return version_code + 1, f'{parts[0]}.{int(parts[1]) + 1}'
    return version_code + 1, f'{version_name}.1'


def write_versions(new_code, new_name):
    txt = GRADLE.read_text(encoding='utf-8')
    txt, n1 = re.subn(r'versionCode\s*=\s*\d+', f'versionCode = {new_code}', txt, count=1)
    txt, n2 = re.subn(r'versionName\s*=\s*"[^"]+"', f'versionName = "{new_name}"', txt, count=1)
    if n1 != 1 or n2 != 1:
        raise SystemExit('写入版本号失败：匹配数量异常')
    GRADLE.write_text(txt, encoding='utf-8')


def write_version_json(new_code, new_name, changelog, apk_url):
    data = {
        'versionCode': new_code,
        'versionName': new_name,
        'changelog': changelog,
        'apkUrl': apk_url,
    }
    VERSION_JSON.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8'
    )


def get_token():
    """从 macOS 钥匙串读 Gitee 令牌；失败则报错退出。"""
    r = subprocess.run(
        ['security', 'find-internet-password', '-s', 'gitee.com', '-w'],
        capture_output=True, text=True,
    )
    if r.returncode != 0 or not r.stdout.strip():
        raise SystemExit('无法从钥匙串读取 Gitee 令牌（security find-internet-password -s gitee.com -w）')
    return r.stdout.strip()


def api(method, path, token, payload=None, params=None):
    """调用 Gitee API；返回解析后的 JSON（204 返回 None）。"""
    url = f'{GITEE_API}{path}'
    if params:
        url += '?' + urllib.parse.urlencode(params)
    headers = {'Authorization': f'token {token}', 'Accept': 'application/json'}
    body = None
    if payload is not None:
        data = dict(payload)
        data['access_token'] = token
        body = json.dumps(data, ensure_ascii=False).encode('utf-8')
        headers['Content-Type'] = 'application/json;charset=UTF-8'
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        detail = e.read().decode('utf-8', errors='replace')[:300]
        raise SystemExit(f'Gitee API 失败 {method} {path}: HTTP {e.code} {detail}')


def upload_asset(release_id, token, file_path):
    """multipart 上传 APK 到指定 Release。

    附件名必须固定为 APK_ASSET_NAME（pickupmanager.apk），与 App 内直链、version.json 一致。
    Gitee 用 multipart 的 filename 字段作为附件名，与本地临时文件名无关，因此这里显式指定。
    """
    url = f'{GITEE_API}/repos/{GITEE_OWNER}/{GITEE_REPO}/releases/{release_id}/attach_files'
    boundary = '----PickupReleaseBoundary'
    name = APK_ASSET_NAME
    data = file_path.read_bytes()
    parts = []
    parts.append(f'--{boundary}\r\n'.encode())
    parts.append(
        f'Content-Disposition: form-data; name="file"; filename="{name}"\r\n'
        f'Content-Type: application/vnd.android.package-archive\r\n\r\n'.encode()
    )
    parts.append(data)
    parts.append(f'\r\n--{boundary}\r\n'.encode())
    parts.append(f'Content-Disposition: form-data; name="access_token"\r\n\r\n{token}\r\n'.encode())
    parts.append(f'--{boundary}--\r\n'.encode())
    body = b''.join(parts)
    req = urllib.request.Request(
        url, data=body, method='POST',
        headers={'Content-Type': f'multipart/form-data; boundary={boundary}',
                 'Accept': 'application/json'},
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read())


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def download_verify(url, expected):
    """国内直连（不走代理）下载并校验 SHA256。"""
    # 清掉代理环境变量，模拟国内直连
    env = {k: v for k, v in os.environ.items()
           if k.lower() not in ('http_proxy', 'https_proxy', 'all_proxy')}
    proxy_handler = urllib.request.ProxyHandler({})
    opener = urllib.request.build_opener(proxy_handler)
    with opener.open(url, timeout=120) as resp:
        data = resp.read()
    got = hashlib.sha256(data).hexdigest()
    if got != expected:
        raise SystemExit(f'下载校验失败：{got} != {expected}')
    return len(data)


def main():
    ap = argparse.ArgumentParser(description='码上取一键发版')
    ap.add_argument('--version', help='指定版本名，如 2.0；不传则自动小版本 +0.1')
    ap.add_argument('--changelog', help='更新说明（用 \\n 换行）')
    ap.add_argument('--dry-run', action='store_true', help='只预览，不落盘/不构建/不上传')
    ap.add_argument('--skip-upload', action='store_true', help='只升版本+打包，不上传 Gitee')
    args = ap.parse_args()

    old_code, old_name = read_versions()
    new_code, new_name = bump_version(old_code, old_name, args.version)
    changelog = (args.changelog or f'· 发布 v{new_name}').replace('\\n', '\n')
    apk_url = (f'https://gitee.com/{GITEE_OWNER}/{GITEE_REPO}/releases/'
               f'download/{LATEST_RELEASE_TAG}/{APK_ASSET_NAME}')

    print('=== 发版预览 ===')
    print(f'  版本：v{old_name} (code {old_code})  →  v{new_name} (code {new_code})')
    print(f'  更新说明：{changelog!r}')
    print(f'  APK 直链：{apk_url}')
    if args.dry_run:
        print('\n[dry-run] 未做任何改动。')
        return

    # 1. 升版本号 + 同步 version.json
    print('\n=== 1. 升版本号 ===')
    write_versions(new_code, new_name)
    write_version_json(new_code, new_name, changelog, apk_url)
    print(f'  ✓ build.gradle.kts → versionCode={new_code}, versionName="{new_name}"')
    print(f'  ✓ version.json 已同步')

    # 2. 构建 release
    print('\n=== 2. 构建 release APK ===')
    run([ROOT / 'gradlew', ':app:assembleRelease', '--console=plain', '-q'])
    unsigned = ROOT / 'app/build/outputs/apk/release/app-release-unsigned.apk'
    if not unsigned.exists():
        raise SystemExit(f'未找到 unsigned APK：{unsigned}')

    # 3. zipalign + apksigner
    print('\n=== 3. 对齐并签名 ===')
    sdk_dir = None
    for line in LOCAL_PROPS.read_text(encoding='utf-8').splitlines():
        if line.startswith('sdk.dir='):
            sdk_dir = Path(line.split('=', 1)[1])
            break
    if not sdk_dir:
        raise SystemExit('local.properties 中无 sdk.dir')
    bts = sorted((sdk_dir / 'build-tools').iterdir(), key=lambda p: p.name)
    if not bts:
        raise SystemExit(f'无 build-tools：{sdk_dir / "build-tools"}')
    bt = bts[-1]
    aligned = Path('/tmp') / f'pickup_aligned_{new_code}.apk'
    run([bt / 'zipalign', '-f', '-p', '4', unsigned, aligned])
    run([bt / 'apksigner', 'sign',
         '--ks', KEYSTORE, '--ks-key-alias', KS_ALIAS,
         '--ks-pass', f'pass:{KS_PASS}', '--key-pass', f'pass:{KS_PASS}',
         '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true',
         '--v3-signing-enabled', 'true', aligned])
    run([bt / 'apksigner', 'verify', aligned])
    final_name = f'码上取-v{new_name}-release.apk'
    final = ROOT.parent / final_name
    shutil.copy2(aligned, final)
    digest = sha256(final)
    print(f'  ✓ 已签名：{final.name}')
    print(f'  ✓ SHA256: {digest}')

    if args.skip_upload:
        print('\n[--skip-upload] 未上传 Gitee。')
        return

    # 4. 重建 Gitee latest Release + 上传新包
    # 为什么不直接覆盖同名附件：Gitee 的 release assets 不返回 id，同名附件会累积，
    # 直链会指向"最先上传"的那个旧包。唯一可靠的做法是删掉整个 latest Release 再重建，
    # 这样直链固定不变、内容永远是本次新包。
    print('\n=== 4. 重建 Gitee latest Release 并上传 ===')
    token = get_token()
    old = api('GET', f'/repos/{GITEE_OWNER}/{GITEE_REPO}/releases/tags/{LATEST_RELEASE_TAG}', token)
    if old and old.get('id'):
        api('DELETE', f'/repos/{GITEE_OWNER}/{GITEE_REPO}/releases/{old["id"]}', token)
        print(f'  ✓ 已删除旧 latest Release (id={old["id"]})')
    fresh = api('POST', f'/repos/{GITEE_OWNER}/{GITEE_REPO}/releases', token, {
        'tag_name': LATEST_RELEASE_TAG,
        'target_commitish': 'main',
        'name': '最新版本（始终指向最新 APK）',
        'body': f'码上取 v{new_name}。App 内「检查更新」固定下载此处，由 tools/release.py 自动维护。',
        'prerelease': False,
    })
    if not fresh or not fresh.get('id'):
        raise SystemExit('重建 latest Release 失败')
    upload = upload_asset(fresh['id'], token, aligned)
    print(f'  ✓ 新 Release id={fresh["id"]}，附件 {upload.get("name")} (id={upload.get("id")})')

    # 5. 国内直连下载校验
    print('\n=== 5. 国内直连下载校验 ===')
    size = download_verify(apk_url, digest)
    print(f'  ✓ 下载一致：{size} 字节，SHA256 {digest}')

    # 6. 提交代码
    print('\n=== 6. 提交版本改动 ===')
    run(['git', 'add', 'app/build.gradle.kts', 'version.json'])
    run(['git', 'commit', '-m', f'chore: 发版 v{new_name} (code {new_code})'], check=False)
    print('\n=== 发版完成 ===')
    print(f'  安装包：{final}')
    print(f'  下载直链：{apk_url}')
    print('  下一步（按需）：git push origin main && git push gitee main')


if __name__ == '__main__':
    main()
