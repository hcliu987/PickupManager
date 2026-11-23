/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.widget

import com.shanqiu.pickupmanager.R

/**
 * 取件码桌面小部件 - 5×4 尺寸（宽高）
 */
class PickupWidgetProvider5x4 : BasePickupWidgetProvider() {
    override val layoutId: Int = R.layout.widget_pickup_5x4
}
