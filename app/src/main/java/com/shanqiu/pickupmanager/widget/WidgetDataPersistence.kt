/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.widget

import android.content.Context
import android.util.Log
import com.shanqiu.pickupmanager.data.model.PickupItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * 小部件数据持久化器
 *
 * 关键设计：
 * 1. 每次 RemoteViewsFactory 加载数据后，序列化保存到 SharedPreferences
 * 2. 当 :widgetProvider 进程被系统回收后，Factory onCreate() 时从 SP 恢复
 * 3. 即使 Room 查询失败或进程冷启动，也能保证 widget 不显示空白
 * 4. 持久化是只读快照，不影响数据库的真实性
 *
 * 数据流：
 *   onDataSetChanged() -> loadData() -> saveToDisk()
 *                                |
 *                                v
 *   onCreate() -> restoreFromDisk() -> pickupItems
 */
object WidgetDataPersistence {

    private const val TAG = "WidgetDataPersist"
    private const val PREF_NAME = "widget_data_cache"
    private const val KEY_DATA = "pending_items_snapshot"
    private const val KEY_TIMESTAMP = "snapshot_timestamp"
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    private const val MAX_ITEMS = 50

    fun saveToDisk(context: Context, items: List<PickupItem>) {
        try {
            val jsonArray = JSONArray()
            val limit = items.take(MAX_ITEMS)
            for (item in limit) {
                jsonArray.put(itemToJson(item))
            }
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_DATA, jsonArray.toString())
                .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                .apply()
            Log.d(TAG, "已持久化 ${jsonArray.length()} 条数据到磁盘")
        } catch (e: Exception) {
            Log.e(TAG, "持久化失败: ${e.message}", e)
        }
    }

    fun restoreFromDisk(context: Context): List<PickupItem> {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_DATA, null) ?: return emptyList()
            val timestamp = prefs.getLong(KEY_TIMESTAMP, 0L)
            val age = System.currentTimeMillis() - timestamp
            if (age > MAX_AGE_MS) {
                Log.d(TAG, "快照已过期 ${age / 1000}秒，清空")
                clear(context)
                return emptyList()
            }
            val jsonArray = JSONArray(jsonStr)
            val items = mutableListOf<PickupItem>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                items.add(jsonToItem(obj))
            }
            Log.d(TAG, "从磁盘恢复 ${items.size} 条数据（${age / 1000}秒前）")
            return items
        } catch (e: Exception) {
            Log.e(TAG, "恢复失败: ${e.message}", e)
            return emptyList()
        }
    }

    fun clear(context: Context) {
        try {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_DATA)
                .remove(KEY_TIMESTAMP)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "清除失败: ${e.message}", e)
        }
    }

    private fun itemToJson(item: PickupItem): JSONObject {
        return JSONObject().apply {
            put("id", item.id)
            put("pickupCode", item.pickupCode)
            put("address", item.address)
            put("addressTag", item.addressTag)
            put("courierCompany", item.courierCompany)
            put("trackingNumber", item.trackingNumber)
            put("receiveTime", item.receiveTime)
            put("expireTime", item.expireTime)
            put("status", item.status)
            // 有意不写入 rawSms / senderPhone：这份快照会以明文 JSON 落在 SharedPreferences 里，
            // 而小部件只需要取件码、地址与公司名。少存一份，就多留一处可被提取的短信原文副本。
            put("phoneNumber", item.phoneNumber)
            put("markedTime", item.markedTime)
            put("restoreTime", item.restoreTime)
            put("notes", item.notes)
        }
    }

    private fun jsonToItem(obj: JSONObject): PickupItem {
        return PickupItem(
            id = obj.optLong("id"),
            pickupCode = obj.optString("pickupCode"),
            address = obj.optString("address"),
            addressTag = obj.optString("addressTag"),
            courierCompany = obj.optString("courierCompany"),
            trackingNumber = obj.optString("trackingNumber"),
            receiveTime = obj.optLong("receiveTime"),
            expireTime = obj.optLong("expireTime"),
            status = obj.optInt("status"),
            rawSms = "",
            senderPhone = "",
            phoneNumber = obj.optString("phoneNumber"),
            markedTime = obj.optLong("markedTime"),
            restoreTime = obj.optLong("restoreTime"),
            notes = obj.optString("notes")
        )
    }
}
