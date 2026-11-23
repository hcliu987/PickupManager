/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.data.model

data class PickupRoute(
    val id: String,
    val addressTag: String,
    val displayName: String,
    val order: Int
)

data class RouteMode(
    val modeType: ModeType,
    val routes: List<PickupRoute>,
    val applyDays: Set<Int> = emptySet()
)

enum class ModeType {
    WORK_MODE,
    VACATION_MODE
}

data class RouteSettings(
    val workMode: RouteMode = RouteMode(
        modeType = ModeType.WORK_MODE,
        routes = emptyList(),
        applyDays = setOf(1, 2, 3, 4, 5)
    ),
    val vacationMode: RouteMode = RouteMode(
        modeType = ModeType.VACATION_MODE,
        routes = emptyList(),
        applyDays = setOf(0, 6)
    ),
    val currentMode: ModeType = ModeType.WORK_MODE,
    val autoSwitch: Boolean = true
)
