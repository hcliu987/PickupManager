/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.data.db

import androidx.room.*
import com.shanqiu.pickupmanager.data.model.SavedAddress
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedAddressDao {
    
    @Query("SELECT * FROM saved_addresses ORDER BY sortOrder ASC, createdAt ASC")
    fun getAllAddresses(): Flow<List<SavedAddress>>
    
    @Query("SELECT * FROM saved_addresses ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAllAddressesList(): List<SavedAddress>
    
    @Query("SELECT * FROM saved_addresses WHERE addressTag = :addressTag LIMIT 1")
    suspend fun getAddressByTag(addressTag: String): SavedAddress?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAddress(address: SavedAddress): Long
    
    @Update
    suspend fun updateAddress(address: SavedAddress)
    
    @Delete
    suspend fun deleteAddress(address: SavedAddress)
    
    @Query("DELETE FROM saved_addresses WHERE addressTag = :addressTag")
    suspend fun deleteAddressByTag(addressTag: String)
    
    @Query("SELECT COUNT(*) FROM saved_addresses")
    suspend fun getAddressCount(): Int
    
    @Query("SELECT MAX(sortOrder) FROM saved_addresses")
    suspend fun getMaxSortOrder(): Int?
    
    @Transaction
    suspend fun insertOrUpdateAddress(addressTag: String, displayName: String) {
        val existing = getAddressByTag(addressTag)
        if (existing == null) {
            val maxOrder = getMaxSortOrder() ?: -1
            insertAddress(
                SavedAddress(
                    addressTag = addressTag,
                    displayName = displayName,
                    sortOrder = maxOrder + 1
                )
            )
        } else {
            updateAddress(existing.copy(displayName = displayName, updatedAt = System.currentTimeMillis()))
        }
    }
    
    @Transaction
    suspend fun updateAddressOrders(addresses: List<SavedAddress>) {
        addresses.forEachIndexed { index, address ->
            updateAddress(address.copy(sortOrder = index))
        }
    }
    
    @Query("UPDATE saved_addresses SET customDisplayName = :customName, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateCustomDisplayName(id: Long, customName: String?, updatedAt: Long = System.currentTimeMillis())
    
    @Query("DELETE FROM saved_addresses")
    suspend fun deleteAll()
}
