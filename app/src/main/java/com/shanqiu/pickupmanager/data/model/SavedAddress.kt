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
    tableName = "saved_addresses",
    indices = [
        Index(value = ["addressTag"], unique = true),
        Index(value = ["sortOrder"])
    ]
)
data class SavedAddress(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val addressTag: String,
    
    val displayName: String,
    
    val customDisplayName: String? = null,
    
    val sortOrder: Int = 0,
    
    val createdAt: Long = System.currentTimeMillis(),
    
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun getDisplayText(): String {
        return customDisplayName ?: displayName
    }
}
