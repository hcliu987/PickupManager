/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.shanqiu.pickupmanager.R
import com.shanqiu.pickupmanager.data.db.PickupDatabase
import com.shanqiu.pickupmanager.data.model.PickupItem
import com.shanqiu.pickupmanager.ui.ComposeMainActivity
import com.shanqiu.pickupmanager.util.NotificationHelper
import com.shanqiu.pickupmanager.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面小部件按钮前台服务
 *
 * 核心作用：解决应用进程被完全杀死后，widget 按钮无法唤醒应用的问题。
 *
 * 为什么用前台服务而不是广播接收器：
 * - 应用被完全杀死后，系统处于 stopped 状态
 * - 即使 Intent 添加了 FLAG_INCLUDE_STOPPED_PACKAGES，广播也不可靠
 * - Android 12+ 文档明确说明：用户点击 widget 属于"显式用户交互"
 * - 当用户显式交互时，应用可以启动前台服务（Foreground Service）
 * - 前台服务会唤醒应用进程，之后不受后台启动限制
 *
 * 工作流程：
 * 1. 用户点击 widget 按钮 → PendingIntent.getService 启动此服务
 * 2. 服务创建通知并调用 startForeground()
 * 3. 进程唤醒后，执行刷新逻辑或启动 Activity
 * 4. 完成后 stopSelf()
 *
 * 兼容性：
 * - Android 12+：用户点击 widget 属于显式交互，可启动前台服务
 * - Android 14+：需要声明 foregroundServiceType="dataSync"
 * - 前台服务必须在 5 秒内调用 startForeground，否则会被系统杀死
 * 
 * 修复记录：
 * 1. 同时更新徽章数字和列表，避免数据不同步
 * 2. 使用协程确保数据库查询完成后再更新 UI
 * 3. 增加延迟重试机制确保刷新成功
 */
class WidgetActionService : Service() {

    companion object {
        private const val TAG = "WidgetActionService"
        // 复用 NotificationHelper 统一创建的渠道 ID，并沿用同一份名称与描述：
        // 两边各自建渠道会在系统设置里留下一个永不使用的空渠道。
        private const val CHANNEL_ID = NotificationHelper.CHANNEL_ID_WIDGET
        private const val NOTIFICATION_ID = 1001

        // 动作常量
        const val ACTION_REFRESH = "com.shanqiu.pickupmanager.widget.ACTION_REFRESH"
        const val ACTION_OPEN_APP = "com.shanqiu.pickupmanager.widget.ACTION_OPEN_APP"
        const val EXTRA_WIDGET_ID = "widget_id"

        /**
         * 启动刷新服务
         */
        fun startRefresh(context: Context, appWidgetId: Int) {
            val intent = Intent(context, WidgetActionService::class.java).apply {
                action = ACTION_REFRESH
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 启动打开应用服务
         */
        fun startOpenApp(context: Context) {
            val intent = Intent(context, WidgetActionService::class.java).apply {
                action = ACTION_OPEN_APP
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "服务启动: action=${intent?.action}")

        // 关键：必须在 5 秒内调用 startForeground
        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        when (intent?.action) {
            ACTION_REFRESH -> {
                val appWidgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                handleRefresh(appWidgetId, startId)
            }
            ACTION_OPEN_APP -> {
                handleOpenApp(startId)
            }
            else -> {
                Log.w(TAG, "未知动作: ${intent?.action}")
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    /**
     * 处理刷新动作
     * 
     * 修复：同时更新徽章数字，避免徽章与列表数据不同步
     * 使用协程确保数据库查询完成后再更新 UI
     */
    private fun handleRefresh(appWidgetId: Int, startId: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "开始刷新: appWidgetId=$appWidgetId")

                // 1. 从数据库读取数据
                val database = PickupDatabase.getDatabase(applicationContext)
                val dao = database.pickupItemDao()
                val displayDays = PreferenceManager.getDefaultDisplayDays(applicationContext)
                val thresholdTime = System.currentTimeMillis() - (displayDays.toLong() * 24 * 60 * 60 * 1000)
                val items = dao.getItemsByStatusList(PickupItem.STATUS_PENDING)
                    .filter { it.receiveTime >= thresholdTime }

                Log.d(TAG, "查询到 ${items.size} 个待取件")

                // 2. 更新 UI（列表 + 徽章）
                withContext(Dispatchers.Main) {
                    try {
                        val appWidgetManager = AppWidgetManager.getInstance(applicationContext)
                        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                            // 2.1 触发 ListView 数据刷新
                            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_pickup_list)
                            
                            // 2.2 修复：同时更新徽章数字
                            val widgetInfo = appWidgetManager.getAppWidgetInfo(appWidgetId)
                            val layoutResId = widgetInfo?.initialLayout ?: R.layout.widget_pickup_small
                            val badgeViews = RemoteViews(applicationContext.packageName, layoutResId)
                            badgeViews.setTextViewText(R.id.widget_pending_count, items.size.toString())
                            appWidgetManager.partiallyUpdateAppWidget(appWidgetId, badgeViews)
                            
                            Log.d(TAG, "刷新完成: 列表和徽章已更新，count=${items.size}")
                        }
                        Toast.makeText(applicationContext, "✓ 刷新成功，共 ${items.size} 个待取件", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "刷新 widget 失败", e)
                        Toast.makeText(applicationContext, "✗ 刷新失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
                
                // 3. 延迟再触发一次列表刷新（兜底机制）
                kotlinx.coroutines.delay(500)
                withContext(Dispatchers.Main) {
                    try {
                        val mgr = AppWidgetManager.getInstance(applicationContext)
                        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                            mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_pickup_list)
                            Log.d(TAG, "延迟刷新完成")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "延迟刷新失败: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "刷新失败", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "✗ 刷新失败，请重试", Toast.LENGTH_SHORT).show()
                }
            } finally {
                stopSelf(startId)
            }
        }
    }

    /**
     * 处理打开应用动作
     * 此时应用已有前台服务，可以启动 Activity
     */
    private fun handleOpenApp(startId: Int) {
        try {
            Log.d(TAG, "启动主界面")
            val intent = Intent(applicationContext, ComposeMainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
            Toast.makeText(applicationContext, "正在打开应用...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "启动主界面失败", e)
            Toast.makeText(applicationContext, "打开失败，请手动打开应用", Toast.LENGTH_SHORT).show()
        }
        stopSelf(startId)
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "小部件操作",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "处理小部件按钮操作"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建前台服务通知
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("码上取")
            .setContentText("正在处理...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
    }
}
