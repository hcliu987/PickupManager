/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
import com.shanqiu.pickupmanager.data.db.DeletedPickupItemDao
import com.shanqiu.pickupmanager.data.db.PickupDatabase
import com.shanqiu.pickupmanager.data.db.PickupItemDao
import com.shanqiu.pickupmanager.data.model.PickupItem
import com.shanqiu.pickupmanager.service.AddressMatcher
import com.shanqiu.pickupmanager.util.PickupCodeMergePolicy
import com.shanqiu.pickupmanager.util.PickupIngest
import com.shanqiu.pickupmanager.util.privacyRef
import com.shanqiu.pickupmanager.widget.WidgetSyncHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 短信接收器。
 *
 * 解析策略**不在这里**：本类只负责"拼装多段短信 → 交给
 * [com.shanqiu.pickupmanager.util.PickupIngest] → 处理它的产出"。
 * 具体的规则引擎（引导词 → 签名格式 → 通用码形五级优先）与
 * `PickupSmsParserV2` 兜底、取件码归一、地址标签生成，全部收敛在
 * `PickupIngest` 一处，与批量导入、通知监听两条通道共用同一份实现。
 * 改造前本类内嵌了一份平行的解析代码，是"三条通道三种结果"的来源之一（H-04）。
 *
 * 准确率不许写在注释里：没有口径的数字会一代代抄下去。用
 * `RuleBasedPickupParserAccuracyEval` 实测，方法见 README 第 7 节。
 *
 * goAsync() 的使用要求（本类与 `BasePickupWidgetProvider` 都踩过）：
 * `goAsync()` **只能在 onReceive 里取一次**，第二次返回 null。
 * 所以它取在 [onReceive] 顶层，通过计数器与所有异步任务共享同一个
 * `PendingResult`，绝不放进可能被复用/递归的辅助函数里。
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        const val ACTION_NEW_PICKUP = "com.shanqiu.pickupmanager.NEW_PICKUP"
        const val EXTRA_PICKUP_CODE = "pickup_code"
        const val EXTRA_ADDRESS = "address"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                Telephony.Sms.Intents.getMessagesFromIntent(intent)
            } else {
                return
            }

            if (messages.isEmpty()) return

            // AOSP 文档：getMessagesFromIntent() 返回的是"一条长消息被拆成的多个 SmsMessage"，
            // 必须按同一发件人 + 同一时间戳把正文拼回一条再解析。
            // 以前是逐片独立解析：超过 70 字的驿站短信（合并发货、带地址与营业时间的那种）
            // 会把取件码从中间切成两半，入库成一个错的码，或两段各自都不像取件码而整条漏掉。
            // 数组里也可能出现 null 元素（畸形 PDU），一并挡掉 —— 否则下面第一个字段访问就是 NPE。
            val grouped = messages.toList().filterNotNull()
                .groupBy { (it.displayOriginatingAddress ?: "") to it.timestampMillis }
                .map { (key, parts) ->
                    Triple(
                        key.first,
                        parts.joinToString("") { it.messageBody ?: "" },
                        key.second,
                    )
                }

            if (grouped.isEmpty()) return

            // 关键：goAsync() 保持进程存活
            // MIUI 14 / 小米系统上 onReceive 返回后会立即回收进程，
            // 导致 IO 协程被中断，SMS 解析失败
            val pendingResult = goAsync()

            // 用计数器跟踪剩余处理任务
            val remainingCount = AtomicInteger(grouped.size)
            val appContext = context.applicationContext

            for ((senderPhone, messageBody, timestamp) in grouped) {
                if (messageBody.isEmpty()) {
                    if (remainingCount.decrementAndGet() == 0) runCatching { pendingResult.finish() }
                    continue
                }

                Log.d(TAG, "收到短信: 发送者=${privacyRef(senderPhone)}, 内容长度=${messageBody.length}, 分段数=${messages.size}")

                processSms(appContext, senderPhone, messageBody, timestamp, remainingCount, pendingResult)
            }
        }
    }

    private fun processSms(
        context: Context,
        senderPhone: String,
        messageBody: String,
        timestamp: Long,
        remainingCount: AtomicInteger,
        pendingResult: PendingResult
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = PickupDatabase.getDatabase(context)
                val dao = database.pickupItemDao()
                val deletedDao = database.deletedPickupItemDao()

                // R3 / H-04：解析 + 归一 + 打标签统一走 PickupIngest。
                // 改造前这里手写了一份"规则引擎 + V2 回落"，与批量导入、
                // 通知通道各写一遍，三份代码对取件码不做归一、地址标签口径也不同，
                // 于是同一条短信在三条通道下会得到不同结果。
                val outcome = PickupIngest.parse(messageBody, senderPhone, timestamp)

                when {
                    outcome.items.isNotEmpty() -> {
                        Log.d(
                            TAG,
                            "解析成功（${outcome.engine}）：${outcome.items.size} 个取件码"
                        )
                        saveItems(context, dao, deletedDao, outcome.items)
                    }
                    outcome.hasNoCode -> {
                        Log.d(TAG, "收到无取件码的取件短信: ${privacyRef(messageBody)}")
                    }
                    else -> {
                        Log.d(TAG, "未识别为取件短信，跳过处理: ${privacyRef(messageBody)}")
                    }
                }
            } catch (e: Throwable) {
                // 顶层兜底：捕获所有 throwable，避免进程崩溃
                Log.e(TAG, "处理短信时出错", e)
            } finally {
                // 所有短信片段处理完毕后再 finish
                if (remainingCount.decrementAndGet() == 0) {
                    try {
                        pendingResult.finish()
                    } catch (e: Throwable) {
                        Log.w(TAG, "pendingResult.finish 失败（可能已 finish）", e)
                    }
                }
            }
        }
    }

    /**
     * 入库：留档检查 → 地址归一 → 写库 → 记录常用地址 → 刷新小部件 → 广播。
     *
     * R3 的三处变化：
     * 1. 取件码已由 [PickupIngest] 归一，留档表查询（[DeletedPickupItemDao.isDeleted]）
     *    因此能真正命中 —— 原实现存裸码查裸码，用户删掉的记录会原地复活（H-02）。
     * 2. 地址过一个 [AddressMatcher]，与批量导入路径保持一致的地址质量。
     *    原实现只有导入路径做归一，README 第 3 节问题 6 记录了这个不一致（H-04）。
     * 3. 同码不同包裹不再被静默吞掉：去重口径交给 `PickupCodeMergePolicy`
     *    的 6 小时时间窗（H-03）。
     */
    private suspend fun saveItems(
        context: Context,
        dao: PickupItemDao,
        deletedDao: DeletedPickupItemDao,
        items: List<PickupItem>
    ) {
        val matcher = AddressMatcher(dao, PickupDatabase.getDatabase(context).savedAddressDao())
        val savedCodes = mutableListOf<String>()

        for (rawItem in items) {
            try {
                if (deletedDao.isDeleted(rawItem.pickupCode, rawItem.receiveTime)) {
                    Log.d(TAG, "取件码曾被用户删除，跳过: ${privacyRef(rawItem.pickupCode)}")
                    continue
                }

                val matchResult = matcher.matchAndPredictAddress(
                    rawItem.address, rawItem.senderPhone, rawItem.courierCompany
                )
                val item = rawItem.copy(
                    address = matchResult.standardizedAddress,
                    addressTag = matchResult.addressTag
                )

                if (dao.insertOrUpdateItem(item)) {
                    savedCodes.add(item.pickupCode)
                    if (item.addressTag.isNotBlank()) {
                        PickupDatabase.getDatabase(context)
                            .savedAddressDao()
                            .insertOrUpdateAddress(item.addressTag, matchResult.standardizedAddress)
                    }
                } else {
                    Log.d(
                        TAG,
                        "同码记录已在 ${PickupCodeMergePolicy.DUPLICATE_WINDOW_MILLIS / 3600000} 小时窗口内，跳过: ${privacyRef(item.pickupCode)}"
                    )
                }
            } catch (e: Throwable) {
                // 单条失败不影响同一条短信里的其它取件码
                Log.e(TAG, "单条取件码入库失败 ${privacyRef(rawItem.pickupCode)}", e)
            }
        }

        // 通知桌面小部件自动刷新
        WidgetSyncHelper.notifyWidgetDataChanged(context)

        // 发送通知（包含所有成功入库的取件码）
        if (savedCodes.isNotEmpty()) {
            broadcastNewPickup(context, savedCodes, items.firstOrNull()?.address ?: "")
        }
    }

    /**
     * 广播「有新取件码」事件（仅本应用可见，见 setPackage 注释）
     */
    private fun broadcastNewPickup(context: Context, codes: List<String>, address: String) {
        val intent = Intent(ACTION_NEW_PICKUP).apply {
            // 必须限定包名：不带 setPackage 的隐式广播会被设备上任意注册了该 action 的应用收到，
            // 等于把取件码和住址发出去 —— 与本应用「数据不出设备」的前提直接冲突。
            setPackage(context.packageName)
            putExtra(EXTRA_PICKUP_CODE, codes.joinToString(","))
            putExtra(EXTRA_ADDRESS, address)
            putExtra("codes_count", codes.size)
        }
        context.sendBroadcast(intent)
    }
}
