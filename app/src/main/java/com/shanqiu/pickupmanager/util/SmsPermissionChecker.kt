/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 短信权限检测器
 * 
 * 设计思路：
 * 1. 保留现有检测逻辑的长处（ContentProvider访问检测）
 * 2. 引入开源项目的优点（AppOpsManager检测）
 * 3. 增加厂商特殊权限检测
 * 4. 不依赖短信内容判断权限状态
 */
object SmsPermissionChecker {
    
    private const val TAG = "SmsPermissionChecker"
    private const val PREFS_NAME = "sms_permission_tracker"
    private const val KEY_READ_SMS_EVER_GRANTED = "read_sms_ever_granted"
    
    /**
     * 权限检测结果
     */
    data class PermissionCheckResult(
        val isFullyGranted: Boolean,                    // 是否完全授权
        val hasReadSmsPermission: Boolean,              // 是否有READ_SMS权限
        val hasReceiveSmsPermission: Boolean,           // 是否有RECEIVE_SMS权限
        val canAccessSmsProvider: Boolean,              // 是否能访问短信ContentProvider
        val hasNotificationSmsPermission: Boolean,      // 是否有通知类短信权限（小米）
        val isBlankPassEnabled: Boolean,                // 是否开启了空白通行证（小米）
        val isPermissionRevoked: Boolean,               // 权限是否被系统收回（OPPO）
        val isVerificationCodeProtection: Boolean,      // 是否开启了验证码保护（华为/荣耀）
        val brand: PhoneBrandDetector.PhoneBrand,       // 手机品牌
        val missingPermissions: List<MissingPermission> // 缺失的权限列表
    )
    
    /**
     * 缺失的权限信息
     */
    data class MissingPermission(
        val permissionType: PermissionType,     // 权限类型
        val displayName: String,                // 显示名称
        val description: String,                // 描述说明
        val isCritical: Boolean                 // 是否关键权限
    )
    
    /**
     * 权限类型枚举
     */
    enum class PermissionType {
        READ_SMS,                   // 读取短信权限
        RECEIVE_SMS,                // 接收短信权限
        NOTIFICATION_SMS,           // 通知类短信权限（小米）
        BLANK_PASS,                 // 空白通行证（小米）
        PERMISSION_REVOKE,          // 权限收回（OPPO）
        VERIFICATION_CODE_PROTECT,  // 验证码保护（华为/荣耀/vivo/iQOO）
        VIVO_VERIFICATION_PROTECT,  // 验证码安全保护（vivo/iQOO）
        SAMSUNG_AUTO_DELETE_SMS,    // 三星自动删除验证码短信
        POST_NOTIFICATIONS,         // 通知权限 (Android 13+)
        BATTERY_OPTIMIZATION,       // 电池优化忽略
        AUTO_START                  // 自启动权限
    }
    
    /**
     * 执行完整的权限检测
     */
    fun checkAllPermissions(context: Context): PermissionCheckResult {
        val brand = PhoneBrandDetector.detectBrand()
        
        // 1. 基础权限检测
        val hasReadSms = hasReadSmsPermission(context)
        val hasReceiveSms = hasReceiveSmsPermission(context)
        val canAccessProvider = canAccessSmsProvider(context)
        
        // 2. 厂商特殊权限检测
        val hasNotificationSms = checkNotificationSmsPermission(context)
        val isBlankPassEnabled = checkBlankPassStatus(context)
        val isPermissionRevoked = checkPermissionRevoked(context, hasReadSms)
        val isVerificationCodeProtection = checkVerificationCodeProtection(context)
        
        // 3. 收集缺失的权限
        val missingPermissions = mutableListOf<MissingPermission>()
        
        if (!hasReadSms) {
            missingPermissions.add(
                MissingPermission(
                    permissionType = PermissionType.READ_SMS,
                    displayName = "短信读取权限",
                    description = "用于读取快递取件短信",
                    isCritical = true
                )
            )
        }
        
        if (!hasReceiveSms) {
            missingPermissions.add(
                MissingPermission(
                    permissionType = PermissionType.RECEIVE_SMS,
                    displayName = "短信接收权限",
                    description = "用于实时监听新短信",
                    isCritical = true
                )
            )
        }
        
        // 小米：通知类短信权限
        if (brand == PhoneBrandDetector.PhoneBrand.XIAOMI && !hasNotificationSms) {
            missingPermissions.add(
                MissingPermission(
                    permissionType = PermissionType.NOTIFICATION_SMS,
                    displayName = "通知类短信权限",
                    description = "小米手机需要此权限才能读取106开头的快递短信",
                    isCritical = true
                )
            )
        }
        
        // 小米：空白通行证
        if (brand == PhoneBrandDetector.PhoneBrand.XIAOMI && isBlankPassEnabled) {
            missingPermissions.add(
                MissingPermission(
                    permissionType = PermissionType.BLANK_PASS,
                    displayName = "关闭空白通行证",
                    description = "空白通行证会阻止应用读取短信内容",
                    isCritical = true
                )
            )
        }
        
        // OPPO：权限被收回
        if (brand == PhoneBrandDetector.PhoneBrand.OPPO && isPermissionRevoked) {
            missingPermissions.add(
                MissingPermission(
                    permissionType = PermissionType.PERMISSION_REVOKE,
                    displayName = "权限被系统收回",
                    description = "OPPO系统已自动收回权限，请重新授权",
                    isCritical = true
                )
            )
        }
        
        // 华为/荣耀：验证码保护
        if ((brand == PhoneBrandDetector.PhoneBrand.HUAWEI || 
             brand == PhoneBrandDetector.PhoneBrand.HONOR) && isVerificationCodeProtection) {
            missingPermissions.add(
                MissingPermission(
                    permissionType = PermissionType.VERIFICATION_CODE_PROTECT,
                    displayName = "关闭验证码保护",
                    description = "验证码保护功能会阻止应用读取快递短信",
                    isCritical = false
                )
            )
        }
        
        // vivo/iQOO：验证码安全保护（与华为类似，会阻止读取验证码短信）
        if ((brand == PhoneBrandDetector.PhoneBrand.VIVO || 
             brand == PhoneBrandDetector.PhoneBrand.IQOO)) {
            missingPermissions.add(
                MissingPermission(
                    permissionType = PermissionType.VIVO_VERIFICATION_PROTECT,
                    displayName = "检查验证码安全保护",
                    description = "vivo/iQOO的「验证码安全保护」可能阻止应用读取快递短信，请在短信APP设置中检查",
                    isCritical = false
                )
            )
        }
        
        // 三星：自动删除验证码短信
        if (brand == PhoneBrandDetector.PhoneBrand.SAMSUNG) {
            missingPermissions.add(
                MissingPermission(
                    permissionType = PermissionType.SAMSUNG_AUTO_DELETE_SMS,
                    displayName = "检查自动删除验证码",
                    description = "三星OneUI的「自动删除验证码」功能会删除验证码短信，请在短信APP→设置→更多设置中检查",
                    isCritical = false
                )
            )
        }
        
        // 通知权限 (Android 13+)
        if (!PermissionChecker.checkNotificationPermission(context)) {
            missingPermissions.add(
                MissingPermission(
                    permissionType = PermissionType.POST_NOTIFICATIONS,
                    displayName = "通知权限",
                    description = "用于显示取件码通知，请在系统设置中开启",
                    isCritical = false
                )
            )
        }
        
        // 电池优化忽略
        if (!PermissionChecker.checkBatteryOptimization(context)) {
            missingPermissions.add(
                MissingPermission(
                    permissionType = PermissionType.BATTERY_OPTIMIZATION,
                    displayName = "电池优化",
                    description = "关闭电池优化可确保小部件实时更新，请在系统设置中设置为「无限制」",
                    isCritical = false
                )
            )
        }
        
        // 自启动权限
        if (!PermissionChecker.checkAutoStartPermission(context)) {
            missingPermissions.add(
                MissingPermission(
                    permissionType = PermissionType.AUTO_START,
                    displayName = "自启动权限",
                    description = "允许应用自启动可确保小部件正常工作，请在系统设置中开启",
                    // 只影响后台存活与反馈通知，与"能否读取并导入短信"无关。
                    // 曾经它是 true：在检测不到自启动状态的机型（Pixel/三星/原生）上
                    // markAutoStartGranted() 无人调用，该恒为 false → 「导入短信」按钮永久不可用。
                    isCritical = false
                )
            )
        }
        
        // 4. 判断是否完全授权
        val isFullyGranted = missingPermissions.none { it.isCritical }
        
        return PermissionCheckResult(
            isFullyGranted = isFullyGranted,
            hasReadSmsPermission = hasReadSms,
            hasReceiveSmsPermission = hasReceiveSms,
            canAccessSmsProvider = canAccessProvider,
            hasNotificationSmsPermission = hasNotificationSms,
            isBlankPassEnabled = isBlankPassEnabled,
            isPermissionRevoked = isPermissionRevoked,
            isVerificationCodeProtection = isVerificationCodeProtection,
            brand = brand,
            missingPermissions = missingPermissions
        )
    }
    
    /**
     * 检测是否有 READ_SMS 权限
     */
    private fun hasReadSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, 
            android.Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 检测是否有 RECEIVE_SMS 权限
     */
    private fun hasReceiveSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, 
            android.Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 检测是否能访问短信 ContentProvider
     * 
     * 保留现有逻辑的长处：通过实际访问检测权限
     * 改进：不依赖短信内容是否存在
     */
    private fun canAccessSmsProvider(context: Context): Boolean {
        if (!hasReadSmsPermission(context)) {
            return false
        }
        
        return try {
            val cursor = context.contentResolver.query(
                android.net.Uri.parse("content://sms/inbox"),
                arrayOf("_id"),
                null,
                null,
                null
            )
            cursor?.close()
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "无法访问短信 ContentProvider", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "访问短信 ContentProvider 时发生错误", e)
            false
        }
    }
    
    /**
     * 检测通知类短信权限（小米特有）
     * 
     * 引入开源项目的 AppOpsManager 检测方式
     */
    private fun checkNotificationSmsPermission(context: Context): Boolean {
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
            // 检测失败时，尝试访问106短信来验证（保留现有逻辑）
            canRead106Sms(context)
        }
    }
    
    /**
     * 检测是否能读取106开头的短信（备用检测方法）
     * 保留现有逻辑
     */
    private fun canRead106Sms(context: Context): Boolean {
        return try {
            val cursor = context.contentResolver.query(
                android.net.Uri.parse("content://sms/inbox"),
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
     * 检测空白通行证状态（小米特有）
     * 
     * 空白通行证：开启后应用只能看到空白界面，无法读取真实内容
     */
    private fun checkBlankPassStatus(context: Context): Boolean {
        if (!PhoneBrandDetector.isXiaomi()) {
            return false
        }
        
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            if (appOps != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                val result = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_READ_SMS,
                    Process.myUid(),
                    context.packageName
                )
                // MODE_IGNORED 可能表示空白通行证开启
                result == AppOpsManager.MODE_IGNORED
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "检测空白通行证状态失败", e)
            false
        }
    }
    
    /**
     * 检测权限是否被系统收回（OPPO特有）
     *
     * OPPO会在应用长时间未使用后自动收回权限。
     * 
     * 修复：引入 SharedPreferences 标记 KEY_READ_SMS_EVER_GRANTED，
     * 区分"用户从未授予过权限"（不提示收回）和"曾被授予后被系统收回"（提示收回）。
     * 
     * 检测流程：
     * 1. hasReadSms == true → 权限正常，记录 ever_granted 标记，返回 false
     * 2. hasReadSms == false → 检查 ever_granted 标记
     *    - 标记为 true：权限曾被授予但现在失效 → 很可能是被系统收回
     *    - 标记为 false：用户从未授予过权限 → 不是被收回
     */
    private fun checkPermissionRevoked(context: Context, hasReadSms: Boolean): Boolean {
        if (!PhoneBrandDetector.isOppo()) {
            return false
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

        if (hasReadSms) {
            // 权限当前有效 → 记录 ever_granted 标记
            prefs.edit().putBoolean(KEY_READ_SMS_EVER_GRANTED, true).apply()
            return false
        }

        // 权限当前无效 → 检查是否曾被授予过
        val everGranted = prefs.getBoolean(KEY_READ_SMS_EVER_GRANTED, false)
        if (!everGranted) {
            // 用户从未授予过权限，不是被系统收回
            return false
        }

        // 曾被授予但现在失效 → 通过 AppOpsManager 确认是否被系统收回
        return try {
            val appOps = context.getSystemService(android.content.Context.APP_OPS_SERVICE) as? AppOpsManager
            if (appOps != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                val result = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_READ_SMS,
                    Process.myUid(),
                    context.packageName
                )
                // MODE_IGNORED 或 MODE_ERRORED 表示权限被系统收回
                result == AppOpsManager.MODE_IGNORED || result == AppOpsManager.MODE_ERRORED
            } else {
                // AppOpsManager 不可用，以 ever_granted 标记为准
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "检测OPPO权限收回失败", e)
            true  // 曾被授予但现在无法确认 → 提示用户检查
        }
    }
    
    /**
     * 检测验证码保护状态（华为/荣耀特有）
     * 
     * 华为/荣耀的验证码保护功能会阻止应用读取验证码类短信
     */
    private fun checkVerificationCodeProtection(context: Context): Boolean {
        val brand = PhoneBrandDetector.detectBrand()
        if (brand != PhoneBrandDetector.PhoneBrand.HUAWEI && 
            brand != PhoneBrandDetector.PhoneBrand.HONOR) {
            return false
        }
        
        // 华为没有公开API检测，只能通过尝试读取来判断
        // 这里返回false，让用户手动检查
        return false
    }
}
