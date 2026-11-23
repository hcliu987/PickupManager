/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shanqiu.pickupmanager.R
import com.shanqiu.pickupmanager.data.db.PickupDatabase
import com.shanqiu.pickupmanager.data.model.PickupItem
import com.shanqiu.pickupmanager.ui.ComposeMainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 通知栏的**待取件汇总**：一条随数据变化更新的常驻通知。
 *
 * ## 与 2026-09-19 删掉的那套通知有什么不同
 *
 * KNOWN_ISSUES #5 记录过一次「整套删除」：当年那条链路是**自指的**——
 * 发通知的方法只由一个从未在 Manifest 注册的 receiver 调用，过期提醒没有任何
 * 调用点，结果只剩两个永远发不出内容的空渠道，以及一段承诺了不存在功能的
 * 引导文案。删的是这套死链路，不是「通知」这个呈现方式本身。
 *
 * 这次按项目所有者 2026-11-09 的要求重新引入，刻意避开当年的坑：
 *
 * - **不新增 receiver、不新增闹钟调度、不做过期提醒**，只有一条汇总通知；
 * - 唯一刷新入口挂在 [com.shanqiu.pickupmanager.widget.WidgetSyncHelper]
 *   这个已经跑通的数据变化信标上，复用它查出来的待取件列表，不额外查库；
 * - 没有待取件时直接撤掉通知，不留空卡片；
 * - 设置页可整条关掉，关掉后不再发任何内容型通知。
 *
 * ## 隐私口径
 *
 * 通知栏（尤其锁屏）是本机上最容易被他人看到的界面，所以默认**只显示数量**。
 * 用户显式打开开关后才显示最新取件码与快递公司；**地址在任何情况下都不进通知**
 * —— 它比取件码更能定位到个人。通知用 VISIBILITY_PRIVATE，锁屏不展开正文。
 * 文案全部由纯函数 [buildContent] 生成，便于用 JVM 单测锁住这条红线。
 */
object PickupSummaryNotifier {

    /** 与 NotificationHelper 里创建的渠道 ID 保持一致。 */
    const val CHANNEL_ID = "pickup_summary"

    private const val TAG = "PickupSummaryNotifier"
    private const val NOTIFICATION_ID = 4201
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    data class Content(val title: String, val text: String)

    /**
     * 纯函数：通知栏上能出现什么文字，只在这里决定。
     *
     * @param codes 按入库时间倒序的待取件码
     * @param latestCompany 最新一件的快递公司，可空
     * @param showCode 用户是否显式允许在通知里显示取件码
     */
    fun buildContent(codes: List<String>, latestCompany: String?, showCode: Boolean): Content {
        if (codes.isEmpty()) return Content("暂无待取件", "点击打开码上取")

        val title = "待取 ${codes.size} 件"
        if (!showCode) return Content(title, "点击打开码上取查看")

        val text = buildString {
            append("最新取件码 ").append(codes.first())
            if (!latestCompany.isNullOrBlank()) append(" · ").append(latestCompany)
            if (codes.size > 1) append("，另有 ").append(codes.size - 1).append(" 件")
        }
        return Content(title, text)
    }

    /**
     * 用已知的待取件列表刷新通知。由 WidgetSyncHelper 在同一协程里调用，
     * 不额外查数据库。
     */
    fun update(context: Context, pending: List<PickupItem>) {
        val appContext = context.applicationContext

        if (!PreferenceManager.isSummaryNotificationEnabled(appContext)) {
            cancel(appContext)
            return
        }
        // Android 13+ 未授予 POST_NOTIFICATIONS 时 notify() 会抛 SecurityException，
        // 这里先判一次；真机上的国产 ROM 仍可能各有差异，下面再兜一层。
        if (!NotificationHelper.isNotificationPermissionGranted(appContext)) return

        if (pending.isEmpty()) {
            cancel(appContext)
            return
        }

        val sorted = pending.sortedByDescending { it.receiveTime }
        val content = buildContent(
            codes = sorted.map { it.pickupCode },
            latestCompany = sorted.firstOrNull()?.courierCompany,
            showCode = PreferenceManager.isNotificationShowCode(appContext)
        )

        val openApp = Intent(appContext, ComposeMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val contentIntent = PendingIntent.getActivity(appContext, 0, openApp, pendingFlags)

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pickup)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

        try {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // 权限被系统设置关掉时静默跳过：常驻通知不该让入库流程失败
            Log.w(TAG, "通知权限未授予，跳过汇总通知")
        }
    }

    /** 冷启动 / 设置页开关变化时用：自己查一次库再刷新。 */
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = PickupDatabase.getDatabase(appContext).pickupItemDao()
                val days = PreferenceManager.getDefaultDisplayDays(appContext)
                val threshold = System.currentTimeMillis() - days.toLong() * DAY_MILLIS
                val pending = dao.getItemsByStatusList(PickupItem.STATUS_PENDING)
                    .filter { it.receiveTime >= threshold }
                update(appContext, pending)
            } catch (e: Exception) {
                Log.w(TAG, "刷新汇总通知失败: ${e.message}")
            }
        }
    }

    fun cancel(context: Context) {
        try {
            NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
        } catch (e: SecurityException) {
            Log.w(TAG, "取消汇总通知失败")
        }
    }
}
