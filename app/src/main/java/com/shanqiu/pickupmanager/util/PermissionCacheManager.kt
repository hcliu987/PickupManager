/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 权限状态缓存管理器
 * 
 * 功能：
 * 1. 缓存权限检测结果，避免频繁检测
 * 2. 记录权限请求历史，避免重复弹窗
 * 3. 提供权限状态变化监听
 * 
 * 缓存策略：
 * - 权限状态缓存有效期：30分钟
 * - 用户拒绝次数记录：永久保存
 * - 上次检测时间：每次检测更新
 */
object PermissionCacheManager {
    
    private const val TAG = "PermissionCacheManager"
    private const val PREFS_NAME = "permission_cache"
    private const val CACHE_VALIDITY_MS = 30 * 60 * 1000L  // 30分钟有效期
    
    // SharedPreferences keys
    private const val KEY_LAST_CHECK_TIME = "last_check_time"
    private const val KEY_PERMISSION_STATUS = "permission_status"
    private const val KEY_LAST_RESULT_JSON = "last_result_json"
    private const val KEY_PERMISSION_REQUESTED = "permission_requested"
    private const val KEY_USER_DISMISSED = "user_dismissed"
    private const val KEY_LAST_DISMISS_TIME = "last_dismiss_time"
    
    /**
     * 获取SharedPreferences实例
     */
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * 检查缓存是否有效
     */
    fun isCacheValid(context: Context): Boolean {
        val prefs = getPrefs(context)
        val lastCheckTime = prefs.getLong(KEY_LAST_CHECK_TIME, 0L)
        val currentTime = System.currentTimeMillis()
        
        // 如果从未检测过，缓存无效
        if (lastCheckTime == 0L) return false
        
        // 检查是否在有效期内
        val isValid = (currentTime - lastCheckTime) < CACHE_VALIDITY_MS
        
        Log.d(TAG, "缓存状态: isValid=$isValid, lastCheck=$lastCheckTime, current=$currentTime")
        return isValid
    }
    
    /**
     * 获取缓存的权限状态
     */
    fun getCachedResult(context: Context): Boolean? {
        if (!isCacheValid(context)) return null
        
        val prefs = getPrefs(context)
        val hasResult = prefs.contains(KEY_PERMISSION_STATUS)
        
        return if (hasResult) {
            prefs.getBoolean(KEY_PERMISSION_STATUS, false)
        } else {
            null
        }
    }
    
    /**
     * 保存权限检测结果到缓存
     */
    fun saveResult(context: Context, isGranted: Boolean) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis())
            .putBoolean(KEY_PERMISSION_STATUS, isGranted)
            .apply()
        
        Log.d(TAG, "权限状态已缓存: isGranted=$isGranted")
    }
    
    /**
     * 标记"已经向系统请求过权限至少一次"。
     *
     * ## 为什么必须单独记（R4 / H-10①）
     *
     * `Activity.shouldShowRequestPermissionRationale()` 在下面**两种**情形下
     * 都返回 `false`：
     *
     * 1. 从未请求过该权限（系统还不知道用户的态度）；
     * 2. 已被永久拒绝 —— 用户在系统弹窗勾了"不再询问"，或系统策略直接拒绝。
     *
     * 只看这个返回值无法区分二者，而 UI 必须区分：情形 1 应该继续弹系统窗，
     * 情形 2 再弹多少次都不会出现任何界面（这是"点按钮没反应、页面永久卡死"的根因）。
     * 唯一可靠的判别依据就是"我们到底请求过没有"，因此要在 launcher 回调里落一个标记。
     */
    fun markPermissionRequested(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_PERMISSION_REQUESTED, true).apply()

        Log.d(TAG, "已记录：向系统请求过权限")
    }

    /** 是否曾经向系统请求过权限（见 [markPermissionRequested] 的说明）。 */
    fun hasRequestedPermission(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PERMISSION_REQUESTED, false)
    }
    
    /**
     * 记录用户关闭弹窗
     */
    fun recordDismiss(context: Context) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putBoolean(KEY_USER_DISMISSED, true)
            .putLong(KEY_LAST_DISMISS_TIME, System.currentTimeMillis())
            .apply()
        
        Log.d(TAG, "记录用户关闭弹窗")
    }
    
    /**
     * 检查用户是否最近关闭过弹窗（用于决定是否再次弹窗）
     * 
     * 修复：改为7天冷却期，用户跳过后7天内不再显示
     */
    fun shouldShowDialog(context: Context): Boolean {
        val prefs = getPrefs(context)
        val userDismissed = prefs.getBoolean(KEY_USER_DISMISSED, false)
        
        if (!userDismissed) return true
        
        // 如果用户之前关闭过弹窗，检查是否超过7天
        val lastDismissTime = prefs.getLong(KEY_LAST_DISMISS_TIME, 0L)
        val currentTime = System.currentTimeMillis()
        val daysSinceDismiss = (currentTime - lastDismissTime) / (1000 * 60 * 60 * 24)
        
        // 超过7天再次弹窗
        val shouldShow = daysSinceDismiss >= 7
        
        Log.d(TAG, "是否显示弹窗: shouldShow=$shouldShow, daysSince=$daysSinceDismiss")
        return shouldShow
    }
    
    /**
     * 重置用户关闭弹窗状态（权限发生变化时调用）
     */
    fun resetDismissState(context: Context) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putBoolean(KEY_USER_DISMISSED, false)
            .remove(KEY_LAST_DISMISS_TIME)
            .apply()
        
        Log.d(TAG, "重置弹窗状态")
    }
    
    /**
     * 清除所有缓存
     */
    fun clearCache(context: Context) {
        val prefs = getPrefs(context)
        prefs.edit().clear().apply()
        
        Log.d(TAG, "清除所有缓存")
    }
    
    /**
     * 获取缓存统计信息（用于调试）
     */
    fun getCacheStats(context: Context): Map<String, Any> {
        val prefs = getPrefs(context)
        return mapOf(
            "lastCheckTime" to prefs.getLong(KEY_LAST_CHECK_TIME, 0L),
            "isGranted" to prefs.getBoolean(KEY_PERMISSION_STATUS, false),
            "permissionRequested" to prefs.getBoolean(KEY_PERMISSION_REQUESTED, false),
            "userDismissed" to prefs.getBoolean(KEY_USER_DISMISSED, false),
            "lastDismissTime" to prefs.getLong(KEY_LAST_DISMISS_TIME, 0L),
            "isCacheValid" to isCacheValid(context)
        )
    }
}
