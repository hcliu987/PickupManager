/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.shanqiu.pickupmanager.data.model.PickupItem
import com.shanqiu.pickupmanager.ui.components.PickupCard
import com.shanqiu.pickupmanager.ui.screens.PickupListScreen

/**
 * 主题预览
 */
@Preview(name = "Light Theme", showBackground = true)
@Composable
fun LightThemePreview() {
    PickupManagerTheme(darkTheme = false) {
        PreviewContent()
    }
}

@Preview(name = "Dark Theme", showBackground = true)
@Composable
fun DarkThemePreview() {
    PickupManagerTheme(darkTheme = true) {
        PreviewContent()
    }
}

@Composable
private fun PreviewContent() {
    // 这里可以放置需要预览的组件
}

/**
 * 卡片预览
 */
@Preview(name = "Pending Card", showBackground = true, widthDp = 400)
@Composable
fun PendingCardPreview() {
    PickupManagerTheme {
        val item = PickupItem(
            id = 1,
            pickupCode = "AB1234",
            address = "菜鸟驿站 -3 号楼 101 室",
            addressTag = "3 号楼",
            courierCompany = "顺丰快递",
            receiveTime = System.currentTimeMillis() - (2 * 24 * 60 * 60 * 1000L),
            expireTime = System.currentTimeMillis() + (5 * 24 * 60 * 60 * 1000L),
            status = PickupItem.STATUS_PENDING,
            rawSms = "【顺丰快递】您的快件已放入菜鸟驿站 3 号楼 101 室，取件码 AB1234，请凭此取件码取件。"
        )
        
        PickupCard(
            item = item,
            onPickClick = {},
            onDeleteClick = {},
            onShareClick = {},
            onCardClick = {}
        )
    }
}

@Preview(name = "Picked Card", showBackground = true, widthDp = 400)
@Composable
fun PickedCardPreview() {
    PickupManagerTheme {
        val item = PickupItem(
            id = 2,
            pickupCode = "CD5678",
            address = "丰巢快递柜 -5 号楼",
            addressTag = "5 号楼",
            courierCompany = "中通快递",
            receiveTime = System.currentTimeMillis() - (10 * 24 * 60 * 60 * 1000L),
            expireTime = System.currentTimeMillis() - (3 * 24 * 60 * 60 * 1000L),
            status = PickupItem.STATUS_PICKED,
            markedTime = System.currentTimeMillis() - (3 * 24 * 60 * 60 * 1000L),
            rawSms = "【中通快递】您的快件已放入丰巢快递柜 5 号楼，取件码 CD5678。"
        )
        
        PickupCard(
            item = item,
            onPickClick = {},
            onDeleteClick = {},
            onShareClick = {},
            onCardClick = {}
        )
    }
}
