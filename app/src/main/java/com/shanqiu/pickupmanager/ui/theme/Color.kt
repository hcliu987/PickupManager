/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.ui.theme

import androidx.compose.ui.graphics.Color

// 浅色主题颜色
val LightPrimary = Color(0xFF2196F3)
val LightOnPrimary = Color.White
val LightPrimaryContainer = Color(0xFFE3F2FD)
val LightOnPrimaryContainer = Color(0xFF1565C0)

val LightSecondary = Color(0xFF4CAF50)
val LightOnSecondary = Color.White
val LightSecondaryContainer = Color(0xFFE8F5E9)
val LightOnSecondaryContainer = Color(0xFF2E7D32)

val LightTertiary = Color(0xFFFF9800)
val LightOnTertiary = Color.White
val LightTertiaryContainer = Color(0xFFFFF3E0)
val LightOnTertiaryContainer = Color(0xFFE65100)

val LightError = Color(0xFFFF4444)
val LightOnError = Color.White
val LightErrorContainer = Color(0xFFFFEBEE)
val LightOnErrorContainer = Color(0xFFC62828)

val LightBackground = Color(0xFFF5F7FA)
val LightOnBackground = Color(0xFF1E293B)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF1E293B)
val LightSurfaceVariant = Color(0xFFF1F5F9)
val LightOnSurfaceVariant = Color(0xFF64748B)

val LightOutline = Color(0xFFE2E8F0)
val LightOutlineVariant = Color(0xFFF1F5F9)

// 深色主题颜色
val DarkPrimary = Color(0xFF64B5F6)
val DarkOnPrimary = Color(0xFF0D47A1)
val DarkPrimaryContainer = Color(0xFF1E3A5F)
val DarkOnPrimaryContainer = Color(0xFFBBDEFB)

val DarkSecondary = Color(0xFF81C784)
val DarkOnSecondary = Color(0xFF1B5E20)
val DarkSecondaryContainer = Color(0xFF1B5E20)
val DarkOnSecondaryContainer = Color(0xFFC8E6C9)

val DarkTertiary = Color(0xFFFFB74D)
val DarkOnTertiary = Color(0xFFE65100)
val DarkTertiaryContainer = Color(0xFF4E342E)
val DarkOnTertiaryContainer = Color(0xFFFFE0B2)

val DarkError = Color(0xFFEF5350)
val DarkOnError = Color(0xFFB71C1C)
val DarkErrorContainer = Color(0xFFB71C1C)
val DarkOnErrorContainer = Color(0xFFFFCDD2)

val DarkBackground = Color(0xFF0D1421)
val DarkOnBackground = Color(0xFFE2E8F0)
val DarkSurface = Color(0xFF1A2332)
val DarkOnSurface = Color(0xFFE2E8F0)
val DarkSurfaceVariant = Color(0xFF2A3A4D)
val DarkOnSurfaceVariant = Color(0xFF94A3B8)

val DarkOutline = Color(0xFF3D4F5F)
val DarkOutlineVariant = Color(0xFF2A3A4D)

// 功能颜色 - 状态色（高饱和度蓝色系 - 待取件）
val StatusPendingBg = Color(0xFF2196F3)
val StatusPendingText = Color.White

// 高饱和度绿色系 - 已取件
val StatusPickedBg = Color(0xFF4CAF50)
val StatusPickedText = Color.White

// 过期状态
val StatusExpiredBg = Color(0xFFEF5350)
val StatusExpiredText = Color.White

// 预警状态（黄色）- 入库>3天且≤6天
val StatusWarningBg = Color(0xFFFFA726)
val StatusWarningText = Color.White

// 紧急状态（红色）- 入库>6天
val StatusUrgentBg = Color(0xFFEF5350)
val StatusUrgentText = Color.White

// 深色模式状态色
val DarkStatusPendingBg = Color(0xFF2196F3)
val DarkStatusPendingText = Color.White
val DarkStatusPickedBg = Color(0xFF4CAF50)
val DarkStatusPickedText = Color.White
val DarkStatusExpiredBg = Color(0xFFEF5350)
val DarkStatusExpiredText = Color.White
val DarkStatusWarningBg = Color(0xFFFFA726)
val DarkStatusWarningText = Color.White
val DarkStatusUrgentBg = Color(0xFFEF5350)
val DarkStatusUrgentText = Color.White

// 操作按钮颜色
val ActionCopyColor = Color(0xFF2196F3)
val ActionPickColor = Color(0xFF4CAF50)
val ActionDeleteColor = Color(0xFFEF5350)
val ActionShareColor = Color(0xFFFF9800)

// 深色模式操作按钮颜色
val DarkActionCopyColor = Color(0xFF64B5F6)
val DarkActionPickColor = Color(0xFF81C784)
val DarkActionDeleteColor = Color(0xFFEF5350)
val DarkActionShareColor = Color(0xFFFFB74D)

// 底部导航栏选中颜色（深蓝色）
val NavSelectedBg = Color(0xFF1565C0)
val NavSelectedText = Color.White
val NavUnselectedText = Color(0xFF64748B)
val NavCardBg = Color(0xFFF0F0F0)

// 深色模式底部导航栏颜色
val DarkNavSelectedBg = Color(0xFF64B5F6)
val DarkNavSelectedText = Color(0xFF0D47A1)
val DarkNavUnselectedText = Color(0xFF94A3B8)
val DarkNavCardBg = Color(0xFF1A2332)
val DarkNavBackground = Color(0xFF0D1421)

// 渐变色 - 主背景渐变
val GradientPrimaryStart = Color(0xFF2196F3)
val GradientPrimaryEnd = Color(0xFF4CAF50)

// 渐变色 - 深色模式背景渐变
val DarkGradientPrimaryStart = Color(0xFF1E3A5F)
val DarkGradientPrimaryEnd = Color(0xFF1B5E20)

// 渐变色 - 卡片装饰渐变
val GradientCardAccentStart = Color(0xFFE3F2FD)
val GradientCardAccentEnd = Color(0xFFE8F5E9)

// 渐变色 - 按钮渐变
val GradientButtonStart = Color(0xFF2196F3)
val GradientButtonEnd = Color(0xFF1976D2)

// Slate色系 - 用于文字和边框
val Slate50 = Color(0xFFF8FAFC)
val Slate100 = Color(0xFFF1F5F9)
val Slate200 = Color(0xFFE2E8F0)
val Slate300 = Color(0xFFCBD5E1)
val Slate400 = Color(0xFF94A3B8)
val Slate500 = Color(0xFF64748B)
val Slate600 = Color(0xFF475569)
val Slate700 = Color(0xFF334155)
val Slate800 = Color(0xFF1E293B)
val Slate900 = Color(0xFF0F172A)

// Blue色系 - 用于主色调
val Blue50 = Color(0xFFE3F2FD)
val Blue100 = Color(0xFFBBDEFB)
val Blue200 = Color(0xFF90CAF9)
val Blue300 = Color(0xFF64B5F6)
val Blue400 = Color(0xFF42A5F5)
val Blue500 = Color(0xFF2196F3)
val Blue600 = Color(0xFF1E88E5)
val Blue700 = Color(0xFF1976D2)
val Blue800 = Color(0xFF1565C0)
val Blue900 = Color(0xFF0D47A1)

// Green色系 - 用于成功状态
val Green50 = Color(0xFFE8F5E9)
val Green100 = Color(0xFFC8E6C9)
val Green200 = Color(0xFFA5D6A7)
val Green300 = Color(0xFF81C784)
val Green400 = Color(0xFF66BB6A)
val Green500 = Color(0xFF4CAF50)
val Green600 = Color(0xFF43A047)
val Green700 = Color(0xFF388E3C)
val Green800 = Color(0xFF2E7D32)
val Green900 = Color(0xFF1B5E20)

// Amber色系 - 用于警告状态
val Amber50 = Color(0xFFFFF8E1)
val Amber100 = Color(0xFFFFECB3)
val Amber200 = Color(0xFFFFE082)
val Amber300 = Color(0xFFFFD54F)
val Amber400 = Color(0xFFFFCA28)
val Amber500 = Color(0xFFFFC107)
val Amber600 = Color(0xFFFFB300)
val Amber700 = Color(0xFFFFA000)
val Amber800 = Color(0xFFFF8F00)
val Amber900 = Color(0xFFFF6F00)

// Red色系 - 用于错误状态
val Red50 = Color(0xFFFFEBEE)
val Red100 = Color(0xFFFFCDD2)
val Red200 = Color(0xFFEF9A9A)
val Red300 = Color(0xFFE57373)
val Red400 = Color(0xFFEF5350)
val Red500 = Color(0xFFF44336)
val Red600 = Color(0xFFE53935)
val Red700 = Color(0xFFD32F2F)
val Red800 = Color(0xFFC62828)
val Red900 = Color(0xFFB71C1C)

// Orange色系 - 用于提示状态
val Orange50 = Color(0xFFFFF3E0)
val Orange100 = Color(0xFFFFE0B2)
val Orange200 = Color(0xFFFFCC80)
val Orange300 = Color(0xFFFFB74D)
val Orange400 = Color(0xFFFFA726)
val Orange500 = Color(0xFFFF9800)
val Orange600 = Color(0xFFFB8C00)
val Orange700 = Color(0xFFF57C00)
val Orange800 = Color(0xFFEF6C00)
val Orange900 = Color(0xFFE65100)
