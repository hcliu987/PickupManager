/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 权限操作助手
 * 
 * 处理各种权限相关的操作，包括：
 * - 跳转到应用设置页面
 * - 跳转到厂商特定的权限设置页面
 * - 打开应用详情页面
 */
object PermissionActionHelper {
    
    private const val TAG = "PermissionActionHelper"
    
    /**
     * 根据权限类型执行对应操作
     */
    fun handlePermissionAction(context: Context, permissionType: SmsPermissionChecker.PermissionType) {
        when (permissionType) {
            SmsPermissionChecker.PermissionType.READ_SMS,
            SmsPermissionChecker.PermissionType.RECEIVE_SMS -> {
                // 跳转到应用权限设置页面
                openAppPermissionSettings(context)
            }
            SmsPermissionChecker.PermissionType.NOTIFICATION_SMS -> {
                // 小米：跳转到通知类短信权限设置
                openXiaomiNotificationSmsSettings(context)
            }
            SmsPermissionChecker.PermissionType.BLANK_PASS -> {
                // 小米：跳转到空白通行证设置
                openXiaomiBlankPassSettings(context)
            }
            SmsPermissionChecker.PermissionType.PERMISSION_REVOKE -> {
                // OPPO：跳转到权限设置重新授权
                openOppoPermissionSettings(context)
            }
            SmsPermissionChecker.PermissionType.VERIFICATION_CODE_PROTECT -> {
                // 华为/荣耀：跳转到验证码保护设置
                openHuaweiVerificationSettings(context)
            }
            SmsPermissionChecker.PermissionType.VIVO_VERIFICATION_PROTECT -> {
                // vivo/iQOO：打开短信APP让用户手动检查
                openVivoSmsAppSettings(context)
            }
            SmsPermissionChecker.PermissionType.SAMSUNG_AUTO_DELETE_SMS -> {
                // 三星：打开短信APP设置
                openSamsungSmsSettings(context)
            }
            SmsPermissionChecker.PermissionType.POST_NOTIFICATIONS -> {
                // 通知权限：跳转到通知设置
                openNotificationSettings(context)
            }
            SmsPermissionChecker.PermissionType.BATTERY_OPTIMIZATION -> {
                // 电池优化：跳转到电池优化设置
                openBatteryOptimizationSettings(context)
            }
            SmsPermissionChecker.PermissionType.AUTO_START -> {
                // 自启动权限：跳转到自启动设置
                openAutoStartSettings(context)
            }
        }
    }
    
    /**
     * 打开通知设置页面
     */
    private fun openNotificationSettings(context: Context) {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "打开通知设置失败", e)
            openAppInfo(context)
        }
    }
    
    /**
     * 打开电池优化设置页面
     */
    private fun openBatteryOptimizationSettings(context: Context) {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "打开电池优化设置失败", e)
            openAppInfo(context)
        }
    }
    
    /**
     * 打开应用权限设置页面
     */
    private fun openAppPermissionSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "打开应用设置页面失败", e)
            openSettingsFallback(context)
        }
    }
    
    /**
     * 打开小米通知类短信权限设置
     */
    private fun openXiaomiNotificationSmsSettings(context: Context) {
        try {
            // 尝试打开小米的权限管理页面
            val intent = Intent("android.intent.action.MAIN").apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_packagename", context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "打开小米权限设置失败，尝试备用方案", e)
            try {
                // 备用：打开应用详情页面
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "备用方案也失败", e2)
                openSettingsFallback(context)
            }
        }
    }
    
    /**
     * 打开小米空白通行证设置
     */
    private fun openXiaomiBlankPassSettings(context: Context) {
        try {
            // 打开小米的空白通行证设置页面
            val intent = Intent("android.intent.action.MAIN").apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.settings.PrivacyAppsManagerActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "打开小米空白通行证设置失败", e)
            // 备用：打开应用详情页面
            openAppPermissionSettings(context)
        }
    }
    
    /**
     * 打开OPPO权限设置
     */
    private fun openOppoPermissionSettings(context: Context) {
        try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.PermissionManagerActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "打开OPPO权限设置失败", e)
            // 备用：打开应用详情页面
            openAppPermissionSettings(context)
        }
    }
    
    /**
     * 打开华为验证码保护设置
     */
    private fun openHuaweiVerificationSettings(context: Context) {
        try {
            // 华为验证码设置在短信应用中
            val intent = Intent().apply {
                component = ComponentName(
                    "com.android.mms",
                    "com.android.mms.ui.MessageListView"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "打开华为短信设置失败", e)
            // 备用：打开应用详情页面
            openAppPermissionSettings(context)
        }
    }
    
    /**
     * 打开vivo/iQOO短信APP设置（验证码安全保护在短信APP中）
     */
    private fun openVivoSmsAppSettings(context: Context) {
        try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.android.mms",
                    "com.android.mms.ui.ConversationList"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "打开vivo短信APP失败", e)
            openAppPermissionSettings(context)
        }
    }
    
    /**
     * 打开三星短信APP设置（自动删除验证码在短信APP→设置→更多设置中）
     */
    private fun openSamsungSmsSettings(context: Context) {
        try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.samsung.android.messaging",
                    "com.samsung.android.messaging.ui.activity.SettingsActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "打开三星短信设置失败", e)
            // 备用：打开通用短信APP
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    type = "vnd.android-dir/mms-sms"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                openAppPermissionSettings(context)
            }
        }
    }
    
    /**
     * 备用方案：打开系统设置
     */
    private fun openSettingsFallback(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "打开系统设置失败", e)
        }
    }
    
    /**
     * 跳转到应用信息页面
     */
    fun openAppInfo(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
    
    /**
     * 打开厂商特定的自启动设置
     */
    fun openAutoStartSettings(context: Context) {
        val brand = PhoneBrandDetector.detectBrand()
        
        when (brand) {
            PhoneBrandDetector.PhoneBrand.XIAOMI -> openXiaomiAutoStart(context)
            PhoneBrandDetector.PhoneBrand.OPPO,
            PhoneBrandDetector.PhoneBrand.REALME,
            PhoneBrandDetector.PhoneBrand.ONEPLUS -> openOppoAutoStart(context)
            PhoneBrandDetector.PhoneBrand.HUAWEI, PhoneBrandDetector.PhoneBrand.HONOR -> openHuaweiAutoStart(context)
            PhoneBrandDetector.PhoneBrand.VIVO, PhoneBrandDetector.PhoneBrand.IQOO -> openVivoAutoStart(context)
            PhoneBrandDetector.PhoneBrand.MEIZU -> openMeizuAutoStart(context)
            PhoneBrandDetector.PhoneBrand.SAMSUNG -> openSamsungAutoStart(context)
            else -> openAppInfo(context)
        }
    }
    
    private fun openXiaomiAutoStart(context: Context) {
        // HyperOS (澎湃OS) 使用新路径，MIUI 使用旧路径
        try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // HyperOS 备用路径
            try {
                val intent = Intent().apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity"
                    )
                    putExtra("extra_pkgname", context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                openAppInfo(context)
            }
        }
    }
    
    private fun openOppoAutoStart(context: Context) {
        // ColorOS 14+ 使用 com.oplus.safecenter，旧版使用 com.coloros.safecenter
        try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // ColorOS 14+ / OPLUS 统一包名
            try {
                val intent = Intent().apply {
                    component = ComponentName(
                        "com.oplus.safecenter",
                        "com.oplus.safecenter.startupapp.StartupAppListActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                openAppInfo(context)
            }
        }
    }
    
    private fun openHuaweiAutoStart(context: Context) {
        // HarmonyOS 4+ 包名可能变更，需要多层尝试
        try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // HarmonyOS 4+ 备用路径
            try {
                val intent = Intent().apply {
                    component = ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.appcontrol.activity.AppControlActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                openAppInfo(context)
            }
        }
    }
    
    private fun openVivoAutoStart(context: Context) {
        // OriginOS 4+ 可能使用 com.vivo.abe 替代 com.iqoo.secure
        try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // OriginOS 4+ / vivo 备用路径
            try {
                val intent = Intent().apply {
                    component = ComponentName(
                        "com.vivo.abe",
                        "com.vivo.abe.ui.phoneoptimize.AddWhiteListActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                openAppInfo(context)
            }
        }
    }
    
    private fun openMeizuAutoStart(context: Context) {
        // 魅族 Flyme 智能休眠/自启动管理
        try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.meizu.safe",
                    "com.meizu.safe.security.SelfStartActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Flyme 备用路径
            try {
                val intent = Intent().apply {
                    component = ComponentName(
                        "com.meizu.safe",
                        "com.meizu.safe.permission.SelfStartActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                openAppInfo(context)
            }
        }
    }
    
    private fun openSamsungAutoStart(context: Context) {
        // 三星 OneUI 没有专门的自启动管理，打开应用详情
        openAppInfo(context)
    }
}
