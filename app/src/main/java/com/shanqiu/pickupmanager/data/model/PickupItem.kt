/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "pickup_items",
    indices = [
        // R3 / H-03：这里**刻意不加 unique**。
        //
        // 迁移 7→8 曾把索引改成 pickupCode 单一列唯一，理由是"取件码是包裹的物理身份"。
        // 该前提不成立：丰巢 4 位码同一台柜机每天发放上千个、当天就会复用；
        // 驿站按当日序号发码，隔几天出现同一个号是常态。
        // 唯一索引 + OnConflictStrategy.IGNORE 的后果是**静默吞件** ——
        // 新包裹不入库、不通知、列表里看不到，用户完全不知道它存在。
        //
        // 现在去重口径改由 PickupCodeMergePolicy 的时间窗表达：
        // 窗口内同码 = 同一包裹（多通道重复采集）；窗口外同码 = 新包裹。
        // 索引保留为非唯一，只为查询性能服务。
        Index(value = ["pickupCode"]),
        Index(value = ["address"]),
        Index(value = ["status"]),
        Index(value = ["expireTime"])
    ]
)
data class PickupItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val pickupCode: String,
    
    val address: String,
    
    val addressTag: String,
    
    val courierCompany: String = "",
    
    val trackingNumber: String = "",
    
    val receiveTime: Long,
    
    val expireTime: Long,
    
    val status: Int = STATUS_PENDING,
    
    val rawSms: String,
    
    val senderPhone: String = "",
    
    val markedTime: Long = 0,
    
    val restoreTime: Long = 0,
    
    val notes: String = "",
    
    val phoneNumber: String = ""
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_PICKED = 1
        const val STATUS_EXPIRED = 2
    }
    
    fun isExpired(): Boolean {
        return System.currentTimeMillis() > expireTime
    }
    
    fun getRemainingDays(): Int {
        val remaining = expireTime - System.currentTimeMillis()
        return if (remaining > 0) (remaining / (24 * 60 * 60 * 1000)).toInt() + 1 else 0
    }
    
    /**
     * 计算已入库天数（自然日计算）
     * 简化：用毫秒差计算，避免 Calendar 跨年逻辑
     */
    fun getStoredDays(): Int {
        val diff = System.currentTimeMillis() - receiveTime
        return if (diff >= 0) (diff / (24 * 60 * 60 * 1000)).toInt() + 1 else 1
    }
    
    fun getStatusText(): String {
        return when (status) {
            STATUS_PENDING -> if (isExpired()) "已过期" else "待取件"
            STATUS_PICKED -> "已取件"
            STATUS_EXPIRED -> "已过期"
            else -> "未知"
        }
    }
    
    fun getUrgencyLevel(): UrgencyLevel {
        if (status != STATUS_PENDING) return UrgencyLevel.NORMAL
        
        val storedDays = getStoredDays()
        return when {
            storedDays > 6 -> UrgencyLevel.URGENT
            storedDays > 3 -> UrgencyLevel.WARNING
            else -> UrgencyLevel.NORMAL
        }
    }
    
    enum class UrgencyLevel {
        NORMAL,
        WARNING,
        URGENT
    }
}

data class AddressGroup(
    val addressTag: String,
    val count: Int,
    val pendingCount: Int
)
