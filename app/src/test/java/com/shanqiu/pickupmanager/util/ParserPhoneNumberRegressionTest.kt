/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2 回归用例：**号码类数字不得被当成取件码**（缺陷 H-01）。
 *
 * 缺陷描述：通用码形（`\d{4,8}`、`\d{2,4}-\d{3,4}`）在**全文**无条件匹配，
 * 而驿站短信几乎必带一个联系电话。于是「咨询 18712345678」里的号码被
 * 切成 `187123`、`45678` 一类的"取件码"入库、上屏、一键复制、一键分享。
 *
 * 这些用例在修复**之前**是红的 —— 保留它们，是为了防止后续有人把
 * 「号码预处理」这段删掉。所有短信内容均为虚构，号码取自预留的
 * 测试号段/示例号，不对应任何真实用户。
 */
class ParserPhoneNumberRegressionTest {

    private fun parse(sms: String) = RuleBasedPickupParser.parse(sms)

    private val phoneHead = "18712345678"      // 11 位，形状合法但非真实在用号
    private val serviceNum = "4001234567"      // 400 服务号

    @Test
    fun `无引导词短信里的手机号不得被切成取件码`() {
        val r = parse("【驿收发】您的包裹已到示例小区快递柜，编号8823，咨询$phoneHead")
        assertTrue("应识别为取件短信", r.isPickupSms)
        assertEquals("只应取到编号 8823，实际=${r.codes}", listOf("8823"), r.codes)
        r.codes.forEach { code ->
            assertFalse("手机号被当成取件码: $code", phoneHead.contains(code.replace("-", "")))
            assertFalse("取件码不应是号码片段: $code", code.length > 8)
        }
    }

    @Test
    fun `400 服务号不得被当成取件码`() {
        val r = parse("【韵达】包裹已到示例小区1号楼驿站，客服热线$serviceNum")
        r.codes.forEach { code ->
            assertFalse("服务号被当成取件码: $code", serviceNum.contains(code.replace("-", "")))
        }
    }

    @Test
    fun `凭手机号取件的短信不应产出任何取件码`() {
        val r = parse("【菜鸟驿站】请凭手机号取件，包裹已到示例小区驿站，联系电话$phoneHead")
        assertTrue(r.isPickupSms)
        assertEquals("凭手机号取件不应有取件码，实际=${r.codes}", emptyList<String>(), r.codes)
        assertTrue("应被标记为无码取件", r.hasNoCode)
    }

    @Test
    fun `日期时间与金额不得被当成取件码`() {
        val r = parse("【丰巢】快件已存入示例小区3号柜，取件码40142，2026-09-20 18:30 前免费，超时收1.50元")
        assertTrue(r.isPickupSms)
        assertTrue("应取到真码 40142，实际=${r.codes}", r.codes.contains("40142"))
        r.codes.forEach { code ->
            assertFalse("日期被当成取件码: $code", code.contains("2026"))
            assertFalse("金额被当成取件码: $code", code.contains("1.50"))
            assertFalse("时刻被当成取件码: $code", code.contains("18:30"))
        }
    }

    @Test
    fun `真码与号码同时存在时只保留真码`() {
        val r = parse("【中通快递】取件码 8-8-1234，详情咨询$phoneHead")
        assertTrue(r.isPickupSms)
        assertEquals(listOf("8-8-1234"), r.codes)
    }

    @Test
    fun `号码预处理不应破坏既有引导词路径`() {
        val r = parse("【兔喜生活】到店输入 24680 或扫码亮灯取件，运单尾号 8812")
        assertTrue(r.isPickupSms)
        assertTrue("输入引导词后的码要保留，实际=${r.codes}", r.codes.contains("24680"))
    }
}
