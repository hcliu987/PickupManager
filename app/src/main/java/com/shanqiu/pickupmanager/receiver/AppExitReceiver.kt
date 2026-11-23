/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.receiver

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import androidx.work.WorkManager

class AppExitReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "AppExitReceiver"
        const val ACTION_EXIT_APP = "com.shanqiu.pickupmanager.EXIT_APP"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "收到退出广播！Action: ${intent.action}")
        Log.d(TAG, "当前进程 PID: ${Process.myPid()}")
        Log.d(TAG, "========================================")
        
        if (intent.action == ACTION_EXIT_APP) {
            terminateApp(context)
        }
    }
    
    private fun terminateApp(context: Context) {
        try {
            Log.d(TAG, "步骤1: 取消所有 WorkManager 任务")
            try {
                WorkManager.getInstance(context).cancelAllWork()
                Log.d(TAG, "WorkManager 任务已取消")
            } catch (e: Exception) {
                Log.e(TAG, "取消 WorkManager 任务失败", e)
            }
            
            Log.d(TAG, "步骤2: 清除应用任务栈")
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val appTasks = activityManager.appTasks
            Log.d(TAG, "当前任务数量: ${appTasks.size}")
            for (task in appTasks) {
                try {
                    Log.d(TAG, "正在清除任务: ${task.taskInfo.taskId}")
                    task.finishAndRemoveTask()
                } catch (e: Exception) {
                    Log.e(TAG, "清除任务失败", e)
                }
            }
            
            Log.d(TAG, "步骤3: 终止进程 PID=${Process.myPid()}")
            Process.killProcess(Process.myPid())
            System.exit(0)
            
        } catch (e: Exception) {
            Log.e(TAG, "进程终止失败，尝试强制退出", e)
            Process.killProcess(Process.myPid())
        }
    }
}
