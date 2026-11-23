/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 问题 9 收尾：`PickupSmsParserV2` 是规则引擎失手时的兜底路径，此前零单测。
 *
 * 这里守的是**兜底路径的契约**，不是评分表的具体分数：
 * 1. 空输入 / null 不抛异常，且绝不误判为取件短信；
 * 2. 验证码类短信必须被一票否决（取件码与验证码长得像，是最危险的误判）；
 * 3. PickupResult → PickupItem 的字段映射不丢信息，尤其是 `phoneNumber`
 *    （问题 17：这个字段只有 V2 兜底路径会填，一旦映射断了就彻底没人写）。
 *
 * 所有短信内容均为虚构，不含真实取件码、地址或手机号。
 */
class PickupSmsParserV2Test {

    private fun confirmed(vararg codes: String) = PickupResult(
        isPickupSms = true,
        confidence = "CONFIRMED",
        codes = codes.toMutableList(),
        address = "虚构驿站A区",
        courier = "虚构快递",
        courierPhone = "13800138000",
        trackingTail = "1234",
        senderNumber = "1069000000000",
        rawSms = "【虚构驿站】您的取件码 A2-1-3456"
    )

    // ---------- PickupResult 的判定与取值契约 ----------

    @Test
    fun `只有 CONFIRMED 才算确定取件`() {
        assertTrue(confirmed("A2-1-3456").isPickup())
        val probable = confirmed("A2-1-3456").apply { confidence = "PROBABLE" }
        assertFalse(probable.isPickup())
        assertTrue(probable.isPickupProbable())
    }

    @Test
    fun `非取件标记优先于置信度`() {
        val r = confirmed("A2-1-3456").apply { isPickupSms = false }
        assertFalse(r.isPickup())
        assertFalse(r.isPickupProbable())
    }

    @Test
    fun `主取件码取首个，空列表返回 null`() {
        assertEquals("A2-1-3456", confirmed("A2-1-3456", "B3-2-1001").getPrimaryCode())
        assertNull(confirmed().getPrimaryCode())
        assertEquals("", confirmed().getAllCodesStr())
    }

    @Test
    fun `多取件码按顺序拼接`() {
        assertEquals("A2-1-3456, B3-2-1001", confirmed("A2-1-3456", "B3-2-1001").getAllCodesStr())
    }

    @Test
    fun `取件点合并驿站与柜机`() {
        assertNull(confirmed("A2-1-3456").getPickupPoint())
        val r = confirmed("A2-1-3456").apply {
            stations.add("虚构驿站")
            lockers.add("虚构柜机")
        }
        assertEquals("虚构驿站 / 虚构柜机", r.getPickupPoint())
    }

    // ---------- parse 的边界与一票否决 ----------

    @Test
    fun `null 与空白输入安全返回非取件`() {
        listOf(null, "", "   ", "\n\t ").forEach { input ->
            val r = PickupSmsParserV2.parse(input)
            assertFalse(r.isPickup())
            assertFalse(r.isPickupProbable())
            assertEquals("NOT_PICKUP", r.confidence)
        }
        assertNull(PickupSmsParserV2.parse(null).rawSms)
    }

    @Test
    fun `发件号码原样保留`() {
        val r = PickupSmsParserV2.parse("", "1069000000000")
        assertEquals("1069000000000", r.senderNumber)
    }

    @Test
    fun `验证码短信被一票否决而不是误判为取件`() {
        val r = PickupSmsParserV2.parse("【虚构银行】您的验证码是 88213，5 分钟内有效")
        assertFalse(r.isPickupSms)
        assertEquals("NOT_PICKUP", r.confidence)
        assertNotNull(r.vetoReason)
        assertTrue(r.scoreDetails.containsKey("VETO"))
    }

    @Test
    fun `parseSms 对验证码返回 null，多码版本返回空表`() {
        val sms = "【虚构平台】校验码 556677，请勿转发"
        assertNull(PickupSmsParserV2.parseSms(sms))
        assertTrue(PickupSmsParserV2.parseSmsMultipleCodes(sms).isEmpty())
        assertFalse(PickupSmsParserV2.isPickupSms(sms))
    }

    // ---------- PickupResult → PickupItem 的字段映射 ----------

    @Test
    fun `toPickupItem 完整映射且过期时间为七天`() {
        val receiveTime = 1_700_000_000_000L
        val item = PickupSmsParserV2.toPickupItem(confirmed("A2-1-3456"), receiveTime)
        assertNotNull(item)
        item!!
        assertEquals("A2-1-3456", item.pickupCode)
        assertEquals("虚构驿站A区", item.address)
        assertEquals("虚构快递", item.courierCompany)
        assertEquals("1234", item.trackingNumber)
        assertEquals("【虚构驿站】您的取件码 A2-1-3456", item.rawSms)
        assertEquals("1069000000000", item.senderPhone)
        // 问题 17：快递员电话只有 V2 兜底路径填，映射必须保住
        assertEquals("13800138000", item.phoneNumber)
        assertEquals(receiveTime, item.receiveTime)
        assertEquals(receiveTime + 7L * 24 * 60 * 60 * 1000, item.expireTime)
        assertEquals(PickupItemStatus.PENDING, item.status)
    }

    @Test
    fun `toPickupItems 把多码拆成多条且共享原文与电话`() {
        val items = PickupSmsParserV2.toPickupItems(confirmed("A2-1-3456", "B3-2-1001"))
        assertEquals(2, items.size)
        assertEquals("A2-1-3456", items[0].pickupCode)
        assertEquals("B3-2-1001", items[1].pickupCode)
        items.forEach {
            assertEquals("13800138000", it.phoneNumber)
            assertEquals("1069000000000", it.senderPhone)
        }
    }

    @Test
    fun `非取件结果不产生记录`() {
        val notPickup = confirmed("A2-1-3456").apply { isPickupSms = false }
        assertNull(PickupSmsParserV2.toPickupItem(notPickup))
        assertTrue(PickupSmsParserV2.toPickupItems(notPickup).isEmpty())
    }

    @Test
    fun `缺省字段走兜底值而不是崩掉`() {
        val r = PickupResult(isPickupSms = true, confidence = "PROBABLE")
        val item = PickupSmsParserV2.toPickupItem(r)
        assertNotNull(item)
        item!!
        assertEquals("未知", item.pickupCode)
        assertEquals("未知地址", item.address)
        assertEquals("快递", item.courierCompany)
        assertEquals("", item.trackingNumber)
        assertEquals("", item.rawSms)
        assertEquals("", item.phoneNumber)
    }

    @Test
    fun `地址标签统一转发到 AddressTag`() {
        assertEquals(
            AddressTag.of("虚构驿站A区"),
            PickupSmsParserV2.generateAddressTag("虚构驿站A区")
        )
    }

    private object PickupItemStatus {
        const val PENDING = 0
    }
}
