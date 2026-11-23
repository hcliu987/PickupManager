/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.work.Configuration
import com.shanqiu.pickupmanager.data.db.PickupDatabase
import com.shanqiu.pickupmanager.data.repository.PickupRepository
import com.shanqiu.pickupmanager.util.BackgroundProcessManager
import com.shanqiu.pickupmanager.util.NotificationHelper
import com.shanqiu.pickupmanager.util.RuleBasedPickupParser
import com.shanqiu.pickupmanager.worker.ExpireCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PickupApplication : Application(), Configuration.Provider {
    
    companion object {
        lateinit var instance: PickupApplication
            private set
    }
    
    // 应用级协程作用域，用于预加载等后台任务
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    override fun onCreate() {
        super.onCreate()
        instance = this

        // 问题 15：Manifest 里 :widgetProvider（仅 5×3 小部件）与 :main
        // （DataChangeContentProvider 信标）都是独立进程，Application.onCreate()
        // 会在每个进程里各跑一遍。不加区分的话，规则覆盖表加载、通知渠道创建、
        // 数据库预热（开库 + 全表查询）和 ExpireCheckWorker 排期都会重复执行：
        // 子进程多一次无谓 I/O，同名周期任务还被反复排期。
        // 这两个子进程都不解析短信、不展示 UI，只需能访问 Room，故整体跳过。
        // 取不到进程名时按主进程处理 —— 宁可多跑一次，也不能漏掉主进程初始化。
        if (!isMainProcess()) return
        
        BackgroundProcessManager.init(this)
        
        // 关键修复：加载地址覆盖表（用户纠正的地址映射）
        RuleBasedPickupParser.loadAddressOverrides(this)
        
        NotificationHelper.createNotificationChannels(this)
        
        // 优化: 预热数据库和Repository，提前初始化缓存
        preloadData()
        
        ExpireCheckWorker.schedule(this)
    }
    
    /**
     * 判断当前是否为主进程。
     * API 28+ 直接读 Application.getProcessName()；24~27 从 ActivityManager 按 pid 反查。
     * 返回 null（系统接口取不到进程名）时视为主进程，保证初始化不被误跳过。
     */
    private fun isMainProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val pid = Process.myPid()
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            manager?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        }
        return processName == null || processName == packageName
    }
    
    /**
     * 预加载数据策略:
     * 1. 在Application启动时异步初始化数据库连接
     * 2. 触发Repository的数据流订阅，开始预加载数据
     * 3. 这样当用户进入主界面时，数据已经准备好
     */
    private fun preloadData() {
        applicationScope.launch {
            try {
                // 预热数据库连接
                val database = PickupDatabase.getDatabase(this@PickupApplication)
                
                // 预热Repository，触发数据流订阅
                val repository = PickupRepository.getInstance(this@PickupApplication)
                
                // 触发一次查询来预热数据库缓存
                // 这会让Room建立数据库连接并缓存常用查询
                repository.getAllItemsList()
                
                android.util.Log.d("PickupApplication", "数据预加载完成")
            } catch (e: Exception) {
                android.util.Log.e("PickupApplication", "数据预加载失败", e)
            }
        }
    }
    
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
