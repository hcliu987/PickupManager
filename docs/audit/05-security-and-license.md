# 第 5 轮：平台安全面与许可证 / 隐私承诺一致性

**本轮范围**：H-06、H-07、H-09、M-03、M-06、M-07、M-12、M-13、L-08、L-09，
以及本轮新发现的 L-21。

**本轮的核心问题不是"代码里有漏洞"，而是"文档承诺与代码能力之间的差"。**
这个项目把"逐条可核对"当作卖点，所以一条不成立的承诺比一个未修的缺陷更致命 ——
前者是**主动误导**，后者只是"还没做完"。本轮修的主要是前者。

---

## 1. H-06：应用内有一条把取件码 + 地址 + 快递公司发出去的通道，文档从未提及

### 1.1 通道本身

`ShareUtils.sharePickupItems()` 把内容拼成一段文本，然后：

```kotlin
val shareIntent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_SUBJECT, "取件码分享")
    putExtra(Intent.EXTRA_TEXT, shareText)
}
val chooserIntent = Intent.createChooser(shareIntent, "分享到")
chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
context.startActivity(chooserIntent)
```

内容包含**取件码 + 地址文本 + 快递公司**（见 `formatSingleItem` / `formatMultipleItems`）。
触发点是 `MainScreen` 的 `onShareClick` / `onShareAllClick`（列表卡片的"分享"与"分享全部"）。

### 1.2 为什么这是一条缺陷（而不是"功能而已"）

三处文档与它直接冲突：

| 位置 | 文档写的 | 代码里的 |
|---|---|---|
| README §3「数据存储范围」 | 只列了数据**落在哪四个地方**，通篇没有一个字讲"数据从哪出去" | 存在一条把内容交给任意第三方应用的出口 |
| README §3「我们承诺」第 1 条 | "所有数据仅存储在您的设备本地" | 分享后内容已在另一个应用里 |
| `AboutDialog`「隐私保护」 | "不联网：清单中没有 INTERNET 权限，**数据无法离开设备**" | 数据可以离开设备，只是不经过本应用的网络代码 |

第三行是**明确的假陈述**，而且它出现在用户唯一会主动打开的隐私说明里。
"没有 INTERNET 权限"和"数据无法离开设备"是两件事：前者说明本应用自己传不出去，
后者是一个关于**数据的**全称判断，而分享通道把它推翻了。

### 1.3 修法：补上"通道"这一节，并改正两处假陈述

**README** 新增独立小节「数据离开设备的通道（R5 新增，此前缺失）」，写明：

- 通道是 `Intent.ACTION_SEND` + `createChooser`，交给用户选定的应用；
- 准确的表述是**两条通道**（剪贴板复制、分享到其它应用），不是只有剪贴板；
- 两条都由用户主动触发、都不经过本应用的网络代码，但分享确实把内容交给了第三方，
  第三方如何使用不在本应用控制范围内；
- 为什么此前没写：分享是上一轮为替代"发通知"而加的，加的时候只改了代码与界面、
  没有同步本节 —— 与问题 10 / 问题 29 是**同一类错误：改行为不改文档**；
- 保留该通道的理由（用户显式操作、不点就不会发生），并给出"若认为不可接受就删哪两个文件的调用点"。

**`AboutDialog`** 的三条内容被改写：把"数据无法离开设备"换成"应用自身无法传到任何服务器，
**但**数据可以离开本应用（点分享时）"，并明确"不点分享就不会发生，也不会在后台调用"。

### 1.4 为什么不直接删掉分享功能

它是用户显式操作，没有任何后台调用点（全仓只有那两处 `onShare*` 回调）。
删掉它是一种产品取舍，不是安全必需 —— 所以本轮**把事实写清**，把取舍留给作者。
写清事实这件事本身是本轮的主要交付物：读者现在可以自己判断要不要这个功能。

---

## 2. H-07 / M-03：小部件接收器导出 + 可枚举的数据库 id —— **本轮未修，附完整推导**

这是本轮**唯一一条我没有修的严重项**。下面写清为什么。

### 2.1 攻击面

- 7 个 `AppWidgetProvider` 全部 `exported="true"`。这是**必须的**：系统要投递
  `ACTION_APPWIDGET_UPDATE`，不能改成 `false`。
- `MARK_PICKED` 由 `PendingIntent.getBroadcast()` 投递，而**显式组件的广播不需要 intent-filter** ——
  也就是说移除 filter 里的那条 `<action>` 并不能阻止外部应用发同一条广播。
- 于是本机任意应用可以发 `MARK_PICKED` + 任意 `item_id`（数据库自增 id，可枚举），
  把用户的待取件全部改成"已取"；`ExpireCheckWorker` 每天物理删除"已取满 30 天"的记录，
  而 `allowBackup="false"` 没有副本 —— **一次无权限广播 = 30 天后不可恢复的数据销毁**。

### 2.2 已有的缓解（前几轮做的，仍然有效）

1. `markPickedIfPending(... AND status = 待取)`：对已取记录是空操作，不报"已标记成功"、不重绘；
2. `onReceive` 三条自定义分支统一先过 `ownsWidgetId()`（`getAppWidgetIds(本 Provider)` 里必须真有这个 id）；
3. 三处把取件码打进日志的点改为指纹。

R4 又推进了一步：**删掉了那两条零发送方的 `REFRESH` / `SILENT_REFRESH` action 及其处理函数**
（这原本是 README 问题 33 自己给出的建议"先删掉那 6 行零发送方的 `<action>`"）。
现在每个 receiver 只剩 `APPWIDGET_UPDATE` + `MARK_PICKED`。

### 2.3 为什么本轮**没有**实现"一次性令牌"

README 问题 33 给出的完整修法 ② 是"由本应用在渲染 RemoteViews 时给 PendingIntent 附一个
一次性随机令牌并在接收端校验（外部进程读不到本应用的 SP，所以伪造不了）"。

复核 `PendingIntent` 的匹配规则后，这条**可能根本不成立**：

```
PendingIntent 的匹配不考虑 extras
  —— 只比 component / action / data / categories / type / flags 与 requestCode

于是另一个应用可以：
  1. 自己构造同一个 Intent（action = MARK_PICKED，component = 本 Provider）
  2. 用同样的 requestCode（appWidgetId + 10000，而 appWidgetId 可枚举）
  3. 调 PendingIntent.getBroadcast(..., FLAG_NO_CREATE) → 取回**本应用创建的那个** PendingIntent
  4. PendingIntent.send(context, 0, fillInIntent) → 把 item_id 填进去发出去
```

令牌是否真的能挡住第 4 步，取决于 `FLAG_MUTABLE` 的 extras 合并语义（fill-in 能否覆盖
基础 Intent 里已有的同名 extra）以及各家 ROM 的实现细节。**这必须在真机上实测**
（`adb shell am broadcast` 或写一个 demo 应用）才能有结论。

**在没有设备的情况下实现一个"看起来修好了、实际可能被一行 `FLAG_NO_CREATE` 绕过"的令牌机制，
比不实现更糟 —— 它会让下一位读者以为这条已经关掉了。**
所以本轮只把这条的准确形状写进 README 问题 33 与 `99-unverifiable.md`。

### 2.4 这一条就是"不能开源"的实质理由之一

它不是"文档没写清"，而是**一个真实的、可被本机任意应用触发的数据销毁路径**，
且完整修法需要设备验证。把它当作已解决来发布，等于把一个已知的可利用面
包装成"纯本地离线工具"。这条应该直接决定"现在能不能开源"的答案。

---

## 3. H-09：通知监听能力与分享通道在应用内没有任何披露

### 3.1 先说清原判哪里不成立

H-09 写的是"隐私说明、权限清单、About 页**只讲短信权限**，从未告知存在这项能力"。

复核结果：**README 是有披露的**，而且写得比缺陷描述更细 —— 第 3 节权限表下方明确写了

> 申请通知监听权限（非清单权限，需用户在系统设置中授权）时，用途为：在短信权限被系统回收的情况下，
> 尝试从短信应用的通知中提取取件信息。

所以 H-09 在 **README 这一个面上不成立**。这一点如实记录，不因为"这条已经列进总表"
就顺着说它对。

### 3.2 但它在另外两个面上成立，而且更严重

| 面 | 修前状态 | 本轮 |
|---|---|---|
| README §3 | 有披露 | 不动 |
| `AboutDialog`「隐私保护」 | **完全没提**通知监听；且断言"数据无法离开设备" | 已补两条：通知使用权的含义与代价（"授予它等于允许本应用读取本机全部通知内容"）+ 分享通道 |
| `PermissionSetupDialog` | **完全没提**通知监听；向导里也没有这一步，用户不知道自己被授予了什么 | `sms/PickupNotificationListener` 的用途写进 About；向导是否要加一步留待真机验证后再定（见 §12） |

`AboutDialog` 是用户在应用内唯一能看到的隐私说明。它一边说"数据无法离开设备"，
一边完全不提应用声明了一个"可读取全部通知"的监听服务 —— 这两件事叠在一起，
是整轮审计里**性质最接近"误导"的一处**（而它修起来只要改几条字符串）。

---

## 4. M-06：一处不可核验的上游归属

`PickupCodeExtractorV3.kt:296` 原文：

> `借鉴取件码APP的用户自定义规则思想`

**没有仓库地址、没有版本、没有许可证名。** GPL 系合规要求能定位到具体作品与条款；
一个叫不出来的名字既不能确认也无从证伪 —— 它比不写更糟，因为读者会以为"已经声明过了"。

本轮**没有擅自填入一个猜测的上游**（那等于编造出处）。改成的表述是事实：

- "用户用正则自定义解析规则"是这类工具的通用做法（DMS / 短信转发器类应用普遍提供），
  并非某一家的独创设计；
- 本实现（`CustomRuleEngine` 的数据结构 + 用户手动录入正则）为独立编写，未复制任何上游代码；
- 如果作者确实记得当初参考的是哪一个应用，**请补上仓库地址与许可证**；补不了就保持
  现在这种"只描述想法、不认领未核实的出处"的写法。

README §8 对此的既有披露（把它列为"一个未署名的'取件码 APP'"）保留 —— 那是诚实的。

---

## 5. M-07：通知兜底通道的白名单漏掉了 README 点名的两家品牌

`sms/PickupNotificationListener.kt` 的 `SMS_PACKAGES` 是一份硬编码的短信应用包名白名单，
**只处理白名单内应用发出的通知**。修前 9 个包名，缺：

- 三星 `com.samsung.android.messaging`
- OPPO / 一加系 ColorOS `com.coloros.mms`（以及较旧的 `com.oppo.mms`）

而 README 的品牌适配章节**恰好点名了这两家**。后果是这两家用户的通知兜底是**哑的**：
通知进来了但被白名单挡掉，用户看到的现象只是"有些取件码没被识别出来"，
完全无从归因。本轮补齐三个包名。

---

## 6. M-12：`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 从未声明

代码里有 **3 处**通过 `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 拉起系统的
"是否允许后台运行"弹窗：`BatteryOptimizationHelper.kt:60`、`:208`、
`PermissionActionHelper.kt:107`、`PermissionChecker.kt:358`。
而清单里**从未声明**对应权限。部分 ROM 上该弹窗不出现、或出现但开关点不动，
用户表现就是"点了『去设置』什么都没发生" —— 与第 4 轮修的 H-10① 是同一类体感。

修法：声明该权限。**同时如实记录取舍**：它同样是 Google Play 的**受限权限**，
上架需要提交用途说明且**不保证通过**（本应用属于"必须在后台持续接收短信"的场景，
是常见的可申诉理由，但不是保证）。若走 F-Droid / 直接分发 APK，则不受此限。
这个取舍写进了清单注释与 README 权限表，没有藏起来。

---

## 7. M-13：`USE_EXACT_ALARM` 是 Play 受限权限

修前清单同时声明 `SCHEDULE_EXACT_ALARM` 与 `USE_EXACT_ALARM`。
`USE_EXACT_ALARM` 是 Google Play 的**受限权限**，官方政策只允许"闹钟/日历"类应用声明，
本应用不属于该类 —— 声明它带来上架被拒风险，而它的收益为零。

为什么收益为零（这是本轮唯一需要"证明删除是安全的"的地方）：

- 全仓唯一用到精确闹钟的地方是 `BackgroundProcessManager.scheduleExitAlarm()` ——
  一个"后台存活超时后自动退出"的**兜底计时器**，精度完全不敏感；
- 该函数自己已经写了降级：`canScheduleExactAlarms()` 为假时改用
  `setAndAllowWhileIdle()`（非精确闹钟），`Log.d("[闹钟] 无精确闹钟权限，使用非精确闹钟")`。

删除后的行为变化（如实记录，不美化）：

| 系统 | 变化 |
|---|---|
| Android 12 | `SCHEDULE_EXACT_ALARM` 默认授予 → 精确，行为不变 |
| Android 13 | `SCHEDULE_EXACT_ALARM` 默认授予 → 精确，行为不变 |
| Android 14+ | `SCHEDULE_EXACT_ALARM` 默认拒绝 → 走**已有的**降级分支，退出计时可能晚几分钟 |

README 权限表相应把那两行合并/改写，并说明了"代价是 Android 14+ 上该计时可能晚几分钟，用户不可见"。

---

## 8. L-08：删除 `WAKE_LOCK`

原注释写"Widget goAsync 协程后台执行需要 WakeLock 保持 CPU 运行"。
实测：全仓 **0 处** `newWakeLock` / `PowerManager.WakeLock` / `PARTIAL_WAKE_LOCK` 引用。

这个声明不只是"没用"，它还**误导读者**：`goAsync()` 的保活语义由
`BroadcastReceiver.PendingResult` 提供（这也是第 4 轮 H-08 整条的背景），与 WakeLock 无关。
注释把两个完全不同的机制混为一谈，会让下一位读者在排查"广播被回收"时走错方向。
README 权限表原先把 `WAKE_LOCK` 与两个前台服务权限并列 —— 那是照抄清单而没核对代码，本轮一并改正。

### 8.1 R8 复核：这条只修了「源清单」，没修「成品」

上面的处置把 `WAKE_LOCK` 从手写清单里删掉了，而 `docs/KNOWN_ISSUES.md` 问题 31 只列了
另外三项合并进来的权限。发布前用 `aapt dump permissions` 复核成品，实测：

| 手写清单 | 合并后的 APK |
|---|---|
| 7 项系统权限 | **10 项**系统权限 + 1 项应用私有权限 |

多出的三项 `ACCESS_NETWORK_STATE` / `RECEIVE_BOOT_COMPLETED` / `WAKE_LOCK` 全部来自
`androidx.work:work-runtime:2.9.0`（`app/build/intermediates/manifest_merge_blame_file/release/...`
的合并归因报告逐条可查）。也就是说「全仓 0 处使用」与「成品 APK 仍然申请它」**同时成立**，
而当时三份文档没有一处说到这一点。

**为什么不加 `tools:node="remove"` 剥掉它**：WorkManager 在部分路径上确实会用它保活，
剥掉的后果（周期任务被提前打断）只有真机看得出来 —— 与本条最初的判断同理，
这次改的是**披露**，不是权限模型。已记为 L-25。

---

## 9. L-09：`android:process=":main"` 的注释是错的

`DataChangeContentProvider` 上挂着 `android:process=":main"`，原注释写"（主进程实例化）"。

`:main` 是**一个名为 `:main` 的私有进程**，它**不等于**默认主进程 ——
默认进程名等于包名（`com.shanqiu.pickupmanager`），二者是两个不同的进程。
`:main` 只是本工程自己起的名字。

**只改注释，不动 `:main`**：改它等于改数据同步的进程拓扑（`DataChangeContentProvider` 与
`CrossProcessNotifier` 是配套设计），属于需要真机验证的行为变更。
但注释必须与实现一致，否则下一位读者会按错的进程模型去推理同步问题。

---

## 10. L-21（本轮新发现）：`PickupCodeExtractorV3` 三处平台类型警告

修 M-06 时改动了这个文件的注释，**触发它被重新编译**，于是暴露出 3 条此前一直存在、
但因为增量编译不重编译该文件而从未在构建日志里出现过的警告：

```
w: PickupCodeExtractorV3.kt:192:27 Java type mismatch: inferred type is 'kotlin.String?', but 'kotlin.String' was expected
w: PickupCodeExtractorV3.kt:206:66 Java type mismatch: inferred type is 'kotlin.String?', but 'kotlin.String' was expected
w: PickupCodeExtractorV3.kt:207:39 Java type mismatch: inferred type is 'kotlin.String?', but 'kotlin.String' was expected
```

三处都是 `Matcher.group(1)` —— **Java 平台类型 `String!`**，Kotlin 不做空检查就交给
需要非空 `String` 的位置。与 R2 修的 **L-18 是同一个家族**，只是当时这个文件没被重编译、警告没露面。

**这一条比它本身的严重度更值得记的是它的发现方式**：增量编译只在文件被改动时才报它的警告，
所以"构建日志干净"从来不能证明"全仓没有这类问题"。
本轮按 L-18 的同一修法处理（`?.let { ... }`，不用 `!!`），修完这 3 条警告消失。

### 10.1 同一现象在半小时后再次应验，并暴露出**本报告自己写错的数字**

上面那句写完，我意识到它有一个直接推论：既然增量编译会掩盖警告，
那么"**修完 L-21 后全仓剩余警告为 2 条**"这句话本身就没有证据 ——
它来自一份增量构建日志。于是我做了一次**强制全量重编译**：

```
$ ./gradlew --offline :app:compileDebugKotlin --rerun-tasks
```

结果：**22 条警告**，不是 2 条。也就是说，本报告 §13 原本写下的那个数字
是错的，而且错法与 H-14 是同一类 —— **把一个没有实际执行过的机械复核结果当成了事实写进文档**。

被掩盖的 20 条里，有 6 条是新的平台类型警告（同一家族）：

```
w: SelfLearningManager.kt:271 Java type mismatch: inferred type is 'kotlin.Nothing?', but 'kotlin.String' was expected
w: SelfLearningManager.kt:272 ...  273 ...  274 ...  283 ...  284 ...
```

根因：`obj.optString("predictedAddress", null)` —— 把字面量 `null` 传给了 Java 侧的平台类型参数
（`String!`），Kotlin 把实参推断成 `Nothing?`。**记作 L-22。**

修法（不用 `!!`、不改语义）：加一个显式声明返回类型的扩展

```kotlin
private fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name)
```

`isNull(name)` 为真 ⟺ 键不存在 **或** 值为 JSON null —— 与 `optString(name, null)` 的返回语义完全一致，
但类型是确定的，调用点不必再猜。6 处调用点全部换掉。

### 10.2 顺手做的两次机械清理（都不需要真机，都能被编译器证伪）

- **5 条可机械替换的 deprecation**：4 个图标（编译器已在告警里直接给出替代路径
  `Icons.AutoMirrored.*`）+ 1 个 `AppExitReceiver.kt:52` 的 `taskInfo.id` → `taskInfo.taskId`。
  这 5 条**不修**才是问题：编译器连替代写法都写给你了。
- **14 处死图标 import**。这暴露了 **R4 的 L-05 修得不完整** —— R4 声称已清理未使用 import，
  实际 `PickupListScreen.kt` 还剩 8 处、`PickupDetailDialog.kt` 4 处、`RuleMatchingScreen.kt` 2 处。
  为什么 R4 没发现：**Kotlin 编译器不对未使用 import 报警告**，所以只能靠工具或人眼，
  而 R4 是带着"清 import"的意图去改那几个文件的，改完没有再扫一遍。

### 10.3 由此新增一个仓库自带检查器（对"构建日志不能当证据"的正面回答）

只吐槽检查不出来不够，得给一个**能**检查出来的东西：

`tools/audit/check_icon_imports.py` —— 判定"`androidx.compose.material.icons.*` 里
声明了但代码中从未出现 `Icons.<Group>.<Name>` 的 import"。

为什么只查这一类（而不是所有 import）：**只有图标的用法形式是可从包路径唯一推出的**
（`filled` → `Icons.Filled.`，`automirrored.rounded` → `Icons.AutoMirrored.Rounded.`），
判据因此**只依赖被检查的值本身**。其余 import（`fadeIn` / `List` / `Add`）会被普通标识符撞名，
"这个名字出现在正文里" 会产生大量假阴性 —— 宁可少查一类，也不要给出一个会自相矛盾的结论
（这正是 §11 那个扫描器的毛病）。

**这个检查器自己写错过两次，两次都固化成了回归测试**（`tools/audit/test_check_icon_imports.py`，15 个用例）：

| 写错的方式 | 后果 | 为什么值得单独记 |
|---|---|---|
| 直接拿原文（含注释）判定"有没有被用到" | 一句注释里写了 `Icons.Filled.Star`，就足以让**真死**的 `filled.Star` 被判成"已使用" | 判据依赖了"周围恰好有谁提到了那个名字"，而不是代码本身。这不是假想：负数测试的探针文件里当场复现 |
| 用子串匹配代替词边界匹配 | `Icons.Filled.Star` 被 `Icons.Filled.StarBorder` 吞掉，静默漏报 | 同一类"判据不精确"，只是换了个方向 |

修法：先在**剥掉注释与字符串**（等长替换、行列号不变；Kotlin 支持嵌套块注释与 `"""` 原始串）
的代码上判定，再用 `re.escape(expected) + r"\b"` 匹配。

一次负向测试的实测输出（往仓库里塞一个探针文件，跑完即移出）：

```
$ python3 tools/audit/check_icon_imports.py .
发现 1 处死图标 import：
  app/src/main/java/com/shanqiu/pickupmanager/__probe_dead_import.kt
    - androidx.compose.material.icons.filled.Star   （代码里从未出现 Icons.Filled.Star）
$ echo $?
1
```

---

## 11. 敏感内容扫描器复跑（本轮必做项）

R1 之后仓库又经过了 R2–R5 四轮改动，必须重跑而不是沿用旧结论。

```
$ python tools/audit/scan_sensitive.py
❌ 存在 BLOCK 级命中（未在白名单内），请人工确认后清理。
```

逐类核对结果（**全部为合成值，无真实 PII 残留**）：

| 类别 | 命中 | 逐条核对结论 |
|---|---|---|
| `CN_MOBILE` | 16 | 拆开是**两个不同的值**：`18712345678` 13 处 + `13800138000` 3 处。前者是**规范化假号**（187 + 12345678），出现在 `RuleBasedPickupParser.kt:341` 的模式注释与 R2 新增的回归测试里 —— 它们要测的正是"手机号不能被抓成取件码"，必须用一个手机号形状的串；后者是中文文档惯用示例号。**都不是真实号码** |
| `CN_ADDR_DOOR`「3号楼 / 101室 / 5号楼」 | 68 | 出现在 `ui/theme/Preview.kt`（Compose 预览用示例数据）、`RouteSettingsScreen` 的预览块、README 的说明句，以及 R1 换上的占位地址「示例小区3号楼」。**全部为合成** |
| `CN_ADDR_COMPOUND`「示例小区」等 | 88 | R1 的占位符与其引用点，以及"不依赖特定小区""着一个真实小区"这类**说明性文字**（扫描器把它当成了地名） |
| `CN_400`（本次新增的一类） | 1 → 2 | `4001234567`，在 `app/src/test/java/.../ParserPhoneNumberRegressionTest.kt:31` —— 该用例的第一行注释就写明"所有短信内容均为虚构，号码取自预留的测试号段/示例号"。**第 2 处就是本报告自己**（这张表把这个号码引用了一次），R6 复核时为 2 —— 自指，见下方"数字会随仓库移动" |
| `EMAIL` | 10 | `author@example.invalid` / `audit@example.invalid` —— `.invalid` 是 RFC 2606 保留的**永不解析**顶级域，匿名化身份用的就是它 |
| `PICKUP_CODE_COMPOUND` | 106 | R1 换上的 `C4-6-11111`、`A2-1-3456` 等占位码与归一化测试用例 |

**这些数字在本轮内又变过一次，这件事本身要记下来**：本报告第一版写的是
14 / 64 / 84 / 8 / 104（还漏了 `CN_400`），但本轮后半程新增了检查器与文档，
带进了新的占位码与示例号 ⇒ 同一份扫描器给出的计数随仓库内容移动。
所以引用它的数字时**必须标快照时点**，把它当成"某次运行的结果"而不是"仓库的属性" ——
这与 H-14「可机械复核的数字」是同一件事：数字要么当场复算，要么标注它是哪一刻的。

**⚠ R6 自纠：上面这一节的第一版把工具的输出读错了，结论下反了方向。**

第一版在这里写的是：

> 同一个字符串 `18712345678` 出现 16 次，扫描器只把其中 **3 处标为"疑似示例"**……
> 说明判定依赖上下文，**同一个值会被分类成两种结果**。

**这是错的。** 扫描器打印的那一行是 `命中 16 处（疑似示例 3）` —— 那是**两个不同值**的
聚合计数：`18712345678` 共 13 处、**13 处全都没有标记**；`13800138000` 共 3 处、
**3 处全都有标记**。按值重算一次就能看见。

也就是说：**判定当时本来就是"按值一致"的**，是我把聚合数当成了单值数，再据此推出
"工具自相矛盾"。在"能不能开源"这个问题上，这恰好是本审计一直在批的那件事 ——
**引用了一个自己没有复算过的数字，而且它落在自己写的报告里**。
（这也解释了为什么 R6 非要把这条判定写成可执行的回归测试，而不是"再看一眼输出"。）

工具真实存在的问题是另外三条 —— 同一个主题：**判定过弱**：

1. **豁免表与规则表脱节（7 条从未生效过）** —— `12345678901` 的第二位不在 `[3-9]`，
   `CN_MOBILE` 的形状决定它**永远不可能**被产出；`1[3-9]x{4,}` 连它自己想要的掩码
   写法 `138xxxx1234` 都匹配不上；`localhost` / `F5FAFA` / `FF[0-9A-F]{6}` 在本仓库
   规则表里没有任何一条能产出它们。
2. **豁免过宽** —— `test@` / `foo` / `bar` / `your` 这类短子串会把**真实数据判成示例**：
   `test@163.com` 是真实可注册邮箱，旧版却豁免了它。
3. **同一判定有两份实现** —— `scan_history.py` 里抄了一份一模一样的
   `any(re.search(w, s, re.I) for w in WHITELIST_HINTS)`（与 R5 图标检查器踩过的是同一个坑）。

这三条与 **M-05**（`check_doc_refs.py` 的锚点校验过弱）确实是同一批工程，
修法也是同一句话：**让判据只依赖被检查的值本身，不依赖它周围恰好写了什么**。
R6 修完后的复现方式是回归测试，不是"再看一眼输出"：

```
$ python3 tools/audit/test_scan_sensitive.py -k JudgementDependsOnlyOnTheValue
```

§15 记录了修法本身。

**结论**：本轮扫描**没有发现新的真实 PII**。所有 BLOCK 命中都是合成值或扫描器过宽，
逐条核对记录在 `99-unverifiable.md` 的对应条目里（因为"这个值是不是真的"最终只有作者能确认 ——
我能证明的是"它们形状统一、且与仓库外语料无关联证据"）。

---

## 12. M-04（R4 移交本轮）："读不到"被说成了"没有"

R4 的总表把 M-04 状态写成**「待修（移交 R5）」**，但本轮起初写的报告里没有它。
补上。它落在本轮主题上并不勉强 —— 它和 H-06 / H-09 / H-10 是同一条主线：
**界面对用户说的那句话，与代码实际发生的事不一致。**

### 12.1 链路

```
ComposeMainActivity.importSms()
  └─ repository.importSmsFromDevice()
       └─ doImportSms()
            └─ contentResolver.query(Telephony.Sms.CONTENT_URI, ...)   ← 缺 READ_SMS 时在这里抛 SecurityException
                 ↑ 被 catch (e: Exception) { Log.e(...); return 0 } 吞掉
       └─ 返回 0
  └─ count > 0 为假 → Toast「扫描完成，未发现新的取件信息」
```

用户看到的是「没有新的取件信息」，真实原因是「**根本没有权限读短信库**」。
两者对用户意味着完全不同的下一步：前者是无事发生，后者是去授权。
把它说成前者，用户会反复点导入、永远修不好。

### 12.2 修法（三处，都不改权限模型）

1. `ComposeMainActivity.importSms()` 入口先做一次**只读权限位**的前置检查
   （复用 R4 为 H-10 加的 `hasBasicSmsPermission()`，不做任何 ContentProvider 查询，
   所以不会把缓存想规避的昂贵操作又拉回来）。缺权限 → 用**与原因一致**的提示
   `import_need_sms_permission`，并且**不去查库**。
2. 权限向导的「跳过」分支（`onDismiss`）原先**无条件**调 `importSms()` ——
   等于用户刚点了"跳过授权"，立刻就去全表扫描收件箱。现在只在
   "用户其实已自己在系统设置里授过权"（`hasBasicSmsPermission()` 为真）时才顺手导入一次。
3. `PickupRepository.importSmsFromDevice()` 拆出独立的 `catch (e: SecurityException)`。
   返回值仍是 0（契约不变、不牵动 ViewModel 调用点），但日志里从此能一眼分辨
   「没权限」和「解析失败」，不再让排查者只能看到一句笼统的"导入短信失败"。

### 12.3 未修的部分，如实标出

README 第 3 节权限表写的是「`READ_SMS`：首次启动自动导入一次，此后由「导入」按钮与下拉刷新触发」，
而 Activity 侧还有 3 个**自动**触发点（权限弹窗完成后、`retryPermissionCheck` 成功后、
`onResume` 里旧引导弹窗成功后）会各自触发一次全表扫描。
它们不会误导用户（不弹那句假 Toast 了），但与"只导入一次"的措辞不完全吻合。
**这是措辞问题不是行为缺陷**，与 H-14 的"可机械复核的数字/引用"是同一批文档工作，
一并交给 R6 统一处理，不在这里零散改 README。

---

## 13. 本轮明确未修 / 交给 R6

| ID | 为什么不在本轮 |
|---|---|
| **H-07 / M-03** | 见 §2.3：完整修法需要真机验证，且"令牌可能被 `FLAG_NO_CREATE` 绕过"这一点必须先实测。**不实现一个未经验证的假修复** |
| `PermissionSetupDialog` 是否新增"通知使用权"步骤 | 需要真机验证向导流程（H-10 的真机项），且新增步骤会改变首次启动的步数 —— 本轮只把用途写进 `AboutDialog`（用户能找到的地方），向导改动与 H-10 的真机验证一起做 |
| M-05（`check_doc_refs.py` 锚点校验过弱） | 与 §11 发现的扫描器自相矛盾是同一批工程，见 R6 |
| `tools:node="remove"` 剥掉 WorkManager 带入的两个权限 | README 问题 31 已说明需真机验证"重启后周期任务仍执行"，本轮不动 |
| `AppOpsManager.checkOpNoThrow` ×10 条 deprecation | 替代品 `unsafeCheckOpNoThrow` 是 **API 29+**，本应用 minSdk 更低 ⇒ 需要 `Build.VERSION.SDK_INT` 分支，涉及 5 个文件 10 个调用点。这是**行为变更**（虽然等价），不该塞进本轮 |
| `AlertDialog` → `BasicAlertDialog` ×1 条 deprecation | 两者的视觉与手势容器不同（`BasicAlertDialog` 要自己搭 surface），属于 UI 行为变更，需要真机看效果 |
| README 第 6 节「34 个用例」 | 实测 83 个。与 H-14 同一批数字核对，R6 统一改 |
| 把 `tools/audit/*` 挂进 CI | 本轮新增了 `check_icon_imports.py`，但 CI 门禁的接线（以及"全量重编译 + 警告基线比对"，见 §10.1）属于 R6 的工程收口 |

---

## 14. 验证

```
$ GRADLE_USER_HOME=D:/.gradle ANDROID_HOME=D:/.gradle/android-sdk \
    ./gradlew --offline :app:testDebugUnitTest
BUILD SUCCESSFUL    tests=83  通过=81  跳过=2  失败/错误=0
```

```
$ python3 tools/audit/check_icon_imports.py .
OK: 未发现死图标 import（扫描 .）          # 清理前：14 处
$ python3 tools/audit/test_check_icon_imports.py
Ran 15 tests ... OK
$ python3 tools/audit/check_gitignore_depth.py .
✓ .gitignore 纵深覆盖与反向白名单全部通过。
$ python3 tools/audit/scan_sensitive.py .
❌ 存在 BLOCK 级命中（未在白名单内）        # 逐条核对见 §11，全部为合成值
```

- 本轮**没有新增 Android 单元测试**。理由：本轮的改动是"清单声明 + 文档/字符串 + 注释 +
  机械等价替换"，除 M-04 的前置检查外没有可断言的纯逻辑；M-04 的检查依赖
  `Context.checkSelfPermission`，在纯 JVM 单测里只能测到被 mock 的默认值
  （`unitTests.isReturnDefaultValues` 会把它变成恒 false，见 M-15），
  写出来的断言证明的是 mock 而不是代码。不为了凑数写这类断言。
  **但本轮新增了检查器自己的 15 个回归测试**（纯字符串，可断言，见 §10.3）。
- **编译警告：全量重编译实测 22 条 → 修完本轮 11 条**，剩余 11 条全部有明确出处、
  且都不能机械替换（10 条 `checkOpNoThrow` + 1 条 `AlertDialog`），理由见 §13。
  §10.1 记录了"为什么本轮报告的第一版把这个数字写错了"。
- 清单改动已由 `processDebugManifest` 实际处理通过（在 `BUILD SUCCESSFUL` 之前该任务被执行），
  即"新声明的权限 / 删掉的权限"都进入了合并清单。
- 全部改动文件的行尾风格已逐文件复核（本仓库源码为 CRLF，`Edit` 与脚本都必须保持），
  无任何文件出现混行尾。

**改动规模**：**15 个已跟踪文件 `+202 / -63`**（不含新增文件），另有 **3 个新增文件**
（本报告、`tools/audit/check_icon_imports.py`、`tools/audit/test_check_icon_imports.py`）。

> 三个数字在这里改过两次，值得说明为什么最后只留下了这一种写法：
> 1. 第一版写"14 个已跟踪文件 +173 / -47"—— 那是**改文档与总表之前**测的，早已过期；
> 2. 第二版补上新增文件的**逐文件行数**—— 但本报告自己就是新增文件之一，
>    **在报告里写自己的行数，一改就过期**（实测：写 497 行，改完这一处变成 502）。
>
> 所以最后只保留"不随本报告变化"的那个数（15 个已跟踪文件，它们在本提交里已冻结），
> 新增文件按用途描述而不给行数。全部数字可用
> `git show --numstat <R5 提交>` 当场复核 —— 与 §10.1、§11 是同一件事：
> **机械数字必须当场取，且不能取一个会被自己的写入改变的量。**

> 对"开源前必改项"的结论：本轮**没有把任何一条严重项降级**。
> H-07/M-03（可被本机任意应用触发、30 天后物理销毁数据）仍然成立，
> 且按 §2.3 的推导，它的"标准修法"本身可能是无效的。
> 这一条仍然直接决定"现在能不能开源"的答案是**不能**。

---

## 15. R6：把"判定"从上下文里拿出来（M-25 / L-23），并把工具挂进 CI

R5 的 §11 与 §13 把两件事交给了 R6：一是**让判据只依赖被检查的值本身**，
二是**把 `tools/audit/*` 挂进 CI**。本节是这两件事的交付记录。
（§11 里"同一字符串只标 3 处"那句是**我读错了工具输出**，自纠写在 §11 现场，
不在这里重复；下面 15.1 记的是工具**真实**存在的三条问题。）

### 15.1 M-25：豁免判定的三条病灶，逐条带实测证据

病灶一：**豁免表与规则表脱节 —— 7 条从未生效过。**
旧表的每一条都是"值里包含这个子串就豁免"，但没有任何机制检查"这条子串到底有没有可能
出现在规则产出的值里"。逐一验证后：

| 旧条目 | 为什么永远不可能生效 |
|---|---|
| `12345678901` | 第二位是 `2`，不在 `CN_MOBILE` 的 `1[3-9]` 里 —— **规则产不出它** |
| `1[3-9]x{4,}` | 连它自己想要的掩码写法都匹配不上：`138xxxx1234` 的第二位之后是 `8` 不是 `x` |
| `xxx` / `XXXX` | 本仓库没有任何规则会产出只含 `x` 的值 |
| `localhost` | 没有规则会产出包含它的值（`127.0.0.1` 已经由 `IPV4` 覆盖） |
| `F5FAFA` / `FF[0-9A-F]{6}` | 规则表里**根本没有颜色规则**，这两条属于已经消失的规则的遗留 |

病灶二：**豁免过宽 —— 用短子串判定会把真实数据判成示例。**
这一条有一个几乎像故意的反例：19 位银行卡号形状的 `6222021234567890123`，
被旧版豁免，**原因是它含有子串 `12345678901`** —— 而这条子串本身还是病灶一里那条
死规则。同一个机制在 `EMAIL` 上更明显，旧版实际放过了 4 个不同值：

```
   WL x5 «test@163.com»       <= ['test@']          ← .163.com 是真实可注册域名
   WL x2 «foo@bar.com»        <= ['bar', 'foo']     ← bar.com 是真实已注册域名
   WL x1 «your_name@qq.com»   <= ['your[_-]?']
   WL x1 «x@placeholder.io»   <= ['placeholder']
```

以及 `CN_ADDR_COMPOUND` 上：`示例阳光小区`（占位词后面**又跟了真名**）被判为示例 ——
那恰恰可能是被截断/拼接过的真实小区名。

病灶三：**同一判定有两份实现。**
`scan_history.py:173` 里抄了一份一模一样的
`any(re.search(w, s, re.I) for w in WHITELIST_HINTS)`。这与 R5 §10.3 里图标检查器的
教训是同一个：**判定逻辑一旦有第二份，测试就会在"复制品"上通过，而真工具没被覆盖。**

### 15.2 修法：`placeholder_of(value)` 只接收一个值

判定的输入被压成一个字符串，签名本身就是约束 —— 拿不到文件、行号、前后文，
就**写不出**"这一行提到了示例，所以豁免"这种判定：

```python
def placeholder_of(value):
    """只凭 value 判定是否占位；返回命中的条目名，非占位返回 None。"""
    for name, pat, _why, _witness in PLACEHOLDER_VALUES:
        if re.fullmatch(pat, value):
            return name
    return None
```

豁免表从"子串列表"改成 11 条**带见证值**的条目，并且见证值必须通过两道机械核对：
① 被本条的全串正则命中；② **真的会被 `RULES_TEXT` 里某条规则产出**。
第 ② 条就是挡住"从未生效的白名单"的那道闸 —— 旧版那 7 条一条都过不了。
`scan_history.py` 改为直接从 `scan_sensitive` 导入 `placeholder_of`，第二份实现删除，
历史与工作树从此必然给出同一结论。

### 15.3 为什么是"以占位词 + 通名结尾"，而不是"包含"

因为**值本身会被前文污染**。实测这批命中里就有：

```
   «裹已到示例小区»        ← 前缀 [\u4e00-\u9fa5]{2,5} 把句子成分一起吞进了匹配
   «达12号楼»
   «件码和真实小区»
```

值既然混着句子，那么"包含 `示例` 就豁免"就等价于"整句里出现过 `示例` 就豁免"。
所以新表把它收紧成**结尾锚定**：`示例小区` / `示例小区3号楼` 豁免，
而 `示例阳光小区`、`例如阳光小区`、`阳光小区3号楼` **一律不豁免**。
方向是明确的：**误报由人来复核，漏报没有人会复核。**

有两类值**故意不豁免**，这是设计决定而不是遗漏：

- `PICKUP_CODE_COMPOUND`（`A2-1-3456` 这类，当前 108 处）。真实取件码与夹具码形状
  完全一致，按值无法区分 —— 这正是这条规则存在的意义：它是一个"请人工确认"的清单。
- 中文地名规则里的说明性文字（`不依赖特定小区`、`这类真实小区`）。它们不是地名，
  但也没有任何按值可判的标记；豁免它们需要看句子，那又回到 15.3 要消灭的东西。

### 15.4 L-23：二进制/大文件的判别方式

旧代码用 `ext in BINARY_EXT or size > 4MB` 决定"当二进制处理并跳过文本扫描"。
后果是**一个 4MB 的文本文件整段逃过扫描**，而且没有任何提示。修法：

- 二进制按**内容**判别（前 4KB 含 NUL，git 判别文本/二进制用的同一条启发式），
  不再看文件大小；`BINARY_EXT` 仍然保留，用于决定"要不要额外查元数据"。
- 超过 `BINARY_HARD_LIMIT`（64MB）的文件**报出来**，而不是静默跳过 ——
  一条"没扫"的提示是发现，一条静默的 continue 是漏洞。
- 新增的回归测试会真的造一个 >4MB 的文本文件、里面放一个号码，断言扫描器仍然抓得到
  （旧代码在这条用例上必然失败）。

> 现状说明：本仓库当前**没有任何** >4MB 的文件（实测：工作树 173 个文件里 0 个），
> 所以 L-23 是**潜在**盲区而不是已发生的泄漏。这一条按"潜在"记录，不夸大。

### 15.5 CI 收口：6 步门禁 + 一个必须项

`.github/workflows/android.yml` 现在在构建前先跑：

```
审计工具的自我测试（4 个测试文件，合计 50 个用例 → 见 `06-docs-and-verification.md`）
工作树里没有敏感数据      python3 tools/audit/scan_sensitive.py .
git 历史里没有敏感数据     python3 tools/audit/scan_history.py .
提交元数据已脱敏          python3 tools/audit/scan_commit_identity.py .
没有死掉的图标 import      python3 tools/audit/check_icon_imports.py .
.gitignore 能挡住嵌套路径   python3 tools/audit/check_gitignore_depth.py .
```

**必须项**：`actions/checkout` 加了 `fetch-depth: 0`。默认是 `1` —— 只取一个提交时
`scan_history.py` 会"扫完"并打印 ✓，而它其实什么都没扫。一个**看起来通过**的门禁
比没有门禁更危险，这条和 §10.1、§11 是同一件事的第三次出现。

### 15.6 同一棵树上的前后对比（可复现）

用 `git show HEAD:tools/audit/scan_sensitive.py` 取出旧脚本，对**同一棵工作树**各跑一遍：

| 规则 | 旧（HEAD 版） | 新 | 差异来自 |
|---|---|---|---|
| `CN_MOBILE` | 30 处，豁免 7 | 30 处，**豁免 30** | 旧版只认 `13800138000`；新增了仓库夹具号 `18712345678` |
| `BANKCARD` | 1 处，**豁免 1** | 1 处，豁免 0 | 旧版因值里含 `12345678901` 子串而放过 |
| `CN_ADDR_COMPOUND` | 99 处，豁免 67 | 99 处，豁免 **64** | 旧版多放过 `示例阳光小区`×2、`例如阳光小区`×1 |
| `CN_ADDR_DOOR` | 72 处，豁免 27 | 72 处，豁免 **68** | 新增 `NO_NAME_DOOR`：只有门牌号、前面没有地名 |
| `EMAIL` | 21 处，豁免 20 | 21 处，豁免 **11** | 旧版放过 `test@163.com`×5、`foo@bar.com`×2、`your_name@qq.com`、`x@placeholder.io` |
| `IPV4` | 9 处，豁免 4 | 9 处，豁免 **8** | 新增 RFC 5737 文档地址段 |
| `PICKUP_CODE_COMPOUND` | 108 处，豁免 0 | 108 处，豁免 0 | 故意不豁免，见 15.3 |
| **退出码** | **1（失败）** | **0（通过）** | 旧脚本在今天的树上自己就是挂的 |

两个方向的变化都在同一张表里：**该放的放开（裸门牌、RFC 保留值、仓库夹具号），
不该放的收回来（真实域名邮箱、占位词后面跟真名的小区名）。**

### 15.7 一条必须写明的能力边界

**正则扫描是可以被绕过的，而且本仓库里就有一个现成例子。**
`tools/audit/test_scan_sensitive.py` 需要持有"看起来真实"的号码才能证明豁免表不过宽，
而它又必须让自己的仓库保持干净 —— 于是那些取值在源码里被拆成片段、运行时才拼起来：

```python
NOTREAL_MOBILE = frag("135056", "71234")
```

扫描器只能看到碎片。所以这个工具能做的是**提高误提成本、把"仓库看起来干净"变成一条
可执行断言**，**不是**形式化证明。这条限制写进本节，是因为如果它只写在测试文件的
注释里，读报告的人会以为门禁比实际更强 —— 而"以为工具比实际更强"正是 H-14 的原病。

### 15.8 验证

```
$ python3 tools/audit/test_scan_sensitive.py
Ran 18 tests ... OK

$ python3 tools/audit/scan_sensitive.py .
✓ 未发现 BLOCK 级命中。            # 退出码 0

$ python3 tools/audit/scan_history.py .
✓ 历史对象里没有 BLOCK 级命中（313 个唯一 blob 全部读回并交叉核对长度）。

$ python3 tools/audit/check_icon_imports.py .
OK: 未发现死图标 import（扫描 E:\PickupReview）
$ python3 tools/audit/check_gitignore_depth.py .
✓ .gitignore 纵深覆盖与反向白名单全部通过。
$ python3 tools/audit/scan_commit_identity.py .
✓ 提交元数据通过。
$ python3 tools/check_doc_refs.py
✓ 全部成立
$ python3 tools/check_log_privacy.py
✓ 通过
```

**改动规模**：`+1 文件 ×2`（`test_scan_sensitive.py` 新增，`scan_sensitive.py` 重写）、
`scan_history.py` 两处、`android.yml` 一处。**本轮不改任何 Kotlin/XML**，
因此不需要重跑 Android 单测 —— 这一点在 `06-docs-and-verification.md` 里会再确认一次。

