# 第 4 轮：UI 链路与死代码

**本轮范围**：H-08、H-10、H-11、H-12、M-01、M-09、M-10、M-22、M-23，
以及 L-04 / L-05 / L-12 / L-13 / L-14 的清理。

**为什么不把这些问题归到"后续再统一清一遍"**：本轮修的九条里有四条
（H-08 / H-11 / H-12 / M-09）的共同形态是「**用户按下去了，界面给了反馈，
但实际什么都没发生**」。这类缺陷不会崩溃、不会报错、不会有日志，
只会让用户以为"这软件就这样"。它们是"能不能开源"这个问题里
**最容易被外部评审者一眼看穿**的那一类 —— 评审者会点那些按钮。

---

## 1. H-08：`goAsync()` 的第二次调用会让主进程崩在 `finally` 里

### 1.1 机制（AOSP 源码只有三行）

`android.content.BroadcastReceiver` 里：

```java
public final PendingResult goAsync() {
    PendingResult res = mPendingResult;
    mPendingResult = null;   // ← 取走即清空，没有第二次
    return res;
}
```

两个直接后果，本轮都命中了：

| 情形 | 结果 |
|---|---|
| 一次广播分发里调用两次 `goAsync()` | 第二次返回 `null` |
| 在**非** `onReceive` 调用链上调用（如 `AppWidgetProvider.onUpdate`） | 恒返回 `null`（`mPendingResult` 本来就是 null） |

原代码把 `goAsync()` 放在 **`updateAppWidget()` 这种被多处复用的中间函数**里，
于是上面的情形 1 会真实发生：拿到 `null` 后，代码走到

```kotlin
} finally {
    pendingResult.finish()   // pendingResult == null → NPE
}
```

**关键点：这个 NPE 抛在 `finally` 块里，同一个 `try` 的 `catch` 抓不到它。**
外层那些 `catch (e: Throwable)` 早已执行完毕。所以它不是"被吞掉的错误"，
而是**主线程崩溃**。更糟的是崩溃发生在 `refreshInProgress.set(false)` 之前 ——
节流标志永远停在 `true`，此后每一次刷新广播都会重走这条路径、反复崩溃。

### 1.2 修法：不猜"该不该保活"，而是给调用点一个幂等的守卫

新增 `app/src/main/java/com/shanqiu/pickupmanager/util/PendingResultGuard.kt`：

- `finish()` 用 `AtomicBoolean.compareAndSet` 保证底层 `finish()` **全局最多执行一次**；
- 没拿到 `PendingResult` 时 `onFinish == null`，`finish()` 是**空操作**；
- 底层 `finish()` 自身抛异常也在内部吞掉 —— 崩在收尾上没有任何收益。

`BasePickupWidgetProvider` 增加私有入口 `goAsyncSafely()`：拿不到就记一条
`Log.w` 并返回空守卫，拿得到就返回真守卫。**日志本身是证据** ——
它会把"哪个调用点其实永远拿不到 PendingResult"直接打在 logcat 里。

### 1.3 三个调用点

| 位置 | 语义 | 处理 |
|---|---|---|
| `markItemAsPicked()`（`onReceive` → `ACTION_MARK_PICKED`） | 真在广播分发中 | `goAsyncSafely()`，幂等守卫 |
| `onReceive` 的 MIUI 曝光刷新分支 | 真在广播分发中，且**循环多个小部件** | 循环外取**一次**守卫，传给每个 `updateAppWidget()`；`finish()` 幂等，第一个跑完的真正结束分发 |
| `updateAppWidget()` | 被 `onUpdate`（非广播）与上面那条分支共同使用 | **不再自己调 `goAsync()`**，改由调用方传 `keepAlive: PendingResultGuard? = null` |

第 3 行是本轮的一个结构性修正：**"该不该保活"是调用点的性质，不是被复用中间函数的性质。**
让中间函数去猜，就必然出现"从 `onUpdate` 进来时安静地拿到 null"这种从未生效的假保活。
现在 `onUpdate` 传 `null`（它本来就拿不到，也不需要），广播路径传真守卫。

同时删掉一个从未被传过的死参数 `skipBadgeUpdate` —— 三个调用点全部使用默认值，
徽章更新一直是执行的，这个开关是纯粹的噪音。

### 1.4 为什么这条必须写成断言

H-08 的触发条件是"框架语义 + 调用顺序"，靠读代码极难发现。但它的核心契约
**是纯逻辑的**，所以新增 `PendingResultGuardTest.kt`（6 条）在 JVM 上钉死：

1. `finish()` 三次调用只真正执行一次；
2. 空守卫 `finish()` 不抛；
3. 底层抛异常不外泄；
4. 底层抛异常后幂等性依然成立；
5. **16 线程并发 `finish()` 仍然只执行一次**（守卫的持有者分布在 IO 协程与主线程 Handler 上）；
6. 初始状态不被误标为已收尾。

第 5 条是这一轮里唯一真正并发安全的断言 —— 其余都是"行为正确"，
它验证的是"行为在竞争下依然正确"。

---

## 2. M-23 / M-01 / M-22：约 200 行"没有发送方"的小部件刷新链路

### 2.1 怎么确认它是死的

`BasePickupWidgetProvider` 原本有一条完整链路：

```
onReceive("…widget.REFRESH")      → handleRefreshAction()  → updateProgressBadge()
onReceive("…widget.SILENT_REFRESH") → silentRefreshWidget()
```

判定"没有发送方"用的是**字面量比对**，而不是"名字看起来一样"：

```
$ grep -rn "ACTION_REFRESH" --include=*.kt .
widget/BasePickupWidgetProvider.kt:42:  const val ACTION_REFRESH = "…widget.REFRESH"          ← 接收方
widget/BasePickupWidgetProvider.kt:197: ACTION_REFRESH -> { … }                            ← 只被自己的 when 读
widget/BasePickupWidgetProvider.kt:521: action = WidgetActionService.ACTION_REFRESH         ← 发送方，值是 "…widget.ACTION_REFRESH"
widget/WidgetActionService.kt:72:       const val ACTION_REFRESH = "…widget.ACTION_REFRESH"
```

两个常量**同名不同值**。真正被 `PendingIntent.getForegroundService()` 发出的是
`WidgetActionService.ACTION_REFRESH`，由服务自己完成"查库 + 刷列表 + 刷徽章 + Toast"
（见 `WidgetActionService.handleRefresh`），**从不回调 Provider**。
Provider 那两个分支因此从来没有被触发过。

### 2.2 为什么必须删而不是留着

它是**两件事的唯一入口**：

1. **H-08 崩溃链的入口** —— `handleRefreshAction()` 里有四段递归重试（节流命中、
   冷却命中、两次 CAS 失败各一段），递归正是"第二次 `goAsync()` 返回 null"的成因；
2. **一个对外可达的攻击面** —— 7 个 widget receiver 全部 `exported="true"` 且无权限，
   而 `AndroidManifest.xml` 里给**每一个**都挂了 `…widget.REFRESH` /
   `…widget.SILENT_REFRESH` 两个 action。也就是说本机任意应用都能发这两个广播，
   带一个可枚举的 `widget_id` 进来。`ownsWidgetId()` 只能证明"这个 id 存在"，
   不能证明"是用户点的"。

一个**没有任何入口的功能**，却同时扩大了崩溃面和伪造面。

### 2.3 删除内容

| 对象 | 处理 |
|---|---|
| `onReceive` 的 `ACTION_REFRESH` / `ACTION_SILENT_REFRESH` 两个分支 | 删除 |
| `handleRefreshAction()`（含四段递归重试） | 删除 |
| `silentRefreshWidget()` | 删除 |
| `updateProgressBadge()` | 删除（同时修掉 M-22，见下） |
| `ACTION_REFRESH` / `ACTION_SILENT_REFRESH` 两个常量 | 删除 |
| `refreshInProgress` / `lastRefreshStartTime` / `REFRESH_COOLDOWN_MS` / `refreshVersion` | 删除（只为上述链路服务） |
| `AndroidManifest.xml` 中 7 个 receiver × 2 个 action | 删除（14 行），保留 `MARK_PICKED` |

`BasePickupWidgetProvider.kt`：**780 行 → 601 行**。

### 2.4 M-22 随之消解

`updateProgressBadge()` 的注释写着「比 `updateAppWidget()` 更快（只更新一个 TextView）」，
实现却是 `AppWidgetManager.updateAppWidget(appWidgetId, views)` —— **整体替换** RemoteViews。
后果：刷新瞬间 ListView 的 RemoteAdapter、空态视图、按钮的 PendingIntent 全部丢失。
该方法随链路一并删除后，M-22 不再存在。
（`WidgetActionService` 与 `updateWidgetBadge()` 走的是 `partiallyUpdateAppWidget()`，本来就正确。）

---

## 3. M-09：数据库写失败被当成"保存成功"

### 3.1 根因是有损编码

`RuleMatchingScreen` 的 `onSave` 回调签名原本是 `(Boolean, String?)`，用来表达三种结局：

| 结局 | 编码 |
|---|---|
| `AddPickupResult.Inserted` | `(true, null)` |
| `AddPickupResult.Duplicate` | `(false, 已存在的取件码)` |
| `AddPickupResult.Error` | `(false, null)` |

而调用点只写了一个判定：

```kotlin
if (!success && dupCode != null) { /* 重复 */ }
…
if (!hasAnyDuplicate) {                       // ← Error 也落进这里
    learningManager.recordCorrection(…)       // 把失败记成"用户认可的正确样例"
    onDismiss()                               // 弹窗正常关闭
}
```

`Error` 的 `dupCode` 是 `null`，于是"不是重复"成立，直接走到成功分支：
**写库失败 → 界面正常关闭 → 用户以为存下了 → 还把这次失败当学习样本写进
`SelfLearningManager`，参与后续置信度计算。**

### 3.2 修法：换成语义显式、编译器可穷举的三态枚举

新增 `enum class SaveOutcome { SUCCESS, DUPLICATE, FAILED }`，`onSave` 签名改为
`(PickupItem, SaveAction, (SaveOutcome, String?) -> Unit) -> Unit`。三处调用点全部改为
`when (outcome)` 穷举，`MainScreen` 的映射改为：

```
Inserted  → SUCCESS
Duplicate → DUPLICATE
Error     → FAILED
```

失败路径新增一个独立计数 `saveFailureCount`，与"重复"分开：

- 重复是**用户可决策**的（覆盖 / 放弃）；
- 失败只能**重试**，而且必须让用户看见。

批次全部回调完成后按优先级判定：`saveFailureCount > 0` → **不关窗、不写学习样本**、
弹出"保存失败"对话框（正文说明"你编辑的内容仍在本页面上，本次也不会被记入学习样本"）。
对话框刻意不用空 `onDismissRequest` 强制用户阅读，但允许点外部关闭 —— 关键是**不关窗**，
用户的编辑还在输入框里，再按一次保存即可。

覆盖（OVERWRITE）路径同样改为区分成功与失败，否则"覆盖失败"会静默什么都不发生。

---

## 4. M-10：把地址改回原样后，用户被锁在编辑态

底部按钮区的判定原本是：

```kotlin
if (showSaveButton && onAddressSave != null && isEditingAddress) { …保存… }
else { …复制取件码… }
```

而 `showSaveButton` 只在 `editedAddress != resolvedAddress` 时为真。于是这条路径：

1. 用户点"编辑"，进入编辑态；
2. 改了几个字，又改回原样（或干脆没动）；
3. `showSaveButton` 变回 `false` → **保存按钮消失**；
4. 落到 `else` → 底部显示"复制取件码"；
5. **编辑态没有任何出口** —— 既不能保存也不能取消。关掉整个弹窗，这次编辑静默丢弃。

修法：判定只看"是否处于编辑态"，编辑态**恒定**渲染 `[取消]` + `[保存]`；
`[保存]` 仅在确实有改动时可用（无改动时置灰、文案变为"没有改动"，但**可见**）。
`[取消]` 把编辑缓冲复位到 `resolvedAddress` 并退回只读态。

附带修掉一个同源的陈旧状态问题：`editedAddress` 原本是
`remember { mutableStateOf(resolvedAddress) }`，**只在首次组合时初始化一次**，
而 `resolvedAddress` 会随 `item / savedAddresses` 变化。弹窗被复用于另一张卡片时，
只读分支会显示上一张卡片的地址。现在用 `LaunchedEffect(resolvedAddress)` 重新对齐，
并顺带退出编辑态，避免把上一张卡片的编辑内容带到新卡片上。

---

## 5. H-11：空列表页唯一的主按钮点了什么都不发生

`PickupListScreen` 的空状态给新用户只提供了一颗按钮：
文案「导入短信」、图标 `ImportExport`，而实现是 `onClick = onRefresh`。

新用户第一次打开、列表为空时按下它：**既不导入、也刷不出任何东西**
（列表本来就是空的，刷新是空操作）。而这是空页面上**唯一**的行动指引。

修法：`ModernEmptyState` 增加 `onImportSms` 参数，按钮改为
`onMenuItemClick(MenuItemType.IMPORT_SMS)` —— 与主菜单里那条"导入短信"走**同一条**路径，
不另造入口。

---

## 6. H-12：返回手势静默丢弃未保存的取件路径

`RouteSettingsScreen` 里同时存在两条返回注册：

- `BackHandler`（负责"有未保存修改 → 弹确认"）；
- `DisposableEffect(Unit)` 里手动 `addCallback` 的 `OnBackPressedCallback`（注释自称
  "修复全面屏手势首次失效"，但它**无条件执行 `onDismiss()`**）。

两者注册在同一个 `onBackPressedDispatcher` 上，**后注册先执行（LIFO）**，
且手动注册的那个 `OnBackPressedCallback(true)` 一旦执行就消费掉事件。
结果是：用户改了取件路径后按返回手势 → 手动注册的回调先跑 →
**未保存的修改被静默丢弃，确认弹窗根本不弹**。该处注释写着"若已注册则由其接管"，
但代码里没有任何"接管"逻辑 —— 注释描述的是它希望发生的事，不是它做的事。

修法：两条注册路径**共用同一份判定逻辑**（提取为 `onBackInvoked`，
经 `rememberUpdatedState` 取最新闭包，避免 `DisposableEffect(Unit)` 捕获首帧的过期状态），
于是"谁先执行"不再影响结果。同时把
`LocalContext.current as ComponentActivity` 改成 `as? ComponentActivity`
—— 原先宿主只要不是 `ComponentActivity`（Preview、或将来被放进别的容器）
就是 `ClassCastException` 崩溃。

---

## 7. H-10：权限流程三个缺陷叠在一起

### ① 永久拒绝后没有任何出路（最高严重度）

全仓**0 处**使用 `shouldShowRequestPermissionRationale()`。而必须步骤的 UI 只有
「去授权」+ 一颗**禁用**的「授权后方可继续」按钮。用户在系统弹窗里选了"不再询问"之后：

- 再点「去授权」→ `launcher.launch(...)` **不会出现任何界面**，系统立刻回调 denied；
- 页面永远停在「授权后方可继续」，而那颗按钮是禁用的。

用户被锁死在一个点不动的页面上，且没有任何文字解释原因。

修法分两处：

**（a）`PermissionCacheManager`**：新增 `markPermissionRequested()` /
`hasRequestedPermission()`。为什么必须单独记：`shouldShowRequestPermissionRationale()`
在「从未请求」和「已被永久拒绝」两种情形下**都返回 `false`**，
单看它无法区分"还没问过"（应继续弹窗）与"问了也不会再弹"（必须改走设置）。
唯一可靠的判别依据是"我们到底请求过没有"，所以在 launcher 回调里落一个标记。
同时删掉了从无调用方的 `recordDeny()` / `getDenyCount()`（同一存储，语义被上面两个取代）。

**（b）`ComposeMainActivity.isSmsPermanentlyDenied()`**：三条件同时成立才为真
（两个短信权限都没拿到 + 确实请求过 + 系统不再允许展示弹窗）。
只满足"没拿到 + 不允许弹"是不够的 —— "从未请求过"也满足这两条。

**（c）`PermissionSetupDialog`**：新增 `isSmsPermanentlyDenied` / `onOpenAppSettings`
两个参数。为真时主按钮变为「去系统设置开启」，并**替换掉**那颗禁用按钮，
改为一段说明文字（讲清"被系统拒绝且不会再弹窗，通常是之前选过不再询问"）。

### ② 缓存不会随权限变化失效

`PermissionCacheManager` 的 30 分钟缓存原本无条件可信：用户在系统设置里把权限收回去后，
App 在最多 30 分钟内仍按"已授权"运行 —— 不弹引导，导入又静默失败。

修法：`checkPermissionsOnStartup()` 中，缓存声称"已授权"时增加一道
**只读 Android 权限位**的廉价复核（刻意不调 `checkAllPermissions()`，
后者正是缓存想规避的昂贵操作）。复核不一致 → 立即 `clearCache()` → 走完整检测。

### ③ 弱条件判定通过后写入强条件缓存

弹窗判定"短信步骤通过"用的是 `SmsPermissionManager.checkSmsPermissionStatus()`
的 `hasReadSmsPermission && hasReceiveSmsPermission`（**弱**，只查两个 Android 权限）；
而全仓其它地方的"是否完全授权"是 `SmsPermissionChecker.checkAllPermissions()`
里的 `missingPermissions.none { it.isCritical }`（**强**，在 MIUI 上还包含
无法通过 API 检测的"通知类短信"）。`onComplete` 原本写的是**硬编码** `saveResult(this, true)`。

于是：弹窗认为走完了 → 往缓存写 `true` → 之后 30 分钟启动流程全部跳过检测 → 实际仍缺权限。

修法：`onComplete` 改为写入**当场强检测的真实结论**
（`SmsPermissionChecker.checkAllPermissions(...).isFullyGranted`）。

**为什么不同时把弹窗的步骤判定也换成强条件**：强条件包含在 MIUI 上**不可检测**的项
（`MiuiPermissionHelper` 自己注释了"不包含空白通行证判断（无法通过 API 检测）"）。
若用它做步骤闸门，MIUI 用户的"短信"步骤将**永远无法通过**，等于把 ① 的锁死换了个位置。
所以：步骤闸门保持弱条件（"基础短信权限拿到了吗"），**缓存必须记录强结论**。
两者的分工写在代码注释里，避免下一轮再被"统一"掉。

---

## 8. L 级清理

| ID | 位置 | 处理 |
|---|---|---|
| **L-04** | `RuleMatchingScreen` 四处 `if (x != null \|\| true)` | 永真条件，拆开是两件事：`if` 是死结构；留着的 `!= null` 判断是**假的**（读者会以为"只有解析器学到了才允许编辑"）。真实语义是"编辑态下四个字段恒定可编辑"。直接去掉永真 `if`，把意图写进注释 |
| **L-04** | `RouteSettingsScreen` 三处 `AnimatedVisibility(visible = true)` | 复核后确认已不存在（`grep` 零命中），本条按已消解处理 |
| **L-05** | `MainScreen.kt` 的 `PerformanceMonitor` / `measurePerformance` import | 纯 import 无使用，随 L-12 一并删除 |
| **L-12** | `util/PerformanceMonitor.kt`（整文件，212 行） | 唯一真实调用是 `BottomNavigationBar` 里每次重组执行一次的 `recordRecomposition()`，它**在组合阶段写 `MutableStateFlow` 并重建整个 Map**，而该 StateFlow 全仓零读取方。删除调用 + 删除整文件 + 删除两个空 import。删除后该文件零引用（含 `measurePerformance` 与两个 data class） |
| **L-13** | `ScrollOptimizations.rememberIsScrollingUp()` / `rememberIsScrollInProgress()` | 两者全仓零调用；且前者**从定义上不可能返回 `true`** —— 它拿 `listState.firstVisibleItemIndex` 与 `layoutInfo.visibleItemsInfo.first().index` 比较，而这两者是同一个东西，两个分支都退化成 `a < a`。删除（连同因此未使用的 `derivedStateOf` / `LazyListState` import） |
| **L-14** | `ShortcutTrampolineActivity` 的 `SHOW_GUIDE` 分支 | 注释写"不调用 `finish()`，让主界面自然显示"，而它调用的 `openMainActivityWithGuide()` **内部就会** `startActivity` + `finish()`。注释与实现直接矛盾。保留 `finish()`（跳板 Activity 启动主界面后结束自己才是对的，否则返回栈里多一层空白页），改注释使其与实现一致，并说明"无论 startActivity 成功与否都结束" |

---

## 9. 本轮明确未修（不留模糊）

| ID | 为什么不在本轮 |
|---|---|
| **M-04** `importSms()` 四个入口绕过 `isSmsImported` 闸门 | 该闸门在 `ComposePickupViewModel` 上，而四个入口在 `ComposeMainActivity`。修法涉及"闸门归属"的产品决策（闸门应放到 Repository 层还是保留在 ViewModel），且与 H-10 的权限路径耦合 —— 权限流程刚被本轮改动，一次性改两处会让"哪一刀导致的回归"无法定位。**排入 R5**，与本轮权限改动分开可归因 |
| **L-02** `STATUS_EXPIRED` 死状态 | 值 `2` 已由 `MIGRATION_8_9` 刷回 `0`，`ExpireCheckWorker` 明确注释不再写入。但常量被 `PickupItem`（常量 + 文案映射）与 `PickupCard`（`when` 分支）引用，删除需要动 3 个文件且**不影响任何当前行为**（该分支不可达）。保留并已在代码注释中标明其为历史遗留，收益 > 风险 |
| **L-07** 布局硬编码 `android:text="ABCD1234"` / `"菜鸟驿站"` | 属"文档与实现"面的资源问题，与 H-14（README 声称"布局 XML 里的中文为 0"）是同一批数字，**排入 R6** 与 README 数字一起改，避免同一批事实分两次修、两次都要复核 |
| **M-19 / M-20 / M-21** 权限检测的机型分支恒真/恒假 | 需要真机（vivo / 三星 / 华为 / 荣耀）逐条验证才敢改 —— 这三条本质是"在有该机型的设备上测过吗"，改错了比不改更糟。列入 `99-unverifiable.md` 的待真机项 |

---

## 10. 验证

```
$ GRADLE_USER_HOME=D:/.gradle ANDROID_HOME=D:/.gradle/android-sdk \
    ./gradlew --offline :app:testDebugUnitTest
BUILD SUCCESSFUL
```

| 项 | 结果 |
|---|---|
| 测试总数 | **83**（R3 结束时为 77，本轮 +6） |
| 通过 | 81 |
| 跳过 | 2（`RuleBasedPickupParserAccuracyEval`，需要真实语料，语料不在仓库内 —— 见 `99-unverifiable.md`） |
| 失败 / 错误 | 0 / 0 |
| 编译警告 | 仅 2 条既有 deprecation（`Icons.Outlined.HelpOutline`、`AlertDialog`），非本轮引入 |

本轮的 6 条新测试全部集中在 `PendingResultGuardTest`，理由见 §1.4：
H-08 是本轮唯一一条"契约本身是纯逻辑、因而可以被 JVM 断言钉死"的缺陷。
其余七条要么依赖 Compose 运行时（H-11 / M-09 / M-10 的 UI 状态机）、
要么依赖真实 `Activity` 与系统权限回调（H-10），在无 Robolectric 的当前测试环境下
无法断言 —— **这一点如实记录，不当作"已测试"**。

改动规模：14 个已跟踪文件，`+623 / -680`（净 −57 行）；新增 2 个文件
（`PendingResultGuard.kt` 70 行、`PendingResultGuardTest.kt` 129 行）。

---

## 11. 交给 R6 的精确清单：仓库自带文档校验器当前报 6 处失效

本轮的编辑**移动了若干源文件的行号**，因此让 `tools/check_doc_refs.py` 里
一些**原本就存在**的失效从"2 处"变成"6 处"（H-14 记录了其中 2 处 +7 漂移）。
本轮**刻意不在这里修 README** —— 理由是 H-14 要修的不只是锚点，
还有 README 里那几个可机械复核的**数字**（中文串 819 vs 实测 1509、
布局 XML 中文"0 处" vs 29 处、`util/` 文件数）。锚点单独修一次、数字再修一次，
等于同一批事实要复核两遍，且第一遍修正会给人"README 已被验证"的错觉。
所以整批留到 R6，与 M-05（校验器本身要变强）一起做。

**R6 必须清零的 6 行（当前实测输出）**：

```
$ python tools/check_doc_refs.py     # 退出码 1
共校验 112 处引用
!! 6 处失效：
   README:71   行号对不上: RuleMatchingScreen.kt:594  找不到 ['PickupCodeExtractorV3','CustomRuleEngine','addRule','rule']
   README:244  行号对不上: RuleMatchingScreen.kt:594  同上
   README:290  行号对不上: PickupItem.kt:64           找不到 ['isExpired']
   README:315  行号对不上: SmsReceiver.kt:115-150     找不到 ['AddressMatcher']
   README:400  行号对不上: PickupDetailDialog.kt:165  找不到 ['PickupItem','rawSms']
   README      文件数不符: util/ 表中 21 实际 23
```

按"是谁让它失效的"分类，方便 R6 判断该改文档还是改代码：

| 行 | 归因 | 说明 |
|---|---|---|
| `PickupDetailDialog.kt:165` | **R4 造成位移** | 本轮在该文件插入 `LaunchedEffect` 与 M-10 的按钮区，`PickupItem`/`rawSms` 的引用被下推 |
| `util/ 表中 21 实际 23` | **R4 造成数字变化** | 本轮删除 `PerformanceMonitor.kt`（占 1 个）+ 新增 `PendingResultGuard.kt`；表中数字本身还要对一次账 |
| `RuleMatchingScreen.kt:594` ×2 | R2/R3/R4 累积位移 | 该文件在 R2 之后被三轮连续改动，README 的锚点是更早的基线 |
| `PickupItem.kt:64` | R3 造成位移 | R3 改了 `Index` 注释与迁移说明 |
| `SmsReceiver.kt:115-150` | R3 造成位移 | R3 把内联解析换成 `PickupIngest.parse`，删了约 45 行 |

**另有一条 R4 无法验证、必须交给 CI 的**：本轮的编译与测试是在
`GRADLE_USER_HOME=D:/.gradle` 的**离线**环境（219 个依赖模块已缓存）下跑的。
这意味着"从干净克隆能否构建"这个硬门禁**本轮没有被真正验证** ——
离线缓存里到底有没有一个"恰好没被引用、但被 `build.gradle.kts` 声明"的依赖，
本轮看不到。R6 需要把这一点作为独立项处理，不能沿用本轮的 `BUILD SUCCESSFUL` 当证据。

