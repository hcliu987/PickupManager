/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.shanqiu.pickupmanager.R
import com.shanqiu.pickupmanager.data.db.PickupDatabase
import com.shanqiu.pickupmanager.data.model.PickupItem
import com.shanqiu.pickupmanager.data.model.ModeType
import com.shanqiu.pickupmanager.data.model.RouteSettings
import com.shanqiu.pickupmanager.util.PreferenceManager
import kotlinx.coroutines.runBlocking

/**
 * 取件码小部件数据服务
 * 为ListView提供可滑动的取件码数据
 * 
 * 修复记录：
 * 1. 优化数据加载逻辑，减少阻塞
 * 2. 添加日志追踪数据加载时机
 * 3. 确保数据一致性
 */
class PickupWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return PickupWidgetFactory(applicationContext, intent)
    }

    class PickupWidgetFactory(
        private val context: Context,
        private val intent: Intent
    ) : RemoteViewsFactory {

        private var pickupItems: MutableList<PickupItem> = mutableListOf()
        private val appWidgetId: Int = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        // 从intent读取显示天数设置（与应用内保持一致）
        private val displayDays: Int = intent.getIntExtra("display_days", 7)
        
        // 用于追踪数据加载的版本号，避免竞态条件
        private var loadVersion: Long = 0

        override fun onCreate() {
            // 关键修复：进程冷启动时从 SharedPreferences 恢复数据
            // 避免 :widgetProvider 进程被系统回收后 widget 显示空白
            val cachedItems = WidgetDataPersistence.restoreFromDisk(context)
            if (cachedItems.isNotEmpty()) {
                pickupItems = cachedItems.toMutableList()
                Log.d(TAG, "onCreate: 从磁盘恢复 ${pickupItems.size} 条数据（兜底）")
            }
        }

        override fun onDataSetChanged() {
            // 增加版本号，用于追踪数据加载
            loadVersion++
            val currentVersion = loadVersion
            Log.d(TAG, "onDataSetChanged: 开始加载数据 (version=$currentVersion)")
            
            loadData(currentVersion)
        }

        override fun onDestroy() {
            pickupItems.clear()
        }

        override fun getCount(): Int = pickupItems.size

        override fun getViewAt(position: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_pickup_item)

            if (position >= pickupItems.size) {
                // 越界兜底（L-07）：数据在刷新瞬间变化时，ListView 仍可能请求越界位置。
                // 直接 return views 会把布局里写死的示例文案渲染出去 ——
                // 用户会看到一条并不存在的包裹，而那个"取件码"看起来和真的一样。
                // 这里把两个可变文本显式清空，让越界项渲染成空行而不是假数据。
                views.setTextViewText(R.id.item_pickup_code, "")
                views.setTextViewText(R.id.item_address, "")
                return views
            }

            val item = pickupItems[position]

            // 设置取件码
            views.setTextViewText(R.id.item_pickup_code, item.pickupCode)

            // 设置地址
            val address = item.address.ifBlank { item.addressTag.ifBlank { "未知地址" } }
            views.setTextViewText(R.id.item_address, address)

            // 设置"已取"按钮点击事件
            // 使用 setOnClickFillInIntent + setPendingIntentTemplate（ListView 标准做法）
            // 每个 item 携带自己的 itemId 和 appWidgetId extras
            // 注意：需要 widget 端在 updateAppWidget 中设置 setPendingIntentTemplate
            val markPickedIntent = Intent().apply {
                action = BasePickupWidgetProvider.ACTION_MARK_PICKED
                putExtra(BasePickupWidgetProvider.EXTRA_ITEM_ID, item.id)
                putExtra(BasePickupWidgetProvider.EXTRA_WIDGET_ID, appWidgetId)
            }
            views.setOnClickFillInIntent(R.id.item_pickup_btn, markPickedIntent)

            return views
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 1

        override fun getItemId(position: Int): Long =
            pickupItems.getOrNull(position)?.id ?: position.toLong()

        // 关键修复：hasStableIds 改为 false
        // MIUI 14 的 RemoteViewsService 实现有 bug，当 hasStableIds=true 时
        // 反序列化 RemoteCollectionItems 内部 Parcel 会触发 ArrayIndexOutOfBoundsException
        // 改为 false 避免 id 数组序列化路径
        override fun hasStableIds(): Boolean = false

        /**
         * 加载数据
         * @param version 当前加载版本号，用于检测竞态条件
         */
        private fun loadData(version: Long) {
            try {
                val database = PickupDatabase.getDatabase(context)
                val dao = database.pickupItemDao()

                // 使用与应用内相同的显示天数过滤
                val thresholdTime = System.currentTimeMillis() - (displayDays.toLong() * 24 * 60 * 60 * 1000)

                // 读取路由排序设置
                val routeSettings = PreferenceManager.getRouteSettings(context)

                // 检查版本号是否已变化（说明有新的刷新请求）
                if (version != loadVersion) {
                    Log.d(TAG, "loadData: 版本已变化，放弃本次加载 (current=$loadVersion, this=$version)")
                    return
                }

                // 使用runBlocking + suspend查询待取件，onDataSetChanged运行在后台HandlerThread上
                val newItems = runBlocking {
                    val pendingItems = dao.getItemsByStatusList(PickupItem.STATUS_PENDING)
                    val filtered = pendingItems.filter { it.receiveTime >= thresholdTime }
                    sortItemsByRoute(filtered, routeSettings).toMutableList()
                }

                // 再次检查版本号，避免竞态条件
                if (version != loadVersion) {
                    Log.d(TAG, "loadData: 加载完成后版本已变化，放弃保存 (current=$loadVersion, this=$version)")
                    return
                }

                pickupItems = newItems

                // 关键修复：加载成功后立即持久化到磁盘
                // 当 :widgetProvider 进程被系统回收时，可从磁盘恢复
                WidgetDataPersistence.saveToDisk(context, pickupItems)

                Log.d(TAG, "加载到 ${pickupItems.size} 条待取件数据（显示天数: $displayDays, version=$version）")
            } catch (e: Exception) {
                Log.e(TAG, "加载取件数据失败: ${e.message}", e)
                // 加载失败不清空，保留磁盘缓存（兜底机制）
                if (pickupItems.isEmpty()) {
                    val cached = WidgetDataPersistence.restoreFromDisk(context)
                    if (cached.isNotEmpty()) {
                        pickupItems = cached.toMutableList()
                        Log.d(TAG, "加载失败，从磁盘恢复 ${pickupItems.size} 条数据")
                    }
                }
            }
        }

        /**
         * 按取件路径排序（与应用内逻辑一致）
         * 三级排序：路由索引 > 地址标签字典序 > 接收时间倒序
         */
        private fun sortItemsByRoute(items: List<PickupItem>, settings: RouteSettings): List<PickupItem> {
            if (items.isEmpty()) return items

            val currentMode = if (settings.autoSwitch) {
                val calendar = java.util.Calendar.getInstance()
                val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK) - 1
                if (settings.workMode.applyDays.contains(dayOfWeek)) ModeType.WORK_MODE
                else ModeType.VACATION_MODE
            } else {
                settings.currentMode
            }

            val routes = if (currentMode == ModeType.WORK_MODE) {
                settings.workMode.routes
            } else {
                settings.vacationMode.routes
            }

            val routeOrder = routes.mapIndexed { index, route ->
                route.addressTag to index
            }.toMap()

            return items.sortedWith(
                compareBy<PickupItem> { routeOrder[it.addressTag] ?: Int.MAX_VALUE }
                    .thenBy { it.addressTag }
                    .thenByDescending { it.receiveTime }
            )
        }

        companion object {
            private const val TAG = "PickupWidget"
        }
    }
}
