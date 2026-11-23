/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 权限检查工具类
 * 
 * 检查以下权限状态：
 * 1. 通知权限 (POST_NOTIFICATIONS) - Android 13+
 * 2. 电池优化忽略权限 (IGNORE_BATTERY_OPTIMIZATION)
 * 3. 自启动权限 - 厂商特定，无法直接检测，引导用户手动设置
 */
object PermissionChecker {

    private const val TAG = "PermissionChecker"
    
    // 自启动权限的 SharedPreferences 键
    private const val PREFS_NAME = "permission_prefs"
    private const val KEY_AUTO_START_GRANTED = "auto_start_granted"
    private const val KEY_LAST_GUIDE_TIME = "last_guide_time"
    private const val KEY_SKIP_UNTIL = "skip_until"
    private const val SKIP_DURATION_MS = 7 * 24 * 60 * 60 * 1000L // 7天

    /**
     * 权限状态数据类
     */
    data class PermissionStatus(
        val notificationGranted: Boolean,
        val batteryOptimizationIgnored: Boolean,
        val autoStartGranted: Boolean
    ) {
        val allGranted: Boolean
            get() = notificationGranted && batteryOptimizationIgnored && autoStartGranted
        
        val missingPermissions: List<String>
            get() = buildList {
                if (!notificationGranted) add("通知权限")
                if (!batteryOptimizationIgnored) add("电池优化")
                if (!autoStartGranted) add("自启动权限")
            }
    }

    /**
     * 检查所有权限状态
     */
    fun checkAllPermissions(context: Context): PermissionStatus {
        val notificationGranted = checkNotificationPermission(context)
        val batteryOptimizationIgnored = checkBatteryOptimization(context)
        val autoStartGranted = checkAutoStartPermission(context)
        
        return PermissionStatus(
            notificationGranted = notificationGranted,
            batteryOptimizationIgnored = batteryOptimizationIgnored,
            autoStartGranted = autoStartGranted
        )
    }

    /**
     * 检查通知权限
     */
    fun checkNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 13 以下默认有通知权限
            true
        }
    }

    /**
     * 检查是否忽略电池优化
     */
    fun checkBatteryOptimization(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            // Android 6 以下默认忽略
            true
        }
    }

    /**
     * 检查自启动权限
     * 
     * 注意：Android 没有标准 API 检测自启动权限
     * 这里尝试多种方式检测，按优先级排序：
     * 1. 反射调用 MIUI/HyperOS 内部 API（最可靠）
     * 2. 读取 Settings.Secure 中的自启动设置
     * 3. 通过 AppOpsManager 检测后台运行权限
     * 4. SharedPreferences 缓存（仅作补充，不优先信任）
     */
    fun checkAutoStartPermission(context: Context): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        
        // 优先执行系统级检测（不信任缓存的 SP 标记，防止误判）
        var systemCheckResult: Boolean? = null
        
        // 小米/红米/POCO 设备
        if (manufacturer.contains("xiaomi") || 
            manufacturer.contains("redmi") ||
            manufacturer.contains("poco")) {
            systemCheckResult = checkXiaomiAutoStart(context)
        }
        
        // 华为/荣耀 设备
        if (systemCheckResult == null && (manufacturer.contains("huawei") || 
            manufacturer.contains("honor"))) {
            systemCheckResult = checkHuaweiAutoStart(context)
        }
        
        // OPPO/Realme 设备
        if (systemCheckResult == null && (manufacturer.contains("oppo") || 
            manufacturer.contains("realme"))) {
            systemCheckResult = checkOppoAutoStart(context)
        }
        
        // vivo/iQOO 设备
        if (systemCheckResult == null && (manufacturer.contains("vivo") || 
            manufacturer.contains("iqoo"))) {
            systemCheckResult = checkVivoAutoStart(context)
        }
        
        // 系统检测成功 → 以系统结果为准，同步更新 SP 缓存
        if (systemCheckResult != null) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (systemCheckResult) {
                prefs.edit().putBoolean(KEY_AUTO_START_GRANTED, true).apply()
            } else {
                // 系统检测未授权，清除 SP 缓存（防止之前错误标记）
                prefs.edit().remove(KEY_AUTO_START_GRANTED).apply()
            }
            return systemCheckResult
        }
        
        // 系统检测无法执行 → 回退到 SP 标记（用户手动确认的标记）
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_START_GRANTED, false)
    }
    
    /**
     * 检测小米/红米/POCO 的自启动白名单
     * 
     * 方法1：反射调用 miui.app.AppOpsUtils.isAutoStartAllowed()
     * 方法2：读取 Settings.Secure 中的 autostart_enabled_<package_name>
     * 方法3：通过 AppOpsManager 检测后台运行权限
     */
    private fun checkXiaomiAutoStart(context: Context): Boolean {
        // 方法1：反射调用 MIUI/HyperOS 内部 API（最可靠）
        try {
            val appOpsUtils = Class.forName("miui.app.AppOpsUtils")
            val method = appOpsUtils.getMethod("isAutoStartAllowed", Context::class.java, String::class.java)
            val result = method.invoke(null, context, context.packageName) as? Boolean
            if (result == true) {
                Log.d(TAG, "Xiaomi auto-start detected via AppOpsUtils")
                return true
            }
        } catch (e: Exception) {
            Log.d(TAG, "Xiaomi AppOpsUtils reflection failed: ${e.message}")
        }
        
        // 方法2：读取 Settings.Secure 中的自启动设置
        // MIUI/HyperOS 将自启动状态存储在 Settings.Secure 中
        val secureSettingsKeys = arrayOf(
            "autostart_enabled_${context.packageName}",
            "autostart_enabled",
            "auto_start_${context.packageName}",
            "auto_start"
        )
        
        for (key in secureSettingsKeys) {
            try {
                val value = android.provider.Settings.Secure.getInt(
                    context.contentResolver,
                    key,
                    -1
                )
                if (value == 1) {
                    Log.d(TAG, "Xiaomi auto-start detected via Settings.Secure: $key")
                    return true
                }
            } catch (e: Exception) {
                // 继续尝试下一个 key
            }
        }
        
        // 方法3：通过 AppOpsManager 检测后台运行权限
        try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            if (appOps != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                // 检测后台运行权限
                val result = appOps.checkOpNoThrow(
                    "android:run_in_background",
                    android.os.Process.myUid(),
                    context.packageName
                )
                if (result == AppOpsManager.MODE_ALLOWED) {
                    Log.d(TAG, "Xiaomi auto-start detected via run_in_background")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Xiaomi AppOpsManager check failed: ${e.message}")
        }
        
        return false
    }
    
    /**
     * 检测华为/荣耀 的自启动权限
     */
    private fun checkHuaweiAutoStart(context: Context): Boolean {
        try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            if (appOps != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                val result = appOps.checkOpNoThrow(
                    "android:manage_background_start",
                    android.os.Process.myUid(),
                    context.packageName
                )
                if (result == AppOpsManager.MODE_ALLOWED) {
                    Log.d(TAG, "Huawei auto-start detected")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Huawei auto-start check failed: ${e.message}")
        }
        return false
    }
    
    /**
     * 检测 OPPO/Realme 的自启动权限
     */
    private fun checkOppoAutoStart(context: Context): Boolean {
        try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            if (appOps != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                val result = appOps.checkOpNoThrow(
                    "android:run_in_background",
                    android.os.Process.myUid(),
                    context.packageName
                )
                if (result == AppOpsManager.MODE_ALLOWED) {
                    Log.d(TAG, "OPPO auto-start detected")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "OPPO auto-start check failed: ${e.message}")
        }
        return false
    }
    
    /**
     * 检测 vivo/iQOO 的自启动权限
     */
    private fun checkVivoAutoStart(context: Context): Boolean {
        try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            if (appOps != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                val result = appOps.checkOpNoThrow(
                    "android:run_in_background",
                    android.os.Process.myUid(),
                    context.packageName
                )
                if (result == AppOpsManager.MODE_ALLOWED) {
                    Log.d(TAG, "vivo auto-start detected")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "vivo auto-start check failed: ${e.message}")
        }
        return false
    }

    /**
     * 标记自启动权限已授予
     */
    fun markAutoStartGranted(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_START_GRANTED, true).apply()
    }

    /**
     * 检查是否应该显示权限引导
     * 
     * @return true 如果应该显示引导
     */
    fun shouldShowPermissionGuide(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val skipUntil = prefs.getLong(KEY_SKIP_UNTIL, 0L)
        val now = System.currentTimeMillis()
        
        // 如果当前时间在 skipUntil 之前，不显示
        if (now < skipUntil) {
            val daysLeft = (skipUntil - now) / (24 * 60 * 60 * 1000) + 1
            Log.d(TAG, "权限引导已跳过，还有 $daysLeft 天")
            return false
        }
        
        return true
    }

    /**
     * 记录用户跳过权限引导
     */
    fun recordSkip(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val skipUntil = System.currentTimeMillis() + SKIP_DURATION_MS
        prefs.edit()
            .putLong(KEY_SKIP_UNTIL, skipUntil)
            .putLong(KEY_LAST_GUIDE_TIME, System.currentTimeMillis())
            .apply()
        Log.d(TAG, "记录跳过权限引导，7天后再次提醒")
    }

    /**
     * 获取通知权限设置 Intent
     */
    fun getNotificationPermissionIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    /**
     * 获取电池优化设置 Intent
     */
    fun getBatteryOptimizationIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    /**
     * 获取自启动设置 Intent
     * 
     * 不同厂商的设置页面不同，这里尝试多种方式
     */
    fun getAutoStartIntent(context: Context): Intent {
        val manufacturer = Build.MANUFACTURER.lowercase()
        
        return try {
            when {
                manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> {
                    // 小米/红米
                    Intent().apply {
                        component = android.content.ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity"
                        )
                    }
                }
                manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                    // 华为/荣耀
                    Intent().apply {
                        component = android.content.ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                        )
                    }
                }
                manufacturer.contains("oppo") || manufacturer.contains("realme") -> {
                    // OPPO/Realme
                    Intent().apply {
                        component = android.content.ComponentName(
                            "com.coloros.safecenter",
                            "com.coloros.safecenter.permission.startup.FakeActivity"
                        )
                    }
                }
                manufacturer.contains("vivo") -> {
                    // vivo
                    Intent().apply {
                        component = android.content.ComponentName(
                            "com.iqoo.secure",
                            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                        )
                    }
                }
                manufacturer.contains("samsung") -> {
                    // 三星
                    Intent().apply {
                        component = android.content.ComponentName(
                            "com.samsung.android.lool",
                            "com.samsung.android.sm.ui.battery.BatteryActivity"
                        )
                    }
                }
                else -> {
                    // 默认跳转到应用详情
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取自启动设置 Intent 失败: ${e.message}")
            // 失败时跳转到应用详情
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    /**
     * 检查自启动设置 Intent 是否可用
     */
    fun isAutoStartIntentAvailable(context: Context): Boolean {
        return try {
            val intent = getAutoStartIntent(context)
            context.packageManager.resolveActivity(intent, 0) != null
        } catch (e: Exception) {
            false
        }
    }
}
