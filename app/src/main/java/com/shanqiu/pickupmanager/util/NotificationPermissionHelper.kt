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
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * 通知监听权限助手
 * 
 * 功能：
 * 1. 检测通知监听权限状态（多重验证）
 * 2. 跳转到通知监听设置页面
 * 3. 检查通知监听服务是否运行
 * 
 * 注意：OPPO 等国产手机上，通知权限需要同时满足：
 * 1. 系统设置中已授权（enabled_notification_listeners）
 * 2. 组件在 PackageManager 中已启用
 * 两个条件缺一不可
 */
object NotificationPermissionHelper {
    
    private const val TAG = "NotificationPermissionHelper"
    
    /**
     * 检测通知监听权限是否已授予（多重验证）
     * 使用三种方式交叉验证，确保在 OPPO 等国产手机上也能准确检测
     */
    fun isNotificationListenerEnabled(context: Context): Boolean {
        // 方式1：检查系统设置中的通知监听器列表
        val secureCheck = checkSecureSettings(context)
        // 方式2：检查 PackageManager 中组件是否启用
        val packageManagerCheck = isNotificationListenerRunning(context)
        // 方式3：使用 NotificationManagerCompat 检查（推荐 API）
        val compatCheck = checkNotificationManagerCompat(context)
        
        Log.d(TAG, "通知权限检测: secure=$secureCheck, pm=$packageManagerCheck, compat=$compatCheck")
        
        // 任一方式通过即认为已授权（兼容不同厂商实现）
        return secureCheck || packageManagerCheck || compatCheck
    }
    
    /**
     * 方式1：检查系统设置中的通知监听器列表
     */
    private fun checkSecureSettings(context: Context): Boolean {
        val packageName = context.packageName
        val flat = try {
            Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
        } catch (e: Exception) {
            Log.e(TAG, "读取通知监听设置失败", e)
            null
        }
        
        if (!TextUtils.isEmpty(flat)) {
            val names = flat!!.split(":")
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && TextUtils.equals(packageName, cn.packageName)) {
                    return true
                }
            }
        }
        
        return false
    }
    
    /**
     * 方式3：使用 NotificationManagerCompat 检查
     */
    private fun checkNotificationManagerCompat(context: Context): Boolean {
        return try {
            val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
            enabledPackages.contains(context.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "NotificationManagerCompat 检查失败", e)
            false
        }
    }
    
    /**
     * 跳转到通知监听设置页面
     */
    fun openNotificationListenerSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "打开通知监听设置失败", e)
            // 备用方案
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "打开系统设置失败", e2)
            }
        }
    }
    
    /**
     * 检查通知监听服务是否正在运行
     */
    fun isNotificationListenerRunning(context: Context): Boolean {
        // 检测服务是否已启用
        val pm = context.packageManager
        val component = ComponentName(context, "com.shanqiu.pickupmanager.sms.PickupNotificationListener")
        val enabled = pm.getComponentEnabledSetting(component)
        
        return enabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
               enabled == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    }
    
    /**
     * 尝试修复通知监听服务状态
     * 如果用户已授权但组件被禁用，自动启用组件
     * @return true 如果修复成功或无需修复
     */
    fun fixNotificationListenerState(context: Context): Boolean {
        val isGranted = checkSecureSettings(context) || checkNotificationManagerCompat(context)
        if (!isGranted) {
            return false // 用户未授权，无法修复
        }
        
        // 用户已授权，检查组件是否启用
        if (!isNotificationListenerRunning(context)) {
            Log.d(TAG, "用户已授权但组件被禁用，自动启用")
            enableNotificationListener(context)
        }
        
        return true
    }
    
    /**
     * 启用通知监听服务
     */
    fun enableNotificationListener(context: Context) {
        try {
            val pm = context.packageManager
            val component = ComponentName(context, "com.shanqiu.pickupmanager.sms.PickupNotificationListener")
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.d(TAG, "通知监听服务已启用")
        } catch (e: Exception) {
            Log.e(TAG, "启用通知监听服务失败", e)
        }
    }
    
    /**
     * 禁用通知监听服务
     */
    fun disableNotificationListener(context: Context) {
        try {
            val pm = context.packageManager
            val component = ComponentName(context, "com.shanqiu.pickupmanager.sms.PickupNotificationListener")
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.d(TAG, "通知监听服务已禁用")
        } catch (e: Exception) {
            Log.e(TAG, "禁用通知监听服务失败", e)
        }
    }
    
    /**
     * 获取通知监听权限状态描述
     */
    fun getStatusDescription(context: Context): String {
        return if (isNotificationListenerEnabled(context)) {
            "通知监听权限已授予"
        } else {
            "通知监听权限未授予，无法使用备用方案"
        }
    }
}
