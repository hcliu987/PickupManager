/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.sms

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.shanqiu.pickupmanager.data.repository.PickupRepository
import com.shanqiu.pickupmanager.util.PickupIngest
import com.shanqiu.pickupmanager.util.privacyRef
import com.shanqiu.pickupmanager.widget.WidgetSyncHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 通知监听服务 —— 短信权限不可用时的备用采集通道。
 *
 * 相比直接收广播，这条路径的优势是不需要成为默认短信应用，也不受厂商
 * 回收 READ_SMS / RECEIVE_SMS 的影响；代价是只能看到通知里露出的文本，
 * 大短信可能被系统截断。
 *
 * 需要用户在系统设置中手动授予「通知使用权」。
 */
class PickupNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "PickupNotificationListener"

        /** 只处理这些短信应用发出的通知 */
        private val SMS_PACKAGES = setOf(
            "com.android.mms",
            "com.android.messaging",
            "com.google.android.apps.messaging",
            "com.miui.mms",
            "com.vivo.message",
            "com.huawei.messaging",
            "com.oneplus.mms",
            "com.realme.mms",
            "com.sonyericsson.conversations",
            // R5 / M-07：补齐 README 品牌适配章节里点名、但此处遗漏的两家。
            // 原来这份白名单里没有三星与 OPPO/一加系 ColorOS 的短信包名，
            // 而 README 恰好声称支持这两家 —— 那两家用户的通知兜底是**哑的**
            // （通知进来了但被白名单挡掉），而用户看到的只是"有些取件码没识别出来"。
            "com.samsung.android.messaging",   // 三星 One UI
            "com.coloros.mms",                  // OPPO / 一加 ColorOS
            "com.oppo.mms",                     // 较旧 ColorOS
        )

        private val PICKUP_KEYWORDS = listOf(
            "取件", "取件码", "快递", "驿站", "丰巢", "包裹",
            "提货码", "取货码", "快递码", "取件号"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName !in SMS_PACKAGES) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

        val fullText = buildString {
            if (title.isNotEmpty()) append(title).append(' ')
            if (text.isNotEmpty()) append(text).append(' ')
            append(bigText)
        }.trim()

        if (fullText.isEmpty()) return
        if (PICKUP_KEYWORDS.none { fullText.contains(it, ignoreCase = true) }) return

        Log.d(TAG, "收到短信通知: ${privacyRef(fullText)}")
        saveParsedSms(fullText, messageTimeOf(sbn))
    }

    /**
     * 取通知上显示的"消息时间"（`Notification.when`），而不是通知被 posted 的时间。
     *
     * 落库与查重用的都是这个值，原因有二：同一条短信的通知被反复刷新时它不变，
     * 于是"用户删过"的留档查询（键：取件码 + 接收时间）才能命中；
     * 而且它与 `SmsReceiver` 从 telephony 拿到的时间戳同源，两条采集通道不会
     * 各存一个 receiveTime 而互相认不出来。
     */
    private fun messageTimeOf(sbn: StatusBarNotification): Long {
        val shown = sbn.notification?.`when` ?: 0L
        return if (shown > 0L) shown else sbn.postTime
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * R3 / H-04：本方法原先**只**调用 `PickupSmsParserV2`，不过
     * `RuleBasedPickupParser`、不做取件码归一、`senderPhone` 传空串，
     * 于是"实时广播 / 事后导入 / 听通知"三条链路对同一条短信可能给出
     * 不同的取件码与不同质量的地址。
     *
     * 现在统一走 [PickupIngest]：与 `SmsReceiver`、`PickupRepository.doImportSms`
     * 完全同一份"解析 + 归一 + 打标签"实现。本通道拿不到发件人号码
     * （通知里没有这个字段），仍传空串，但这一步已不再影响解析结果 ——
     * `senderPhone` 只被存下来供展示，不参与码形判断。
     */
    private fun saveParsedSms(smsText: String, receiveTime: Long) {
        scope.launch {
            try {
                val outcome = PickupIngest.parse(smsText, senderPhone = "", receiveTime = receiveTime)
                if (outcome.items.isEmpty()) {
                    if (outcome.hasNoCode) {
                        Log.d(TAG, "无取件码的取件短信，跳过入库")
                    }
                    return@launch
                }

                val repository = PickupRepository.getInstance(applicationContext)
                val savedCodes = mutableListOf<String>()
                for (item in outcome.items) {
                    // 用户删过的这条不再复活。留档表与查询侧都用归一码（H-02）。
                    if (repository.wasDeleted(item.pickupCode, item.receiveTime)) {
                        Log.d(TAG, "该取件码曾被用户删除，跳过: ${privacyRef(item.pickupCode)}")
                        continue
                    }
                    if (repository.insertItemWithDedup(item) is PickupRepository.InsertResult.Inserted) {
                        savedCodes += item.pickupCode
                    }
                }

                if (savedCodes.isNotEmpty()) {
                    Log.d(TAG, "通知通道入库 ${savedCodes.size} 条: ${savedCodes.size} 个取件码")
                    WidgetSyncHelper.notifyWidgetDataChanged(applicationContext)
                }
            } catch (e: Throwable) {
                // 顶层兜底：通知回调里抛出的异常会直接终止本服务所在进程
                Log.e(TAG, "通知通道处理失败", e)
            }
        }
    }
}
