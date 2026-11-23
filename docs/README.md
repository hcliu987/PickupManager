# 文档目录

仓库的文档分两处：根目录的 `README.md` 面向使用者与贡献者，本目录放展开内容。

| 文件 | 内容 |
|---|---|
| [`KNOWN_ISSUES.md`](KNOWN_ISSUES.md) | 已知问题与未完成功能的完整明细（README 第 2、4 节的展开） |
| [`screenshots/`](screenshots/) | README「界面」一节引用的 5 张截图（作者手机实拍，小区名已打码） |
| [`audit/`](audit/) | 审计与验证记录：缺陷台账、逐轮依据、无法在仓库内验证的项 |

## audit/ 索引

| 文件 | 内容 |
|---|---|
| `00-defect-ledger.md` | 缺陷总表（编号、状态、所在轮次与依据） |
| `01-desensitization.md` | 敏感信息清理：范围、方法与核查方式 |
| `02-parser.md` | 解析层缺陷与修复依据 |
| `03-data-consistency.md` | 数据一致性（取件码归一、留档键、地址标签） |
| `04-ui-and-deadcode.md` | 界面可用性缺陷与死代码清理 |
| `05-security-and-license.md` | 安全面、隐私承诺一致性与许可 |
| `06-docs-and-verification.md` | 文档与校验工具本身的问题（含校验器加固） |
| `99-final-report.md` | 汇总报告 |
| `99-unverifiable.md` | 仓库内无法验证的项（逐条给出原因） |
| `CROSS-VERIFICATION.md` | 外部交叉复核的输入与结论 |

这些文件记录的是**核查过程**，其中的数字与 `文件:行号` 引用由
[`tools/check_doc_refs.py`](../tools/check_doc_refs.py) 在 CI 中校验。
