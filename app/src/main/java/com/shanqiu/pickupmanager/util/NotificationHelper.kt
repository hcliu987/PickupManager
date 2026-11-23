/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/**
 * 通知渠道管理。
 *
 * 2026-11-09 起本应用有两条通知渠道，都在这里统一创建：
 *
 * - [CHANNEL_ID_WIDGET]：点击小部件按钮后的前台服务通知（系统强制要求，
 *   服务侧复用此 ID 而非自建渠道，否则设置里会多出一个永不使用的空渠道）；
 * - [CHANNEL_ID_SUMMARY]：[PickupSummaryNotifier] 的待取件汇总，可在设置页关闭。
 *
 * 这里**不做新取件逐条提醒、不做过期提醒**。2026-09-19 删掉的正是那套
 * 从未被触发、只剩空渠道与虚假引导文案的自指链路（见 KNOWN_ISSUES #5）；
 * 现在这条汇总通知挂在已跑通的小部件数据变化信标上，不是它的复活。
 */
object NotificationHelper {

    const val CHANNEL_ID_WIDGET = "widget_feedback"
    private const val CHANNEL_NAME_WIDGET = "小部件操作"

    const val CHANNEL_ID_SUMMARY = PickupSummaryNotifier.CHANNEL_ID
    private const val CHANNEL_NAME_SUMMARY = "待取件汇总"

    // 历史版本创建过这两个渠道却从未发出过任何通知（发送路径没有触发入口）。
    // 代码已删除，但老用户系统设置里的空渠道不会自行消失，故在此显式删除。
    private const val LEGACY_CHANNEL_PICKUP = "pickup_notification"
    private const val LEGACY_CHANNEL_EXPIRE = "expire_notification"

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager = context.getSystemService(NotificationManager::class.java)

        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_WIDGET,
                CHANNEL_NAME_WIDGET,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "处理小部件按钮操作"
                setShowBadge(false)
                enableVibration(false)
            }
        )

        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_SUMMARY,
                CHANNEL_NAME_SUMMARY,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "在通知栏显示待取件数量，可在应用设置中关闭"
                setShowBadge(false)
                enableVibration(false)
            }
        )

        notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_PICKUP)
        notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_EXPIRE)
    }

    /** Android 13+ 需要显式授予；前台服务通知同样受此约束 */
    fun isNotificationPermissionGranted(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun getRequiredNotificationPermission(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }
}
