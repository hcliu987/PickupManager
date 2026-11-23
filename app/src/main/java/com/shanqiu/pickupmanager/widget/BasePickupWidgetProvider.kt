/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.RemoteViews
import com.shanqiu.pickupmanager.R
import com.shanqiu.pickupmanager.data.db.PickupDatabase
import com.shanqiu.pickupmanager.data.model.PickupItem
import com.shanqiu.pickupmanager.ui.ComposeMainActivity
import com.shanqiu.pickupmanager.util.PendingResultGuard
import com.shanqiu.pickupmanager.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 取件码桌面小部件 - 基类
 * 提供所有 6 种尺寸的通用逻辑，子类只需指定 layoutId
 *
 * ## R4 结构性清理（M-23 / M-01 / M-22）
 *
 * 本类原先带一条完整的"刷新广播"链路：`onReceive` 收 `ACTION_REFRESH` /
 * `ACTION_SILENT_REFRESH` → `handleRefreshAction()`（含四段递归重试）→
 * `updateProgressBadge()`。这条链路**全仓没有任何发送方**：
 *
 * - 刷新按钮走的是 `PendingIntent.getForegroundService()` →
 *   `WidgetActionService`，服务自己完成"查库 + 刷列表 + 刷徽章 + Toast"
 *   （见 `WidgetActionService.handleRefresh`），从不回调本类；
 * - 全仓 `grep ACTION_REFRESH` 只命中两处**同名字面量**：
 *   `WidgetActionService.ACTION_REFRESH`（发送方，值 `...widget.ACTION_REFRESH`）
 *   与本类这个（值 `...widget.REFRESH`，只被自己的 `when` 读）。
 *
 * 也就是说它是约 200 行死代码。而它同时是两件事的**唯一入口**：
 * ① H-08 的主线程崩溃链（`handleRefreshAction` 的递归 → 第二次 `goAsync()`
 * 返回 null → `finally` 里的 `finish()` 抛 NPE）；
 * ② 一个**对外可达的攻击面** —— 7 个 receiver 全部 `exported="true"` 且无权限，
 * 且清单里挂着 `...widget.REFRESH` / `...widget.SILENT_REFRESH` 两个 action，
 * 本机任意应用都能发。没有入口的功能，却扩大了伪造面。
 *
 * 所以本轮直接删除整条链路（连同清单里那两个 action、连同只为它服务的
 * `refreshInProgress` / `lastRefreshStartTime` / `REFRESH_COOLDOWN_MS` /
 * `refreshVersion` 四个成员）。删除后小部件刷新行为**不变** ——
 * 因为它本来就只由 `WidgetActionService` 驱动。
 *
 * 顺带修掉 M-22：`updateProgressBadge()` 注释自称"只更新一个 TextView"，
 * 实际用 `updateAppWidget()` **整体替换** RemoteViews（刷新瞬间 ListView
 * 适配器 / 空态 / 按钮 PendingIntent 全部丢失）。该方法随链路一并删除。
 */
abstract class BasePickupWidgetProvider : AppWidgetProvider() {

    companion object {
        /**
         * 列表项的"标记已取"动作。
         *
         * 由 `PickupWidgetService` 与 `updateAppWidget()` 通过
         * `PendingIntent.getBroadcast()`（**显式** Intent）投递，是本类
         * `onReceive` 里唯一仍有真实发送方的自定义 action。
         */
        const val ACTION_MARK_PICKED = "com.shanqiu.pickupmanager.widget.MARK_PICKED"
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_PICKUP_CODE = "pickup_code"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_WIDGET_ID = "widget_id"
        const val TAG = "PickupWidget"

        // 全局 Toast 计数器（用于日志调试）
        private val toastCounter = java.util.concurrent.atomic.AtomicInteger(0)
    }

    /** 子类提供该尺寸对应的 layout ID */
    abstract val layoutId: Int

    /** 子类标记布局是否包含 ListView（2×2 布局无 ListView） */
    protected open val hasListView: Boolean = true

    /** 标记本 Provider 的字符串标签（用于日志/区分） */
    protected open fun providerTag(): String = this::class.java.simpleName

    /**
     * 安全版 `goAsync()`（H-08 / M-01）。
     *
     * AOSP 的实现只有三行 —— 取走 `mPendingResult` 并把它置空，所以：
     *
     * 1. **第二次调用返回 null**。只要一次广播分发里走到两次 `goAsync()`
     *    （本类的 `onReceive`（MIUI 曝光刷新）→ `updateAppWidget`、
     *    `onReceive` → `markItemAsPicked` 都是这种关系），
     *    第二次拿到 null，随后 `finally { pendingResult.finish() }` 抛 NPE。
     *    那个 NPE 抛在 `finally` 里，**同一个 try 的 catch 抓不到**，
     *    直接成为主线程崩溃。
     * 2. **非 `onReceive` 调用点恒为 null**。`onUpdate` 由 AppWidgetService 回调，
     *    不在广播分发链路里，`mPendingResult` 本来就是 null。
     *
     * 统一走这个入口后：拿不到就记一条 warning 并返回一个空守卫
     * （`finish()` 是空操作），拿得到就返回幂等守卫（`finish()` 最多生效一次）。
     * 崩溃消失，而且日志会明确告诉你哪条调用点其实拿不到 PendingResult。
     */
    private fun goAsyncSafely(): PendingResultGuard {
        val pending = try {
            goAsync()
        } catch (t: Throwable) {
            Log.w(TAG, "goAsync() 抛出异常", t)
            null
        }
        if (pending == null) {
            Log.w(
                TAG,
                "goAsync() 返回 null：当前不在 onReceive 的广播分发中，或本次分发已被取走。" +
                    "异步保活守卫失效，但不会 NPE。"
            )
            return PendingResultGuard(null)
        }
        return PendingResultGuard { pending.finish() }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // 首次/每次 onUpdate 时调用 updateAppWidget 完成按钮绑定和布局初始化
        // 之后通过 notifyAppWidgetViewDataChanged 仅刷新列表数据
        for (appWidgetId in appWidgetIds) {
            try {
                // 1. 完整初始化：绑定按钮 PendingIntent、设置 RemoteAdapter 等
                updateAppWidget(context, appWidgetManager, appWidgetId)

                // 2. 异步更新徽章数字（延迟避免竞争）
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        updateWidgetBadge(context, appWidgetId)
                    } catch (e: Throwable) {
                        Log.w(TAG, "onUpdate 徽章更新失败: ${e.message}")
                    }
                }, 200)
            } catch (e: Throwable) {
                Log.e(TAG, "onUpdate 失败: ${e.message}", e)
                // 兜底：重试一次完整更新
                try {
                    updateAppWidget(context, appWidgetManager, appWidgetId)
                } catch (ex: Throwable) {
                    Log.e(TAG, "onUpdate 兜底也失败: ${ex.message}", ex)
                }
            }
        }
    }

    /**
     * 仅更新徽章数字，不重置其他 UI
     * 注意：本方法从 Handler.postDelayed 回调中调用（onReceive 已返回），
     * goAsync() 在此时无效，直接使用协程执行
     */
    private fun updateWidgetBadge(context: Context, appWidgetId: Int) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = PickupDatabase.getDatabase(appContext)
                val dao = database.pickupItemDao()
                val displayDays = PreferenceManager.getDefaultDisplayDays(appContext)
                val thresholdTime = System.currentTimeMillis() - (displayDays.toLong() * 24 * 60 * 60 * 1000)
                val count = dao.getItemsByStatusList(PickupItem.STATUS_PENDING)
                    .count { it.receiveTime >= thresholdTime }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        val badgeViews = RemoteViews(appContext.packageName, layoutId)
                        badgeViews.setTextViewText(R.id.widget_pending_count, count.toString())
                        AppWidgetManager.getInstance(appContext).partiallyUpdateAppWidget(appWidgetId, badgeViews)
                    } catch (e: Throwable) {
                        Log.w(TAG, "更新徽章UI失败: ${e.message}")
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "查询徽章数据失败: ${e.message}")
            }
        }
    }

    /**
     * 优化响应速度：直接处理自定义 action，不调 super.onReceive
     *
     * 遵循 MIUI/HyperOS 官方文档：
     * - miui.appwidget.action.APPWIDGET_UPDATE：曝光刷新（用户滑动到桌面时触发）
     * - 系统 action 仍然调 super 处理
     *
     * R4（M-23）：原先这里还有 `ACTION_REFRESH` / `ACTION_SILENT_REFRESH`
     * 两个分支，全仓无发送方，已连同处理函数一并删除。详见类注释。
     */
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // MIUI/HyperOS 曝光刷新（官方文档 2.2 节）
            // 用户滑动到有小部件的桌面页面时触发
            "miui.appwidget.action.APPWIDGET_UPDATE" -> {
                val appWidgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                if (appWidgetIds != null) {
                    Log.d(TAG, "MIUI 曝光刷新触发: ${appWidgetIds.size} 个小部件")
                    // 这是一条真正的广播分发，可以在 onReceive 里合法地 goAsync()。
                    // 只取一次，循环里所有小部件共用同一个守卫 —— finish() 是幂等的，
                    // 第一个跑完的异步任务真正结束分发，其余调用都是空操作（H-08）。
                    val keepAlive = goAsyncSafely()
                    for (appWidgetId in appWidgetIds) {
                        val mgr = AppWidgetManager.getInstance(context)
                        mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_pickup_list)
                        updateAppWidget(context, mgr, appWidgetId, keepAlive = keepAlive)
                    }
                }
                return
            }
            ACTION_MARK_PICKED -> {
                val itemId = intent.getLongExtra(EXTRA_ITEM_ID, -1)
                val appWidgetId = intent.getIntExtra(
                    EXTRA_WIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                android.util.Log.d(TAG, "收到 ACTION_MARK_PICKED: itemId=$itemId, appWidgetId=$appWidgetId, action=${intent.action}")
                if (itemId != -1L && ownsWidgetId(context, appWidgetId)) {
                    markItemAsPicked(context, itemId, appWidgetId)
                } else {
                    android.util.Log.w(TAG, "ACTION_MARK_PICKED 被忽略：itemId 缺失或 appWidgetId 不属于本 Provider")
                }
                return
            }
        }
        // 仅对系统 action 调 super
        super.onReceive(context, intent)
    }

    /**
     * 这个 appWidgetId 是否真的由本 Provider 承载。
     *
     * 三条自定义 action 都挂在 exported="true" 的接收器上，本机任意应用都能发；
     * 而 intent 里的 appWidgetId / item_id 都是可枚举的整数，所以"带了 id"不等于"是本应用发出的点击"。
     * 这一层至少把伪造面从"任意 id"收窄到"必须是本机已存在的本 Provider 实例"。
     * 彻底堵住需要真机验证（给接收器加 BIND_APPWIDGET，或给 PendingIntent 带一次性令牌），
     * 已记进 README 的问题清单。
     */
    protected fun ownsWidgetId(context: Context, appWidgetId: Int): Boolean {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return false
        return try {
            val ids = AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, javaClass))
            ids.any { it == appWidgetId }
        } catch (e: Exception) {
            Log.w(TAG, "校验 appWidgetId 归属失败，按不通过处理")
            false
        }
    }

    override fun onEnabled(context: Context) {}

    override fun onDisabled(context: Context) {}

    // R4（M-23）：此处原为 `silentRefreshWidget()` / `handleRefreshAction()`
    // （含四段递归重试）/ `updateProgressBadge()` 共约 195 行死代码，
    // 已删除。删除依据与影响面见类注释「R4 结构性清理」。
    /**
     * 立即显示 Toast
     *
     * 关键设计：
     * 1. 每次都创建新 Toast 实例，不 cancel 前一个（避免 cancel+show 时序问题）
     * 2. 使用 applicationContext 避免 Activity 泄漏
     * 3. 仅 Toast 反馈，不再使用通知栏
     */
    private fun showToastImmediately(context: Context, message: String) {
        try {
            val toast = android.widget.Toast.makeText(
                context.applicationContext,
                message,
                android.widget.Toast.LENGTH_SHORT
            )
            toast.setGravity(
                android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL,
                0,
                200
            )
            toast.show()

            val count = toastCounter.incrementAndGet()
            Log.d(TAG, "Toast #$count: $message")
        } catch (e: Exception) {
            Log.w(TAG, "Toast 显示失败: ${e.message}")
        }
    }

    /**
     * 更新小部件
     *
     * 遵循 MIUI/HyperOS 官方小部件技术规范：
     * 1. 所有按钮使用 PendingIntent.getActivity（官方推荐）
     * 2. 刷新按钮通过参数区分，Activity 启动后触发刷新
     * 3. 先 notifyAppWidgetViewDataChanged 异步触发 Factory.onDataSetChanged
     *
     * ## R4（M-01）：保活守卫改为**由调用方传入**
     *
     * 原先本方法内部无条件调用 `goAsync()`。但本方法有两个调用来源，
     * 语义完全不同：
     * - `onUpdate()` —— 由 AppWidgetService 回调，**不在广播分发中**，
     *   `goAsync()` 必然返回 null（原写法只是安静地拿到 null，随后
     *   `finally` 里的 `finish()` 抛 NPE 被 `catch` 吞掉，即"守卫从未生效"）；
     * - `onReceive()` 的 MIUI 曝光刷新分支 —— **在广播分发中**，
     *   `goAsync()` 合法且必要。
     *
     * 现在把守卫作为参数（`keepAlive`）：广播路径传一个真的守卫，onUpdate 路径
     * 传 null（本来也拿不到）。这样"该不该保活"由调用点决定，而不是让被复用的
     * 中间函数去猜。`PendingResultGuard.finish()` 是幂等的，所以循环里多个
     * 小部件共用同一个守卫时，只有第一个真正结束分发。
     *
     * 顺带删掉从未被传过的 `skipBadgeUpdate` 死参数 —— 三个调用点全部用默认值，
     * 徽章更新一直是执行的。
     */
    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        keepAlive: PendingResultGuard? = null
    ) {
        val views = RemoteViews(context.packageName, layoutId)

        // 打开应用按钮：直接启动 ComposeMainActivity
        // 使用 requestCode 区分不同 widget（不依赖 data URI，避免 HyperOS 解析异常）
        val openIntent = Intent(context, ComposeMainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            appWidgetId + 5000,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_btn_open, openPendingIntent)

        // 刷新按钮：PendingIntent.getForegroundService() 启动 WidgetActionService
        // 仅 ListView 布局（3×3/4×4 等）有刷新按钮，2×2 无此按钮
        if (hasListView) {
            val refreshIntent = Intent(context, WidgetActionService::class.java).apply {
                action = WidgetActionService.ACTION_REFRESH
                data = android.net.Uri.parse("pickupmanager://widget/refresh/${appWidgetId}")
                putExtra(WidgetActionService.EXTRA_WIDGET_ID, appWidgetId)
            }
            val refreshPendingIntent = PendingIntent.getForegroundService(
                context,
                appWidgetId,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_refresh, refreshPendingIntent)
        }

        // 读取显示天数
        val displayDays = PreferenceManager.getDefaultDisplayDays(context)
        val thresholdTime = System.currentTimeMillis() - (displayDays.toLong() * 24 * 60 * 60 * 1000)

        // ListView adapter 和模板 PendingIntent — 仅 ListView 布局需要
        if (hasListView) {
            val serviceIntent = Intent(context, PickupWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra("display_days", displayDays)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME) + "/$appWidgetId/${System.currentTimeMillis()}")
            }
            views.setRemoteAdapter(R.id.widget_pickup_list, serviceIntent)
            views.setEmptyView(R.id.widget_pickup_list, R.id.widget_empty_state)

            val templateIntent = Intent(context, this::class.java).apply {
                action = ACTION_MARK_PICKED
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
            }
            val templatePendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId + 10000,
                templateIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
            )
            views.setPendingIntentTemplate(R.id.widget_pickup_list, templatePendingIntent)
        }

        // 提交 RemoteViews（包含按钮 PendingIntent + 列表适配器）
        appWidgetManager.updateAppWidget(appWidgetId, views)

        // 触发列表数据刷新（仅 ListView 布局）
        if (hasListView) {
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_pickup_list)
        }

        // 异步更新徽章数字（不重置按钮 PendingIntent / 列表适配器）。
        // keepAlive 为 null 说明调用点在广播分发之外，此时没有任何东西需要 finish。
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = PickupDatabase.getDatabase(context)
                val dao = database.pickupItemDao()
                val pendingItems = dao.getItemsByStatusList(PickupItem.STATUS_PENDING)
                    .filter { it.receiveTime >= thresholdTime }

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        val badgeViews = RemoteViews(context.packageName, layoutId)
                        badgeViews.setTextViewText(R.id.widget_pending_count, pendingItems.size.toString())
                        appWidgetManager.partiallyUpdateAppWidget(appWidgetId, badgeViews)
                    } catch (e: Exception) {
                        Log.e(TAG, "更新徽章失败: ${e.message}", e)
                        val badgeViews = RemoteViews(context.packageName, layoutId)
                        badgeViews.setTextViewText(R.id.widget_pending_count, "!")
                        appWidgetManager.partiallyUpdateAppWidget(appWidgetId, badgeViews)
                    } finally {
                        keepAlive?.finish()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "查询徽章数据失败: ${e.message}", e)
                keepAlive?.finish()
            }
        }
    }

    /**
     * 标记取件码为已取
     *
     * 关键修复：
     * 1. 立即显示"正在标记"Toast（同步调用，不切线程）
     * 2. goAsync() 保持进程存活，避免 onReceive 返回后进程被回收
     * 3. Handler.post 替代 withContext(Dispatchers.Main)，减少上下文切换延迟
     * 4. 修复嵌套 goAsync() 问题，直接更新徽章数字
     * 5. 修复 updateAppWidget 重置徽章和列表的问题
     */
    private fun markItemAsPicked(
        context: Context,
        itemId: Long,
        appWidgetId: Int
    ) {
        // 立即显示反馈 Toast（同步，让用户立即感知）
        showToastImmediately(context, "正在标记...")

        val pendingResult = goAsyncSafely()
        val originalContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = PickupDatabase.getDatabase(originalContext)
                val dao = database.pickupItemDao()

                // 短暂延迟让用户看到"正在标记"
                kotlinx.coroutines.delay(200)

                // 数据库更新
                val updated = dao.markPickedIfPending(
                    itemId,
                    PickupItem.STATUS_PICKED,
                    PickupItem.STATUS_PENDING,
                    System.currentTimeMillis(),
                )
                if (updated == 0) {
                    // 这条 id 不存在或已不是待取：不做"已标记成功"的反馈，也不重绘小部件。
                    // 注意必须先 finish 广播 —— 早退不 finish 会让 AMS 侧的广播一直挂着。
                    Log.w(TAG, "markItemAsPicked: itemId=$itemId 不是可标记的待取记录，忽略")
                    runCatching { pendingResult.finish() }
                    return@launch
                }
                Log.d(TAG, "markItemAsPicked: itemId=$itemId 已更新")

                // 跨进程通知主进程（widget → APP 数据同步）
                com.shanqiu.pickupmanager.data.CrossProcessNotifier.notifyDataChanged(originalContext)

                // 修复：先查询最新数据，再统一更新 UI
                val displayDays = PreferenceManager.getDefaultDisplayDays(originalContext)
                val thresholdTime = System.currentTimeMillis() - (displayDays.toLong() * 24 * 60 * 60 * 1000)
                val pendingItems = dao.getItemsByStatusList(PickupItem.STATUS_PENDING)
                    .filter { it.receiveTime >= thresholdTime }

                // 切主线程更新 UI
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        showToastImmediately(originalContext, "✓ 已标记为已取件")

                        val mgr = AppWidgetManager.getInstance(originalContext)
                        
                        // 修复：先更新整个 widget（包含徽章和列表适配器），再触发列表刷新
                        // 这样徽章值会被设置在 views 中，不会被重置
                        updateAppWidgetWithValue(originalContext, mgr, appWidgetId, pendingItems.size)
                        
                        // 然后触发列表数据刷新
                        mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_pickup_list)
                        
                        // 关键修复：通知所有其他尺寸的小部件更新
                        // 否则 2×2 小部件不会同步更新
                        WidgetSyncHelper.notifyWidgetDataChanged(originalContext)
                    } catch (e: Throwable) {
                        Log.e(TAG, "markItemAsPicked UI 更新失败: ${e.message}", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "markItemAsPicked 失败: ${e.message}", e)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    showToastImmediately(originalContext, "✗ 标记失败，请重试")
                    pendingResult.finish()
                }
            }
        }
    }
    
    /**
     * 更新小部件并设置徽章值（避免徽章被重置）
     * 
     * 与 updateAppWidget 的区别：此方法会在 views 中设置徽章值，确保徽章不会被重置
     */
    private fun updateAppWidgetWithValue(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        badgeCount: Int
    ) {
        val views = RemoteViews(context.packageName, layoutId)

        // 打开应用按钮
        val openIntent = Intent(context, ComposeMainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            appWidgetId + 5000,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_btn_open, openPendingIntent)

        // 刷新按钮
        if (hasListView) {
            val refreshIntent = Intent(context, WidgetActionService::class.java).apply {
                action = WidgetActionService.ACTION_REFRESH
                data = android.net.Uri.parse("pickupmanager://widget/refresh/${appWidgetId}")
                putExtra(WidgetActionService.EXTRA_WIDGET_ID, appWidgetId)
            }
            val refreshPendingIntent = PendingIntent.getForegroundService(
                context,
                appWidgetId,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_refresh, refreshPendingIntent)
        }

        // 读取显示天数
        val displayDays = PreferenceManager.getDefaultDisplayDays(context)

        // ListView adapter 和模板 PendingIntent
        if (hasListView) {
            val serviceIntent = Intent(context, PickupWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra("display_days", displayDays)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME) + "/$appWidgetId/${System.currentTimeMillis()}")
            }
            views.setRemoteAdapter(R.id.widget_pickup_list, serviceIntent)
            views.setEmptyView(R.id.widget_pickup_list, R.id.widget_empty_state)

            val templateIntent = Intent(context, this::class.java).apply {
                action = ACTION_MARK_PICKED
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
            }
            val templatePendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId + 10000,
                templateIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
            )
            views.setPendingIntentTemplate(R.id.widget_pickup_list, templatePendingIntent)
        }

        // 关键修复：在 views 中设置徽章值，这样 updateAppWidget 不会重置徽章
        views.setTextViewText(R.id.widget_pending_count, badgeCount.toString())

        // 提交 RemoteViews（包含按钮 PendingIntent + 列表适配器 + 徽章值）
        appWidgetManager.updateAppWidget(appWidgetId, views)

        // 触发列表数据刷新
        if (hasListView) {
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_pickup_list)
        }
    }
}
