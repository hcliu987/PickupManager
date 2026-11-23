# 已知问题与未完成功能明细

本文件是 README 第 2 节「未完成与实验性功能」与第 3 节「已知问题清单与修复状态」的展开。README 保留结论与状态表，
这里放每一条的完整说明与代码定位。条目的编号与 README 的状态表一一对应。

- 「实验性与未完成功能」= 界面上可用但底层未接入或不完整的功能
- 「已知问题清单」= 缺陷与修复记录，含未修项与不修项

---

## 一、实验性与未完成功能

### 1. 规则匹配 / 学习模式（实验性：开发到一半，未接入解析）

**入口**：取件列表右上角菜单 → 「规则匹配」。

该功能当前为实验性状态，完成度约一半：规则的保存与读取接口已存在，但既不持久化，也未被生产解析器调用。因此学习结果不影响任何解析行为。

界面包含四项能力，前三项已接入生产路径，第四项未接入：

| 功能 | 状态 | 依据 |
|---|---|---|
| 粘贴一条短信 → 预览解析结果（取件码 / 地址 / 快递公司 / 置信度） | ✅ 正常 | 直接调用生产解析器 `RuleBasedPickupParser.parse()` |
| 确认或编辑后写入取件条目 | ✅ 正常 | `ComposePickupViewModel.addPickupItemWithDedup()`，编辑模式下走 `overwritePickupItem()` |
| **纠正地址**（把识别错的驿站名改成你想要的） | ✅ **正常且持久** | `RuleBasedPickupParser.saveAddressOverride()` 写入 SharedPreferences 的 `address_overrides`，之后每次解析都会查表替换，重启不丢 |
| 「学习模式」学出的规则 / 「手动创建」的自定义规则（含正则表达式） | ⚠️ **未接入**（实验性） | 见下 |

#### 未接入的具体位置

保存动作调用的是 `PickupCodeExtractorV3.CustomRuleEngine.addRule(rule)`（`RuleMatchingScreen.kt:624, 1137`），链路上有三个各自独立的断点：

1. **不持久化** —— `CustomRuleEngine.customRules` 是一个普通内存 `mutableListOf`（`PickupCodeExtractorV3.kt:333`），应用进程一重启就全空。
2. **生产解析器不看它** —— 实际在跑的 `RuleBasedPickupParser` 里没有任何一处读取这张表；它的规则来自内置的签名-格式映射表和上面那条**真正有效的地址覆盖表**。
3. **唯一的读取者不在调用链上** —— 会读它的是 `extractWithRules` 的调用点 `PickupCodeExtractorV3.kt:476`，位于 `extract()` 内，而 `extract()` 在生产代码里没有调用点。
4. 附带：`CustomRuleEngine.loadDefaultRules()` 只在 `initialize()` 里调用，而 `initialize()` 生产也从未调用。

**结论：保存的规则不会改变之后任何一条短信的解析结果。** 界面上"学习成功""置信度 90%"一类反馈，描述的是解析器对**当前这条**短信的判断，不代表规则已被系统保存并生效。

#### 使用说明

- 该功能在完成接入前不会改变解析结果，与使用时长无关。
- 当前唯一能改善解析结果的用户操作是**纠正地址**。取件码识别错误暂时无法通过界面修正。
- 如需支持某类短信，请在 Issue 中提供**脱敏后的短信格式**（取件码、楼栋号、电话替换为假值，保留结构与引导词），以便加入内置规则表。

#### 若继续开发，前置条件

- 规则的持久化存储（Room 表或结构化 SharedPreferences）
- 与内置签名-格式模板的**优先级与冲突**处理（否则用户规则可能让整体解析变差）
- 用 `RuleBasedPickupParserAccuracyEval` 建立前后对比基线，确保改规则不降准确率

---

### 2. `notes` 字段 —— 有列、有管道、无入口

取件记录有 `notes`（备注）列，一路传到 `ComposePickupViewModel` 的写入参数（`overwritePickupItem(notes = …)`），但**没有任何界面可以输入它**，因此实际永远是空串。属于"给未来留的接口"，不是 bug。

---

### 3. `phoneNumber` 字段 —— 只有一条路径会填

`PickupSmsParserV2`（兜底解析器）会从短信里的快递员电话填入 `phoneNumber`，但**主用的规则引擎路径不填**，并且全应用没有任何地方展示它。所以现在它是一个"偶尔有值、永远看不到"的字段。

---

### 4. 已移除的历史实验（列出以免你以为是隐藏功能）

- **本地 ONNX 模型推理层**（`ml/` 包、`onnxruntime` 依赖、`assets/` 模型与词表）：**当前版本未包含，属暂缓，不是废弃**。评估结论是它与正则匹配的成绩没有明显差距，缺少可信的留出集数据支持其更优；同时模型资产约 20 MB、推理开销更大，收益与代价不成比例。若后续有数据证明其优于现行正则方案，会重新评估并打包。评估过程见本文「二、已知问题清单与修复状态」第 3 条与 README 第 3 节的状态表。
- **应用内提醒通知**（新取件提醒、过期提醒及其两个通知渠道）：该链路从未真正被触发过，作者决定不做，已整套删除。老用户系统设置里遗留的空渠道会在下次启动时被主动清除。

---


---

## 二、已知问题清单与修复状态

**机型覆盖**：本条与其它需要真机的项，作者的验证范围**仅限于小米 / HyperOS 机型**（作者日常使用的设备全部是小米）。其它品牌的差异未做真机验证，遇到问题请提 Issue 并附机型与系统版本。

### 1. 通知监听通道解析成功却不入库（✅ 已修复）

修复前的 `sms/PickupNotificationListener.kt` 里：

```kotlin
private fun savePickupCode(result: PickupResult, senderNumber: String) {
    scope.launch {
        try {
            val repository = PickupRepository.getInstance(applicationContext)
            // 这里可以调用repository保存取件码
            // 具体实现取决于您的数据模型
            Log.d(TAG, "取件码已保存: ...")   // ← 却什么都没存
```

**当前状态**：该文件已重写为 `PickupSmsParserV2.parseSmsMultipleCodes()` + `PickupRepository.insertItemWithDedup()`，入库后触发小部件刷新，协程作用域加了 `SupervisorJob` 并在 `onDestroy` 取消，短信全文改为指纹。**第三轮又补了两处**：① `receiveTime` 改取通知上显示的**消息时间**（`Notification.when`，为 0 才退回 `postTime`），而不是"通知被 posted 的时间" —— 否则这条通道与广播通道对同一条短信会各存一个时间戳，互相认不出重复；② 补上了删除留档查询（`wasDeleted()`），否则用户删掉一条、同一条短信的通知再弹一次它就复活。**待真机验证。**

**为什么值得留这条通道**（修复前的影响）：在 MIUI 等系统回收短信权限、或用户拒绝授予 `READ_SMS` 的场景下，"改听通知"是官方文档里给出的替代路径 —— 而修复前它走完解析后什么都不留下，日志措辞却会让人误以为它工作了。

**同轮一并处理**：短信全文的 `Log.d` 改为 `privacyRef()` 指纹；协程作用域改 `SupervisorJob` 并在 `onDestroy()` 里 cancel；`SMS_PACKAGES` 白名单删掉 `com.heytap.mcs`（OPPO 的推送服务，不是短信应用）。

---

### 2. 「规则自学习」不影响解析

- `ui/components/RuleMatchingScreen.kt:624, 1137` 调用 `PickupCodeExtractorV3.CustomRuleEngine.addRule(rule)`
- `util/PickupCodeExtractorV3.kt:333` 的 `customRules` 是一个**普通内存 List**，进程重启即清空
- 唯一读取它的 `extractWithRules()` 只被 `PickupCodeExtractorV3.kt:476` 调用，而 `extract()` 不在生产调用链上
- 生产在用的 `util/RuleBasedPickupParser` 从不查询这张表

**影响**：用户在一个 1600 行的界面上学到的规则，对实际解析零作用。这既误导用户，也误导后来读代码的人。

**两条路**：(a) 让规则持久化并真正插进 `RuleBasedPickupParser` 的匹配流程；(b) 若判断该功能不值得维护，就把界面与相关类一起撤掉，README 里也不再提"自学习"。**当前状态（保留门面）是最差的。**

**处理结果（2026-09-19）：不删，但也不再隐瞒 —— 标注为实验性功能。** 作者决定留着它将来做完，同时新增 第 2 节「实验性与未完成功能」 逐条向使用者说明：这个界面里哪些是真的（预览解析、手动录入取件条目、**地址纠正 —— 真实持久化并生效**）、哪些是空的（自定义取件码规则）、使用中注意什么、以及要做完需要哪三步。README 的功能表与已知问题清单已同步标注。**剩下的正路是 (a)。**

> 对照：地址纠正是**真的**。`RuleBasedPickupParser.kt:49-96` 的 `address_overrides` 会持久化到 SharedPreferences 并在解析时被 `lookupAddressOverride()` 应用。

---

### 3. 本地 ONNX 模型推理层：当前版本未包含（⏸ 暂缓）

以下描述针对当前版本已不含的 `ml/` 包，保留用于说明评估过程与重启条件：

`assets/sms_ner.onnx`(104 KB) 与 `sms_classifier.onnx`(107 KB) 是以「外部数据」模式导出的 —— 权重在各自的 `.onnx.data`（10.5 MB / 11 MB）里，而这两个文件没有被放进 `assets/`。创建 session 那一步找不到外部权重就抛异常，被同一个 catch 兜住、把 `isAvailable` 置为 false，于是**静默退回纯规则引擎**。没有崩溃，也没有显式提示。（上面这些符号随 `ml/` 一起删了，本节只描述"当初为什么没生效"，所以不再给出仓库内引用。）

叠加的两个配置错误：

- 配置给 NER 的词表是上一代模型的，与新模型不配套
- 词表加载按整行做 key，不切分 `字符<TAB>索引` 两列格式，所以即便换对文件也拿不到正确映射
- 推理侧序列长度 64，而训练侧是 128

**影响**：宣传中的"四级兜底"实际只有两级。

**处理结果（2026-09-19）：当前版本不包含该推理层。**

决策依据是成本与收益不成比例，而不是「正则方案已经足够好」：接通它会让 assets 增加约 20 MB 并提高运行开销，而当时**没有可信证据表明模型强于正则匹配** —— `evaluation_results.json` 里那个 98.1% 是在训练集上测的（`train_model.py` 用 `样本_enhanced.json` 训练，而它由 `样本.json` 扩造；`evaluate_model.py`（在本地数据区，未随仓库发布）又拿同一份 `样本.json` 评测），并且 80/20 切分所用的 `random.shuffle` **没有固定随机种子**，留出集已无法复原。

当前版本因此少一个依赖，也少了一条缺少评测依据的推理路径。（release 包体从 4.68 MB 降到 2.90 MB 是**整轮清理的合计**，其中这一项占多少没有单独测过，口径见 README 第 6 节「构建与发布」。）

**重启条件：先用一批从未参与过任何训练的新标注样本，在真正的留出集上做对比评测；若结果显示优于现行正则方案，则重新接入并打包。** 直接复用旧 `样本.json` 得到的任何高分都不作数。

---

### 4. 过期记录会从界面上凭空消失

- 修复前 `ExpireCheckWorker` 会把到期记录的状态改写成 `2`（那个批量改写方法现已随本次修复一起删除）
- `ui/viewmodel/ComposePickupViewModel.kt:108-114` 只查询 `STATUS_PENDING(0)` 与 `STATUS_PICKED(1)`

**影响**：一条待取记录在到期后，会从"待取件"和"已取件"两个页签同时消失，且没有任何地方能再看到它 —— 而用户可能只是晚去了一天。同时 `PickupCard.kt` 里针对 `STATUS_EXPIRED` 的渲染分支不可达。

**修法**：要么让 ViewModel 增加过期页签 / 查询条件，要么取消 worker 的状态改写、改为纯展示层用 `isExpired()` 判断（`PickupItem.kt:72` 已有该逻辑）。后者改动面更小。

**处理结果（2026-09-19）：取后者。** `ExpireCheckWorker` 不再改写状态，过期只由展示层表达；全仓已确认没有任何地方再写入 `STATUS_EXPIRED`。同时新增 `MIGRATION_8_9` 把历史上被写成 `status = 2` 的记录回补成待取 —— 否则老用户已经"消失"的包裹不会因为升级了就自己回来。**真机上应能观察到：升级后"待取件"数量比升级前多（多出来的正是被回补的历史记录）；若一条都没多，说明迁移没跑到。**

---

### 5. 应用内通知：整条链路从来没有被触发过

`sms` 与 `receiver` 两处，比"忘了注册"更深一层：

- 旧的 `receiver/NotificationReceiver` 从未在 `AndroidManifest.xml` 注册
- 更关键的是：发通知的那个方法是**由这个没注册的 receiver 自己调用的**，而安排过期提醒的两个方法**在整个工程里没有任何调用点**
- 于是这条链路是自指的、永远启动不了的
- 而通知渠道创建每次启动都执行 —— 结果是**用户系统设置里会出现两个永远不会有内容的渠道**

**处理结果（2026-09-19）：选择整套删除。** 决定不做内容型提醒 —— 取件信息一律走桌面小部件。

已删：`receiver/NotificationReceiver.kt` 整个文件、`NotificationHelper` 的 `showPickupNotification` / `showExpireNotification` / `scheduleExpireReminder` / `cancelExpireReminder` / `createOpenAppIntent` 与取件/过期两个渠道定义。

同时做了两件收尾：① `createNotificationChannels()` 现在会**主动删除那两个历史渠道**，否则老用户系统设置里会永久留着两个空渠道；② 那个用来引导用户授予通知使用权的对话框曾承诺"新取件短信到达时提醒""即将过期时提醒" —— 这两项功能从未实现，删除发通知的代码后该文案属于与实际不符的声明。第三轮审计复核时发现**这个对话框本身就没有任何调用点**（`@Composable` 全仓零引用），上一轮"改写其文案"实际未产生任何效果；现已连同文件一起删除。保留的 `CHANNEL_ID_WIDGET` 是真实需要的：小部件按钮走前台服务，系统强制要求通知渠道。

**仍需真机确认**：小部件按钮的反馈通知在 MIUI 上照常工作。

**2026-11-09 补记：按项目所有者要求重新引入「常驻汇总通知」。** 这不是把上面那套死链路复活：`PickupSummaryNotifier` 不新增 receiver、不新增闹钟调度、不做逐条新取件提醒、不做过期提醒，只在通知栏保留一条随数据变化的待取件数量；没有待取件时直接撤掉。刷新入口挂在已经跑通的 `WidgetSyncHelper.notifyWidgetDataChanged` —— 全仓 13 处写库路径都会调用它，且复用它已查出的待取件列表，不额外查库。渠道是新的 `pickup_summary`，与旧的两个遗留渠道 ID 不同，旧渠道仍按上文主动删除。设置页提供总开关（默认开）与「显示取件码」开关（默认关），**地址在任何情况下都不进通知**，通知本身用 `VISIBILITY_PRIVATE` 且默认只显示数量。2026-09-19 那句「决定不做内容型提醒」针对的是逐条提醒与过期提醒，本次没有推翻这一点。

### 6. 采集入口有两份重复的解析—入库实现

`receiver/SmsReceiver.kt:57-222`（实时广播：onReceive → processSms）与 `data/repository/PickupRepository.kt:270-415`（批量导入）各自实现了一遍"规则引擎 → 失败回落 V2 → 存库"。两条路径的后续处理并不等价：**只有批量导入路径过了 `AddressMatcher` 做地址归一，广播路径直接存原始地址文本**。

**影响**：同一条短信，实时收到 vs 导入进来，落库的地址字段可能不一样。这类分叉是"某天下单号突然不认识了"这种疑难杂症的源头。

**修法**：抽一个 `ParsingPipeline`（输入短信 + 来源，输出 `PickupItem`），两个入口都调它。

**处理结果（2026-09-20）：已按"修法"的思路做完，但入口叫 `PickupIngest` 而不是 `ParsingPipeline`。** 三条采集通道（广播、批量导入、通知监听）现在都调用同一个 `util/PickupIngest.parse()`：它内部统一做全角转半角、取件码归一（`PickupCodeNormalizer`）、地址标签归一（`AddressTag`），广播路径也会过 `AddressMatcher`。
**上面这段文字保留原样**，因为它记录的是缺陷被发现时的状态；实际修法见 `docs/audit/03-data-consistency.md` §2/§6/§7。
**真机上应能观察到**：同一条短信用「实时收到」与「事后导入」两种方式进入应用，得到的取件码与地址标签一致（改前不一致）。

---

### 7. Room schema 不可校验

修复前 `data/db/PickupDatabase.kt` 设 `exportSchema = false`，仓库里也没有 `app/schemas/`。

**影响**：8 个 migration 无法被 `MigrationTestHelper` 覆盖，也没有任何自动化手段能证明"老用户升级后数据不丢"。对一个存有用户取件历史的数据库，这类验证不应省略。

**处理结果（2026-09-19，2026-09-20 补齐）：已修。** `exportSchema` 已置 `true`，KSP 的 `room.schemaLocation` 已配置，`app/schemas/` 下 version 8 / 9 / 10 三份 JSON 已提交，可供 `MigrationTestHelper` 与人工 diff 使用。`Migration_1_N` 自动化测试**仍未做**（见 问题 26）：它需要 instrumented 环境，而 1→7 的中间 schema 当时根本没导出，已不可复原。

顺带一条值得复核的迁移：`MIGRATION_7_8`(`PickupDatabase.kt:113-128`) 先 `DELETE` 数据再建 `pickupCode` 单列唯一索引 —— 这与"同一个取件码对应多个包裹"的场景存在语义冲突。

---

### 8. 无多语言

Kotlin 里有 **1510 处**含中文的字符串字面量（**口径**：先剔除注释，再统计双引号字面量里含中日韩汉字的个数 —— `tools/check_doc_refs.py` 复算这个数字，不靠人工数）；`res/values/strings.xml` 有 **25** 条；布局与资源 XML 里的硬编码中文文案有 **28 处**（全在 8 个小部件布局的 `android:text` 与提示文案上）。`values-en` 不存在。

> 本节此前写的是"819 处"与"布局与资源 XML 里的中文为 0"，两个都不对。前者是因为**没把口径写下来**：同一仓库、同一问题能数出 819 / 1375 / 1376 三个值，差别全在"注释算不算""字符串里的 `//` 会不会把这行截断"；后者是纯粹的错。现在这两个数字都由脚本复算，口径就写在这一段里。

**影响**：非中文用户无法使用；同时硬编码文案让 UI 改动无法通过资源层验证。

---

### 9. 测试覆盖错位

`app/src/test/` 曾只有一个 `PickupCodeExtractorV3Test`（20 个用例），测的正是问题 2 里那条未接入的路径；主力解析器 `RuleBasedPickupParser`(912 行) 与 `PickupSmsParserV2`(1007 行) 零覆盖。

`android.util.Log` 在 JVM 下抛 "not mocked" 曾使这 20 个用例全部失败，现已通过 `testOptions.unitTests.isReturnDefaultValues = true` 解决。**这个开关有代价**：它同时会让被测代码里的真实 `android.*` 调用静默返回默认值，从而掩盖逻辑错误（记在 `docs/audit/00-defect-ledger.md` 的 M-15，未改）。

**处理结果（2026-09-19）：基线已建，覆盖仍不完整。** 新增 `RuleBasedPickupParserTest`（14 个用例，全部使用虚构短信）与一个环境变量门控的准确率评测 `RuleBasedPickupParserAccuracyEval`。当前共 **123 个用例**，其中 **2 个**由环境变量门控、默认跳过（两个数字都由 `tools/check_doc_refs.py` 复算）。

评测在本地标注集上的结果是 `取件码命中 100.0% (1133/1133)`。**这个数字必须带着口径读**：它测的是"改规则之后有没有把已知的做坏"，语料与规则同源，属于 in-sample，不能当作对外宣传的准确率。真正的留出集评测见 README 第 7 节「解析准确率怎么量」。

**✅ 2026-11-09 已补**：新增 `app/src/test/java/com/shanqiu/pickupmanager/util/PickupSmsParserV2Test.kt`（14 个用例，全部使用虚构短信与虚构号码），覆盖空 / null 输入、验证码一票否决（含 `vetoReason` 与 `scoreDetails["VETO"]`）、非取件结果不产记录、主取件码取首个、发件号码原样保留、`PickupResult → PickupItem` 全字段映射与 7 天过期时间、地址标签统一走 `AddressTag`、`CONFIRMED` 判定、取件点合并。全量 `testDebugUnitTest` 现为 **119 用例 / 0 失败 / 2 跳过**。

注意这些是**契约测试**，不是准确率评测：锁的是「输入形态 → 输出结构」的约定，不证明解析器在真实短信上的识别率。留出集评测仍需私有语料，口径见 README 第 7 节。

---

### 10. 日志中的隐私泄漏

修复前 `PickupSmsParserV2.kt`、`RuleBasedPickupParser.kt`、`PickupNotificationListener.kt` 等多处会打印短信原文或用户真实地址。release 包靠 `proguard-rules.pro` 的 `-assumenosideeffects` 移除了 `Log.v/d/i`，但**debug 包全裸**，且 `Log.w/e` 不受该规则约束。

另外 `RuleBasedPickupParser.kt` 的**代码注释**里写着一个真实小区名；`<application>` 上曾同时写 `allowBackup="true"` 与 `fullBackupContent="false"`（当前状态见 `AndroidManifest.xml:31`），语义互相矛盾（前者允许备份、后者拒绝，实际行为取决于系统版本）。

**处理结果（2026-09-19）：已修。** 日志统一走 `util/PrivacyLog.kt` 的 `privacyRef()`，只输出 `len=字符数#hashCode十六进制`；注释里的真实地址换成虚构样例；`allowBackup` 显式置 `false`，并删掉两个无人引用的备份规则 XML（保留着只会让人以为备份被配置过）。

**这一条修了两遍**：第一轮只改了当时看到的那几处，外部审计又指出 5 个漏点 —— 全部是 `Log.w` / `Log.e`，正好落在 `-assumenosideeffects` 覆盖不到的范围里。教训是：**以"哪些规则会剥离日志"来界定脱敏范围是错的，应以"哪些调用点会带出用户数据"来界定。**

**仍然存在的前提**：debug 包不做任何剥离。发布请只用 `assembleRelease`。

---

### 11. 隐式广播把取件码和住址发出去

修复前 `receiver/SmsReceiver` 在每条新取件短信入库后执行：

```kotlin
val intent = Intent(ACTION_NEW_PICKUP).apply { putExtra(EXTRA_PICKUP_CODE, ...); putExtra(EXTRA_ADDRESS, ...) }
context.sendBroadcast(intent)   // 自定义 action，未限定包名
```

`ACTION_NEW_PICKUP` 是自定义 action 且未 `setPackage()`，设备上任何注册了该 action 的应用都可能收到 —— 内容包含**取件码与用户住址**。而全工程没有任何组件接收它，也就是说这条广播只有泄漏面、没有功能面。对一个以"数据不出设备"为卖点的应用，这是根上的矛盾。

**已修复**：加上 `setPackage(context.packageName)`，把广播限定在自己进程内，保留将来接通知时的事件钩子。

---

### 4.1 第二轮全量审计（2026-09-19，清理后复查）

第一轮修完后重新全仓扫描，另发现 9 项。前 3 项已处理。

### 12. `WidgetRefreshActivity`：exported 却零入口的孤儿 Activity ✅ 已删

它的唯一启动者是 `WidgetActionReceiver`，而后者从未在 `AndroidManifest.xml` 注册 —— 也就是说这条"透明 Activity 唤醒进程"的链路本来就断着（小部件刷新实际走的是 `WidgetActionService` 前台服务，那条是通的）。删除 receiver 后它变成彻底的孤儿，却仍以 `exported="true"` 对外可启动。已连同 Manifest 声明一并移除。

### 13. 三个漏删的死文件 ✅ 已删

`util/TouchResponseOptimizer`、`util/ViewPagerOptimizations`、`ui/components/SportyDesignSystem`（共 379 行）。第一轮汇报删除清单时把它们算进了行数，但删除命令里没包含 —— 数字对不上，是汇报口径错误，不是判断错误。

### 19. 隐私章节曾错误声明"不保存短信原文" ✅ 已改正

代码是对的，文档是错的：`PickupItem.rawSms` 确实存了整条短信，而且是有用功能（`PickupDetailDialog.kt:172` 展示原文、`MainScreen.kt:236` 复制取件信息）。隐私政策写错比不写更糟，已改为如实描述存储范围与不含原文的例外。

### 14. `deleted_pickup_items` 只增不减，且每行存完整原文 ✅ 已修复

`DeletedPickupItemDao` 的 `deleteDeletedItem()`、`getDeletedItem()`、`getAllDeletedItems()` **三个方法零调用**。只有 `insertDeletedItem`（`PickupRepository.deleteItem()`）和 `isDeleted`（4 处查重）在用。

后果：用户每删一条记录，就往这张表追加一行 `rawSms` 全文，永不清理、永不展示、也无法在 UI 里查看或撤销。既无界增长，又让"用户已删除的短信"实际仍留在设备上。

**处理结果（2026-09-19）：两半都做了。** ① `PickupRepository.deleteItem()` 归档时写入 `rawSms = ""` —— 留档的作用只是"防止同一条短信被重新导入"，查重用的是 `pickupCode + timestamp`，原文从来不是判断条件，留着只是白白增加暴露面；② 那三个零调用的读接口已删除，`ExpireCheckWorker` 现在会调用此前一直没人调的 `deleteOldDeletedItems()` 清理 30 天前的留档。

**遗留**：老用户表里已经存下的原文不会被这次改动清掉（它只影响新写入的行），需要一次把 `rawSms` 置空的迁移 —— 未做，见 问题 21 的同一处讨论。

### 15. `<application>` 没有 `android:name`，整个 Application 从未生效（✅ 影响面最大的一条）

外部审计的第一条阻断项，回源码确认后**成立，而且比审计说的更严重**。

修复前的 `AndroidManifest.xml` 里 `<application>` 没有 `android:name`，全仓也没有任何第二处注册点 —— `PickupApplication` 这个类从未被系统实例化过，`onCreate` 一次都没执行。于是里面三件事从来没发生过：

- `RuleBasedPickupParser.loadAddressOverrides()`：全仓唯一调用点。`lookupAddressOverride()` 开头就是 `if (!overridesLoaded …) return null`，所以**用户在学习模式里纠正过的地址，一旦杀进程重启就静默失效** —— 表现为"修改无效"，而数据实际仍存于 SharedPreferences 中。
- `ExpireCheckWorker.schedule()`：全仓唯一调用点。定时过期检查与清理从未被排期（问题 4、14 的清理都受影响）。
- `NotificationHelper.createNotificationChannels()`：这条侥幸没出事，因为 `ComposeMainActivity.kt:177` 也调了一次。

**这就是"每天在用却说不清哪里怪"的那类根因。** 已补上 `android:name=".PickupApplication"`。

**代价（原 15 条的真身，✅ 2026-11-09 已修）**：注册之后，多进程问题从"潜在"变成"真实" —— 主进程、`:widgetProvider`（仅 5×3 声明）、`DataChangeContentProvider` 的 `:main` 各有一个 Application 实例，`onCreate` 会在每个进程里各跑一遍，包括 `preloadData()` 的开库与全表查询。

**已采用的修法**：`PickupApplication.onCreate()` 开头用新增的 `isMainProcess()` 判断进程名，非主进程直接 `return`，跳过规则覆盖表加载、通知渠道创建、数据库预热与 `ExpireCheckWorker.schedule()`。API 28+ 读 `Application.getProcessName()`，API 24–27 从 `ActivityManager.runningAppProcesses` 按 pid 反查；取不到进程名时按主进程处理，宁可多跑一次也不漏掉主进程初始化。`:main` 与 `:widgetProvider` 两个进程声明**保持不动** —— 改进程拓扑会影响跨进程数据同步时序，需真机验证。

**验证状态**：`./gradlew :app:testDebugUnitTest`（119 用例）与 `:app:assembleDebug` 均通过，Kotlin 编译无误。子进程确实不再重复初始化这一点属于运行时行为，仍建议在真机上以多进程日志复核。

### 16. 跨进程刷新有两套并存机制，且其中一套泄漏

- `CrossProcessNotifier` + `DataChangeContentProvider`（ContentObserver 信标）
- `PickupDatabase.enableMultiInstanceInvalidation()`（Room 自带的跨进程失效通知）

后者的 `InvalidationTracker` 本已覆盖前者要解决的问题。

**处理结果（2026-09-19）：泄漏已修，两套并存未动。** `ComposePickupViewModel` 现在把 observer 存到成员字段并在 `onCleared()` 里反注册。把 `CrossProcessNotifier` 整条链路换成 Room 自带的失效通知，是一次需要真机验证小部件刷新时序的改动，当时没有做。

### 17. 半截字段：`PickupItem.notes` 与 `phoneNumber`

**`notes`**：列建了、更新语句带着走、一路传到 `ComposePickupViewModel.overwritePickupItem(notes = …)` 的入参——管道全通，但没有任何 UI 输入框，所以实际永远是空串。缺的只是一个文本框。（先前版本此处写作 `createItem` 是错的，函数实名 `overwritePickupItem` / `addPickupItemWithDedup`。另见 README 第 2 节「未完成与实验性功能」 #2）

**`phoneNumber`**：并非完全没人写 —— `PickupSmsParserV2:934/953` 会从 `result.courierPhone` 填入，但**只有 V2 兜底路径填，规则主路径不填**；且全应用没有任何地方展示它，只有 `WidgetDataPersistence` 序列化时带着走。要么填全并展示（快递员联系电话在驿站不接电话时很有用），要么两列一起删。

**2026-11-09 复核：仍未修，需要二选一决策。** 两个方向都不是纯静态修复：「填全并展示」要在编辑界面加 `notes` 输入框、在详情 / 小部件加 `phoneNumber` 展示，并让规则主路径也写该字段（属新增功能）；「两列一起删」要开 Room v11 迁移并配迁移测试（而 #26 的迁移测试本身还缺）。两者分别改变产品形态与数据库 schema，不适合由贡献者代选，留待明确选择后单独一批处理。

### 18. ViewModel 里有一整层没人用的筛选实现

`ComposePickupViewModel` 的 `filteredItems`、`setMenuExpanded`、`getAvailableAddresses` 无任何外部调用；而 `PickupListScreen.kt:194-210` 与 `:405-420` 在 UI 里把排序 / 搜索 / 筛选又实现了两遍。结果是同一逻辑三份、活的那两份在 Composable 里，每次重组重算。

**✅ 2026-11-09 已修**：ViewModel 里零引用的整条筛选链（`filteredItems`）、地址分组（`addressGroups`）、菜单展开状态（`_isMenuExpanded`）及其设置方法、可用地址列表方法、异步路线排序方法、当前模式查询方法，连同相关 import 全部删除；顺带清掉刷新触发器上对非空 `Int` 的无意义 Elvis 兜底。UI 层 `PickupListScreen` 的两份实现保留 —— 那是当前真正生效的路径，把它们收敛成一份属于后续重构，不影响本条「零引用死层」的结论。编译与 119 个用例通过。

### 20. 小部件的复制粘贴债（功能正常，维护成本高）

- `AndroidManifest.xml` 里 7 段近乎逐字的 `<receiver>` 块（实测 155 行）
- `res/layout/widget_pickup_{3x4,4x3,4x4,5x3,5x4,small}.xml` 6 份 149–153 行、只差 dp/sp
- 只有 5×3 声明了 `android:process=":widgetProvider"`，其余 6 个跑主进程 —— 这个不一致没有对应理由，却会让"某个尺寸行为不同"这类 bug 极难复现

模板方法本身（`BasePickupWidgetProvider` + 7 个各 10 行的子类）是干净的，问题全在声明层与布局层。

---

### 4.2 第三轮：外部对抗审计（2026-09-19）

把仓库的干净副本（**连这份 README 一起**）交给另一个 AI 做对抗性复核，要求它专门找"文档说得比代码好"的地方。产出 5 项「阻断」、8 项「严重」、15 项「一般」。每条都回源码验证，结果分三类，**三类都记在这里** —— 一份只写"已修复"的清单，对下一个读它的人没有价值。

#### 阻断项：5/5 成立

其中 4 项就是本表里的 1、4、10、14 条（不重复展开），第 5 项是 问题 15 —— 那个 `<application>` 缺 `android:name` 的洞，审计只判到"生命周期代码可能没跑"，实际是**整段从未跑过**。

#### 严重 / 一般项：已修

| 问题 | 处理 |
|---|---|
| `AddressMatcher` 命中缓存时返回短标签而不是完整地址 | 已修，见 问题 25 |
| `WidgetBrandAdapter` 的 provider 清单漏了 `PickupWidgetProvider2x2`，2×2 小部件不刷新 | 已补进 `triggerWidgetUpdate` 的列表 |
| `NotificationHelper` 与 `WidgetActionService` 各用一个渠道 ID，设置里留下空渠道 | 统一为 `CHANNEL_ID_WIDGET`，并主动删除历史空渠道 |
| `AboutDialog` 四条失实文案 + 硬编码版本号 | 改为如实描述，版本号取自 `PackageManager` |
| ViewModel 里 60 秒空转定时器（`_filterType.value = _filterType.value` 对 `StateFlow` 不发射） | 已删 |
| 一次性导入闸门只读不写，`isSmsImported()` 永远为 false | 自动路径补上 `setSmsImported()` |
| 小部件冷启动快照把短信原文与发件号码写进 SharedPreferences | `WidgetDataPersistence` 已去掉 `rawSms` / `senderPhone` |
| 5 处 `Log.w` / `Log.e` 仍在输出用户数据 | 全部改为 `privacyRef()`（`-assumenosideeffects` 不覆盖 `w/e`，见 问题 10） |
| README 的 `文件:行号` 与函数名有 6 处已对不上源码（自查工具计得） | 全部改对，并把该校验挂进 CI |
| 通知权限引导对话框仍在向用户承诺不存在的提醒功能 | 复核时发现它本身零调用（上一轮"改写文案"改了个没人看的界面），整文件删除 |
| `ui/animation/` 里一张零引用的动画卡片组件 | 整文件删除，并清掉屏幕上那个悬空 import（详见 问题 28） |
| 通知采集通道的 `receiveTime` 用了"通知 posted 时间"，与广播通道不同源 | 改取 `Notification.when`；并补上 `wasDeleted()` 查询，见 问题 1 |

#### 故意不修（不是遗漏，是权衡）

- **问题 6（两份重复的解析—入库实现）**：这是全仓改动面最大的一处，会同时碰到广播路径、导入路径、通知路径三条落库链路。它值得单独一个 PR 配一次真机回归，混进这轮清理只会让两件事都不可信。
- **`distributionSha256Sum`**（给 Gradle 发行包钉哈希）：本机 wrapper 缓存里只留下了解压后的目录和 `.ok` 标记，zip 已被删，**无法在本地独立算出正确的哈希值**，而钉错哈希会让每个人的构建直接失败。要加的话请从 `https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256` 取值并与自己下载到的文件比对一致后再写。
- **`WidgetActionService` 的 exported 加固**：见 问题 23，含"为什么不盲改"。
- **无多语言（问题 8）、小部件声明层的复制粘贴（问题 20）**：都属于"值得做但明显超出该轮范围"的范围。

#### 审计本身判错的三条

复核后认为不成立。列出以免后续读者误判：

1. **"LICENSE 文件是 CRLF"** —— 不成立。逐个 `git cat-file` 读出全部提交对象的字节内容后，含 `\r\n` 的只有 7 个，全是 PNG 与 `gradle-wrapper.jar` 这类二进制（其中的 `\r\n` 是文件内容的一部分，不是换行符）。所有文本 blob 存的都是 LF。工作区里看到 CRLF 是 `core.autocrlf=true` 的检出转换，不影响仓库内容。
2. **"通知渠道 ID 不一致只是风格问题"** —— 判断方向对，但严重级别被高估：因为 `android:name` 缺失（问题 15），渠道创建此前根本只在 `ComposeMainActivity` 那条路径上跑。修完 15 之后它才成为真问题，也正因如此，当时一并统一了。
3. **"跨进程 PendingIntent 使用 `FLAG_MUTABLE` 是漏洞"** —— 不成立，审计自己也把它归到了"非缺陷"。`RemoteViews` 的填充需要可变 Intent，且这些 Intent 都是显式组件指向本应用，无外部可控空间。

（另有一条对审计的**更正**：它说 `app/src/androidTest` 不存在。目录在本地存在但为空，而 git 不跟踪空目录，所以**公开仓库里确实没有** —— 实质结论成立，见 问题 26。）

---

### 21. 应用内没有「一键清空全部数据」入口

`PickupItemDao.deleteAll()`、`SavedAddressDao.deleteAll()`、`DeletedPickupItemDao.deleteAll()` 三个方法**全仓零调用**，`PickupRepository.deleteAll()` 只是转发。也就是说"清空"的能力写到了 Repository 层，然后断在那里。

后果：README 第 4 节「隐私」里"清除数据"那条只能靠系统设置或卸载兑现。逐条删除在记录多的时候根本不可行，而"不再使用该应用、希望先清除数据"这类需求应被优先满足。

**修法**：设置页加一项带二次确认的清空，调 `PickupRepository.deleteAll()` 并把三张表一起清掉，同时清全部 7 个 SharedPreferences 文件（名单见 README 第 4 节"数据只落在本地"那条）。需要真机验证，已列入清单。

**✅ 2026-11-09 已修**：`SettingsDialog` 新增「清空全部数据」入口与 `AlertDialog` 二次确认，`MainScreen` 把 `onClearAll` 接到新增的 `ComposePickupViewModel.clearAllData()`。

`PickupRepository.deleteAll()` 从「只转发 `dao.deleteAll()`」改为在 IO 线程清掉三张表（`pickup_items`、`saved_addresses`、`deleted_pickup_items`）与全部 7 个 SharedPreferences 文件。文件名集中列在 Repository 的 `ALL_SHARED_PREFS`，与 README 第 4 节名单一一对应 —— 新增偏好文件时必须同步这份名单，否则清空会留下残留。

两个原清单没写、实现时必须处理的点：

1. **清完必须把 `sms_imported` 闸门重新写回 true**。`autoImportSms()` 以它为唯一闸门，若随偏好一起清掉，下次 ViewModel 创建就会把整本短信收件箱重新灌回来，用户刚清掉的数据立刻"复活"。重新导入只能走菜单里的显式入口「导入短信」。
2. **只清磁盘不够**。`RuleBasedPickupParser` 的地址覆盖表与 `AddressMatcher` 的地址缓存都是内存态；实现里重载空覆盖表 + `refreshCache()` + 复位 `isInitialized`，否则旧数据会继续生效到进程结束。偏好清空后 ViewModel 还要把 `_defaultDisplayDays` / `_routeSettings` 同步回默认值，避免「设置页显示 7 天、实际按 20 天过滤」的错位。

另外：清空用 `commit()` 而非 `apply()`，保证真正落盘后才返回；完成后通知桌面小部件刷新。

**验证状态**：`testDebugUnitTest`（119 用例）与 `assembleDebug` 通过。清空动作涉及 Room 事务、跨进程小部件刷新与权限向导复位，属运行时行为，**仍需真机点一遍**：确认清空后列表与小部件都为空、重启应用不会自动重新导入、手动「导入短信」能正常补回。

### 22. 下拉刷新会重新扫描短信收件箱

`ComposePickupViewModel.refresh()` 里调的是 `repository.importSmsFromDevice(...)`，而不只是重读本地库。语义上"刷新"应该是便宜的本地操作，现在它每次都：读全量短信 → 逐条解析 → 逐条查重。

**影响**：一是 `READ_SMS` 的实际使用频率高于用户在权限说明里看到的预期（权限与用途的逐条说明见 `app/src/main/AndroidManifest.xml` 的注释）；二是随收件箱增长而变慢，且和 `importSms()` 是同一份实现 —— 两个入口没有理由做同一件事。

**未修原因**：把 `refresh()` 改成只重读本地，是一次产品语义的收窄（作者本人可能就靠下拉刷新来补导漏掉的短信）。**这条要等产品作者的决策，不适合贡献者代改。**

**✅ 2026-11-09 已修（该语义已由项目所有者确认）**：`refresh()` 不再调 `importSmsFromDevice()`，改为 `refreshData()` 触发本地 Room Flow 重订阅；补导漏掉的短信走菜单里已有的 `importSms()`，两个入口不再做同一件事。`READ_SMS` 的实际使用频率随之回到权限说明所描述的范围。编译与单测通过。

### 23. `WidgetActionService` 是 exported 且无权限保护

声明在 `AndroidManifest.xml:109-112`：`android:exported="true"`，没有任何 `android:permission`。设备上的任意应用都能 `startService` 它。

**实际影响面**（读完整个服务后）：它只响应两个动作 —— 刷新（读本应用自己的数据库、更新小部件、弹一个 Toast）或打开主界面。它**不读取调用方传入的字符串**（`EXTRA_WIDGET_ID` 是 int，且只用于 `AppWidgetManager` 查询），所以没有注入面；数据也不出设备。真正的危害是骚扰性的：外部可以反复把它拉成前台服务。

**为什么没有直接加 `android:permission`**：该服务只由桌面进程通过本应用自己的 `PendingIntent` 启动。给 PendingIntent 的目标组件加权限要求时，判定口径涉及 PendingIntent 的创建者身份与各家桌面的发送实现，**在没真机的情况下加，最坏结果是 7 个尺寸的小部件按钮全部失灵且没有报错**。所以留到真机：先加签名级权限、跑一遍 7 个尺寸 × 主流桌面的按钮点击，通过才提交。

### 24. `MIGRATION_7_8` 的 DELETE 不可逆，且与"同码多包裹"语义冲突

`PickupDatabase` 的 `MIGRATION_7_8` 先 `DELETE FROM pickup_items WHERE id NOT IN (SELECT MIN(id) … GROUP BY pickupCode)`，再建 `pickupCode` **单列**唯一索引。

两件事叠在一起：① 迁移前库里同码的多行会被**静默删除且无法恢复**（没有备份表）；② 单列唯一索引从此在数据库层面禁止同码多包裹 —— 而"一个驿站一次到 3 个包裹、取件码相同"是真实场景。代码注释里写明这是为了堵住"手动添加与短信导入产生不同 `receiveTime` 的重复行"，方向没错，但用一个索引解决两条不同的问题。

**为什么没动**：这是历史迁移，改它会改变已发布升级路径的行为。正确做法是**新增** `MIGRATION_9_10` 放宽索引，而不是回改 7→8。需要先有真机与真实数据量作依据。

### 25. `AddressMatcher` 命中缓存时返回短标签（✅ 已修）

修复前 `matchAndPredictAddress()` 的缓存分支写的是 `standardizedAddress = matchedAddress?.addressTag ?: cached.first`。取的是匹配到那条的**短标签**（`generateAddressTag()` 产出的是"3号楼""××小区"这类 ≤6 字的片段），而不是它 `displayName` 里的完整地址 —— 而 `?: cached.first` 这个兜底本身就说明作者想要的就是 `cached.first`。

**真正的危害是回路**：调用方 `PickupRepository` 把返回值同时用作入库的 `address` 和 `savedAddressDao.insertOrUpdateAddress(tag, standardizedAddress)` 的第二个参数。于是每命中一次，`saved_addresses.displayName` 就被覆写成更短一截的标签 —— 地址库会随导入次数单调地退化，而"按地址智能分类"是这个应用宣传的核心能力。

**已改**为 `standardizedAddress = cached.first`，顺带删掉不再需要的 `find`。**遗留**：已经被覆短的历史行不会自愈；恢复它们要么靠用户重新编辑，要么靠一次从 `pickup_items.address` 反推的迁移。真机验证要点：同一个驿站重复导入两次，第二次之后地址文本**不应**变短。

### 26. 迁移测试缺失

`app/src/androidTest/` 在公开仓库里不存在（本地目录为空，git 不跟踪空目录），`MigrationTestHelper` 一个用例都没有。对一个存着用户取件历史、已经迭代到 version 10 的库来说，这意味着"老用户升级不丢数据"目前只能靠人肉相信。

**为什么没做**：instrumented 测试需要连真机或起模拟器，属于必须真机参与的项。**最该先补的**是 9→10 与 8→9 两条迁移测试（`MIGRATION_9_10` 会改写既有行的取件码、`MIGRATION_8_9` 会把"已过期"回填成"待取"，两条都会动数据；schema 已导出，条件已经具备），这也是新人最容易接的一个任务。

### 27. 两项发布前需要作者确认的外部事实

- **应用图标版权（已确认）**：`res/mipmap-*/ic_launcher*.png` 等 12 个位图由作者自制（用 AI 绘制），不含任何第三方素材，因此不存在需要确权的来源。
- **提交作者身份（已确认）**：公开提交的作者与提交者均为 `shanqiugzs <shanqiugzs@users.noreply.github.com>`，源码 SPDX 版权行同为此署名；`tools/audit/scan_commit_identity.py` 在 CI 里核对这一点（白名单外的邮箱域名即报错）。
- **包名 `com.shanqiu.pickupmanager` 保留**：它是作者名的拼音。作者已决定不改 —— 改 `applicationId` 会让已装用户失去升级路径，而署名对开源项目本是必要的。

### 28. `ui/animation/AnimationConfig` 是一整层没人用的动画参数

第三轮清理顺手删掉了 `ui/animation/` 里的 `AnimatedPickupCard`（101 行、全仓零引用的 `@Composable`，只在一个屏幕文件里留着一个没人用的 import）。删完重新统计：`AnimationConfig` 一共 **32 个成员（23 个字段 + 9 个返回 AnimationSpec 的工厂函数），其中 30 个在本文件之外没有任何引用点** —— 外部真正读的只有 `buttonPressScale` 与 `cardStatusTransitionDuration` 两个。四套 `AnimationPresets`（Fast / Standard / Smooth / Accessible）里改的那些参数，绝大多数也落不到屏幕上。

这类东西比死函数更麻烦：它看起来像"有一套人人都在用的动画系统"，于是新贡献者会往里加参数、而没人回头删。

**为什么只删了整文件、没删这 30 个成员**：删零引用的整文件是编译可证明无影响的；而 `AnimationConfig` 是 **data class**，字段参与 `equals()`，它又经 `staticCompositionLocalOf` 下发给整棵组件树 —— 少几个字段会改变重组时的相等性判断，动效因此是否受影响，只有真机看得出来。该条按"无真机验证则不修改渲染路径"处理。

**建议的收尾**：把 `AnimationConfig` 收缩成实际被读的那几个字段，预设留 `Standard` 一个；改完在真机上把卡片点击、标记已取、对话框开合各看一遍。等哪天真要做无障碍动效开关，再按需求把参数长回来。

**✅ 2026-11-09 已按建议收尾**：`AnimationConfig` 只保留实际被读的 `buttonPressScale` 与 `cardStatusTransitionDuration`，预设只留 `AnimationPresets.Standard`；其余 21 个字段、9 个返回 `AnimationSpec` 的工厂函数与 Fast / Smooth / Accessible 三套预设全部删除。`staticCompositionLocalOf` 下发方式与 `provideAnimationConfig()` 签名不变，`ComposeMainActivity` 无需改动。

原文明说「无真机验证则不修改渲染路径」—— 这次是按项目所有者 2026-11-09 的明确要求做的收缩，编译与 119 个用例通过；**data class 少字段会改变 `equals()` 进而影响重组**，仍建议按上文在真机上把卡片点击、标记已取、对话框开合各看一遍。

### 4.3 第四轮：怀疑"检查本身"（2026-09-20）

前三轮都在查代码，这一轮核查的对象是用于判定"干净"的检查手段本身。结论：该手段不成立。

### 29. 真实取件码与真实地址片段写在源码注释里，躲过了前三轮 ✅ 已删

`RuleBasedPickupParser` 的注释里出现过两条真实数据（**本文档刻意不复述原值**，只写形状）：

- 一条 `C<位>-<位>-<5位>` 形态的圆通取件码，出现在"兼容格式"与"去重"三处注释里 —— 它是作者某条真实短信里的取件码**原文**。
- 一个 `X2组团` 形态的地址片段 —— 来自作者真实住址里的一段楼栋编号，出现在"支持含字母的地址"那条注释里。

**为什么前三轮没抓到**（这一条比缺陷本身更重要）：

1. **核查源不全**。此前的两份核查脚本只把标注集与负样本两份文件当作比对源，全量短信导出从未进入 oracle。"零命中"这句话一直是相对一个不完整的语料集说的，而它被表述成了绝对结论。
2. **token 化只按纯数字切**。那条码是"字母+分段数字"形态，既不是 11 位手机号也不是 6 位以上连续数字；按纯数字切出来后只剩尾段，与注释里的整串对不上，形同漏网。
3. **容易默认注释不是数据**。示例、"实测"、"例如"这类句子里的数字，看上去是说明文字，实际是从真实短信里抄来的。

**已处理**：上述三处码值与地址片段换成不会与任何真实语料相撞的假值（`C4-6-11111` / `A2组团`）；测试夹具里一个小区名与一个"看着假但恰好撞上"的示例码一并换掉。现在的判据是**仓库里任何像码的串都不该出现在语料里**，靠仓库外的三份脚本重跑：码形专项、地址/驿站专名专项、以及"提交对象 + APK 全内容"合并版。

**该条修复过程中出现过一次同类问题**：真实值曾被原样写入本 README 以说明删除动作，被码形扫描当场查出（README 命中 14 处）。结论：记录泄露证据的位置同样必须脱敏，否则修复动作本身构成新的泄露 —— 因此本条只写形状。

**残留风险（必须说明）**：换成 `11111` 这类假值只是让**这次**不撞。只要示例来自真实样本，同一条错误就会重犯 —— 真正的约束是"示例必须是先比对过语料、确认不存在的值"，而这条约束目前只能靠人复查时想着。三份脚本的构建方式与判据边界写在 `docs/audit/06-docs-and-verification.md` §13.2 与 `docs/audit/README.md`。

### 30. 泄露核查跑不进 CI

隐私核查需要真实语料才能反向匹配，而语料永远不该进仓库 —— 所以这道检查在贡献者的 CI 里**不存在**，只在作者本机存在。README 与 CI 都无法机械证明"下一次改动没把真实短信抄进注释"。

**当前缓解**：① `tools/check_doc_refs.py` 保证文档与代码一致；② 贡献规范（README 第 9 节「参与贡献」）要求示例用虚构值，且真实语料类 PR 会被拒；③ 这三份核查脚本保留在仓库外，作者在推送前手工重跑。

**没有解决的**：CI 里那道"零真实数据"的门其实不存在，任何声称它存在的表述都是假的 —— 所以这里明说。


---

### 31. 合并后的 APK 比手写清单多四项权限，其中一项是"删过又被库加回来"的

手写清单只有 7 项；`aapt dump permissions app-release-unsigned.apk` 读出来是 **10 项系统权限 + 1 项应用私有权限**。多出来的四项及其来源（`app/build/intermediates/manifest_merge_blame_file/release/...` 的合并归因报告，逐条可查）：

| 多出来的权限 | 由谁引入 | 用途与判断 |
|---|---|---|
| `ACCESS_NETWORK_STATE` | `androidx.work:work-runtime:2.9.0` | 只问"现在有没有网"，没有发包能力；本应用未设任何网络约束 |
| `RECEIVE_BOOT_COMPLETED` | 同上 | 开机后恢复未完成的排期任务 |
| `WAKE_LOCK` | 同上 | **本应用从未使用**：全仓 0 处 `newWakeLock` / `PowerManager.WakeLock`。R5 把手写清单里那条删了（L-08），但库的清单会把它加回来 —— 所以"删掉了"只对手写清单成立，对成品 APK 不成立（这一条是本节此前的漏项） |
| `com.shanqiu.pickupmanager.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | `androidx.core:core:1.12.0` | 应用自己定义、自己持有的私有权限，用于给非导出接收器加锁 |

**为什么"不联网"这条卖点仍然成立**：`INTERNET` 确实不存在（合并清单、`resources.arsc`、`classes.dex` 三处都查过），没有它任何进程都建不了 socket。

**为什么没直接 `tools:node="remove"` 掉**：删 `RECEIVE_BOOT_COMPLETED` 会改变 WorkManager 在设备重启后恢复排期的行为，而本应用的周期任务（过期清理、留档清理）正是依赖它；`WAKE_LOCK` 是 WorkManager 自身在某些路径上要用的，剥掉它的后果同样只有真机看得出来。**要做的话**：在 `<manifest>` 里对目标项加 `tools:node="remove"`，然后验证"重启手机后周期任务仍会执行""清理任务不被提前打断"。没有设备时不动代码，只把清单如实列出来。

### 32. 权限向导曾把"通知"设成必须步骤，并用它挡住导入

`PermissionSetupDialog` 的通知步骤原来 `isRequired = true`：首次启动 400ms 后必弹，未授予时只渲染"去授权"和一个禁用的"授权后方可继续"，**没有任何跳过入口**。而它的说明文字写着"开启通知后，应用会在新包裹到达和即将过期时提醒你" —— 这两项功能已被整体删除。

后果是两层：新用户被硬挡着去授予一个承诺着不存在功能的权限；同时权限说明（`app/src/main/AndroidManifest.xml` 的注释）告诉他"POST_NOTIFICATIONS 可选、本应用不发主动提醒"，文档与界面直接对立。

**已改**：该步骤降为 `isRequired = false`，文案改为如实说明"只用于点击小部件按钮时系统要求的前台服务反馈通知"。
**同时修掉的连带阻断**：`SmsPermissionChecker` 把 `AUTO_START` / `BATTERY_OPTIMIZATION` / `POST_NOTIFICATIONS` 都标为 `isCritical`，而 `markAutoStartGranted()` 全仓零调用 —— 在检测不到自启动状态的机型（Pixel、三星、原生）上该项恒为未授予，于是 `isFullyGranted` 永远 false，菜单里的「导入短信」点下去只会弹"权限未完全授权"。**核心功能在三分之一的主流机型上是不可用的**，且此前四轮审计一次都没碰到这条。
**仍需真机验证**：向导的四步流程、跳过后的状态记忆、以及小米机上"通知类短信"那一步的分支。

### 33. 桌面小部件的接收器是导出的，而它的"已取"按钮接受的是一条可枚举的数据库 id

七个 `AppWidgetProvider` 全部 `exported="true"`（这是必须的，系统要投递 `ACTION_APPWIDGET_UPDATE`），并且各自在 `intent-filter` 里声明了三条自定义 action。实测：

> **R5 更新（对应 R4 的执行结果）**：这三条里已有两条被删除，现在每个 receiver 只剩 `APPWIDGET_UPDATE` + `MARK_PICKED`。
> 删除的是 `...widget.REFRESH` / `...widget.SILENT_REFRESH`，连同它们的处理函数（约 200 行）一起删除 ——
> 理由见下一条以及 `docs/audit/04-ui-and-deadcode.md` §2。**下面这段文字保留原样，因为它记录的是缺陷被发现时的状态。**

- `...widget.REFRESH` 与 `...widget.SILENT_REFRESH` —— **全仓没有任何发送方**（刷新按钮走的是 `WidgetActionService`，而它的 action 常量是另一个字符串）。也就是说这两条 filter 的唯一效果就是把内部函数变成外部可触发入口。
- `...widget.MARK_PICKED` —— 只由本应用自己的显式组件 `PendingIntent` 投递，而显式组件的广播**根本不需要 intent-filter**。

于是本机任意应用都能发 `MARK_PICKED` + 任意 `item_id`（数据库 id 是自增整数，可枚举），把用户全部待取件改成"已取"；而 `ExpireCheckWorker` 每天物理删除"标记为已取满 30 天"的记录，`allowBackup="false"` 又没有副本 —— **一次无权限的广播 = 30 天后不可恢复的数据销毁**。这比 README 问题 23 之前给 `WidgetActionService` 的"骚扰性"评级严重得多。

**已经做的（都能编译验证，对正常使用零影响）**：
1. `updateStatus` 那条 `WHERE id = ?` 无前置条件的写法，为小部件路径另加 `markPickedIfPending(... AND status = 待取)`，返回 0 行就不报"已标记成功"也不重绘；
2. `onReceive` 三条自定义分支统一先过 `ownsWidgetId()`（`AppWidgetManager.getAppWidgetIds(本 Provider 组件)` 里必须真有这个 id）；
3. 三处把取件码打进 `Log.w`/`Log.e` 的点改为指纹，并新增 `tools/check_log_privacy.py` 挂进 CI（理由见问题 10 的教训）。

**仍然没有彻底解决，且必须真机才能推进**：以上只是把伪造面从"任意 id"收窄到"必须是本机已存在的 widget 实例"。两条完整修法都需要设备验证 —— ① 给接收器加 `android:permission="android.permission.BIND_APPWIDGET"`（签名级，只有系统与各桌面持有；风险是自家 `PendingIntent` 的投递身份在各家 ROM 上不一定通过）；② 由本应用在渲染 `RemoteViews` 时给 PendingIntent 附一个一次性随机令牌并在接收端校验（外部进程读不到本应用的 SP，所以伪造不了，但要处理进程被杀后令牌失效的问题）。

> **为什么至今没有实现 ②（这是刻意的决定，不是遗漏）**
>
> **作者的处置意见（2026-09-20）**：该风险当前保留。彻底修法（一次性令牌或关闭 `exported`）
> 会改动 PendingIntent 与桌面宿主的交互，**必须真机验证**（作者的验证范围仅小米机型），
> 在无法验证的情况下改动，最坏结果是 7 种尺寸的小部件按钮全部失效。
>
> 复核 PendingIntent 的匹配规则后发现，②**可能根本不成立**：
> `PendingIntent` 的匹配**不考虑 extras**（只比 component / action / data / categories / type / flags 与 requestCode），
> 所以另一个应用可以自己构造同一个 `Intent`（`MARK_PICKED` + 本 Provider 组件）加上同样的 `requestCode`
> （`appWidgetId + 10000`，而 appWidgetId 可枚举），用 `FLAG_NO_CREATE` **取回本应用创建的那个 PendingIntent**，
> 再用 `send(context, 0, fillInIntent)` 把 `item_id` 填进去 —— 完全绕过令牌校验。
> 令牌是否真的不可绕过，取决于 `FLAG_MUTABLE` 的 extas 合并语义与各家 ROM 的实现细节，
> **这一点必须在真机上实测（`adb shell am broadcast` 或写一个 demo 应用）才能有结论。**
>
> 在没有设备的情况下，实现一个"看起来修好了、实际可能被一行 `FLAG_NO_CREATE` 绕过"的令牌机制，
> 比不实现更糟 —— 它会让下一位读者以为这条已经关掉了。所以**不动代码**，
> 只把这条的准确形状记在这里，并保留原建议中"先删掉零发送方的 action"这一步（R4 已执行）。


### 34. 还有一类"声明"是仓库结构上无法证伪的

README 与本文里的若干陈述属于"作者自述做过某件仓库外的事"：包体从 4.68 MB 降到 2.90 MB 且基线产物已核对哈希、Kotlin 基线是 106 个文件 28877 行、三个死文件共 379 行、`AnimatedPickupCard` 101 行、ONNX 权重与词表的体积、引导词提级前 89.1%、反向穷举用了 57 个文件约 730 万字符。

这些**都无法由克隆本仓库的人复核** —— 因为本仓库只有一个"Initial release"提交，被删的东西在历史里不存在任何反证空间。它们与本文档花了一整节去批评的"实测 98.8%"是同一类东西，区别只是这次说得比较谦虚。

**能做的只有两点**：① 把每个可机械复核的数字都交给 `tools/` 下的脚本复核（`check_doc_refs.py` 现在连"包职责"表的文件数与行号是否仍指着它声称的东西都判）；② 把无法复核的这类明确标为"作者自述"。本节就是 ②。

---


