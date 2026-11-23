/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.data.repository

import com.shanqiu.pickupmanager.util.privacyRef
import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.shanqiu.pickupmanager.data.db.DeletedPickupItemDao
import com.shanqiu.pickupmanager.data.db.PickupDatabase
import com.shanqiu.pickupmanager.data.db.PickupItemDao
import com.shanqiu.pickupmanager.data.db.SavedAddressDao
import com.shanqiu.pickupmanager.data.model.AddressGroup
import com.shanqiu.pickupmanager.data.model.DeletedPickupItem
import com.shanqiu.pickupmanager.data.model.PickupItem
import com.shanqiu.pickupmanager.data.model.SavedAddress
import com.shanqiu.pickupmanager.service.AddressMatcher
import com.shanqiu.pickupmanager.util.AddressTag
import com.shanqiu.pickupmanager.util.PickupCodeMergePolicy
import com.shanqiu.pickupmanager.util.PickupCodeNormalizer
import com.shanqiu.pickupmanager.util.PickupIngest
import com.shanqiu.pickupmanager.util.PreferenceManager
import com.shanqiu.pickupmanager.util.PickupSmsParserV2
import com.shanqiu.pickupmanager.util.RuleBasedPickupParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class PickupRepository private constructor(context: Context) {
    
    private val dao: PickupItemDao = PickupDatabase.getDatabase(context).pickupItemDao()
    private val deletedDao: DeletedPickupItemDao = PickupDatabase.getDatabase(context).deletedPickupItemDao()
    private val savedAddressDao: SavedAddressDao = PickupDatabase.getDatabase(context).savedAddressDao()
    private val addressMatcher: AddressMatcher = AddressMatcher(dao, savedAddressDao)
    private val appContext = context.applicationContext
    private var isInitialized = false
    
    companion object {
        private const val TAG = "PickupRepository"

        /** 历史记录保留天数：入库满 7 天的记录由手动清理或每天兜底任务删除。 */
        const val RETENTION_DAYS = 7

        /**
         * 本应用写入的全部 SharedPreferences 文件名，与 README 第 4 节
         * 「数据只落在本地」的 7 项名单一一对应。
         * 新增偏好文件时必须同步这里，否则「一键清空」会留下残留数据。
         */
        private val ALL_SHARED_PREFS = listOf(
            "address_overrides",        // 地址纠正覆盖表
            "widget_data_cache",        // 小部件数据快照
            "self_learning_prefs",      // 纠错历史
            "permission_cache",         // 权限缓存
            "permission_prefs",         // 权限向导状态
            "sms_permission_tracker",   // 短信权限状态
            "pickup_manager_prefs"      // 通用偏好与短信导入闸门
        )

        @Volatile
        private var INSTANCE: PickupRepository? = null

        fun getInstance(context: Context): PickupRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = PickupRepository(context)
                INSTANCE = instance
                instance
            }
        }
    }

    suspend fun ensureInitialized() {
        if (!isInitialized) {
            addressMatcher.initialize()
            isInitialized = true
        }
    }
    
    fun getAllItems(): Flow<List<PickupItem>> = dao.getAllItems()
    
    suspend fun getAllItemsList(): List<PickupItem> = dao.getAllItemsList()
    
    fun getItemsByStatus(status: Int): Flow<List<PickupItem>> = dao.getItemsByStatus(status)

    /** 列表专用：不加载 rawSms，详情需要时再走 [getItemById]。 */
    fun getItemsByStatusLite(status: Int): Flow<List<PickupItem>> = dao.getItemsByStatusLite(status)
    
    fun getItemsByAddress(addressTag: String): Flow<List<PickupItem>> = dao.getItemsByAddress(addressTag)
    
    fun getItemsByStatusAndAddress(status: Int, addressTag: String): Flow<List<PickupItem>> = 
        dao.getItemsByStatusAndAddress(status, addressTag)
    
    fun getAddressGroups(): Flow<List<AddressGroup>> = dao.getAddressGroups()
    
    fun searchItems(keyword: String): Flow<List<PickupItem>> = dao.searchItems("%$keyword%")
    
    fun getItemsByTimeRange(startTime: Long, endTime: Long): Flow<List<PickupItem>> = 
        dao.getItemsByTimeRange(startTime, endTime)
    
    fun getPendingCount(): Flow<Int> = dao.getPendingCount()
    
    fun getExpiredCount(): Flow<Int> = dao.getExpiredCount()
    
    suspend fun getItemById(id: Long): PickupItem? = dao.getItemById(id)

    /** 7 天保留期：统计/清理入库满 7 天的记录（含待取）。 */
    suspend fun countItemsOlderThan(beforeTime: Long): Int = dao.countItemsOlderThan(beforeTime)

    suspend fun deleteItemsOlderThan(beforeTime: Long) = dao.deleteItemsOlderThan(beforeTime)
    
    suspend fun insertItem(item: PickupItem): Long = dao.insertItem(item)

    /**
     * 关键修复（R3 / H-02 + H-03）：按**归一化取件码 + 时间窗**去重插入。
     *
     * 改造前的两个问题：
     * 1. 直接拿 `item.pickupCode` 比较。同一张码在三条采集通道下可能是
     *    全角/带空格/大小写不同的字符串，于是"同一条短信入库两行"。
     *    现在比较前一律过 [PickupCodeNormalizer]。
     * 2. 按 pickupCode **单一键**判重，隐含前提是"取件码是包裹的物理身份"。
     *    该前提不成立（丰巢 4 位码当天复用），后果是新包裹被静默吞掉，
     *    不入库、不通知、列表看不到。现在改由 [PickupCodeMergePolicy]
     *    的 6 小时时间窗判定：窗口内同码 = 同一包裹；窗口外同码 = 新包裹。
     *
     * 行为：
     * - 若 6 小时窗口内已存在同（归一）码记录：不插入，返回 [InsertResult.Duplicate]
     * - 否则：插入新记录，返回 [InsertResult.Inserted]
     *
     * 日志刻意打印"窗口外同码"这一分支：它是用户最容易误解的现象
     * （"这个码我刚才取过了，怎么又出现一条"），有日志才能解释。
     */
    suspend fun insertItemWithDedup(item: PickupItem): InsertResult {
        val normalizedCode = PickupCodeNormalizer.normalizePickupCode(item.pickupCode)
        if (normalizedCode.isEmpty()) {
            Log.w(TAG, "取件码归一后为空，拒绝插入")
            return InsertResult.Duplicate(item.copy(id = -1))
        }
        val normalizedItem = item.copy(pickupCode = normalizedCode)

        val (windowStart, windowEnd) = PickupCodeMergePolicy.windowAround(normalizedItem.receiveTime)
        val existing = dao.getSameParcelInWindow(
            normalizedCode, windowStart, windowEnd, normalizedItem.receiveTime
        )
        if (existing != null) {
            Log.d(
                TAG,
                "去重命中：取件码 ${privacyRef(normalizedCode)} 在 ${PickupCodeMergePolicy.DUPLICATE_WINDOW_MILLIS / 3600000}h 窗口内已存在（id=${existing.id}），跳过插入"
            )
            return InsertResult.Duplicate(existing)
        }

        val newId = dao.insertItem(normalizedItem)
        return if (newId == -1L) {
            // 归一索引已降级为非唯一，理论上不该再有 -1；保留兜底以防
            // 调用方绕过 DAO 的 insertOrUpdateItem 走了别的写入路径。
            val dbExisting = dao.getItemByPickupCode(normalizedCode)
            if (dbExisting != null) {
                Log.d(TAG, "去重兜底命中：取件码 ${privacyRef(normalizedCode)} 插入被拒，返回现有记录 id=${dbExisting.id}")
                InsertResult.Duplicate(dbExisting)
            } else {
                Log.w(TAG, "去重兜底异常：插入返回 -1 但查询为空，取件码 ${privacyRef(normalizedCode)}")
                InsertResult.Duplicate(normalizedItem.copy(id = -1))
            }
        } else {
            // 窗口外同码会走到这里并成功入库 —— 这是**预期行为**，不是重复。
            Log.d(TAG, "去重通过：取件码 ${privacyRef(normalizedCode)} 新建 id=$newId")
            InsertResult.Inserted(newId)
        }
    }

    sealed class InsertResult {
        data class Inserted(val newId: Long) : InsertResult()
        data class Duplicate(val existing: PickupItem) : InsertResult()
    }

    /**
     * 关键修复：按 pickupCode 覆盖更新
     *
     * 背景：用户在 RuleMatchingScreen 编辑模式下手动确认修改结果，
     * 应当用新数据覆盖原记录（保留原 id、receiveTime、status 等），
     * 而不是插入新行（避免重复卡片）。
     *
     * 行为：
     * - 根据 pickupCode 找到现有记录
     * - 更新用户编辑过的字段：address、addressTag、courierCompany、
     *   trackingNumber、rawSms、notes
     * - 保留：id、receiveTime、status、markedTime、senderPhone
     * - 返回是否成功更新
     */
    suspend fun overwriteItemByPickupCode(
        pickupCode: String,
        address: String,
        addressTag: String,
        courierCompany: String,
        trackingNumber: String,
        rawSms: String,
        notes: String
    ): Boolean {
        // R3 / H-02：UPDATE 的 WHERE 也是比较，同样必须归一，
        // 否则用户在编辑界面点"保存"会静默改不到任何行（rowsUpdated = 0）。
        val rowsUpdated = dao.updateItemByPickupCode(
            PickupCodeNormalizer.normalizePickupCode(pickupCode), address, addressTag,
            courierCompany, trackingNumber, rawSms, notes
        )
        return rowsUpdated > 0
    }
    
    suspend fun updateItem(item: PickupItem) = dao.updateItem(item)
    
    /**
     * 删除即「不再提醒这条短信」，所以只留 (归一化取件码, receiveTime) 这个查重键。
     *
     * 曾经这里会把整条短信原文复制进留档表，而全应用没有任何地方读它 ——
     * 等于用户删掉的短信一直留在设备上。故写空串。
     *
     * R3 / H-02：**必须存归一化后的码**。留档表的用途就是"下次同一条短信
     * 再出现时认得出来"，而传入的 [item] 可能来自任意通道；只要有一处存裸码、
     * 一处查归一码，`isDeleted` 就恒为 false，用户删掉的记录会原地复活。
     */
    suspend fun deleteItem(item: PickupItem) {
        val deletedItem = DeletedPickupItem(
            pickupCode = PickupCodeNormalizer.normalizePickupCode(item.pickupCode),
            timestamp = item.receiveTime,
            rawSms = "",
            deletedTime = System.currentTimeMillis()
        )
        deletedDao.insertDeletedItem(deletedItem)
        dao.deleteItem(item)
    }
    
    suspend fun updateStatus(id: Long, status: Int) = dao.updateStatus(id, status)

    /**
     * 这条短信是否被用户删过（键：**归一化**取件码 + 接收时间 ± 6 小时）。
     *
     * 实时采集路径必须查它，否则"用户删掉 → 同一条短信的通知再次弹出 → 记录复活"。
     * 只对自动路径有意义：用户主动新增不该被它挡住，所以暴露给调用方，
     * 而不是塞进 insertItemWithDedup 里对所有来源生效。
     *
     * R3 / H-02：入参先归一。留档表里存的是归一码，查询侧若不归一，
     * 通知通道那种"全角/带空格"的解析结果永远查不到留档，于是复活。
     */
    suspend fun wasDeleted(pickupCode: String, receiveTime: Long): Boolean =
        deletedDao.isDeleted(PickupCodeNormalizer.normalizePickupCode(pickupCode), receiveTime)
    
    suspend fun markAsPicked(id: Long) {
        dao.updateStatus(id, PickupItem.STATUS_PICKED, System.currentTimeMillis())
    }
    
    suspend fun markAsPending(id: Long) {
        // 恢复时保留原始 receiveTime，只更新 restoreTime 为当前时间
        val item = dao.getItemById(id)
        val originalReceiveTime = item?.receiveTime ?: System.currentTimeMillis()
        val currentTime = System.currentTimeMillis()
        dao.updateStatusAndReceiveTimeWithRestore(
            id,
            PickupItem.STATUS_PENDING,
            0,
            originalReceiveTime,
            currentTime
        )
    }
    
    /**
     * 问题 21：一键清空全部数据。
     *
     * 改造前这里只转发 `dao.deleteAll()`：另外两张表（已删留档、保存的地址）
     * 与 7 个 SharedPreferences 文件全都留下，且清空能力到 Repository 就断了，
     * 全仓没有任何调用点 —— README 里"清除数据"的承诺只能靠系统设置兑现。
     *
     * 两个容易漏掉的点：
     * 1. 清空偏好后必须把 `sms_imported` 闸门重新写回 true。
     *    `autoImportSms()` 以它为闸门，若一起清掉，下次 ViewModel 创建就会
     *    把整本短信收件箱重新灌回来，用户刚清掉的数据立刻"复活"。
     *    想重新导入，应走菜单里的「导入短信」这一显式入口。
     * 2. 只清磁盘不够：`RuleBasedPickupParser` 的地址覆盖表与
     *    `AddressMatcher` 的地址缓存都在内存里，不复位会继续生效到进程结束。
     *
     * 用 commit() 而非 apply()：清空必须真正落盘后才向 UI 报告完成。
     */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dao.deleteAll()
        savedAddressDao.deleteAll()
        deletedDao.deleteAll()

        ALL_SHARED_PREFS.forEach { name ->
            appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
        PreferenceManager.setSmsImported(appContext, true)

        // 内存态一起复位；loadAddressOverrides 在文件已清空后会载入空表。
        RuleBasedPickupParser.loadAddressOverrides(appContext)
        addressMatcher.refreshCache()
        isInitialized = false

        com.shanqiu.pickupmanager.widget.WidgetSyncHelper.notifyWidgetDataChanged(appContext)
        Log.i(TAG, "一键清空完成：3 张表 + ${ALL_SHARED_PREFS.size} 个偏好文件")
    }
    
    fun getSavedAddresses(): Flow<List<SavedAddress>> = savedAddressDao.getAllAddresses()
    
    suspend fun getSavedAddressesList(): List<SavedAddress> = savedAddressDao.getAllAddressesList()
    
    suspend fun saveAddress(addressTag: String, displayName: String) {
        savedAddressDao.insertOrUpdateAddress(addressTag, displayName)
    }
    
    suspend fun deleteSavedAddress(address: SavedAddress) {
        savedAddressDao.deleteAddress(address)
    }
    
    suspend fun updateAddressOrder(addresses: List<SavedAddress>) {
        savedAddressDao.updateAddressOrders(addresses)
    }
    
    suspend fun updateAddressCustomName(addressId: Long, customName: String?) {
        savedAddressDao.updateCustomDisplayName(addressId, customName)
    }
    
    /**
     * 全量扫描短信库并入库。
     *
     * 必须在 IO 线程：三个调用点都是 `viewModelScope.launch { … }`，而 viewModelScope
     * 的默认调度是 Main.immediate —— 这里面的 contentResolver 全表游标、每条短信的正则解析、
     * 每条一次地址缓存查询，过去全都跑在 UI 线程上（收件箱几千条时首次启动必然冻住）。
     */
    suspend fun importSmsFromDevice(defaultDisplayDays: Int = 7): Int = withContext(Dispatchers.IO) {
        // 在导入完成后通知小部件
        try {
            val result = doImportSms(defaultDisplayDays)
            // 通知小部件刷新
            com.shanqiu.pickupmanager.widget.WidgetSyncHelper.notifyWidgetDataChanged(appContext)
            result
        } catch (e: SecurityException) {
            // R5 / M-04②：没有 READ_SMS 时 contentResolver.query() 抛的就是它。
            // 单独一条分支是为了让日志能一眼分辨「没权限」与「解析失败」——
            // 改前两者都落到下面那条 catch、返回值同为 0，调用方无从区分，
            // 界面因此只能说「没有新的取件记录」。
            Log.e("PickupRepository", "导入短信失败：缺少 READ_SMS 权限（不是「没有新短信」）", e)
            0
        } catch (e: Exception) {
            Log.e("PickupRepository", "导入短信失败", e)
            0
        }
    }

    private suspend fun doImportSms(defaultDisplayDays: Int = 7): Int {
        ensureInitialized()
        var importedCount = 0

        try {
            val contentResolver = appContext.contentResolver
            val cursor = contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE
                ),
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )

            val currentTime = System.currentTimeMillis()
            val thresholdTime = currentTime - (defaultDisplayDays.toLong() * 24 * 60 * 60 * 1000)

            cursor?.use {
                val addressIndex = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIndex = it.getColumnIndex(Telephony.Sms.BODY)
                val dateIndex = it.getColumnIndex(Telephony.Sms.DATE)

                while (it.moveToNext()) {
                    try {
                        // 关键：每条短信的处理用独立 try-catch 包裹
                        // 防止某条短信解析失败导致整个导入崩溃
                        val senderPhone = it.getString(addressIndex) ?: ""
                        val body = it.getString(bodyIndex) ?: ""
                        val timestamp = it.getLong(dateIndex)

                        // R3 / H-04：与 SmsReceiver、通知通道共用同一个入口。
                        // 改造前这里是手写的一份"规则引擎 + V2"平行业本，与 SmsReceiver
                        // 各写一遍；两份代码对取件码不做归一、地址标签的口径也不同，
                        // 于是同一条短信在"实时收到"和"事后导入"下结果不一样。
                        val outcome = PickupIngest.parse(body, senderPhone, timestamp)
                        val ruleParsedItems = outcome.items

                        if (ruleParsedItems.isNotEmpty()) {
                            for (parsedItem in ruleParsedItems) {
                                try {
                                    val matchResult = addressMatcher.matchAndPredictAddress(
                                        parsedItem.address,
                                        senderPhone,
                                        parsedItem.courierCompany
                                    )

                                    val enhancedItem = parsedItem.copy(
                                        address = matchResult.standardizedAddress,
                                        addressTag = matchResult.addressTag
                                    )

                                    Log.d("PickupRepository", "[规则引擎] 解析到取件码: ${enhancedItem.pickupCode}, 时间: $timestamp, 原地址: ${privacyRef(parsedItem.address)}, 匹配地址: ${privacyRef(matchResult.standardizedAddress)}, 置信度: ${matchResult.confidence}, 来源: ${matchResult.source}")

                                    val isDeleted = deletedDao.isDeleted(enhancedItem.pickupCode, enhancedItem.receiveTime)

                                    if (!isDeleted) {
                                        val item = if (timestamp < thresholdTime) {
                                            enhancedItem.copy(status = PickupItem.STATUS_PICKED, markedTime = timestamp + (24 * 60 * 60 * 1000))
                                        } else {
                                            enhancedItem
                                        }
                                        val inserted = dao.insertOrUpdateItem(item)
                                        if (inserted) {
                                            importedCount++
                                            if (enhancedItem.addressTag.isNotBlank()) {
                                                savedAddressDao.insertOrUpdateAddress(
                                                    enhancedItem.addressTag,
                                                    matchResult.standardizedAddress
                                                )
                                            }
                                        }
                                    }
                                } catch (e: Throwable) {
                                    // 单条取件码处理失败不影响整体
                                    Log.e("PickupRepository", "处理单条取件码失败 ${privacyRef(parsedItem.pickupCode)}", e)
                                }
                            }
                            // PickupIngest 已包含"规则引擎 → V2 兜底"的完整回落，
                            // 这里处理完直接进入下一条短信。
                            continue
                        }

                        // R3 / H-04：这里原有一段**独立的 V2 平行业本**（约 45 行），
                        // 与 PickupIngest 内部的兜底分支完全重复。PickupIngest 已经
                        // 跑过 V2 且返回空，此段永远不会产出记录 —— 是一段死代码，
                        // 而且它曾经是"三条通道三种结果"的第三个来源。已删除。
                        Log.d("PickupRepository", "短信未被识别为取件短信: ${privacyRef(body)}")
                    } catch (e: Throwable) {
                        // 单条短信读取失败不影响整体
                        Log.e("PickupRepository", "读取单条短信失败", e)
                    }
                }
            }

            Log.d("PickupRepository", "导入短信完成，共导入 $importedCount 条取件信息")
        } catch (e: Throwable) {
            // 兜底：捕获所有 throwable（包括 native Error），避免进程崩溃
            Log.e("PickupRepository", "导入短信失败", e)
        }

        return importedCount
    }
    
    suspend fun updateItemAddressAndSave(
        itemId: Long,
        newAddress: String,
        senderPhone: String = "",
        courierCompany: String = ""
    ): Boolean {
        return try {
            val item = dao.getItemById(itemId) ?: return false
            
            val oldAddressTag = item.addressTag
            val addressTag = AddressTag.of(newAddress)
            
            // 更新当前物品
            val updatedItem = item.copy(
                address = newAddress.trim(),
                addressTag = addressTag
            )
            dao.updateItem(updatedItem)
            
            // 关键修复：如果地址标签发生变化，更新所有具有旧标签的物品
            if (oldAddressTag != addressTag && oldAddressTag.isNotBlank()) {
                val itemsWithOldTag = dao.getItemsByAddressTag(oldAddressTag)
                val updatedItems = itemsWithOldTag.map { it.copy(address = newAddress.trim(), addressTag = addressTag) }
                if (updatedItems.isNotEmpty()) {
                    dao.updateItems(updatedItems)
                    Log.d("PickupRepository", "同步更新 ${updatedItems.size} 个同址物品: 旧标签=${privacyRef(oldAddressTag)}, 新标签=${privacyRef(addressTag)}, 新地址=${privacyRef(newAddress)}")
                }
            }
            
            // 更新保存的地址表
            if (addressTag.isNotBlank() && addressTag != "其他") {
                savedAddressDao.insertOrUpdateAddress(addressTag, newAddress.trim())
                
                // 如果标签变化，删除旧标签
                if (oldAddressTag != addressTag && oldAddressTag.isNotBlank() && oldAddressTag != "其他") {
                    savedAddressDao.deleteAddressByTag(oldAddressTag)
                    Log.d("PickupRepository", "删除旧地址标签: ${privacyRef(oldAddressTag)}")
                }
                
                Log.d("PickupRepository", "用户修正地址已保存: item=${item.pickupCode}, 原地址=${privacyRef(item.address)}, 新地址=${privacyRef(newAddress)}, 地址标签=${privacyRef(addressTag)}")
            } else {
                Log.d("PickupRepository", "用户修正地址已保存(无标签): item=${item.pickupCode}, 原地址=${privacyRef(item.address)}, 新地址=${privacyRef(newAddress)}")
            }
            
            addressMatcher.refreshCache()
            
            true
        } catch (e: Exception) {
            Log.e("PickupRepository", "❌ 更新地址失败", e)
            false
        }
    }
    
    /**
     * 更新所有相同地址标签的物品地址
     * 用于路径页面统一更新地址
     */
    suspend fun updateAllItemsAddressByTag(
        addressTag: String,
        newAddress: String
    ): Boolean {
        return try {
            val items = dao.getItemsByAddressTag(addressTag)
            val updatedItems = items.map { it.copy(address = newAddress.trim()) }
            if (updatedItems.isNotEmpty()) {
                dao.updateItems(updatedItems)
                Log.d("PickupRepository", "批量更新 ${updatedItems.size} 个物品地址: 标签=${privacyRef(addressTag)}, 新地址=${privacyRef(newAddress)}")
            }
            true
        } catch (e: Exception) {
            Log.e("PickupRepository", "❌ 批量更新地址失败", e)
            false
        }
    }
    
    /**
     * R3 / H-13：原 `generateSimpleAddressTag()`（楼号 → 前 8 字）已删除。
     *
     * 它是"同一个地址标签有四套不兼容实现"里的第二套。`addressTag` 是
     * 取件路径排序与 `saved_addresses` 的关联键，两套规则意味着用户
     * 手动改过的地址永远匹配不上已保存的驿站。现统一走 [AddressTag.of]。
     */
}
