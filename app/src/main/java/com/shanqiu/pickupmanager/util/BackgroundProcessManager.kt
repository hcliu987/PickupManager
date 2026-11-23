/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.shanqiu.pickupmanager.receiver.AppExitReceiver

object BackgroundProcessManager : DefaultLifecycleObserver {
    
    private const val TAG = "BackgroundProcessManager"
    private const val BACKGROUND_TIMEOUT_MS = 5 * 60 * 1000L
    private const val REQUEST_CODE_EXIT = 10001
    
    private var applicationContext: Context? = null
    private var isBackground = false
    private var isExiting = false
    private var lastBackgroundTime = 0L
    
    fun init(application: Application) {
        applicationContext = application.applicationContext
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        Log.d(TAG, "========================================")
        Log.d(TAG, "后台进程管理器已初始化")
        Log.d(TAG, "后台超时时间: ${BACKGROUND_TIMEOUT_MS / 1000} 秒")
        Log.d(TAG, "========================================")
    }
    
    override fun onStart(owner: LifecycleOwner) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "[生命周期] 应用进入前台 (onStart)")
        if (isBackground) {
            val backgroundDuration = System.currentTimeMillis() - lastBackgroundTime
            Log.d(TAG, "后台持续时间: ${backgroundDuration / 1000} 秒")
        }
        Log.d(TAG, "========================================")
        isBackground = false
        cancelExitAlarm()
    }
    
    override fun onStop(owner: LifecycleOwner) {
        if (isExiting) {
            Log.d(TAG, "[生命周期] 正在退出流程，跳过设置闹钟")
            return
        }
        Log.d(TAG, "========================================")
        Log.d(TAG, "[生命周期] 应用进入后台 (onStop)")
        Log.d(TAG, "设置退出闹钟: ${BACKGROUND_TIMEOUT_MS / 1000} 秒后")
        Log.d(TAG, "========================================")
        isBackground = true
        lastBackgroundTime = System.currentTimeMillis()
        scheduleExitAlarm()
    }
    
    private fun scheduleExitAlarm() {
        val context = applicationContext ?: run {
            Log.e(TAG, "context 为空，无法设置闹钟")
            return
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        val triggerTime = System.currentTimeMillis() + BACKGROUND_TIMEOUT_MS
        val pendingIntent = createExitPendingIntent(context)
        
        try {
            // Android 12+: 检查是否有精确闹钟权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!alarmManager.canScheduleExactAlarms()) {
                    // 没有精确闹钟权限，使用非精确闹钟
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    Log.d(TAG, "[闹钟] 无精确闹钟权限，使用非精确闹钟")
                    return
                }
            }
            
            // 三星设备：setAlarmClock 会显示在系统闹钟列表中，改用 setExactAndAllowWhileIdle
            val isSamsung = Build.MANUFACTURER.lowercase().contains("samsung")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && isSamsung) {
                // 三星：避免 setAlarmClock，使用 setExactAndAllowWhileIdle
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                Log.d(TAG, "[闹钟] 三星设备：使用 setExactAndAllowWhileIdle")
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val alarmClockInfo = AlarmManager.AlarmClockInfo(
                    triggerTime,
                    null
                )
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                Log.d(TAG, "[闹钟] 使用 AlarmClock API 设置退出闹钟")
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
            val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            Log.d(TAG, "[闹钟] 退出闹钟已设置，触发时间: ${sdf.format(java.util.Date(triggerTime))}")
        } catch (e: SecurityException) {
            Log.e(TAG, "[闹钟] 设置精确闹钟失败 (SecurityException)，尝试非精确闹钟", e)
            try {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "[闹钟] 非精确闹钟也失败", e2)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[闹钟] 设置退出闹钟失败", e)
        }
    }
    
    private fun cancelExitAlarm() {
        val context = applicationContext ?: return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = createExitPendingIntent(context)
        
        try {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d(TAG, "[闹钟] 退出闹钟已取消")
        } catch (e: Exception) {
            Log.e(TAG, "[闹钟] 取消退出闹钟失败", e)
        }
    }
    
    private fun createExitPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AppExitReceiver::class.java).apply {
            action = AppExitReceiver.ACTION_EXIT_APP
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        return PendingIntent.getBroadcast(context, REQUEST_CODE_EXIT, intent, flags)
    }
    
    fun setExiting(exiting: Boolean) {
        isExiting = exiting
    }
}
