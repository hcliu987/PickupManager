/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * 跨进程数据变化通知 ContentProvider
 *
 * 用于解决 Room 在多进程场景下（主进程 + :widgetProvider 进程）的失效通知不生效问题。
 *
 * 工作原理：
 * 1. 在主进程 manifest 中注册（android:process=":main"），这样 ContentProvider 始终在主进程实例化
 * 2. 小部件进程（:widgetProvider）调用 contentResolver.notifyChange(CONTENT_URI) 时，
 *    系统会通过 Binder 桥接到主进程的 ContentProvider，触发跨进程通知
 * 3. 主进程的 ContentObserver 收到 onChange 通知，主动重新查询数据库
 *
 * 这个 ContentProvider 本身不存储任何数据，只作为跨进程通知的"信标"。
 */
class DataChangeContentProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.shanqiu.pickupmanager.datachange"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/data_change")
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
