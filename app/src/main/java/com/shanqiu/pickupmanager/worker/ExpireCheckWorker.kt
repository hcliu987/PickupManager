/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shanqiu.pickupmanager.data.db.PickupDatabase
import com.shanqiu.pickupmanager.data.model.PickupItem
import java.util.Calendar
import java.util.concurrent.TimeUnit

class ExpireCheckWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    
    companion object {
        private const val TAG = "ExpireCheckWorker"
        private const val WORK_NAME = "expire_check_work"
        
        fun schedule(context: Context) {
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 5)
                set(Calendar.SECOND, 0)
                if (before(Calendar.getInstance())) {
                    add(Calendar.DAY_OF_MONTH, 1)
                }
            }
            
            val initialDelay = calendar.timeInMillis - System.currentTimeMillis()
            
            val workRequest = PeriodicWorkRequestBuilder<ExpireCheckWorker>(
                24, TimeUnit.HOURS
            )
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
            
            Log.d(TAG, "已安排每日过期检查任务")
        }
    }
    
    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "开始执行清理任务")

            val database = PickupDatabase.getDatabase(applicationContext)
            val dao = database.pickupItemDao()

            // 不再把到期记录改写成 STATUS_EXPIRED：列表只查询 PENDING / PICKED，
            // 一旦改写，记录会从两个页签同时消失且再也回不来。
            // 过期状态由展示层用 PickupItem.isExpired() 渲染成「已过期」标签，
            // 超过显示天数的记录由 ViewModel 的时间窗过滤负责隐藏。
            val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
            dao.deleteOldPickedItems(thirtyDaysAgo)

            // 留档表只需挡住「同一条短信被重新导入」，30 天后原短信早已不在收件箱热区，
            // 不清理就会随用户每次删除无界增长。
            database.deletedPickupItemDao().deleteOldDeletedItems(thirtyDaysAgo)

            Log.d(TAG, "清理任务完成")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "清理任务失败", e)
            Result.retry()
        }
    }
}
