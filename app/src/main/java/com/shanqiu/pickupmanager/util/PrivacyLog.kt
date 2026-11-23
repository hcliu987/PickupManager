/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

/**
 * 日志里引用短信原文或用户地址时，只留下不可还原的定位指纹。
 *
 * debug 包不受 ProGuard 的 -assumenosideeffects 保护，Log.w/Log.e 在 release 包里同样保留，
 * 所以任何可能含个人信息的字符串都必须先过这里。
 */
fun privacyRef(text: String?): String =
    if (text == null) "null" else "len=${text.length}#${Integer.toHexString(text.hashCode())}"
