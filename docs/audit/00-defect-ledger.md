# 缺陷总表

**基线**：本表在「审计基线」提交中创建时，**全部条目的状态都是「待修」**。
每一轮修复会把对应行改成「已修」并填上**轮次 + 该轮的修复文档**。

**为什么「提交」列填的是 `RN / docs/...` 而不是 SHA**：填 SHA 会陷入自指 ——
本次提交的哈希要等提交完成才知道，写进去就得 amend，而 amend 又会改掉哈希。
所以「轮次 → 纪实文档」这条映射是唯一不依赖提交序列的追溯方式。

> **R7 对本节的两处更正**（原文只写了"六轮/最终报告末尾有轮次→SHA 对照表"，
> 那两句话在 R7 之后同时失效，失效原因本身就是 B-02）：
>
> 1. **发布形态是单个 `Initial release` 提交**，不再有"第 N 轮 = 名为 `RN:` 的那个提交"。
>    分轮提交里含作者本人的真实取件码与真实小区名，只要它们还在提交序列里就永久可寻址
>    （`git show <那个提交>` 一条命令即可取回），所以折叠发布。
>    因此 `git log -p docs/audit/00-defect-ledger.md` **看不到**"什么时候修了哪一条" ——
>    要看过程请读 `docs/audit/01~06-*.md` 每一轮的纪实，每篇都带可复现命令。
>    折叠前的完整提交序列只保留在本机备份 `E:\PickupBackup\*.bundle`。
> 2. **`99-final-report.md` 的 §1 表已经删掉 SHA 列**（改成"轮次 → 纪实文档"），
>    因为它指向的那 15 个提交不再存在于公开仓库里。留着一张指向不存在对象的地图，
>    正是本表在 H-14 里批过的缺陷。

**严重度定义**（本表自用，不引用外部标准）：

| 级别 | 含义 |
|---|---|
| **阻断** | 一旦公开就无法收回，或会让"能不能公开"这个问题的答案是"不能" |
| **高** | 会持续交付给用户的错误；或可被设备上任意第三方应用触发的写入/崩溃 |
| **中** | 功能在特定路径上失效、文档与实现不符、明显的资源/构建浪费 |
| **低** | 死代码、死参数、注释与实现矛盾、干净度问题；不影响当前行为 |

**状态图例**：`待修` / `已修` / `不修（附理由）` / `无法验证`

---

## 一、阻断

| ID | 位置 | 缺陷 | 状态 | 提交 |
|---|---|---|---|---|
| **B-01** | git 对象库（`Initial release` 提交） | 注释里写着作者本人手机上的**真实取件码**与**真实小区名**。删除该行只是新增一个提交，旧对象永久可寻址；`git show <初始提交>` 一条命令即可取回。仓库自带的两个检查器一个读 README、一个读源文件，**都不看 `.git`**，因此 CI 永远拦不住。**R7 更正范围**：同一提交里的 `RuleBasedPickupParserTest.kt` 测试夹具**也**写了真实小区名 —— 原记录只列了主源文件。 | 已修 | R1 / `docs/audit/01-desensitization.md` §1-3；范围更正见 R7 / `docs/audit/06-docs-and-verification.md` §11 |
| **B-02** | 两条审计线的 git 历史 + `tools/audit/scan_history.py` 的退出码规则 | **自带历史门输出 `✓ 历史对象里没有 BLOCK 级命中`（退出码 0），而 8/15 个提交携带作者本人的真实取件码与真实小区名。** 根因是判据结构而非漏扫：只有 `BLOCK` 级参与退出码，而真实取件码命中的 `PICKUP_CODE_COMPOUND` 是 MED、真实小区名命中的 `CN_ADDR_COMPOUND` 是 HIGH，两条**永远不可能让门失败**；把它们定成低严重度本身是对的（夹具同形），所以形状判据**单独不构成发布门**。泄漏点：R1…R6 与交接单提交把"证据"原样写进 `docs/audit/01-desensitization.md` 与 `tools/audit/redact_history.py`（我方线的 `README` 犯过同一错）。 | 已修 | R7 / `docs/audit/06-docs-and-verification.md` §9-12（补 A/B 两级精确值判据 + 折叠为单提交发布） |

---

## 二、高

| ID | 位置 | 缺陷 | 状态 | 提交 |
|---|---|---|---|---|
| **H-01** | `RuleBasedPickupParser.kt` `GENERIC_CODE_PATTERNS` / `extractCodes()` / `extractAddressAfterCode()` | 通用码形（`\d{4,8}`、`\d{2,4}-\d{3,4}`）在**全文**匹配且无引导词邻近约束，而驿站短信必带联系电话 → 电话号码被切成取件码入库、展示、一键复制、一键分享。`PickupCodeExtractorV3.isValidCode()` 里的手机号排除不在主路径上，且要求整串匹配，被切片后完全绕过。 | 已修 | R2 / `docs/audit/02-parser.md` §1-5 |
| **H-02** | `DeletedPickupItemDao.kt:26`、`SmsReceiver.kt:179`、`PickupNotificationListener.kt:115` | 「删掉的记录不复活」这条修复只做了一半：留档表主键是**未经归一化的取件码**，而取件码由哪条通道解析决定 → 广播通道存 `1-2-3456`、通知通道存 `3456`，`wasDeleted` 查不到 → 记录复活。反向亦然：同一包裹入库两行，标掉一行另一行永远挂在待取页。 | 已修 | R3 / `docs/audit/03-data-consistency.md` §1-3 |
| **H-03** | `PickupItemDao.kt:113,162-177`、`PickupDatabase.kt:126` | 取件码 `UNIQUE` 索引 + `insertOrUpdateItem` 命中即 `return false` ⇒ **同码新包裹被静默吞掉**：不入库、不通知、列表里看不到，用户完全不知道有这个包裹。丰巢 4 位码与驿站当日序号码都会复用，"取件码是包裹物理身份"这个前提对同日同柜机不成立。 | 已修 | R3 / `docs/audit/03-data-consistency.md` §4-5 |
| **H-04** | `SmsReceiver.kt:117,135`、`PickupRepository.kt:288,314,357`、`PickupNotificationListener.kt:108` | 三条采集通道三套解析：广播=规则引擎→V2 回落；批量导入=规则引擎+`AddressMatcher` 归一；通知=**只跑 V2**、不过 `AddressMatcher`、`senderPhone` 传空串 ⇒ 同一条短信得到不同取件码、不同地址质量，并且 `saved_addresses` 只会被导入通道写入。 | 已修 | R3 / `docs/audit/03-data-consistency.md` §6-7 |
| **H-05** | `.gitignore:76-85` | 「兜底」规则写成 `/*.json`、`/*.txt`（**带前导斜杠**），git 只与仓库根比对 ⇒ 把一份短信导出改名后丢进 `docs/`、`app/src/main/assets/`、`tools/` 等子目录，`git add -A` 就能把它随 APK 一起公开。这恰好是该规则注释里自己写明的威胁模型。 | 已修 | R1 / `docs/audit/01-desensitization.md` §6 |
| **H-06** | `ShareUtils.kt:50-134`、`MainScreen.kt` 的 `onShareClick`/`onShareAllClick` | App 里存在一条把**取件码 + 地址 + 快递公司**打包发给任意应用的通道（`Intent.ACTION_SEND` + `createChooser`），而 README 的能力表、数据流章节、隐私承诺**一个字都没提**，§4 却专门为"复制原文"补了更正条目。 | 已修 | R5 / `docs/audit/05-security-and-license.md` §1 |
| **H-07** | `AndroidManifest.xml:101-254`、`BasePickupWidgetProvider.kt:207-217`、`PickupItemDao.kt:131` | 7 个小部件 receiver 全部 `exported="true"` 且无权限，intent-filter 上挂着 `MARK_PICKED` 自定义 action。`ownsWidgetId()` 只能证明"这个 id 存在"，不能证明"是用户在点" ⇒ 设备上任意应用可先枚举本机 AppWidget 实例拿到合法 id，再自增 `item_id` 广播 `MARK_PICKED`，把用户全部包裹静默改成"已取"（30 天后被清理物理删除，不可逆）。 | 不修（附理由） | R5 / `docs/audit/05-security-and-license.md` §2 |
| **H-08** | `BasePickupWidgetProvider.kt:360,390,397`（链：`onReceive:158`→`handleRefreshAction:298`→递归 `305/320/332/344`）、`AndroidManifest.xml:94-98` | `goAsync()` 被放在**被多处复用的非入口函数**里。AOSP 语义：`goAsync()` 只能成功取走一次，第二次返回 `null`。递归/循环路径第二次进入时 `pendingResult` 为 `null`，`finally` 里的 `finish()` 抛 NPE；该 NPE 不会被同一 `try` 的 `catch` 捕获 ⇒ **主进程主线程崩溃**，且崩前 `refreshInProgress` 未复位 ⇒ 之后每次刷新广播都反复崩溃。前台服务 `WidgetActionService` 同样 `exported="true"` 无权限，可被任意应用反复拉起。 | 已修 | R4 / `docs/audit/04-ui-and-deadcode.md` §1 |
| **H-09** | `AndroidManifest.xml`（`PickupNotificationListener`）、`AboutDialog.kt` 隐私段、`PermissionSetupDialog.kt` 权限说明 | 应用声明了 `NotificationListenerService`（**可读取全部通知**），但隐私说明、权限清单、About 页只讲短信权限，从未告知用户存在这项能力，也没有任何开关入口。开源后这是第一质疑点。 | 已修 | R5 / `docs/audit/05-security-and-license.md` §3 |
| **H-10** | `PermissionSetupDialog.kt:539-562`、`PermissionCacheManager.kt:86-94`、`SmsPermissionChecker.kt` / `SmsPermissionManager.kt` / `MiuiPermissionHelper.kt` | 权限流程有三个独立缺陷叠在一起：① 全仓 **0 处**使用 `shouldShowRequestPermissionRationale`，用户在系统弹窗选"不再询问"后按钮点不动、页面永久卡在"授权后方可继续"，无降级路径；② 权限状态被缓存 30 分钟且**不在权限变化时失效** ⇒ 用户在系统里收回权限后 App 仍认为已授权；③ 同一个"是否已授权"存在 **4 套实现**（`isFullyGranted` × 4），弹窗用弱条件（只查 READ+RECEIVE）判定通过后写入强条件的缓存。 | 已修 | R4 / `docs/audit/04-ui-and-deadcode.md` §7 |
| **H-11** | `PickupListScreen.kt:605-630` | 空列表页**唯一的**主行动按钮文案是"导入短信"、带 `ImportExport` 图标，实际 `onClick = onRefresh`（只触发下拉刷新）⇒ 新用户第一次打开、列表为空时点它，什么都不发生。 | 已修 | R4 / `docs/audit/04-ui-and-deadcode.md` §5 |
| **H-12** | `RouteSettingsScreen.kt:212-221` | `DisposableEffect(Unit)` 无条件注册返回回调并直接 `onDismiss()`，与 `:200` 负责"保存更改？"确认的 `BackHandler` 同时注册且后注册优先（LIFO）⇒ 用户改了取件路径后按返回手势，未保存的修改被**静默丢弃**，不弹确认。该处注释自称"由内层 BackHandler 接管"，代码里没有这段逻辑。 | 已修 | R4 / `docs/audit/04-ui-and-deadcode.md` §6 |
| **H-13** | `AddressMatcher.kt:100`、`PickupRepository.kt:492`、`RuleMatchingScreen.kt:819` | 同一个"地址标签"有**三套互不兼容的生成规则**：`AddressMatcher.generateAddressTag()`（3号楼/小区名/前6字）、`PickupRepository.generateSimpleAddressTag()`（前8字）、`RuleMatchingScreen` 内联的 `finalAddress.take(2)`（前2字）。标签是取件路径排序与"常用驿站"的关联键 ⇒ 手动添加的记录永远匹配不上已保存的地址，既不显示自定义名也不参与排序。 | 已修 | R3 / `docs/audit/03-data-consistency.md` §8 |
| **H-14** | `README.md` §3/§4/§7/§9 | README 里**可机械复核的数字与引用是错的**，而且恰好落在自带检查器的盲区里：① 声称"布局与资源 XML 里的中文为 0"，实测 8 个 widget 布局里 `android:text` 硬编码中文 **29 处**（R6 修 L-07 后为 **28 处**）；② 声称 Kotlin 中文串字面量 **819 处**，实测 **1376 处**（口径：先剔注释、再统计双引号字面量里含中日韩汉字的个数 —— 口径没写下来正是这个数字能跑偏的原因，中途 ad-hoc 数还得到过 1509 / 1402 两个值）；③ 两处 `文件:行号` 引用整体漂移 **+7 行**（指向空行/指向另一个字段），而 `tools/check_doc_refs.py` 仍输出"✓ 全部成立"。README 用"机械校验"这个词支撑读者对它全部数字的信任，该强度超出工具能力。 | 已修 | R6 / `docs/audit/06-docs-and-verification.md` §1（数字改对 + 由 `check_doc_refs.py` 复算，删掉声明也算失败） |
| **H-15** | `RuleBasedPickupParser.kt:407`（原 `ADDRESS_PREFIX_WORDS`）、`cleanAddress()` | **R2 新发现**。`ADDRESS_PREFIX_WORDS = listOf("到达","已到","抵达","到","在","位于")` 被声明、被注释说明为「用于清洗地址开头的多余字符」，**全文没有任何地方使用它** —— 一具尸体常量。后果：`ADDRESS_PATTERNS` 的模式 3 没有引导词锚点、可从任意汉字起匹配，于是 `已到示例小区3号楼` 整串进库，用户看到的是「已到示例小区3号楼」。这与 H-13 是三套标签规则之外的**第四条**地址质量缺陷。 | 已修 | R2 / `docs/audit/02-parser.md` §6 |
| **H-16** | `RuleBasedPickupParser` 的 `CODE_PREFIX_PATTERNS` / `GENERIC_CODE_PATTERNS` 字符类（`[A-Za-z]`、`\d`） | **R3 新发现**。字符类只认 ASCII，而部分通知与短信确实用全角字母数字（`取件码 Ａ２－１－３４５６`）。这类短信在**进解析器那一步**就匹配不到，整条被丢弃 —— 用户看到"没被识别"，而下游所有归一化逻辑**根本没有机会执行**（等于白写）。这是典型的"下游修好了、上游走不到"，只有断言能发现。修法：`PickupIngest` 入口增加输入规范化（只转全角字母/数字/全角连字符，排版破折号不动）。 | 已修 | R3 / `docs/audit/03-data-consistency.md` §2 |

---

## 三、中

| ID | 位置 | 缺陷 | 状态 | 提交 |
|---|---|---|---|---|
| **M-01** | `BasePickupWidgetProvider.kt:538,557,562`、`:79-101`、`:585-589` | `goAsync()` 出现在 `updateAppWidget` 内部，而该函数被 `onUpdate` 的 `for (appWidgetId)` 循环调用、也被前台服务调用 ⇒ 一次 `onReceive` 内第 2 次起 `goAsync()` 返回 `null`，`finally` 的 `finish()` 抛 NPE（此处被 `catch (Throwable)` 吞掉，所以不崩，但异步守卫失效）。 | 已修 | R4 / `docs/audit/04-ui-and-deadcode.md` §1.3 |
| **M-02** | `SmsReceiver.kt:66-74`（【平台推断】） | 多段短信按 `(发件人, timestampMillis)` 分组，而 `timestampMillis` 是短信中心的 SCTS：某些运营商给同一长短信的各段写入**逐段递增**的 SCTS ⇒ 该合没合、码被从中间切断；同发件人同一秒投递两条**不同**短信 ⇒ 不该合却合了。 | 待修 | — |
| **M-03** | `BasePickupWidgetProvider.kt:519`、`WidgetDataPersistence.kt` | `PendingIntent` 用 `FLAG_MUTABLE`（fill-in intent 所必需）但没有任何调用方身份凭证 ⇒ 与 H-07 同源。修法必须与 H-07 一起做。 | 不修（附理由） | R5 / `docs/audit/05-security-and-license.md` §2.3 |
| **M-04** | `ComposeMainActivity.kt:251,261,320,445`、`:500-530` | `importSms()` 有 **4 个入口**且全部绕过 `ComposePickupViewModel` 里的 `isSmsImported` 闸门；向导"跳过"分支在无 `READ_SMS` 时立即查询短信库 → `SecurityException` 被 `PickupRepository.kt:409-412` 吞掉返回 0 → 用户看到 Toast **"没有新的取件记录"**（真相是"根本没有权限读"，而且"首次启动只导入一次"这个承诺在 Activity 路径上没有实现）。 | 已修 | R5 / `docs/audit/05-security-and-license.md` §12 |
| **M-05** | `tools/check_doc_refs.py:163-177` | 反引号内**裸类名引用**（`ComposeMainActivity:135`）分支只做行数越界判断，**从不做锚点校验**；带扩展名的分支锚点窗口 ±4/5 行且"句中任一标识符命中即通过" ⇒ H-14 的两处 +7 漂移正是这样漏掉的。**R6 打开后实际是 6 个缺口**（另含：多行号规格 `12-34`/`594, 1137` 整批不校验、逗号列表第二个数从未被查、无数字复算、符号池只装 Kotlin），逐条见 R6 文档 §2。 | 已修 | R6 / `docs/audit/06-docs-and-verification.md` §2；R7 补到 11 个突变 |
| **M-06** | `PickupCodeExtractorV3.kt:296`、`README.md` §8 | 上游披露数目核对为真（四处注释、三个上游），但其中一处写作**"取件码 APP"**：无仓库地址、无版本、无许可证名。GPL 系合规要求能定位到具体作品与条款，"一个名字"不可核验。 | 已修 | R5 / `docs/audit/05-security-and-license.md` §4 |
| **M-07** | `PickupNotificationListener.kt:39-49`（【平台推断】） | 通知兜底通道的短信应用白名单硬编码 9 个包名，**缺三星 `com.samsung.android.messaging` 与 ColorOS `com.coloros.mms`**，而 README 的品牌适配章节恰好点名了这两家 ⇒ 这两家用户的通知兜底是哑的。 | 已修 | R5 / `docs/audit/05-security-and-license.md` §5 |
| **M-08** | `RuleMatchingScreen.kt:819` | 手动添加记录时 `addressTag = finalAddress.take(2)`（前 2 字）。详见 H-13。 | 已修 | R3 / `docs/audit/03-data-consistency.md` §8（随 H-13 一并改） |
| **M-09** | `RuleMatchingScreen.kt:826-862` | ViewModel 返回 `AddPickupResult.Error` 时 `!success && dupCode != null` 为假 ⇒ 走到 `if (!hasAnyDuplicate)` ⇒ **数据库写失败被当成保存成功**，界面正常关闭，并把这次失败写入 `SelfLearningManager` 的纠错样本。 | 已修 | R4 / `docs/audit/04-ui-and-deadcode.md` §3 |
| **M-10** | `PickupDetailDialog.kt:198-251,284-297` | 编辑地址时 `showSaveButton` 仅在"文本与原文不同"时为真，为假时落到 `else` 渲染"复制取件码" ⇒ 用户把地址改回原样后**保存按钮消失、且没有取消按钮**，编辑态无法退出，关弹窗则修改静默丢弃。 | 已修 | R4 / `docs/audit/04-ui-and-deadcode.md` §4 |
| **M-11** | `PermissionActionHelper.kt:294-303`、`:145-152`、`:173-189` | ① `openAppInfo()` 是唯一没有 `try-catch` 的 `startActivity`，却被多条兜底链调用（兜底函数本身成了崩溃源）；② 小米跳转用 `extra_packagename`，而 `SmsPermissionManager`/`MiuiPermissionHelper` 用的是正确的 `extra_pkgname` ⇒ 页面打开但不定位到本应用；③ 空白通行证写死 `com.miui.permcenter.settings.PrivacyAppsManagerActivity`，该页在多数 MIUI/HyperOS 上不存在。 | 待修 | — |
| **M-12** | `PermissionChecker.kt:356-366`、`BatteryOptimizationHelper.kt:60-64`、`AndroidManifest.xml` | 调用了 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，但清单**未声明** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` ⇒ 部分 ROM 上弹窗不出现或开关不可点。 | 已修 | R5 / `docs/audit/05-security-and-license.md` §6 |
| **M-13** | `AndroidManifest.xml:11` | 同时声明 `USE_EXACT_ALARM` 与 `SCHEDULE_EXACT_ALARM`。`USE_EXACT_ALARM` 是 Google Play **受限权限**（仅闹钟/日历类应用可声明），本应用声明它有被拒风险；README 权限表也未披露这一点。 | 已修 | R5 / `docs/audit/05-security-and-license.md` §7 |
| **M-14** | `app/build.gradle.kts:78-80` | `lint { abortOnError = false }` ⇒ lint 永不使构建失败，CI 里没有任何静态检查门。 | 待修 | — |
| **M-15** | `app/build.gradle.kts:75-77` | `unitTests.isReturnDefaultValues = true` 让所有未 mock 的 `android.*` 调用返回默认值而不抛错；README 把它当作"解决 not mocked 的正解"，但它同时会让被测代码里的真实 Android API 调用**静默返回默认值**，掩盖逻辑错误。 | 待修 | — |
| **M-16** | `res/values/widget_colors.xml`（整文件）、`res/values/colors.xml`（25 条中 18 条）、`res/drawable/ic_check.xml`、`ic_package.xml` | 全仓零引用。`widget_colors.xml` 整文件已死 —— 因为 8 个 widget 布局直接写 `#FFFFFF`/`#1A1A1A` 字面量。README 却声称本轮已"删掉无人引用的资源"。 | 待修 | — |
| **M-17** | `res/values-night/themes.xml` | 与 `res/values/themes.xml` **逐字节等价**（含亮色 `windowBackground`）⇒ night 资源目录存在但零差异，深色下窗口背景仍是亮色。 | 待修 | — |
| **M-18** | `res/drawable/ic_empty_box.xml:9` | `pathData` 含绝对跳变（`...V18zM18,10h-2V8h2V10z`），导致空状态图标右半形状错误。 | 待修 | — |
| **M-19** | `SmsPermissionChecker.kt:168-190` | vivo/iQOO 与三星机型被**无条件**塞入"检查验证码安全保护/自动删除验证码"缺失项，与用户实际状态无关 ⇒ 每次检测都固定显示，"重新检测"也永不消失 ⇒ 用户认为权限永远修不好。 | 待修 | — |
| **M-20** | `SmsPermissionChecker.kt:443-453`、`:155-165` | `checkVerificationCodeProtection` 恒返回 `false` ⇒ 华为/荣耀的 `VERIFICATION_CODE_PROTECT` 条目、`PermissionType` 分支、`PermissionGuideDialog.kt:317` 图标分支全部不可达（有 UI 无实现）。 | 待修 | — |
| **M-21** | `NotificationPermissionHelper.kt:120-128` | `isNotificationListenerRunning` 把 `COMPONENT_ENABLED_STATE_DEFAULT` 当作"已启用"，而 DEFAULT 几乎是每台设备的默认值 ⇒ 该三路 OR 判定恒真。 | 待修 | — |
| **M-22** | `BasePickupWidgetProvider.kt:408-416` | `updateProgressBadge` 注释自称"只更新一个 TextView"，实际用 `updateAppWidget` **整体替换** RemoteViews ⇒ 刷新瞬间 ListView adapter、空态、按钮的 PendingIntent 全部丢失。 | 已修 | R4 / `docs/audit/04-ui-and-deadcode.md` §2.4 |
| **M-23** | `BasePickupWidgetProvider.kt:158-178,232-284,298-416` | `ACTION_REFRESH` / `ACTION_SILENT_REFRESH` **全仓无发送方**（刷新按钮走 `WidgetActionService`）⇒ 约 200 行死代码；而这些分支正是 H-08 崩溃链的唯一入口，属于"没有入口却扩大了伪造面"。 | 已修 | R4 / `docs/audit/04-ui-and-deadcode.md` §2 |
| **M-24** | `RaceConditionNote` | 见 `03-data-consistency.md` 对本轮新增并发路径的说明。 | 待修 | — |
| **M-25** | `tools/audit/scan_sensitive.py:82-87,113`、`tools/audit/scan_history.py:173`（R6 新发现） | 脱敏扫描器的"疑似示例"豁免判定有**三条**病灶：① **豁免表与规则表脱节，7 条从未生效过** —— `12345678901` 第二位不在 `[3-9]`，`CN_MOBILE` 的形状决定它永远不可能被产出；`1[3-9]x{4,}` 连它自己想要的掩码写法都匹配不上；`localhost`/`F5FAFA`/`FF[0-9A-F]{6}` 在本仓库规则表里没有任何一条能产出。② **过宽**：判定是"值里含某个子串"，于是 19 位银行卡号 `6222021234567890123` 因为含子串 `12345678901` 被判成示例，`test@163.com`/`foo@bar.com`/`your_name@qq.com` 这些**真实可注册**的邮箱也被放过（实测旧版放过 9 处）。③ **同一判定有两份实现**（`scan_history.py` 抄了一份），与 R5 图标检查器踩过的是同一个坑：测试会在复制品上通过。 | 已修 | R6 / `docs/audit/05-security-and-license.md` §15（判定改成 `placeholder_of(value)`，只看值本身；每条豁免带可机械核对的见证值） |
| **M-26** | `tools/audit/scan_history.py:217-231`（R7 新发现） | 历史门的退出码只看 `BLOCK` 级 ⇒ 本仓库**实际发生过两次的泄漏类别**（注释/文档里写真实取件码、真实小区名）落在 MED/HIGH，**结构上不可能让门失败**。门打印 `✓ 历史对象里没有 BLOCK 级命中` 时，8/15 个提交正带着作者的真实数据。修法不是提升严重度（会把夹具全炸出来，于是有人去删夹具或去放宽门），而是**加第二条判据**：精确值反查（A 级进退出码、B 级只报命中提交），并让逐提交 LEAK 可见。 | 已修 | R7 / `docs/audit/06-docs-and-verification.md` §9-10 |
| **M-27** | `tools/audit/*.py`（11 个文件，R7 新发现） | 在中文 Windows 上 `print` 里的 `«` `✓` `❌` `⚠` 抛 `UnicodeEncodeError`，脚本**在报告结论之前崩掉**。`scan_history.py` 实测崩溃并返回非零码 —— 于是"门跑不了"与"门查出了问题"在退出码上不可区分；而 CI 跑在 ubuntu 上永远绿，维护者本机永远不可用。修法：11 个脚本统一在开头 `sys.stdout/stderr.reconfigure(encoding='utf-8', errors='replace')`。 | 已修 | R7 / `docs/audit/06-docs-and-verification.md` §13 |
| **M-28** | `tools/audit/scan_sensitive.py:174`（R7 第二次复核新发现） | 二进制元数据表里写着 `b"E:/PickupReview"` —— 一个**公开**工具把作者私有目录名当成"泄漏特征"。两个错叠在一起：① 把那个名字本身公开了出去（检测特征恰恰是最容易被读到的地方）；② 只能抓这一个字符串，换盘符或换目录名就完全失效 ⇒ 假阴性。修法：删掉硬编码，改成通用形态 `DRIVE_SELF_PATH_RE`（`盘符:\目录\...`，要求 ≥2 层压噪声）；`scan_history.py` 的二进制分支此前只认"必须含 `Users`"，一并改为共用这条通用判据；补 2 条回归用例（能抓到 `C:\build\agent\proj\src`；源码里不许再出现这三个私有目录名）。 | 已修 | R7 / `docs/audit/06-docs-and-verification.md` §13.1 |

---

## 四、低

| ID | 位置 | 缺陷 | 状态 | 提交 |
|---|---|---|---|---|
| **L-01** | `SmsReceiver.kt:181,199,201`、`PickupNotificationListener.kt:116,125`、`PickupRepository.kt:103,112,120` | `Log.d` 里带裸取件码。`proguard-rules.pro` 的 `-assumenosideeffects` 覆盖 `v/d/i`，release 会剥掉 ⇒ 不影响发布包，debug 构建会打印。 | 待修 | — |
| **L-02** | `PickupItem.kt:61,86`、`PickupCard.kt:436` | `STATUS_EXPIRED` 已是死状态（全仓无写入方，`ExpireCheckWorker` 明确注释"不再改写"，`MIGRATION_8_9` 还把历史值刷回 0）。 | 不修（附理由） | R4 / `docs/audit/04-ui-and-deadcode.md` §9 |
| **L-03** | `PickupRepository.kt:330-331,376-377` | 导入时把超过显示天数的短信直接写成 `STATUS_PICKED` 且 `markedTime` 在过去 ⇒ 用户看到一批自己从没取过的包裹出现在"已取件"页，且下次 `deleteOldPickedItems` 会把它们物理删除。 | 待修 | — |
| **L-04** | 全仓 | `\|\| true` 永真条件 ×4（`RuleMatchingScreen.kt:467,476,485,494`）、`AnimatedVisibility(visible = true)` ×3（`RouteSettingsScreen.kt:396,411,428`）、`isAnalyzePressed`/`isSavePressed` 恒 false 的死状态、`remember` 缓存时间派生值。 | 已修 | R4 / `docs/audit/04-ui-and-deadcode.md` §8 |
| **L-05** | 多处 | 未使用 import（`MainScreen.kt`、`PickupListScreen.kt`、`BottomNavigationBar.kt`、`PickupCard.kt`、`RouteSettingsScreen.kt`）。 | 已修 | R4 / `docs/audit/04-ui-and-deadcode.md` §8 部分；R5 补完 14 处 / `docs/audit/05-security-and-license.md` §10.2 |
| **L-06** | `WidgetPinHelper.kt:52-68,245`、`AddWidgetGuideDialog.kt:213`、`res/xml/app_shortcuts.xml` | 小部件尺寸映射表与引导文案写死 6 种，**漏掉已声明的 2×2** ⇒ 用户按引导找不到该尺寸。 | 待修 | — |
| **L-07** | `res/layout/widget_pickup_item.xml:34,49`、`widget/PickupWidgetService.kt:83-85` | 布局里硬编码 `android:text="ABCD1234"` / `"菜鸟驿站"` 占位文案；`getViewAt()` 的越界提前返回**直接 `return views`** ⇒ 数据刷新瞬间的越界请求会把这两条写死的示例文案渲染出去，用户看到一条并不存在的包裹、而那个"取件码"看起来和真的一样。 | 已修 | R6 / `docs/audit/06-docs-and-verification.md` §5.4 |
| **L-08** | `AndroidManifest.xml:15-16` | 声明 `WAKE_LOCK`，注释称"goAsync 需要"，但全仓无任何 `newWakeLock`。 | 已修 | R5 / `docs/audit/05-security-and-license.md` §8 |
| **L-09** | `AndroidManifest.xml:85-89` | `DataChangeContentProvider` 声明 `android:process=":main"`，注释却称"主进程实例化" —— `:main` **不等于**默认主进程（默认进程名=包名）。 | 已修 | R5 / `docs/audit/05-security-and-license.md` §9 |
| **L-10** | `ExpireCheckWorker.kt:70-75` | 只清 `status=1 AND markedTime<30天`；过期未取的待取记录从不清理 ⇒ 长期只增不减。 | 待修 | — |
| **L-11** | `mipmap-{m,h,x,xx,xxx}dpi/ic_launcher_round.png` | 5 个 round 图标与同目录方图**逐字节相同** ⇒ API 24–25 上"圆形图标"实为方图。 | 待修 | — |
| **L-12** | `Util/PerformanceMonitor.kt:26-27,74-137` | `startTimes`/`frameTimings` 为非线程安全可变 Map；`recordFrameTime`/`getFrameStats`/`getPerformanceReport` 全仓零调用；`ENABLED` 恒 true 且逐次 `Log.d`。 | 已修 | R4 / `docs/audit/04-ui-and-deadcode.md` §8 |
| **L-13** | `ScrollOptimizations.kt:33-48` | `rememberIsScrollingUp` 在同一帧内自比，没有保留上一帧 ⇒ 恒返回 false（当前无调用方）。 | 已修 | R4 / `docs/audit/04-ui-and-deadcode.md` §8 |
| **L-14** | `ShortcutTrampolineActivity.kt:90-92,117` | 注释写"不调用 finish()"，代码立即 `finish()`。 | 已修 | R4 / `docs/audit/04-ui-and-deadcode.md` §8 |
| **L-15** | Kotlin / XML | UI 文案大量以内联字面量硬编码（可机械复核的数字见 `06-docs-and-verification.md`），未外置到 `strings.xml`。 | 待修 | — |
| **L-16** | `com.shanqiu.pickupmanager` 包名 | 包名嵌入了作者的个人化昵称（Windows 用户名同源）。改名会破坏已安装用户的数据库与备份路径，代价高于收益。 | 待修 | — |
| **L-17** | `PickupRepository.kt` / `ComposePickupViewModel.kt` / `WidgetBrandAdapter.kt` 等 | 一批"有实现没入口"的死函数（代理审计逐条列出）。 | 待修 | — |
| **L-18** | `RuleBasedPickupParser.kt`（原 686、831 行） | **R2 新发现**。两处编译告警：`trackingNumbers.add(matcher.group(1))` / `addresses.add(matcher.group(1))`。`Matcher.group()` 是 Java 平台类型，Kotlin 不做空检查就交给 `add()`。今天正则第 1 组必然存在所以不炸；但只要有人把该组改成可选 `(...)?`，立刻变成一条**没有任何提示的运行时 NPE**，且只在那条特定短信上炸。 | 已修 | R2 / `docs/audit/02-parser.md` §4 |
| **L-19** | `RuleBasedPickupParser.kt` `removeCompanyKeywords()` | **R2 新发现**。`trimStart('内','的','在','到')` 会把「在水一方小区」截成「水一方小区」。**不修**：要修必须先能判断"这个字是不是引导词"，而模式 3 没有引导词锚点、拿不到这个信息；在能正确区分之前，引入单字截断规则的风险大于收益。已在代码注释与 R2 文档 §8 记录。 | 不修（附理由） | R2 / `docs/audit/02-parser.md` §8 |
| **L-20** | `PickupRepository.doImportSms()` | **R3 新发现**。约 45 行的 V2 平行解析分支。`PickupIngest` 内部已经跑过 V2 且返回空，此段**永远不会产出记录** —— 纯死代码，而它曾经是"三条通道三种结果"的第三个来源。已删除。 | 已修 | R3 / `docs/audit/03-data-consistency.md` §4 |
| **L-21** | `PickupCodeExtractorV3.kt:192,206,207` | **R5 新发现**。三处 `Matcher.group(1)` —— Java 平台类型 `String!` 直接交给需要非空 `String` 的位置。与 L-18 同族，但只有在**该文件被重新编译**时才会出现在构建日志里。 | 已修 | R5 / `docs/audit/05-security-and-license.md` §10 |
| **L-22** | `SelfLearningManager.kt:271-274,283-284` | **R5 新发现**。`obj.optString(name, null)` —— 字面量 `null` 传给 Java 平台类型参数，实参被推断成 `Nothing?`。与 L-18/L-21 同族。**它是一次强制全量重编译翻出来的**：增量构建下构建日志里 0 条。 | 已修 | R5 / `docs/audit/05-security-and-license.md` §10.1 |
| **L-23** | `tools/audit/scan_sensitive.py:146`（R6 新发现） | 二进制判别写成 `ext in BINARY_EXT or size > 4MB` ⇒ **一个 4MB 的文本文件会被整段跳过**，而且没有任何提示；同时"什么算二进制"是靠扩展名猜的。**潜在**盲区而非已发生的泄漏（实测当前工作树 173 个文件里 0 个超 4MB）。修法：按内容判别（前 4KB 含 NUL，git 用的同一条启发式），超 64MB 的文件**报出来**而不是静默 continue；回归测试会真的造一个 >4MB 的文本文件断言仍抓得到。 | 已修 | R6 / `docs/audit/05-security-and-license.md` §15.4 |
| **L-24** | `tools/audit/redact_history.py:16,43` + `.gitignore:83`（R7 新发现） | 报错让读者"按 `tools/audit/redact_map.example.txt` 的格式在本机准备"，但 `git ls-files` 查不到这个文件 —— 它被兜底段的 `*.txt` 挡住了。仓库把要提交的内容清干净了，却把**说明指向的格式样例**挡在仓库外；克隆者照提示找不到文件，只能猜格式（而格式猜错是静默的：`split(TAB)` 不中就整行跳过，于是"重写历史"什么都不替换）。修法：`.gitignore` 加一条白名单放行该样例文件。 | 已修 | R7 / `docs/audit/06-docs-and-verification.md` §13 |
| **L-25** | `app/src/main/AndroidManifest.xml:25-30`、README §4、`docs/KNOWN_ISSUES.md` 问题 31（发布前核对新增） | R5 记的是「删掉了 WAKE_LOCK」，问题 31 也只列了另外三项合并进来的权限。但 `aapt dump permissions` 实测成品 APK 申请 **10 项系统权限** —— `androidx.work:work-runtime:2.9.0` 把自己的 `WAKE_LOCK` 合并了进来，所以那句「删除」只对手写清单成立，对分发出去的 APK 不成立。修法：§4 与问题 31 逐项列全并标注引入方（合并归因报告可查），清单注释里写明「删的只是这张手写清单」；真正剥掉需 `tools:node="remove"` + 真机验证。 | 已修 | R8 / `docs/audit/05-security-and-license.md` |

---

## 一、H-06/M-06 之外的「文档与实现不符」汇总

README 是本项目最重要的产品面（它以"逐条可核对"为卖点），因此文档与实现的偏差单列一节。
下表在 R1–R5 期间只记"发现了什么"，**R6 把每一条都补上了终态**（右列的"R6 已改/已修"）：

| 位置 | 文档说的 | 代码里的 |
|---|---|---|
| §1 能力表 / §3 数据存储范围 | 数据离开设备的通道只有剪贴板 | 还有 `Intent.ACTION_SEND` 分享（H-06）—— **R5 已补「数据离开设备的通道」小节** |
| §1 权限表 | 通知权限用于"显示取件码通知" | `PermissionSetupDialog.kt:632` 与实现都说"不发任何内容型提醒"，两处文案互相矛盾 |
| §3/§4 | `READ_SMS` "首次启动自动导入一次" | Activity 有 4 个入口绕过闸门（M-04） |
| §3 | 公开版本不包含任何真实数据 | 在工作树成立、在 git 历史不成立（B-01） |
| §3 | 布局与资源 XML 里的中文为 0 | 29 处（H-14）—— **R6 已改**（数字改为 28，因为 L-07 顺手删掉了两条写死的示例文案），且由 `check_doc_refs.py` 复算 |
| §3 | Kotlin 中文串字面量 819 处 | 1376 处（**口径**：先剔注释再统计双引号字面量）—— **R6 已改**，且由 `check_doc_refs.py` 复算 |
| §4 | 所有 `文件:行号` 由 `check_doc_refs.py` **机械校验** | 工具不校验裸类名引用、锚点窗口过宽 ⇒ 两处 +7 漂移漏检（H-14/M-05）—— **R6 已修工具**并补负向突变测试（R7 补到 11 个） |
| §7 | 取件码命中 100.0% | 语料不在仓库，无法复算；且解析器在真实模板短信上会把电话号当取件码（H-01） |
| §8 | 未复制上游任何一行 | 仓库内无法证伪（`99-unverifiable.md` U-2） |
| 权限清单 | 未出现 `USE_EXACT_ALARM` 的 Play 受限属性说明 | 清单里确实声明了（M-13） |
| 环境要求表 | Python 只被 `check_doc_refs.py` 用到 | 仓库里共有 **13** 个 Python 脚本（`tools/` 2 个 + `tools/audit/` 11 个），其中 **9 条命令是 CI 门禁** —— **R6 已改**，README 改为指向完整清单 |
| §（文件行数） | `PickupSmsParserV2` 在不同小节被写成 1000 行与 1007 行 | 实测 1007 —— **R6 已统一** |
| §6 测试 | 单元测试「34 个用例」（同节上文另写「36 个」） | 实测 **83** 个（81 通过 / 2 环境门控跳过）—— **R6 已改**，且由 `check_doc_refs.py` 复算 |
| §6 测试 | 「`tools/check_doc_refs.py` 机械校验」暗示文档检查已覆盖 | 编译器不对未使用 import 报警告，构建日志干净 ≠ 工作树干净：实测存在 **14 处死图标 import**（R5 已补检查器） |
| 审计报告 §13（本轮自身） | 「全仓剩余警告 2 条」 | 强制全量重编译实测 **22 条**（R5 自纠，见 `05-security-and-license.md` §10.1） |
| 审计报告 §11（R5 自身） | 「同一个字符串 `18712345678` 出现 16 次，扫描器只标 3 处 ⇒ 判定自相矛盾」 | **16 是两个值的合计**（`18712345678` ×13 + `13800138000` ×3），判定当时**本来就是按值一致的** —— 是报告把**聚合计数当成了单值计数**。R6 自纠，见 `05-security-and-license.md` §11 与 `06-docs-and-verification.md` §4 |
