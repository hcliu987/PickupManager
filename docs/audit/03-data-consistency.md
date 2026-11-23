# R3 — 数据层与三条采集通道的一致性（H-02 / H-03 / H-04 / H-13）

**本轮目标**：把"同一个包裹会不会变成两条记录 / 会不会消失 / 同一条短信在三条通道下结果一样吗"这三件事钉死。
**结论**：三件都是坏的。最严重的一条是**新包裹被静默吞掉** —— 不入库、不通知、列表里看不到。

| 条目 | 内容 | 状态 |
|---|---|---|
| H-02 | 留档表用未归一化取件码 → 删掉的记录复活 | **已修** |
| H-03 | `pickupCode` 全局唯一 + IGNORE ⇒ 同码新包裹静默消失 | **已修**（含 schema 迁移 9→10） |
| H-04 | 三条通道三套解析/归一/标签 | **已修** |
| H-13 / M-08 | 地址标签四套实现（清点后实为四套 + 两种裸用） | **已修** |
| **H-16** | 全角取件码**根本进不了解析器**（R3 自查发现） | **已修** |
| **L-20** | `PickupRepository` 里一段约 45 行的死代码（V2 平行分支） | **已修**（删除） |

---

## 1. H-02 的根因：归一化不是"顺手清理"，是所有判断的前提

留档表（`deleted_pickup_items`）的作用只有一句话：**下次同一条短信再出现时认得出来**。
它的键是取件码。问题是同一张取件码在不同通道下会得到**不同的字符串**：

| 来源 | 可能的形态 |
|---|---|
| 广播通道（规则引擎） | `A2-1-3456` |
| 通知通道（通知文本） | `Ａ２－１－３４５６`（全角，系统通知里很常见） |
| 批量导入（短信库原文） | `A2 - 1 - 3456`（带空格） |
| 大小写 | `a2-1-3456` |

只要有一处存裸码、一处查归一码，`isDeleted` 就**恒为 false**。用户的现象是：
"我明明删掉了，下拉刷新一下它又回来了。"

修法是新增 [PickupCodeNormalizer]，并在**所有比较点**上先归一：

- `PickupRepository.deleteItem()` —— 写入留档表前归一；
- `PickupRepository.wasDeleted()` —— 查询前归一；
- `PickupRepository.insertItemWithDedup()` —— 判重前归一；
- `PickupRepository.overwriteItemByPickupCode()` —— `UPDATE ... WHERE` 也是比较，同样要归一
  （不归一的话，用户在编辑界面点"保存"会静默改不到任何行，`rowsUpdated = 0`）；
- `PickupItemDao.insertOrUpdateItem()` —— 落库前归一。

**归一化刻意不做的事**（每一条都有理由写在代码里）：

| 不做 | 为什么 |
|---|---|
| 不删连字符 | `1234-5678` 与 `12345678` 在某些驿站是两个不同字段，合并会撞车 |
| 不去前导零 | `0012` 与 `12` 可能是同一柜机的不同格口，无法证明等价 |
| 不做"取末 4 位"截断 | 那正是 R2 里"把手机号切成取件码"的成因 |

规则只有一条：**只允许消除确定的书写差异，不允许做任何猜测性改写。**

## 2. 一个被自己的测试逼出来的漏洞（H-16）

写归一化器时我在文档里写了"通知通道的文本常带全角"，然后写测试去证明它。
测试**红了**：

```
输入：【菜鸟驿站】…取件码 Ａ２－１－３４５６，请及时领取
结果：codes = []
```

原因不在归一化器，在**它的上游**：`CODE_PREFIX_PATTERNS` 的字符类是
`[A-Za-z]` 与 `\d` —— 只认 ASCII（源文件里还专门注明"修复7：ASCII字母"）。
全角取件码在**进解析器那一步**就匹配不到，于是整条短信被丢弃，
`normalizePickupCode` **从来没有机会被执行**。

也就是说：R3 前面所有归一化工作，对全角短信是**无效的**。
这类"下游修好了、上游根本走不到"的问题，只有断言能发现 —— 读代码永远读不出来。

修法：在 `PickupIngest.parse` 的入口加一层**输入规范化**
（`normalizeFullWidthAscii`），只转全角字母与数字，不动全角标点
（正则的前瞻里本来就同时列了全角与半角标点，转标点只会让 diff 变大）。

同时守住一条：`rawSms` 字段存的是**用户收到的原文**，不是规范化后的副本 ——
详情页的"查看原文"必须能看到原始短信。这条单独有一个测试。

## 3. H-03：为什么必须改 schema，而不是只改应用层判断

原设计给 `pickupCode` 建了**全局唯一索引**，注释里的理由是
"取件码是包裹的物理身份"。这个前提**不成立**：

- 丰巢柜的取件码是 4 位，同一台柜机每天发放上千个，**当天就可能重复**；
- 驿站按当日序号发码，同一驿站隔几天出现同一个号是常态。

唯一索引 + `OnConflictStrategy.IGNORE` 的后果是**静默吞件**：`insertItem` 返回 `-1`，
调用方看到"插入返回 false"就 `Log.d("取件码已存在，跳过")` 一笔带过。
用户完全不知道有这个包裹存在。

**为什么不能只在应用层改判断**：应用层就算判对了"这是窗口外的新包裹"，
`INSERT` 到了数据库一样被唯一索引 IGNORE 掉。所以必须动 schema。

新增 `MIGRATION_9_10`：

```kotlin
// 1. 归一化历史数据的 pickupCode（只做大小写/空白/连字符收敛）
// 2. DROP INDEX IF EXISTS index_pickup_items_pickupCode      ← 唯一索引
// 3. CREATE INDEX index_pickup_items_pickupCode              ← 同名非唯一索引
```

**不删任何数据。** 但这里必须挂一条诚实的说明，写进 README：

> 历史上因唯一索引冲突而"本该存在却没入库"的记录**无法找回** —— 它们从未被写入。

去重口径改由 [PickupCodeMergePolicy] 表达：

```
同一张码 + 接收时间相差 ≤ 6 小时  → 同一个包裹（多通道重复采集），不插入
同一张码 + 接收时间相差 > 6 小时  → 新包裹，正常入库
```

**6 小时这个值的来源**，以及为什么与 `DeletedPickupItemDao.isDeleted` 的容差
**刻意保持一致**：三条通道的时钟不同源（短信中心的 SCTS / 本机接收时间 /
`Notification.when`），相差几分钟到几小时都属正常，窗口必须覆盖这个抖动；
而"同一张码被复用"在现实中至少隔一次投递周期。两处口径不一致的话，
"删掉的复活"和"重复入库"会以互相矛盾的方式出现。

窗口端点由 `windowAround()` 在 Kotlin 侧算好后**作为 SQL 参数**传入，
SQL 里不写死毫秒数 —— 保证"什么算同一个包裹"全仓只有一处定义，
并且这一处在纯 JVM 单测里测得到（`PickupCodeMergePolicyTest`）。

## 4. H-04：三条通道，一次收敛

改造前后对照：

| | 解析器 | 取件码归一 | 地址标签 | 地址归一 |
|---|---|---|---|---|
| `SmsReceiver`（实时广播） | 规则引擎 → V2 回落（**手写一份**） | 无 | **原始地址整串** | **不过 AddressMatcher** |
| `doImportSms`（批量导入） | 规则引擎 → V2 回落（**另写一份**） | 无 | AddressMatcher | 过 |
| `PickupNotificationListener`（通知） | **只有 V2** | 无 | V2 自己的 | 不过 |

三份代码、两种标签口径、零归一化。用户看不到"通道"这个概念，
只看到"为什么我实时收到的记录和导入进来的不一样"。

现在：三处都调用 `PickupIngest.parse(smsText, senderPhone, receiveTime)`，
统一完成**解析 → 归一 → 打标签**；地址归一（`AddressMatcher`，需要 DB）
作为后续增强步骤，**广播通道也补上了**（原来只有导入通道做，这正是
README 第 4 节问题 6 记录的不一致）。

顺手删掉 `PickupRepository` 里那段约 45 行的 V2 平行业本（**L-20**）：
`PickupIngest` 内部已经跑过 V2 且返回空，此段永远不会产出记录，
是纯粹的死代码 —— 而它曾经是"三条通道三种结果"的第三个来源。

## 5. H-13：地址标签，清点后发现是四套加两种裸用

基线审计说"三套"，实际逐个 `grep` 出来是：

| 位置 | 规则 | 对「示例小区3号楼驿站」的实际产出 |
|---|---|---|
| `AddressMatcher.generateAddressTag()` | 楼号 → 小区 → 驿站 → 前 6 字 | `3号楼` |
| `PickupRepository.generateSimpleAddressTag()` | 楼号 → 前 **8** 字 | `3号楼` |
| `PickupSmsParserV2.generateAddressTag()` | 楼号 → 小区 → 驿站(**只取通名**) → 前 6 字 | **`3号`** / **`驿站`** |
| `RuleMatchingScreen:819` | `finalAddress.take(2)` | **`示例`** |
| `SmsReceiver:188`、`PickupRepository:294` | `addressTag = 原始地址`（长度不限） | 整串 |

`addressTag` 不是展示用的美化字段，它是**取件路径排序**与 `saved_addresses`
（常用驿站）的**关联键**。五套口径意味着：手动添加的记录永远匹配不上已保存的地址，
用户设的自定义名不显示、也不参与路线排序 —— 用户会认为"我明明设过"。

修法：新增 [AddressTag] 作为唯一实现，算法**刻意选择** `AddressMatcher` 那一套。
理由不是"它最好"，而是 `saved_addresses` 里的历史键就是按它生成的 ——
换算法会让老用户的已保存驿站全部失联，这个迁移代价不可接受。

`AddressMatcher.generateAddressTag()` 与 `PickupSmsParserV2.generateAddressTag()`
保留为**转发**（删除公开方法属于接口变更），另外两处调用点直接改掉。

## 6. 本轮新增的三个测试类（27 条）

| 类 | 条数 | 守什么 |
|---|---|---|
| `PickupCodeNormalizerTest` | 11 | 归一的每条规则 + **刻意不做的事**（防止后人"顺手"加上）+ 幂等性 |
| `PickupCodeMergePolicyTest` | 5 | 窗口内/窗口外/边界/码不同的判定；窗口常量与留档表容差一致 |
| `PickupIngestTest` | 11 | 产出已归一、标签由统一生成器产出、rawSms 存原文、全角能进解析器、确定性、去重 |

全量结果（`app/build/test-results/testDebugUnitTest/` 实测，不是估算）：

```
ParserPhoneNumberRegressionTest     6
ParserRobustnessTest                8
PickupCodeExtractorV3Test          20
PickupCodeMergePolicyTest           5
PickupCodeNormalizerTest           11
PickupIngestTest                   11
RuleBasedPickupParserAccuracyEval   2   ← 跳过 2：需要真实短信语料，脚本见 README 第 7 节
RuleBasedPickupParserTest          14
                                 ----
                             合计  77 条（执行 75，跳过 2）
```

R2 结束时是 50 条，R3 增加 27 条。**既有测试没有一条被改动**。

其中三条是**反回归**性质的，专门用来挡住"看起来更合理"的简化：

- `不删连字符_因为有无连字符是两个不同字段`
- `不去前导零_因为无法证明等价`
- `不做末几位截断_截断正是把手机号切成码的成因`

## 7. 数据库迁移的验证边界（必须说清楚）

`MIGRATION_9_10` **没有**被自动化测试覆盖 —— 覆盖它需要
`androidx.room:room-testing` 的 `MigrationTestHelper`，而那是
instrumentation 测试（要真机或模拟器）。本项目目前只有纯 JVM 单测。

所以本轮的验证是**分层**的，边界写在这里，不装作全覆盖：

| 层 | 是否验证 | 方式 |
|---|---|---|
| 归一化、窗口判定、统一入口 | **是** | 23 条纯 JVM 单测 |
| 全量编译（含 KSP 生成的 DAO 实现、schema 导出） | **是** | `./gradlew --offline :app:testDebugUnitTest` 通过 |
| 迁移 9→10 在真机上的执行 | **否** | 需要 instrumentation；列为 `99-final-report.md` 的"无法验证"项 |

编译通过本身有意义：KSP 会把 `@Query` 上的 SQL 与实体定义做静态校验，
`getSameParcelInWindow` 的 SQL、`Index` 属性的变化、schema 版本号如果不自洽，
编译就会失败。但这不等于迁移在真机上跑得通。

## 8. 复现

```bash
GRADLE_USER_HOME=D:/.gradle ANDROID_HOME=D:/.gradle/android-sdk \
    ./gradlew --offline :app:testDebugUnitTest
```

要验证 H-16（全角短信进不了解析器），把 `PickupIngest.parse` 里的
`val scannable = PickupCodeNormalizer.normalizeFullWidthAscii(smsText)`
换回 `val scannable = smsText`，`全角短信能走到解析器_这是输入规范化的意义`
与 `产出里的取件码已经归一` 应当重新变红。
