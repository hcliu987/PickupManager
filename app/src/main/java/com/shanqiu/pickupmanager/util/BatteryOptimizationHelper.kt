/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 电池优化检测助手
 *
 * 检测应用是否被电池优化限制，并提供跳转到设置页面的功能。
 *
 * 问题背景：
 * - MIUI/HyperOS/EMUI/ColorOS 等国产 ROM 默认对应用进行电池优化
 * - 电池优化会导致应用被完全杀死后，widget 按钮无法唤醒应用
 * - 用户需要手动关闭电池优化或开启自启动权限
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptimizationHelper"

    /**
     * 检测应用是否被电池优化限制
     * @return true 表示应用被电池优化（需要引导用户关闭）
     *         false 表示应用未被电池优化或不需要检查
     */
    fun isBatteryOptimized(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false
        }
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager == null) {
            Log.w(TAG, "PowerManager 不可用")
            return false
        }
        val isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        Log.d(TAG, "电池优化状态: isIgnoring=$isIgnoring, package=${context.packageName}")
        return !isIgnoring
    }

    /**
     * 请求关闭电池优化
     * 跳转到系统设置页面，用户手动关闭
     */
    fun requestDisableBatteryOptimization(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "已跳转到电池优化设置页面")
        } catch (e: Exception) {
            Log.e(TAG, "跳转电池优化设置失败", e)
            // 备用方案：跳转到应用详情页面
            openAppDetailSettings(context)
        }
    }

    /**
     * 跳转到应用详情设置页面
     */
    fun openAppDetailSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "跳转应用详情设置失败", e)
            openSystemSettings(context)
        }
    }

    /**
     * 跳转到系统设置页面
     */
    private fun openSystemSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "跳转系统设置失败", e)
        }
    }

    /**
     * 获取电池优化设置页面的 Intent（用于品牌特定跳转）
     */
    fun getBatteryOptimizationIntent(context: Context): Intent? {
        return when {
            PhoneBrandDetector.isXiaomi() -> getMiuiBatteryIntent(context)
            PhoneBrandDetector.isHuawei() -> getHuaweiBatteryIntent(context)
            PhoneBrandDetector.isOppo() -> getOppoBatteryIntent(context)
            PhoneBrandDetector.isVivo() -> getVivoBatteryIntent(context)
            else -> getGenericBatteryIntent(context)
        }
    }

    private fun getMiuiBatteryIntent(context: Context): Intent? {
        // MIUI/HyperOS: 使用标准Android电池优化页面
        // 不再跳转到自启动管理（AutoStartManagementActivity）
        return getGenericBatteryIntent(context)
    }

    private fun getHuaweiBatteryIntent(context: Context): Intent? {
        return try {
            val intent = Intent().apply {
                setClassName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                intent
            } else {
                getGenericBatteryIntent(context)
            }
        } catch (e: Exception) {
            getGenericBatteryIntent(context)
        }
    }

    private fun getOppoBatteryIntent(context: Context): Intent? {
        // OPPO/ColorOS 各版本组件路径差异大，依次尝试多个路径
        val oppoIntents = listOf(
            // ColorOS 11/12 自启动管理
            Intent().apply {
                setClassName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                )
            },
            // ColorOS 12+/13 新路径
            Intent().apply {
                setClassName(
                    "com.oplus.safecenter",
                    "com.oplus.safecenter.startupapp.StartupAppListActivity"
                )
            },
            // ColorOS 权限管理
            Intent().apply {
                setClassName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.PermissionManagerActivity"
                )
            },
            // 部分机型安全中心
            Intent().apply {
                setClassName(
                    "com.heytap.safe",
                    "com.heytap.safe.startup.StartupAppListActivity"
                )
            }
        )

        for (intent in oppoIntents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) != null) {
                    return intent
                }
            } catch (_: Exception) {}
        }

        // 所有路径都失败，使用通用方式
        return getGenericBatteryIntent(context)
    }

    private fun getVivoBatteryIntent(context: Context): Intent? {
        return try {
            val intent = Intent().apply {
                setClassName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                intent
            } else {
                getGenericBatteryIntent(context)
            }
        } catch (e: Exception) {
            getGenericBatteryIntent(context)
        }
    }

    private fun getGenericBatteryIntent(context: Context): Intent? {
        return try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            intent
        } catch (e: Exception) {
            null
        }
    }
}
