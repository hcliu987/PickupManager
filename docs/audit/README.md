# 审计与修复记录（交叉验证入口）

这个目录是给**下一位审查者**用的。它存在的唯一目的是：让任何一个拿到这份仓库的人，
不必读代码就能知道**改过什么、为什么改、怎么验证没改错**；然后**自己独立复现**一遍。

> 立场声明：本目录里的每一条结论都是**主张**，不是事实。
> 请把下表的每一行都当作待验证项，用「验证命令」列自己跑一遍。
> 本目录的作者在写这些字的时候，已经知道"文档比代码乐观"是这类项目最不该有的缺陷，
> 所以这里的每条都配了可执行的复现命令 —— 跑不出来，就是这条记录在说谎。

## 目录

| 文件 | 内容 |
|---|---|
| `00-defect-ledger.md` | **缺陷总表**：全部缺陷的 ID、严重度、位置、状态、对应提交 |
| `01-desensitization.md` | 第 1 轮：脱敏与仓库卫生（含 git 历史重写） |
| `02-parser.md` | 第 2 轮：解析引擎正确性 |
| `03-data-consistency.md` | 第 3 轮：数据层与三条采集通道一致性 |
| `04-ui-and-deadcode.md` | 第 4 轮：UI 缺陷与死代码清理 |
| `05-security-and-license.md` | 第 5 轮：平台安全面与许可证/隐私承诺一致性 |
| `06-docs-and-verification.md` | 第 6 轮：README 数字与引用收口（H-14/M-05）、校验器加固、工具挂进 CI；**§9–§14 是第 7 轮（R7）：交叉验证另一条审计线，含 B-02** |
| `99-unverifiable.md` | **无法验证项**与遗留风险（诚实清单，不外推） |
| `99-final-report.md` | **最终报告**：七轮之后的结论、证据链、仍然未修的清单，以及"能不能开源"的答案 |

## 提交身份约定

本轮修复的提交统一使用：

```
PickupManager Audit <audit@example.invalid>
```

原始 5 个提交（`Initial release` 起）的作者是 `PickupManager Author <author@example.invalid>`。
两者都是**匿名化身份**，仓库里不含任何真实姓名或邮箱 —— 可用下列命令复核：

```bash
git log --format='%h %an <%ae>' | sort -u
```

## 三条硬规则（后续提交请继续遵守）

1. **任何真实数据不得进入仓库** —— 包括工作树、提交信息、注释、测试用例、图片元数据，
   以及 **git 对象库**。最后一项最容易被忘：删掉一行只是新增一个提交，旧对象永久可寻址。
2. **缺陷必须一条一行地记录**，不接受"已修复"这种没有位置和验证方式的说法。
3. **测试用例、注释、预览夹具只允许用虚构数据**。本仓库统一使用：`示例小区`、
   `13800138000`、`18712345678`、`4001234567`、`C4-6-11111`、`AB1234`、
   `author@example.invalid`。任何来自真实短信/真实驿站的名字、小区名、单元门牌、手机号、
   运单号，一律视为 **BLOCK** 级缺陷。
   > 上面这串值不是"随手挑的样例"，而是 `tools/audit/scan_sensitive.py` 的
   > `PLACEHOLDER_VALUES` **按值豁免**的那几个。**只有这几个值被豁免** ——
   > 换任何一个别的号码、邮箱、小区名，扫描器都会以退出码 1 失败。
   > 想扩这个名单，就得往那张表里加一条**带见证值**的条目，并且它的见证值必须真的会被
   > 规则表产出（`tools/audit/test_scan_sensitive.py` 会机械核对这一点）。

## 复现全部检查

以下命令与 CI（`.github/workflows/android.yml`）跑的完全一致（A–F 共 12 条检查命令，
另加 G 的真编译；CI 在构建之前跑的正是这 12 条）：

```bash
# A. 四个检查器各自的回归测试（50 个用例）
python3 tools/audit/test_kotlin_lexer.py            # 17
python3 tools/audit/test_check_icon_imports.py      #  9
python3 tools/audit/test_check_doc_refs.py          #  4（含 11 个突变）
python3 tools/audit/test_scan_sensitive.py          # 20

# B. 工作树 + 二进制 + 图片元数据脱敏扫描
python3 tools/audit/scan_sensitive.py .

# C. 全部 git 对象（所有提交 × 所有文件）脱敏扫描 —— 这一条是最容易被跳过的一条
python3 tools/audit/scan_history.py .

# C2. 同一批对象再过一遍**精确值判据**（R7 / B-02）。清单由本机私有语料派生、不入库，
#     所以公开 CI 上这一步会打印 SKIPPED —— **`git push` 之前必须在本机跑一次**。
python3 tools/audit/scan_history.py . \
    --denylist "$PRIV/deny_A.local.txt" --advisory "$PRIV/deny_B.local.txt"

# D. 提交元数据（作者邮箱、remote 是否暴露真实身份）
python3 tools/audit/scan_commit_identity.py .

# E. 死图标 import、.gitignore 纵深探针、缺陷总表结构
python3 tools/audit/check_icon_imports.py .
python3 tools/audit/check_gitignore_depth.py .
python3 tools/audit/check_ledger.py .        # 总表能否被机械读出（最终报告的统计来自它）

# F. 仓库原有的两个检查器
python3 tools/check_doc_refs.py
python3 tools/check_log_privacy.py

# G. 真编译 + 真跑单元测试（这是唯一能验"代码真的能跑"的检查）
./gradlew --offline :app:testDebugUnitTest
```

> **跑 C 之前先确认历史是完整的**：浅克隆（`fetch-depth: 1`）下 `scan_history.py`
> 会只看见一个提交、却照样打印 `✓`。CI 里已显式设了 `fetch-depth: 0`。

全部命令的预期输出写在各自轮次的文档里；**出现任何与预期不符的输出，就是一条未修复的缺陷**。
逐条期望值（含退出码）集中在 `99-final-report.md` 的"证据链"一节。
