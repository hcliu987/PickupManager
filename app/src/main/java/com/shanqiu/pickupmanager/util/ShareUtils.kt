/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import android.content.Context
import android.content.Intent
import com.shanqiu.pickupmanager.data.model.PickupItem

/**
 * 分享工具类
 * 格式化取件信息并调用系统分享
 * 
 * 格式参考 SmsForwarder 的多步提取思路：
 * 快递公司 - 取件码 - 地址
 */
object ShareUtils {
    
    // Emoji 数字映射
    private val emojiDigits = mapOf(
        '0' to "0️⃣",
        '1' to "1️⃣",
        '2' to "2️⃣",
        '3' to "3️⃣",
        '4' to "4️⃣",
        '5' to "5️⃣",
        '6' to "6️⃣",
        '7' to "7️⃣",
        '8' to "8️⃣",
        '9' to "9️⃣"
    )
    
    /**
     * 将数字转换为 emoji keycap 格式
     * 例如: 1 → 1️⃣, 10 → 🔟, 11 → 1️⃣1️⃣, 25 → 2️⃣5️⃣
     */
    private fun numberToEmoji(number: Int): String {
        if (number == 10) return "🔟"
        return number.toString().map { emojiDigits[it] ?: it.toString() }.joinToString("")
    }
    
    /**
     * 分享单个或多个取件码
     * 格式清晰、信息完整，适合微信/QQ等平台分享
     */
    fun sharePickupItems(context: Context, items: List<PickupItem>) {
        if (items.isEmpty()) return
        
        val shareText = if (items.size == 1) {
            formatSingleItem(items.first())
        } else {
            formatMultipleItems(items)
        }
        
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "取件码分享")
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        
        // 使用.createChooser让用户选择分享目标（微信/QQ/飞书等）
        val chooserIntent = Intent.createChooser(shareIntent, "分享到")
        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooserIntent)
    }
    
    /**
     * 格式化单个取件码
     * 格式：
     * 📦 取件码: 1-2-34567
     * 📍 示例小区3号楼（菜鸟驿站）
     * 🏢 京东快递
     */
    private fun formatSingleItem(item: PickupItem): String {
        val sb = StringBuilder()
        sb.appendLine("📦 取件码: ${item.pickupCode}")
        
        if (item.address.isNotBlank()) {
            sb.appendLine("📍 ${item.address}")
        }
        
        if (item.courierCompany.isNotBlank()) {
            sb.appendLine("🏢 ${item.courierCompany}")
        }
        
        sb.appendLine()
        sb.append("「码上取」——让取件回归简单！")
        
        return sb.toString()
    }
    
    /**
     * 格式化多个取件码（合并分享）
     * 格式：
     * 📦 我有 N 个待取件包裹：
     * 
     * 1️⃣ 取件码: 1-2-34567
     *    📍 示例小区3号楼（菜鸟驿站）
     *    🏢 京东快递
     * 
     * 2️⃣ 取件码: A1-23456
     *    📍 示例小区9号楼兔喜生活店
     *    🏢 兔喜生活
     */
    private fun formatMultipleItems(items: List<PickupItem>): String {
        val sb = StringBuilder()
        sb.appendLine("📦 我有 ${items.size} 个待取件包裹：")
        sb.appendLine()
        
        items.forEachIndexed { index, item ->
            sb.appendLine("${index + 1}️⃣ 取件码: ${item.pickupCode}")
            
            if (item.address.isNotBlank()) {
                sb.appendLine("   📍 ${item.address}")
            }
            
            if (item.courierCompany.isNotBlank()) {
                sb.appendLine("   🏢 ${item.courierCompany}")
            }
            
            if (index < items.size - 1) {
                sb.appendLine()
            }
        }
        
        sb.appendLine()
        sb.append("「码上取」——让取件回归简单！")
        
        return sb.toString()
    }
}
