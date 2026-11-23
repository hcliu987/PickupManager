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
    tableName = "deleted_pickup_items",
    indices = [
        Index(value = ["pickupCode", "timestamp"], unique = true)
    ]
)
data class DeletedPickupItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val pickupCode: String,
    
    val timestamp: Long,
    
    val rawSms: String,
    
    val deletedTime: Long = System.currentTimeMillis()
)
