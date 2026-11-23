/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.RemoteViews
import com.shanqiu.pickupmanager.R
import com.shanqiu.pickupmanager.data.db.PickupDatabase
import com.shanqiu.pickupmanager.data.model.PickupItem
import com.shanqiu.pickupmanager.util.PickupSummaryNotifier
import com.shanqiu.pickupmanager.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 小部件同步助手
 * 用于在主应用内数据变化时通知小部件自动刷新
 *
 * 2026-11-09 起这里同时是**待取件汇总通知**的唯一刷新入口：全仓 13 处写库路径
 * 都会调用本方法，而这里本来就要查一次待取件列表，顺手把列表交给
 * [PickupSummaryNotifier.update] 即可，不必再为每个调用点补一遍通知、也不多查一次库。
 * 依然是静默刷新：不弹 Toast、不逐条提醒，只更新小部件与那一条汇总通知。
 *
 * 支持 7 种尺寸的小部件：
 * - PickupWidgetProvider (3×3)
 * - PickupWidgetProvider2x2
 * - PickupWidgetProvider3x4
 * - PickupWidgetProvider4×3
 * - PickupWidgetProvider4×4
 * - PickupWidgetProvider5×4
 * - PickupWidgetProvider5×3
 * 
 * 修复记录：
 * 1. 直接更新徽章数字，不依赖异步的 notifyAppWidgetViewDataChanged
 * 2. 使用协程查询数据库，确保数据准确
 * 3. 多重触发机制确保刷新成功
 * 4. 添加版本号机制避免竞态条件
 */
object WidgetSyncHelper {

    private const val TAG = "WidgetSyncHelper"

    /** 所有 Provider 类列表 */
    private val allProviderClasses: Array<Class<*>> = arrayOf(
        PickupWidgetProvider::class.java,
        PickupWidgetProvider2x2::class.java,
        PickupWidgetProvider3x4::class.java,
        PickupWidgetProvider4x3::class.java,
        PickupWidgetProvider4x4::class.java,
        PickupWidgetProvider5x4::class.java,
        PickupWidgetProvider5x3::class.java
    )
    
    /** Provider 对应的布局 ID */
    private val providerLayoutMap: Map<Class<*>, Int> = mapOf(
        PickupWidgetProvider::class.java to R.layout.widget_pickup_small,
        PickupWidgetProvider2x2::class.java to R.layout.widget_pickup_2x2,
        PickupWidgetProvider3x4::class.java to R.layout.widget_pickup_3x4,
        PickupWidgetProvider4x3::class.java to R.layout.widget_pickup_4x3,
        PickupWidgetProvider4x4::class.java to R.layout.widget_pickup_4x4,
        PickupWidgetProvider5x4::class.java to R.layout.widget_pickup_5x4,
        PickupWidgetProvider5x3::class.java to R.layout.widget_pickup_5x3
    )

    /**
     * 通知所有小部件实例静默刷新
     * 在主应用内任何修改取件数据后调用（如标记已取、删除、新增等）
     * 不会弹出任何 Toast 或通知，仅默默更新小部件
     *
     * 遍历 7 种尺寸的所有 Provider，刷新每个尺寸下所有已添加的小部件实例
     *
     * 关键设计：使用多重触发避免数据丢失
     * 1. 立即查询数据库获取最新待取件数量
     * 2. 直接更新所有小部件的徽章数字（同步，不依赖异步刷新）
     * 3. 触发 ListView 数据刷新
     * 4. 延迟再触发一次（兜底机制）
     */
    fun notifyWidgetDataChanged(context: Context) {
        val appContext = context.applicationContext
        
        // 使用协程查询最新数据并更新 UI
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(appContext)
                val listViewResId = appContext.resources.getIdentifier(
                    "widget_pickup_list", "id", appContext.packageName
                )

                // 1. 查询最新待取件数量
                val database = PickupDatabase.getDatabase(appContext)
                val dao = database.pickupItemDao()
                val displayDays = PreferenceManager.getDefaultDisplayDays(appContext)
                val thresholdTime = System.currentTimeMillis() - (displayDays.toLong() * 24 * 60 * 60 * 1000)
                
                // 使用 runBlocking 确保数据加载完成。保留列表本身而不只是数量：
                // 汇总通知要用它生成文案，交给它就不必再查一次库。
                val pending = runBlocking {
                    dao.getItemsByStatusList(PickupItem.STATUS_PENDING)
                }.filter { it.receiveTime >= thresholdTime }
                val count = pending.size

                PickupSummaryNotifier.update(appContext, pending)

                Log.d(TAG, "查询到 $count 个待取件，准备更新所有小部件")

                var totalCount = 0

                // 2. 切换到主线程更新 UI
                Handler(Looper.getMainLooper()).post {
                    try {
                        // 3. 遍历每种尺寸的 Provider
                        for (providerClass in allProviderClasses) {
                            val componentName = ComponentName(appContext, providerClass)
                            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

                            if (appWidgetIds.isEmpty()) continue

                            totalCount += appWidgetIds.size
                            Log.d(TAG, "${providerClass.simpleName}: ${appWidgetIds.size} 个实例")

                            // 4. 直接更新徽章数字（关键修复：同步更新，不依赖异步刷新）
                            val layoutResId = providerLayoutMap[providerClass] ?: R.layout.widget_pickup_small
                            val badgeViews = RemoteViews(appContext.packageName, layoutResId)
                            badgeViews.setTextViewText(R.id.widget_pending_count, count.toString())
                            appWidgetManager.partiallyUpdateAppWidget(appWidgetIds, badgeViews)

                            // 5. 触发 ListView 数据刷新
                            if (listViewResId != 0) {
                                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, listViewResId)
                            }
                        }

                        Log.d(TAG, "已静默刷新 $totalCount 个小部件，徽章更新为 $count")
                    } catch (e: Exception) {
                        Log.e(TAG, "更新小部件失败: ${e.message}", e)
                    }
                    
                    // 6. 延迟再触发多次列表刷新（兜底机制）
                    if (totalCount > 0) {
                        val delays = listOf(300L, 800L, 1500L)
                        for (delay in delays) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                try {
                                    val mgr = AppWidgetManager.getInstance(appContext)
                                    val resId = appContext.resources.getIdentifier(
                                        "widget_pickup_list", "id", appContext.packageName
                                    )
                                    if (resId != 0) {
                                        for (providerClass in allProviderClasses) {
                                            val componentName = ComponentName(appContext, providerClass)
                                            val appWidgetIds = mgr.getAppWidgetIds(componentName)
                                            if (appWidgetIds.isNotEmpty()) {
                                                mgr.notifyAppWidgetViewDataChanged(appWidgetIds, resId)
                                            }
                                        }
                                    }
                                    Log.d(TAG, "延迟刷新完成 (delay=${delay}ms)")
                                } catch (e: Exception) {
                                    Log.w(TAG, "延迟刷新失败: ${e.message}")
                                }
                            }, delay)
                        }
                    }
                }

                if (totalCount == 0) {
                    Log.d(TAG, "无小部件实例，跳过通知")
                }
            } catch (e: Exception) {
                Log.e(TAG, "通知小部件刷新失败: ${e.message}", e)
            }
        }
    }
}
