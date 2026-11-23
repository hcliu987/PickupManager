/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.widget

import com.shanqiu.pickupmanager.R

/**
 * 取件码桌面小部件 - 2×2 超小尺寸
 * 仅显示待取件数量和打开按钮
 */
class PickupWidgetProvider2x2 : BasePickupWidgetProvider() {
    override val layoutId: Int = R.layout.widget_pickup_2x2
    override val hasListView: Boolean = false
}
