/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.ui.animation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 问题 28 收尾：全仓真正被组件树读取的只有这两个参数 ——
 * `buttonPressScale`（PickupDetailDialog / SportyDialogs）与
 * `cardStatusTransitionDuration`（PickupCard）。
 *
 * 原文件有 23 个字段 + 9 个返回 AnimationSpec 的工厂函数 + 4 套预设，
 * 其中 30 个成员在本文件之外零引用；它们看起来像"一套人人都在用的动画系统"，
 * 实际改了落不到屏幕上，只会诱导新贡献者继续往里加参数。
 * 现按「无真机验证则不改渲染路径」的原则收缩为实际生效的两个字段，
 * 预设只留 Standard（ComposeMainActivity 唯一使用的那个）。
 *
 * 注意：data class 的字段参与 equals()，而本对象经 staticCompositionLocalOf
 * 下发。字段减少会改变重组时的相等性判断，改动只影响"无引用字段"，
 * 真机上卡片点击 / 标记已取 / 对话框开合仍建议各看一遍。
 */
data class AnimationConfig(
    val buttonPressScale: Float = 0.95f,
    val cardStatusTransitionDuration: Int = 400
)

val LocalAnimationConfig = staticCompositionLocalOf { AnimationConfig() }

@Composable
fun provideAnimationConfig(
    config: AnimationConfig = AnimationConfig(),
    content: @Composable () -> Unit
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalAnimationConfig provides config,
        content = content
    )
}

object AnimationPresets {
    val Standard = AnimationConfig()
}
