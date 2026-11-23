/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import android.Manifest
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
import androidx.core.content.ContextCompat

/**
 * 短信权限管理器
 * 
 * 设计思路：
 * 1. 权限检测不依赖短信内容是否存在，只检测权限状态
 * 2. 针对不同厂商采用不同的权限检测策略
 * 3. 支持权限状态缓存，避免重复弹窗
 * 4. 提供厂商特定的设置页面跳转
 */
object SmsPermissionManager {
    
    private const val TAG = "SmsPermissionManager"
    
    /**
     * 权限状态数据类
     */
    data class SmsPermissionStatus(
        val hasReadSmsPermission: Boolean,      // 是否有 READ_SMS 权限
        val hasReceiveSmsPermission: Boolean,   // 是否有 RECEIVE_SMS 权限
        val canAccessSmsProvider: Boolean,      // 是否能访问短信 ContentProvider
        val hasNotificationSmsPermission: Boolean, // 是否有通知类短信权限（小米特有）
        val hasSmsOpsPermission: Boolean,       // 是否有 SMS Ops 权限
        val brand: PhoneBrandDetector.PhoneBrand,
        val isFullyGranted: Boolean,            // 是否完全授权
        val permissionIssue: PermissionIssue = PermissionIssue.NONE
    )
    
    /**
     * 权限问题类型
     */
    enum class PermissionIssue {
        NONE,                                   // 无问题
        NO_READ_SMS_PERMISSION,                 // 缺少读取短信权限
        NO_RECEIVE_SMS_PERMISSION,              // 缺少接收短信权限
        XIAOMI_NOTIFICATION_SMS,                // 小米通知类短信权限未开启
        VERIFICATION_CODE_PROTECTION,           // 验证码保护功能阻止
        SMS_PROVIDER_ACCESS_DENIED,             // 短信内容提供者访问被拒绝
        OPPO_PERMISSION_REVOKED,                // OPPO 权限被系统收回
        VIVO_VERIFICATION_PROTECT,              // vivo/iQOO 验证码安全保护
        SAMSUNG_AUTO_DELETE_SMS                 // 三星自动删除验证码短信
    }
    
    /**
     * 检查是否有 READ_SMS 权限
     */
    fun hasReadSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 检查是否有 RECEIVE_SMS 权限
     */
    fun hasReceiveSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 检查是否能访问短信 ContentProvider
     * 
     * 注意：此方法只检测是否能访问，不检测是否有短信数据
     */
    fun canAccessSmsProvider(context: Context): Boolean {
        if (!hasReadSmsPermission(context)) {
            return false
        }
        
        return try {
            // 尝试访问短信数据库，只检测访问权限，不关心是否有数据
            val cursor = context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id"),  // 只查询最小必要字段
                null,
                null,
                null  // 不限制数量，只检测访问权限
            )
            // 只要不抛出异常，就说明有权限
            cursor?.close()
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "无法访问短信 ContentProvider，权限可能未生效", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "访问短信 ContentProvider 时发生错误", e)
            false
        }
    }
    
    /**
     * 检查是否有通知类短信权限（小米特有）
     * 
     * 小米系统对通知类短信（106开头）有特殊权限控制
     * 使用 AppOpsManager 检测 OP_READ_SMS 权限
     */
    fun hasNotificationSmsPermission(context: Context): Boolean {
        if (!hasReadSmsPermission(context)) {
            return false
        }
        
        // 非小米设备，默认有权限
        if (!PhoneBrandDetector.isXiaomi()) {
            return true
        }
        
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            if (appOps != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                val result = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_READ_SMS,
                    Process.myUid(),
                    context.packageName
                )
                result == AppOpsManager.MODE_ALLOWED
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "检测通知类短信权限失败", e)
            // 检测失败时，尝试访问106短信来验证
            canRead106Sms(context)
        }
    }
    
    /**
     * 检查是否能读取106开头的短信（备用检测方法）
     */
    private fun canRead106Sms(context: Context): Boolean {
        return try {
            val cursor = context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id"),
                "address LIKE ?",
                arrayOf("106%"),
                null
            )
            cursor?.close()
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 检查是否有 SMS Ops 权限（Android 12+）
     */
    fun hasSmsOpsPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                if (appOps != null) {
                    val result = appOps.checkOpNoThrow(
                        "android:send_sms",
                        Process.myUid(),
                        context.packageName
                    )
                    result == AppOpsManager.MODE_ALLOWED || result == AppOpsManager.MODE_FOREGROUND
                } else {
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "检测 SMS Ops 权限失败", e)
                true
            }
        }
        return true
    }
    
    /**
     * 综合检查权限状态
     * 
     * 这是主要的权限检测方法，返回完整的权限状态
     */
    fun checkSmsPermissionStatus(context: Context): SmsPermissionStatus {
        val brand = PhoneBrandDetector.detectBrand()
        val hasReadSms = hasReadSmsPermission(context)
        val hasReceiveSms = hasReceiveSmsPermission(context)
        val canAccessProvider = canAccessSmsProvider(context)
        val hasNotificationSms = hasNotificationSmsPermission(context)
        val hasSmsOps = hasSmsOpsPermission(context)
        
        // 判断是否完全授权
        val isFullyGranted = when (brand) {
            PhoneBrandDetector.PhoneBrand.XIAOMI -> {
                // 小米需要：READ_SMS + RECEIVE_SMS + 通知类短信权限
                hasReadSms && hasReceiveSms && hasNotificationSms && canAccessProvider
            }
            PhoneBrandDetector.PhoneBrand.HUAWEI,
            PhoneBrandDetector.PhoneBrand.HONOR -> {
                // 华为/荣耀需要：READ_SMS + RECEIVE_SMS
                hasReadSms && hasReceiveSms && canAccessProvider
            }
            PhoneBrandDetector.PhoneBrand.OPPO,
            PhoneBrandDetector.PhoneBrand.REALME,
            PhoneBrandDetector.PhoneBrand.ONEPLUS -> {
                // OPPO/realme/一加需要：READ_SMS + RECEIVE_SMS
                // OPPO 有权限回收机制
                hasReadSms && hasReceiveSms && canAccessProvider
            }
            PhoneBrandDetector.PhoneBrand.VIVO,
            PhoneBrandDetector.PhoneBrand.IQOO -> {
                // vivo/iQOO需要：READ_SMS + RECEIVE_SMS
                hasReadSms && hasReceiveSms && canAccessProvider
            }
            else -> {
                // 其他品牌：READ_SMS + RECEIVE_SMS
                hasReadSms && hasReceiveSms && canAccessProvider
            }
        }
        
        // 判断具体问题
        val issue = when {
            !hasReadSms -> PermissionIssue.NO_READ_SMS_PERMISSION
            !hasReceiveSms -> PermissionIssue.NO_RECEIVE_SMS_PERMISSION
            !canAccessProvider -> PermissionIssue.SMS_PROVIDER_ACCESS_DENIED
            brand == PhoneBrandDetector.PhoneBrand.XIAOMI && !hasNotificationSms -> 
                PermissionIssue.XIAOMI_NOTIFICATION_SMS
            brand == PhoneBrandDetector.PhoneBrand.OPPO && !canAccessProvider -> 
                PermissionIssue.OPPO_PERMISSION_REVOKED
            else -> PermissionIssue.NONE
        }
        
        return SmsPermissionStatus(
            hasReadSmsPermission = hasReadSms,
            hasReceiveSmsPermission = hasReceiveSms,
            canAccessSmsProvider = canAccessProvider,
            hasNotificationSmsPermission = hasNotificationSms,
            hasSmsOpsPermission = hasSmsOps,
            brand = brand,
            isFullyGranted = isFullyGranted,
            permissionIssue = issue
        )
    }
    
    /**
     * 打开厂商特定的权限设置页面
     */
    fun openBrandPermissionSettings(activity: Activity) {
        val brand = PhoneBrandDetector.detectBrand()
        
        when (brand) {
            PhoneBrandDetector.PhoneBrand.XIAOMI -> openXiaomiPermissionSettings(activity)
            PhoneBrandDetector.PhoneBrand.HUAWEI -> openHuaweiPermissionSettings(activity)
            PhoneBrandDetector.PhoneBrand.HONOR -> openHonorPermissionSettings(activity)
            PhoneBrandDetector.PhoneBrand.OPPO,
            PhoneBrandDetector.PhoneBrand.REALME,
            PhoneBrandDetector.PhoneBrand.ONEPLUS -> openOppoPermissionSettings(activity)
            PhoneBrandDetector.PhoneBrand.VIVO,
            PhoneBrandDetector.PhoneBrand.IQOO -> openVivoPermissionSettings(activity)
            PhoneBrandDetector.PhoneBrand.SAMSUNG -> openSamsungPermissionSettings(activity)
            PhoneBrandDetector.PhoneBrand.MEIZU -> openMeizuPermissionSettings(activity)
            else -> openAppDetailSettings(activity)
        }
    }
    
    /**
     * 打开小米权限设置
     * 小米需要特殊处理通知类短信权限
     */
    private fun openXiaomiPermissionSettings(activity: Activity) {
        try {
            // 尝试打开小米权限编辑器
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
            Log.e(TAG, "无法打开小米权限设置，尝试备用方案", e)
            openXiaomiPermissionSettingsFallback(activity)
        }
    }
    
    private fun openXiaomiPermissionSettingsFallback(activity: Activity) {
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
    
    /**
     * 打开华为权限设置
     */
    private fun openHuaweiPermissionSettings(activity: Activity) {
        try {
            val intent = Intent().apply {
                setClassName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.AppControlActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "无法打开华为权限设置，尝试备用方案", e)
            // 尝试打开华为应用启动管理
            try {
                val intent = Intent().apply {
                    setClassName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "备用方案也失败，打开应用详情", e2)
                openAppDetailSettings(activity)
            }
        }
    }
    
    /**
     * 打开荣耀权限设置
     */
    private fun openHonorPermissionSettings(activity: Activity) {
        // MagicOS 7+ 可能使用 com.hihonor.magichome
        try {
            val intent = Intent().apply {
                setClassName(
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.addviewmonitor.AddViewMonitorActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            // MagicOS 7+ 备用路径
            try {
                val intent = Intent().apply {
                    setClassName(
                        "com.hihonor.magichome",
                        "com.hihonor.magichome.addviewmonitor.AddViewMonitorActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "无法打开荣耀权限设置", e2)
                openAppDetailSettings(activity)
            }
        }
    }
    
    /**
     * 打开 OPPO 权限设置
     * OPPO 有特殊的权限回收机制，需要引导用户设置
     */
    private fun openOppoPermissionSettings(activity: Activity) {
        // ColorOS 14+ 使用 com.oplus.safecenter，旧版使用 com.coloros.safecenter
        try {
            // 尝试打开 OPPO 安全中心
            val intent = Intent().apply {
                setClassName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.PermissionManagerActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "无法打开 OPPO 权限设置，尝试备用方案", e)
            // ColorOS 14+ / OPLUS 统一包名
            try {
                val intent = Intent().apply {
                    setClassName(
                        "com.oplus.safecenter",
                        "com.oplus.safecenter.permission.PermissionManagerActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            } catch (e2: Exception) {
                // 尝试打开 OPPO 应用列表
                try {
                    val intent = Intent().apply {
                        setClassName(
                            "com.coloros.safecenter",
                            "com.coloros.safecenter.startupapp.StartupAppListActivity"
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(intent)
                } catch (e3: Exception) {
                    Log.e(TAG, "备用方案也失败，打开应用详情", e3)
                    openAppDetailSettings(activity)
                }
            }
        }
    }
    
    /**
     * 打开 vivo 权限设置
     */
    private fun openVivoPermissionSettings(activity: Activity) {
        try {
            val intent = Intent().apply {
                setClassName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity"
                )
                putExtra("packagename", activity.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "无法打开 vivo 权限设置，尝试备用方案", e)
            // 尝试打开 vivo 应用管理
            try {
                val intent = Intent().apply {
                    setClassName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.PurviewTabActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "备用方案也失败，打开应用详情", e2)
                openAppDetailSettings(activity)
            }
        }
    }
    
    /**
     * 打开一加权限设置
     */
    private fun openOnePlusPermissionSettings(activity: Activity) {
        try {
            val intent = Intent().apply {
                setClassName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.PermissionManagerActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "无法打开一加权限设置", e)
            openAppDetailSettings(activity)
        }
    }
    
    /**
     * 打开三星权限设置
     */
    private fun openSamsungPermissionSettings(activity: Activity) {
        openAppDetailSettings(activity)
    }
    
    /**
     * 打开魅族权限设置
     */
    private fun openMeizuPermissionSettings(activity: Activity) {
        try {
            val intent = Intent().apply {
                setClassName(
                    "com.meizu.safe",
                    "com.meizu.safe.security.SelfStartActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "无法打开魅族权限设置", e)
            openAppDetailSettings(activity)
        }
    }
    
    /**
     * 打开应用详情设置
     */
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
    
    /**
     * 打开系统设置
     */
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
    
    /**
     * 获取权限引导标题
     */
    fun getPermissionGuideTitle(status: SmsPermissionStatus): String {
        return when (status.permissionIssue) {
            PermissionIssue.NO_READ_SMS_PERMISSION -> "需要短信读取权限"
            PermissionIssue.NO_RECEIVE_SMS_PERMISSION -> "需要短信接收权限"
            PermissionIssue.XIAOMI_NOTIFICATION_SMS -> "需要开启通知类短信权限"
            PermissionIssue.VERIFICATION_CODE_PROTECTION -> "可能需要关闭验证码保护"
            PermissionIssue.SMS_PROVIDER_ACCESS_DENIED -> "短信读取权限未生效"
            PermissionIssue.OPPO_PERMISSION_REVOKED -> "权限被系统收回，请重新授权"
            PermissionIssue.VIVO_VERIFICATION_PROTECT -> "检查验证码安全保护"
            PermissionIssue.SAMSUNG_AUTO_DELETE_SMS -> "检查自动删除验证码"
            PermissionIssue.NONE -> "权限已授予"
        }
    }
    
    /**
     * 获取权限引导消息
     */
    fun getPermissionGuideMessage(status: SmsPermissionStatus): String {
        val brandName = PhoneBrandDetector.getBrandDisplayName()
        
        return when (status.permissionIssue) {
            PermissionIssue.NO_READ_SMS_PERMISSION -> 
                "应用需要短信读取权限才能导入快递取件短信。\n\n" +
                "请点击「前往设置」授予「短信」权限。"
            PermissionIssue.NO_RECEIVE_SMS_PERMISSION ->
                "应用需要短信接收权限才能实时监听新短信。\n\n" +
                "请点击「前往设置」授予「短信」权限。"
            PermissionIssue.XIAOMI_NOTIFICATION_SMS -> 
                "${brandName}手机需要额外开启「通知类短信」权限才能读取快递短信。\n\n" +
                "请按以下步骤操作：\n" +
                "1. 点击「前往设置」\n" +
                "2. 找到「通知类短信」或「短信」权限\n" +
                "3. 选择「允许」而非「空白通行证」\n\n" +
                "注意：如果看到「空白通行证」选项，请务必关闭它！"
            PermissionIssue.VERIFICATION_CODE_PROTECTION -> 
                "${brandName}手机可能开启了「验证码安全保护」功能，这会阻止应用读取验证码类短信。\n\n" +
                "请尝试以下操作：\n" +
                "1. 打开短信APP\n" +
                "2. 进入设置 → 验证码安全保护\n" +
                "3. 关闭该功能或允许本应用读取"
            PermissionIssue.SMS_PROVIDER_ACCESS_DENIED -> 
                "短信读取权限未生效，请尝试以下操作：\n" +
                "1. 点击「前往设置」\n" +
                "2. 找到「短信」权限并重新授予\n" +
                "3. 重启应用后重试"
            PermissionIssue.OPPO_PERMISSION_REVOKED ->
                "${brandName}手机系统可能已自动收回权限。\n\n" +
                "OPPO手机会在应用长时间未使用时自动收回权限。\n\n" +
                "请按以下步骤操作：\n" +
                "1. 点击「前往设置」\n" +
                "2. 找到「短信」权限\n" +
                "3. 重新授予权限\n" +
                "4. 建议同时关闭电池优化"
            PermissionIssue.VIVO_VERIFICATION_PROTECT ->
                "${brandName}手机的「验证码安全保护」可能阻止应用读取快递短信。\n\n" +
                "请按以下步骤操作：\n" +
                "1. 打开短信APP\n" +
                "2. 进入设置 → 验证码安全保护\n" +
                "3. 关闭该功能或允许本应用读取"
            PermissionIssue.SAMSUNG_AUTO_DELETE_SMS ->
                "三星OneUI的「自动删除验证码」功能会删除验证码短信。\n\n" +
                "请按以下步骤操作：\n" +
                "1. 打开短信APP\n" +
                "2. 进入设置 → 更多设置\n" +
                "3. 关闭「自动删除验证码」功能"
            PermissionIssue.NONE -> 
                "权限已正确配置，可以正常使用。"
        }
    }
    
    /**
     * 获取需要请求的权限列表
     */
    fun getRequiredPermissions(): Array<String> {
        return arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS
        )
    }
}
