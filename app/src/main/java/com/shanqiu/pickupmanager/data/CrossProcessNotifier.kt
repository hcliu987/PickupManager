/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.data

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * 跨进程数据变化通知器
 *
 * 用于在小部件进程（:widgetProvider）写入数据库后，
 * 主动通知主进程 ViewModel 重新查询数据。
 *
 * 工作流程：
 * 1. 小部件进程调用 notifyDataChanged()
 * 2. → contentResolver.notifyChange(CONTENT_URI) 跨进程调用
 * 3. → 系统路由到主进程的 DataChangeContentProvider
 * 4. → 主进程 ViewModel 注册的 ContentObserver 收到 onChange()
 * 5. → 触发 _refreshTrigger 重新查询
 * 6. → Room Flow 通过 flatMapLatest 重新订阅并 emit 新数据
 * 7. → UI 自动更新
 */
object CrossProcessNotifier {
    private const val TAG = "CrossProcessNotifier"

    /**
     * 通知主进程数据已变化
     * 应在所有数据库写入操作后调用（markAsPicked / markAsPending / delete / insert / update 等）
     */
    fun notifyDataChanged(context: Context) {
        try {
            context.contentResolver.notifyChange(
                DataChangeContentProvider.CONTENT_URI,
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "跨进程通知失败: ${e.message}", e)
        }
    }
}
