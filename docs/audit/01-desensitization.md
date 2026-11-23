# R1 — 脱敏审计（工作树 / 历史对象库 / 二进制元数据）

**本轮目标**：回答一个问题 —— 把这个仓库推到公开托管之后，一个陌生人能拿到哪些真实信息？
**结论**：能拿到。而且拿到的不是"疑似"，是**作者本人手机上的真实取件码和真实小区名**。
**处理**：已修，并留下可复现的验证步骤。

| 条目 | 内容 | 状态 |
|---|---|---|
| B-01 | git 对象库里的真实取件码 / 小区名 | **已修** |
| H-05 | `.gitignore` 兜底规则写成 `/*.ext`（只匹配仓库根） | **已修** |

---

## 1. 为什么"删掉那一行"不算修

这是本轮唯一一条阻断级缺陷，值得把机理写清楚，否则下一轮还会有人踩：

`git` 的存储模型是内容寻址。执行

```
git commit -am "删掉含真实取件码的注释"
```

之后，工作树里确实看不到那行字了。但 `HEAD~1` 指向的旧 commit 仍然完整存在，
它引用的旧 tree 仍然完整存在，旧 tree 里那个旧 blob 仍然**一字不差地存在**。
只要任何一个可达对象引用着它，它就不会被回收。于是：

```
git show 2f88f4d^:app/src/main/java/.../RuleBasedPickupParser.kt   # 直接读回真值
git log -p --all -S'<真实值>'                                        # 或者干脆搜出来
```

**仓库自带的两个检查器都读不到这里**：一个读 `README.md`，一个读 `app/src` 下的源文件。
它们扫的是工作树，而 `.git/objects` 是一个用户不可见的、结构完全不同的数据库。
CI 上跑再多次 `check_doc_refs.py` 也不会碰它。这正是"检查器通过 ≠ 没有泄露"的标本。

所以修复必须在**对象层**做，不是在工作树层做。

## 2. 修法（以及为什么不用 `git filter-repo`）

标准答案现在是 `git filter-repo`，它更快也更不容易出错。本轮用的是
`git filter-branch --tree-filter`，原因很具体：**`filter-repo` 不在本机，且本机网络
无法访问 `github.com`（代理只放行 `api.github.com`），装不上。**
`filter-branch` 是 git 自带的，不需要额外依赖 —— 对"别人拿到仓库后能不能重跑一遍"
这件事来说，自带反而是优点。取舍写在这里，供后续选择。

实际执行的脚本：`tools/audit/redact_history.py`（`--tree-filter` 调用）。

替换表（**刻意选择与作者自己的脱敏约定一致的哨兵值**，避免引入第二套风格）：

| 真实值（出现位置） | 替换为 | 说明 |
|---|---|---|
| `C<1位>-<1位>-<5位>`（只写形状；原值不入本文档） | `C4-6-11111` | 真实取件码，出现在解析器注释与测试夹具里 |
| 上面那条的 5 位子串 | `11111` | 上面那条的子串（单独出现于另一处注释） |
| 一个真实小区名（原值不入本文档） | `示例小区` | 真实小区名，出现在地址提取注释与测试夹具里 |

完整命令序列（按依赖顺序，每一步都不能省）：

```bash
git filter-branch --force --tree-filter \
    'python tools/audit/redact_history.py' --tag-name-filter cat -- --all

# 下面四步是把旧对象真正"弄死"，缺任何一步都会残留：
git update-ref -d refs/original/refs/heads/main
rm -rf .git/refs/original
git reflog expire --expire=now --all
rm -rf .git/logs
git gc --prune=now --aggressive
```

**踩过的坑（写下来省下一次）**：`filter-branch` 跑完会打印
`WARNING: Ref 'refs/heads/main' is unchanged`。
这条信息**不代表失败** —— 当替换发生在文件内容里而不改变 tree 的哈希
结构时，git 仍会重写 commit（author/committer/tree 的父链变了），
但它的措辞会让人以为整轮白跑。判断依据不是这行 warning，而是
`git rev-parse HEAD` 前后是否变化（本轮：`48b36ee` → `580f426`）。

## 3. 验证（`docs/audit/README.md` 里那条命令的可读版本）

```
$ git cat-file -e 68e242a && echo 存在 || echo 已清除      # 旧初始提交
已清除
$ for s in 4aaba32 3819278 d94c4a6 48b36ee; do ...        # 其余四个旧提交
已清除 / 已清除 / 已清除 / 已清除

$ git log --all --oneline -S'<真实值>'
（无输出）

$ git rev-list --all | xargs -n1 git grep -c '<真实值>'
（无输出）

$ ls .git/refs/original
ls: cannot access '.git/refs/original': No such file or directory

$ git reflog
f3d7ef0 HEAD@{0}: commit: audit: 第二轮对抗审计的基线与缺陷总表（56 条待修）
                          ← reflog 里只剩重写之后的提交

$ git show 2f88f4d:app/src/main/java/.../RuleBasedPickupParser.kt | grep -o 'C[0-9]*-[0-9]*-[0-9]*' | sort -u
C12-3-45678
C4-6-11111        ← 重写后的第一个提交里已经只有哨兵值
```

`tools/audit/scan_history.py` 把上面这套动作固化成了**一条命令**，并且会枚举
全部 6 个提交、246 个唯一 blob，逐个 blob 正则扫一遍 —— 也就是说它扫的不是
"工作树像不像干净的"，而是"对象库里还有没有"。

## 4. 顺带发现：扫描器自身的两个假阴性

在第一轮跑 `scan_history.py` 时，它报出 `RuleBasedPickupParser.kt:1689`，
而该文件只有 905 行。**排查结论不是解析错位，而是行号口径**：

```
$ git cat-file -s 219e2508
40823
$ git cat-file blob 219e2508 | wc -l
905
$ python -c "... d.count(b'\r\n'), d.count(b'\r') ..."
LF 905  CR 1810  CRLF 905   ⇒ 这个历史 blob 的行尾是 \r\r\n
```

早期某次提交把已经带 CRLF 的文件又做了一次 CRLF 转换（`.gitattributes`
是后加的，之前的提交不受 `text=auto` 约束）。`str.splitlines()` 会额外在
**孤立 `\r`** 处断行，于是 905 行被算成 1810 行，报出根本不存在的位置。

这一条本身不是泄露，但它是**审查记录可信度**问题：行号错了，人就无法按图索骥。
所以 R1 一并改掉了，同时补了两处会导致"静默漏扫却报通过"的脆弱点：

1. `--batch` 遇到取不到的 object 会输出 `<sha> missing`，旧代码在这里 `break`
   ⇒ 后面所有 blob 全部丢失、扫描照样报"✓ 没有命中"。现在改为记账 + 非零退出码。
2. 增加 `--batch-check` 交叉核对：`<sha> blob <size>` 的 size 与读回长度
   逐条比对，不等即判为解析失同步，退出码 2（"本次结果不可信"）。
   当前 246/246 全部相等。

## 5. 工作树与图片

工作树扫描（`tools/audit/scan_sensitive.py`）结果：

- **BLOCK 级命中 2 处，均为白名单内的哨兵值**：`13800138000`
  （中国移动测试号段专用示例号）出现在 `docs/audit/README.md` 与扫描器自身的规则表里。
- **二进制 / 图片元数据：0 处**。仓库内 12 个 PNG 全部读过块结构，
  没有 `tEXt`/`iTXt`/`eXIf` 块，没有软件名、没有 GPS、没有 Windows 用户路径。
- **签名与密钥：0 个**。`git ls-files` 里不存在 `.jks/.keystore/.p12/.pem/.key/.bks`、
  不存在 `local.properties`、不存在 `*.apk/*.aab`、不存在任何文件名含
  `secret/credential/token` 的条目。

### 5.1 提交元数据（第三种扫描器）

前三类扫描读的都是**文件**。但提交者姓名/邮箱既不在工作树、也不在历史 blob 里 ——
它只存在于 commit 对象。`git log --format='%an <%ae>'` 一条命令就能拿到，
而且它跟着 `git clone` 走。这一条单独用 `tools/audit/scan_commit_identity.py` 兜：

```
提交数=6
作者身份=2  提交者身份=2
  ✓ 全部提交身份都在白名单内
出现过的提交时区偏移：['+0800']
  [INFO] 时区偏移可推断作者地域。
本地配置里的 remote：
  ✓ 未配置任何 remote（不会误推到备份目录）
```

- 六个提交全部是 `PickupManager Author <author@example.invalid>` /
  `PickupManager Audit <audit@example.invalid>`，`example.invalid` 是 RFC 2606
  保留域，不可能指向真人。
- **`+0800` 保留不动**：它确实能推断出"东八区"，但（a）统一成 `+0000`
  需要**再重写一次全历史**，会让本轮已验证的六个 SHA 全部失效、
  使上文的证据链作废；（b）一个中文 App 的作者身处东八区是公开信息，
  不构成新的信息泄露。列为**已知且接受**项，而不是"已解决"。
- **`remote` 已被删除**。原先存在 `origin = file:///E:/PickupManager`，
  它会把作者本机盘符与目录结构一起公开，更糟的是下一次 `git push` 会
  静默推到备份目录。原 URL 已记到仓库外的
  `.workbuddy/work/origin-url.txt`，**不入库**。脚本对任何指向
  `file://`、盘符、`192.168./10./172.16-31.` 的 remote 都会判 BLOCK。

## 6. 修 H-05：兜底规则只匹配仓库根

`.gitignore` 原先的"兜底"段是：

```
/*.json
/*.txt
/*.csv
/*.db
```

前导斜杠在 gitignore 语法里的含义是**锚定到仓库根**，不是"任意层级"。
也就是说 `/*.txt` 只能拦住仓库根下的 txt。而这条规则自己的注释里写的威胁模型是：
"把一份短信导出丢进来别被提交"。用户导出短信时文件落在哪？多半是
`docs/`、`app/src/main/assets/`、`tools/` —— 一个都对不上。

改成不带前导斜杠 + 白名单：

```
*.json
*.txt
*.csv
*.db
*.sqlite
*.html
*.log
*.bak
*.orig
!app/schemas/**/*.json     ← Room 的 exportSchema 产物必须入库，否则迁移无法回归测试
secrets.env
env
*.env.*
secrets/
*_secret*
*_token*
*credential*
```

`tools/audit/check_gitignore_depth.py` 把这件事变成一个可执行的断言：
20 条「必须被忽略」的子目录路径 + 7 条「必须未被忽略」的跟踪路径（Room schema、
gradle 脚本、源码），用 `git check-ignore --stdin -z -v` 批量判定。

**这里也有一个坑**：`git check-ignore --stdin` 在 Windows 上用换行分隔的输入
会静默失败（退出码 1、stdout 和 stderr 都是空的），只有 `-z`（NUL 分隔）可用；
而且不能用退出码判断白名单 `!` 规则 —— `-v` 对 `!` 也会返回 0。
正确做法是解析 `-z -v` 的 4 元组输出
（`<来源>\0<行号>\0<模式>\0<路径>\0`），按模式是否以 `!` 开头判断。

当前结果（2.6s）：**20/20 忽略 ✓、7/7 未忽略 ✓、无跟踪文件被误伤**。

## 7. 本轮未改的、以及为什么不改

诚实记录，避免"看起来全清了"：

| 项 | 命中 | 决定 | 理由 |
|---|---|---|---|
| `阳/幸福/通用小区` 类示例名 | 4 个文件 | **已改**，统一为 `示例小区` | 与作者既有约定一致，消除"原始真名有没有以近似形式漏出"的疑问 |
| `3 号楼` / `101 室` / `12室` / `301室` | `Preview.kt`、`RuleBasedPickupParser.kt` 注释、`README.md` | **保留** | 无小区名、无城市，属通用门牌示例，不具定位性；改动会动到解析器注释里的正则说明示例，收益小于风险 |
| `com.shanqiu.pickupmanager` 包名 | 全仓 | **保留，但列为需决策项** | `shanqiu` 是作者名的拼音。开源仓库里作者署名是正常且必要的；但若作者要匿名开源，改 `namespace`+`applicationId` 是机械替换、代价是已装用户失去升级路径。**这是产品决策，不是审计能替作者拍的**，故在总结报告里单列 |
| 历史里那个 `\r\r\n` 的 blob | 历史对象库 | **保留** | 纯空白字符差异，无信息内容 |

## 8. 复现本轮全部结论

```bash
python tools/audit/scan_sensitive.py .        # 工作树 + 二进制元数据
python tools/audit/scan_history.py .          # 全历史 246 个唯一 blob（退出码 0/1/2）
python tools/audit/check_gitignore_depth.py . # .gitignore 深度断言
python tools/audit/scan_commit_identity.py .  # 提交者姓名/邮箱（见 R5）
```

四个脚本的退出码语义统一：`0` = 通过，`1` = 发现未白名单命中，
`2` = 扫描本身不可信（必须当失败处理，不能当通过）。
