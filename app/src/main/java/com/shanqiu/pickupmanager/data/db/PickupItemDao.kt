/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.data.db

import androidx.room.*
import com.shanqiu.pickupmanager.data.model.PickupItem
import com.shanqiu.pickupmanager.data.model.AddressGroup
import com.shanqiu.pickupmanager.util.PickupCodeMergePolicy
import com.shanqiu.pickupmanager.util.PickupCodeNormalizer
import kotlinx.coroutines.flow.Flow

@Dao
interface PickupItemDao {
    
    @Query("SELECT * FROM pickup_items ORDER BY receiveTime DESC")
    fun getAllItems(): Flow<List<PickupItem>>
    
    @Query("SELECT * FROM pickup_items ORDER BY receiveTime DESC")
    suspend fun getAllItemsList(): List<PickupItem>
    
    @Query("SELECT * FROM pickup_items WHERE status = :status ORDER BY receiveTime DESC")
    fun getItemsByStatus(status: Int): Flow<List<PickupItem>>

    /**
     * 列表专用投影：显式列出字段并把 rawSms 置空。
     *
     * 列表在 ViewModel 里常驻三份完整 PickupItem（待取原始、待取按天数过滤、已取），
     * 每份都带短信全文，记录一多就是三份重复文本。详情弹窗与「复制原文」才需要
     * rawSms，那时按 id 走 [getItemById] 单独取。字段顺序与 [PickupItem] 构造函数一致。
     */
    @Query("""
        SELECT id, pickupCode, address, addressTag, courierCompany, trackingNumber,
               receiveTime, expireTime, status, '' AS rawSms, senderPhone,
               markedTime, restoreTime, notes, phoneNumber
        FROM pickup_items WHERE status = :status ORDER BY receiveTime DESC
    """)
    fun getItemsByStatusLite(status: Int): Flow<List<PickupItem>>

    @Query("SELECT * FROM pickup_items WHERE status = :status ORDER BY receiveTime DESC")
    suspend fun getItemsByStatusList(status: Int): List<PickupItem>
    
    @Query("SELECT * FROM pickup_items WHERE addressTag = :addressTag ORDER BY receiveTime DESC")
    fun getItemsByAddress(addressTag: String): Flow<List<PickupItem>>
    
    @Query("SELECT * FROM pickup_items WHERE addressTag = :addressTag")
    suspend fun getItemsByAddressTag(addressTag: String): List<PickupItem>
    
    @Query("SELECT * FROM pickup_items WHERE status = :status AND addressTag = :addressTag ORDER BY receiveTime DESC")
    fun getItemsByStatusAndAddress(status: Int, addressTag: String): Flow<List<PickupItem>>
    
    @Query("SELECT addressTag, COUNT(*) as count, SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END) as pendingCount FROM pickup_items GROUP BY addressTag ORDER BY count DESC")
    fun getAddressGroups(): Flow<List<AddressGroup>>
    
    @Query("SELECT * FROM pickup_items WHERE pickupCode LIKE :keyword OR address LIKE :keyword OR trackingNumber LIKE :keyword ORDER BY receiveTime DESC")
    fun searchItems(keyword: String): Flow<List<PickupItem>>
    
    @Query("SELECT * FROM pickup_items WHERE receiveTime >= :startTime AND receiveTime <= :endTime ORDER BY receiveTime DESC")
    fun getItemsByTimeRange(startTime: Long, endTime: Long): Flow<List<PickupItem>>

    @Query("SELECT * FROM pickup_items WHERE receiveTime >= :startTime AND receiveTime <= :endTime ORDER BY receiveTime DESC")
    suspend fun getItemsByTimeRangeList(startTime: Long, endTime: Long): List<PickupItem>

    @Query("SELECT * FROM pickup_items WHERE receiveTime >= :startTime AND receiveTime <= :endTime ORDER BY receiveTime DESC")
    fun getItemsByTimeRangeListSync(startTime: Long, endTime: Long): List<PickupItem>
    
    @Query("SELECT * FROM pickup_items WHERE id = :id")
    suspend fun getItemById(id: Long): PickupItem?
    
    @Query("SELECT * FROM pickup_items WHERE pickupCode = :pickupCode AND receiveTime = :receiveTime LIMIT 1")
    suspend fun getItemByPickupCodeAndTime(pickupCode: String, receiveTime: Long): PickupItem?

    /**
     * 关键修复（R3 / H-03）：按 pickupCode **单一键**查重已被替换为**时间窗**查重。
     *
     * 背景：原唯一索引是 (pickupCode, receiveTime)，但 RuleMatchingScreen 等
     * 手动添加路径使用 System.currentTimeMillis() 作为 receiveTime，导致
     * 同一条短信在 SmsReceiver（用真实时间戳）和 RuleMatchingScreen（用当前时间）
     * 两条路径下产生重复记录。
     *
     * 该问题已由 [com.shanqiu.pickupmanager.util.PickupCodeMergePolicy] 解决：
     * 传入的窗口端点由它计算，SQL 只负责比较。
     */
    @Query("SELECT * FROM pickup_items WHERE pickupCode = :pickupCode ORDER BY receiveTime DESC LIMIT 1")
    suspend fun getItemByPickupCode(pickupCode: String): PickupItem?

    /**
     * R3 / H-03：在给定时间窗内找同码记录。
     *
     * 窗口端点必须由 [PickupCodeMergePolicy.windowAround] 算出来再传进来 ——
     * SQL 里不写死毫秒数，保证"什么算同一个包裹"只有一处定义。
     */
    @Query("""
        SELECT * FROM pickup_items
        WHERE pickupCode = :pickupCode AND receiveTime BETWEEN :windowStart AND :windowEnd
        ORDER BY ABS(receiveTime - :pivot) ASC
        LIMIT 1
    """)
    suspend fun getSameParcelInWindow(
        pickupCode: String,
        windowStart: Long,
        windowEnd: Long,
        pivot: Long
    ): PickupItem?

    /**
     * 关键修复：按 pickupCode 覆盖更新（保留原 id 和 receiveTime）
     *
     * 背景：用户在 RuleMatchingScreen 编辑模式下手动确认修改结果，
     * 应当用新数据覆盖原记录，而不是插入新行（避免重复卡片）。
     *
     * 实现：保留原 id、receiveTime、status、markedTime，只更新用户编辑过的字段
     * （address、addressTag、courierCompany、trackingNumber、rawSms、notes）。
     */
    @Query("""
        UPDATE pickup_items
        SET address = :address,
            addressTag = :addressTag,
            courierCompany = :courierCompany,
            trackingNumber = :trackingNumber,
            rawSms = :rawSms,
            notes = :notes
        WHERE pickupCode = :pickupCode
    """)
    suspend fun updateItemByPickupCode(
        pickupCode: String,
        address: String,
        addressTag: String,
        courierCompany: String,
        trackingNumber: String,
        rawSms: String,
        notes: String
    ): Int
    
    /**
     * 关键修复：OnConflictStrategy.IGNORE
     *
     * 背景：原本使用 REPLACE 策略，但当 pickupCode 冲突时 REPLACE 会用新记录
     * 替换原记录（虽然 id 不同），导致 receiveTime 等重要数据被覆盖。
     *
     * 改为 IGNORE 后：
     * - 数据库唯一索引（pickupCode 唯一）触发冲突时，insertItem 返回 -1
     * - 不修改原记录，原记录的 status、address、markedTime 等都保留
     * - Repository 根据返回值判断是否重复
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: PickupItem): Long
    
    @Update
    suspend fun updateItem(item: PickupItem)
    
    @Update
    suspend fun updateItems(items: List<PickupItem>)
    
    @Delete
    suspend fun deleteItem(item: PickupItem)
    
    @Query("UPDATE pickup_items SET status = :status, markedTime = :markedTime WHERE id = :id")
    suspend fun updateStatus(id: Long, status: Int, markedTime: Long = System.currentTimeMillis())

    // 桌面小部件的"已取"由广播驱动，而 id 是自增整数、可被本机任意应用枚举。
    // 加 status = 待取 这个前置条件：伪造的 id 命中不了任何行，调用方据返回行数决定要不要
    // 报"已标记成功"。updateStatus 保持原样，供应用内主动"取件/退回待取"这类可信入口使用。
    @Query("UPDATE pickup_items SET status = :picked, markedTime = :markedTime WHERE id = :id AND status = :pending")
    suspend fun markPickedIfPending(id: Long, picked: Int, pending: Int, markedTime: Long): Int
    
    @Query("UPDATE pickup_items SET status = :status, markedTime = :markedTime, receiveTime = :receiveTime WHERE id = :id")
    suspend fun updateStatusAndReceiveTime(id: Long, status: Int, markedTime: Long = 0, receiveTime: Long = System.currentTimeMillis())
    
    @Query("UPDATE pickup_items SET status = :status, markedTime = :markedTime, receiveTime = :receiveTime, restoreTime = :restoreTime WHERE id = :id")
    suspend fun updateStatusAndReceiveTimeWithRestore(id: Long, status: Int, markedTime: Long = 0, receiveTime: Long = System.currentTimeMillis(), restoreTime: Long = 0)
    
    @Query("SELECT COUNT(*) FROM pickup_items WHERE status = 0")
    fun getPendingCount(): Flow<Int>
    
    @Query("SELECT COUNT(*) FROM pickup_items WHERE status = 0 AND expireTime < :currentTime")
    fun getExpiredCount(currentTime: Long = System.currentTimeMillis()): Flow<Int>
    
    @Query("DELETE FROM pickup_items WHERE status = 1 AND markedTime < :beforeTime")
    suspend fun deleteOldPickedItems(beforeTime: Long)

    /**
     * 7 天保留期兜底：删除入库满 7 天的**所有**记录（含待取）。
     * 用户已确认「没手动清理的话第八天自动删」，因此待取件也在此列。
     * 手动清理与 WorkManager 每天兜底共用这一条 SQL，口径一致。
     */
    @Query("DELETE FROM pickup_items WHERE receiveTime < :beforeTime")
    suspend fun deleteItemsOlderThan(beforeTime: Long)

    @Query("SELECT COUNT(*) FROM pickup_items WHERE receiveTime < :beforeTime")
    suspend fun countItemsOlderThan(beforeTime: Long): Int
    
    @Query("DELETE FROM pickup_items")
    suspend fun deleteAll()
    
    /**
     * 关键修复（R3 / H-02 + H-03）
     *
     * **H-02**：原实现直接用传入的 `item.pickupCode` 去查、去比对留档表，
     * 但同一张码在三条采集通道下可能得到不同字符串（全角/空格/大小写/
     * 不同连字符）。归一化必须发生在**比较之前**，否则整套去重形同虚设。
     * 现统一由 [PickupCodeNormalizer.normalizePickupCode] 在入口处归一。
     *
     * **H-03**：原实现按 `pickupCode` **单一键**去重，隐含前提是
     * "取件码是包裹的物理身份"。该前提不成立 —— 丰巢 4 位码同一天就会复用。
     * 于是同码不同包裹被静默吞掉：不入库、不通知、列表看不到。
     * 现改为 [PickupCodeMergePolicy] 的时间窗判定：窗口内同码 = 同一个包裹
     * （多通道重复采集）；窗口外同码 = 新包裹，正常入库。
     *
     * 数据库侧：`pickupCode` 的唯一索引已在迁移 9→10 中降级为普通索引，
     * 因为唯一索引会把"窗口外同码"这条合法路径也一起挡死。
     */
    @Transaction
    suspend fun insertOrUpdateItem(item: PickupItem): Boolean {
        if (item.pickupCode.isBlank()) {
            android.util.Log.w("PickupItemDao", "拒绝插入空取件码记录（会被唯一索引吞掉后续所有空码）")
            return false
        }
        val normalized = item.pickupCode.let { PickupCodeNormalizer.normalizePickupCode(it) }
        if (normalized.isEmpty()) {
            android.util.Log.w("PickupItemDao", "取件码归一后为空，拒绝插入")
            return false
        }
        val (windowStart, windowEnd) = PickupCodeMergePolicy.windowAround(item.receiveTime)
        val sameParcel = getSameParcelInWindow(normalized, windowStart, windowEnd, item.receiveTime)
        if (sameParcel != null) {
            return false
        }
        return insertItem(item.copy(pickupCode = normalized)) != -1L
    }
}
