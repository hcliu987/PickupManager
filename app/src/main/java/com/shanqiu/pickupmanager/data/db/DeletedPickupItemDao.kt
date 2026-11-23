/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.data.db

import androidx.room.*
import com.shanqiu.pickupmanager.data.model.DeletedPickupItem

@Dao
interface DeletedPickupItemDao {

    // 这张表只服务一件事：防止被用户删掉的同一条短信被重新导入。
    // 曾经还有 getAllDeletedItems / getDeletedItem / deleteDeletedItem 三个读接口，
    // 全仓零调用（没有"已删除列表/撤销删除"的界面），故移除。
    // 若要补那半个功能，再按需加回来。

    // 时间戳按 6 小时容差比对，不做精确相等：三条采集通道用的是三个不同的时钟 ——
    // 广播存的是 SmsMessage.timestampMillis（短信中心时间），导入查的是 Telephony.Sms.DATE
    // （本机接收时间），通知通道取的是 Notification.when。精确相等时 isDeleted 几乎恒为 false，
    // 于是"用户删掉的记录，一次下拉刷新就原地复活"。6 小时远大于真实网络投递延迟，
    // 又远小于取件码被复用的间隔（通常按天/月），不会误伤新包裹。
    @Query("SELECT EXISTS(SELECT 1 FROM deleted_pickup_items WHERE pickupCode = :pickupCode AND ABS(timestamp - :timestamp) <= 21600000)")
    suspend fun isDeleted(pickupCode: String, timestamp: Long): Boolean
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeletedItem(item: DeletedPickupItem)
    
    @Query("DELETE FROM deleted_pickup_items WHERE deletedTime < :beforeTime")
    suspend fun deleteOldDeletedItems(beforeTime: Long)
    
    @Query("DELETE FROM deleted_pickup_items")
    suspend fun deleteAll()
}
