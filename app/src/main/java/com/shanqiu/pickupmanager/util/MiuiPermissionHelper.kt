/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log

object MiuiPermissionHelper {
    
    private const val TAG = "MiuiPermissionHelper"
    private const val MIUI_VERSION_PROP = "ro.miui.ui.version.name"
    private const val HYPER_OS_VERSION_PROP = "ro.mi.os.version.name"
    
    fun isMiui(): Boolean {
        return !getSystemProperty(MIUI_VERSION_PROP).isNullOrEmpty()
    }
    
    fun isHyperOs(): Boolean {
        return !getSystemProperty(HYPER_OS_VERSION_PROP).isNullOrEmpty()
    }
    
    private fun getSystemProperty(key: String): String? {
        return try {
            val prop = Class.forName("android.os.SystemProperties")
            val method = prop.getMethod("get", String::class.java)
            method.invoke(null, key) as? String
        } catch (e: Exception) {
            null
        }
    }
    
    fun getSystemType(): SystemType {
        return when {
            isHyperOs() -> SystemType.HYPER_OS
            isMiui() -> SystemType.MIUI
            else -> SystemType.OTHER
        }
    }
    
    fun openAppDetailSettings(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", activity.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "无法打开应用详情设置", e)
            openSystemSettings(activity)
        }
    }
    
    fun openMiuiPermissionSettings(activity: Activity) {
        try {
            val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", activity.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "无法打开MIUI权限设置，尝试备用方案", e)
            openMiuiPermissionSettingsFallback(activity)
        }
    }
    
    private fun openMiuiPermissionSettingsFallback(activity: Activity) {
        try {
            val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
                )
                putExtra("extra_pkgname", activity.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "备用方案也失败，打开应用详情", e)
            openAppDetailSettings(activity)
        }
    }
    
    fun openMiuiSmsPermissionSettings(activity: Activity) {
        try {
            val intent = Intent().apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", activity.packageName)
                putExtra("extra_perm_name", "android.permission.READ_SMS")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "无法直接打开短信权限设置", e)
            openMiuiPermissionSettings(activity)
        }
    }
    
    private fun openSystemSettings(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "无法打开系统设置", e)
        }
    }
    
    fun hasSmsPermission(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.READ_SMS) == 
            PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 检测是否有通知类短信权限
     * 
     * MIUI/HyperOS 将短信权限分为两类：
     * 1. 普通短信（READ_SMS）- 只能读取联系人短信
     * 2. 通知类短信 - 读取验证码/快递/通知等短信
     * 
     * 注意：MIUI 没有提供官方 API 来检测通知类短信权限状态，
     * 因此使用 AppOps 检查作为近似检测。
     * 如果检测失败，保守假设权限未授予。
     */
    fun hasNotificationSmsPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            if (appOps != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                val result = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_READ_SMS,
                    Process.myUid(),
                    context.packageName
                )
                // MODE_ALLOWED 表示未被限制，但无法区分是"普通短信"还是"通知类短信"
                // 因此返回 true 仅表示基本 AppOps 层面通过，不代表 MIUI 通知类短信已授权
                result == AppOpsManager.MODE_ALLOWED
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "检测短信 AppOps 权限失败", e)
            // 检测失败时返回 false（保守策略），引导用户手动确认
            false
        }
    }
    
    /**
     * 判断是否需要引导用户检查"空白通行证"
     * 
     * MIUI/HyperOS 的"空白通行证"是隐私保护功能，默认对第三方应用返回空数据。
     * 即使授予了 READ_SMS 权限，如果空白通行证开启，应用仍读不到真实短信内容。
     * 
     * 没有官方 API 可检测空白通行证状态，因此对于 MIUI/HyperOS 设备，
     * 始终建议用户检查此设置。
     */
    fun shouldCheckBlankPass(): Boolean {
        return isMiui() || isHyperOs()
    }
    
    /**
     * 打开应用信息页面（用户可从此页进入权限管理 → 短信 → 关闭空白通行证）
     */
    fun openAppInfoSettings(activity: Activity) {
        openAppDetailSettings(activity)
    }
    
    /**
     * 打开应用权限管理页面（用户可在此找到"其他权限 → 通知类短信"）
     */
    fun openAppPermissionSettings(activity: Activity) {
        openMiuiPermissionSettings(activity)
    }
    
    fun checkMiuiSmsPermissionStatus(context: Context): MiuiSmsPermissionStatus {
        val hasBasicSms = hasSmsPermission(context)
        val hasNotificationSms = hasNotificationSmsPermission(context)
        val needsBlankPassCheck = shouldCheckBlankPass()
        // isFullyGranted 不包含空白通行证判断（无法通过 API 检测）
        // 空白通行证为 UI 提示项，不阻塞权限检查流程
        val isFullyGranted = hasBasicSms && hasNotificationSms
        
        return MiuiSmsPermissionStatus(
            hasBasicSmsPermission = hasBasicSms,
            hasNotificationSmsPermission = hasNotificationSms,
            needsBlankPassCheck = needsBlankPassCheck,
            isFullyGranted = isFullyGranted,
            systemType = getSystemType()
        )
    }
    
    data class MiuiSmsPermissionStatus(
        val hasBasicSmsPermission: Boolean,
        val hasNotificationSmsPermission: Boolean,
        val needsBlankPassCheck: Boolean = false,
        val isFullyGranted: Boolean,
        val systemType: SystemType = SystemType.OTHER
    )
    
    enum class SystemType {
        HYPER_OS,
        MIUI,
        OTHER
    }
}
