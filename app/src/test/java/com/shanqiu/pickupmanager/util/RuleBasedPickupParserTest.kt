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
 * 现役主解析器 RuleBasedPickupParser 的行为基线测试。
 *
 * 所有短信内容均为虚构：小区名、驿站名、取件码、运单号不对应任何真实数据。
 *
 * 这些用例的首要目的是「锁定现有行为」，而不是宣称行为正确。
 * 若某个断言与你直觉中的「应该是什么」不符，先去看 README 第 3 节「已知问题清单与修复状态」，
 * 再决定是否连同测试一起修改 —— 静默改变解析结果是这个项目最危险的退步。
 */
class RuleBasedPickupParserTest {

    private fun parse(sms: String) = RuleBasedPickupParser.parse(sms)

    // ---------- 识别范围 ----------

    @Test
    fun `非快递短信不应被识别`() {
        listOf(
            "您的验证码是 839201，5 分钟内有效。",
            "您有一笔 1200.00 元交易到账，详情请查账户余额。",
            "双十一大促，全场五折点击领取红包",
            "您预订的航班 CA1234 已出票",
            "今日体检报告已出，请预约挂号复诊"
        ).forEach { sms ->
            val r = parse(sms)
            assertFalse("不应识别为取件短信: $sms", r.isPickupSms)
            assertTrue(r.codes.isEmpty())
        }
    }

    @Test
    fun `有快递词但无取件动作词不算取件短信`() {
        val r = parse("【中通快递】您的包裹已发货，请耐心等待物流更新。")
        assertFalse(r.isPickupSms)
    }

    // ---------- 取件码提取 ----------

    @Test
    fun `菜鸟驿站 带杠取件码`() {
        val r = parse("【菜鸟驿站】您的包裹已到达示例小区菜鸟驿站，取件码 3-1-2048，请及时领取")
        assertTrue(r.isPickupSms)
        assertEquals(listOf("3-1-2048"), r.codes)
    }

    @Test
    fun `丰巢柜 数字取件码`() {
        val r = parse("【丰巢】您在示例小区的快件已存入 3 号柜，取件码 40142，免费存放 18 小时")
        assertTrue(r.isPickupSms)
        assertTrue("应取到取件码，实际=${r.codes}", r.codes.contains("40142"))
    }

    @Test
    fun `引导词后的短码不应被运单尾号过滤误删`() {
        val r = parse("【兔喜生活】到店输入 24680 或扫码亮灯取件，运单尾号 8812")
        assertTrue(r.isPickupSms)
        assertTrue("输入引导词后的码要保留，实际=${r.codes}", r.codes.contains("24680"))
    }

    @Test
    fun `多个包裹的多个取件码`() {
        val r = parse("【韵达快递】您有2个包裹已到示例小区驿站，取件码分别为1-2-3456、7-8-9012")
        assertTrue(r.isPickupSms)
        assertEquals(2, r.codes.size)
        assertEquals(2, r.packageCount)
    }

    @Test
    fun `字母数字混合取件码`() {
        val r = parse("【极兔速递】请凭取件码 C12-3-8890 到幸福一路驿站取件")
        assertTrue(r.isPickupSms)
        assertTrue("应识别字母数字码，实际=${r.codes}", r.codes.isNotEmpty())
    }

    // ---------- 快递公司 ----------

    @Test
    fun `从签名中提取快递公司`() {
        val r = parse("【圆通速递】您的包裹已到站，取件码 6-2-1188，地址示例小区3号楼驿站")
        assertTrue(r.company.isNotEmpty())
        assertTrue("实际=${r.company}", r.company.any { it.contains("圆通") })
    }

    // ---------- 地址 ----------

    @Test
    fun `地址应被提取且不含运单尾号尾巴`() {
        val r = parse("【中通快递】取件码 4-5-6789，请到示例小区1号楼1单元101取运单尾号 3321")
        assertTrue(r.isPickupSms)
        assertTrue("应提取到地址，实际=${r.address}", r.address.isNotEmpty())
        r.address.forEach { addr ->
            assertFalse("地址不应带运单尾号尾巴: $addr", addr.contains("运单尾号"))
        }
    }

    @Test
    fun `取件码不应被当成地址`() {
        val r = parse("【菜鸟驿站】取件码 9-9-9999，地址示例小区菜鸟驿站")
        r.address.forEach { addr ->
            assertFalse("地址里不应混进取件码: $addr", addr.contains("9-9-9999"))
        }
    }

    @Test
    fun `方位距离型地址不应整条丢失`() {
        // 「地标 + 方位 + 距离 + 方位」是真实驿站常见写法，但不以地址后缀收尾，
        // 曾经会让取件码正常入库、地址字段却是空的。
        val r = parse("【虚构速递】包裹已到虚构地标北30米路东，凭 5-2-7788 取货")
        assertTrue(r.isPickupSms)
        assertTrue("应提取到方位距离型地址，实际=${r.address}", r.address.isNotEmpty())
        assertTrue(
            "地址应保留完整方位描述，实际=${r.address}",
            r.address.any { it.contains("虚构地标") && it.endsWith("路东") }
        )
        r.address.forEach { addr ->
            assertFalse("地址不应吞掉后半句: $addr", addr.contains("凭") || addr.contains("取货"))
        }
    }

    @Test
    fun `方位词后跟公司名不应把地址截断`() {
        val r = parse("【虚构速递】取货码 5-2-7788，包裹已到虚构地标东100米路南圆通")
        assertTrue(r.isPickupSms)
        assertTrue(
            "方位词后跟公司名时地址不该截在「路」字上，实际=${r.address}",
            r.address.any { it.contains("路南") }
        )
        r.address.forEach { addr ->
            assertFalse("地址里不应残留公司名: $addr", addr.contains("圆通"))
        }
    }

    @Test
    fun `客服尾句不应被当成取件地址`() {
        val r = parse(
            "【虚构速递】您有快递已到虚构地标北50米路东五金商铺，" +
                "取件码 6-3-8899，详询代收驿站"
        )
        assertTrue(r.isPickupSms)
        assertTrue(
            "应取到真实地址而不是客服话术，实际=${r.address}",
            r.address.any { it.contains("五金商铺") }
        )
        r.address.forEach { addr ->
            assertFalse("「详询代收驿站」不是地址: $addr", addr.contains("详询"))
        }
    }

    @Test
    fun `公司名剔除后不应留下重复后缀字`() {
        val r = parse("【虚构驿站】您有包裹已到达虚构镇中通快递5号柜兔喜快递柜，取件码为 40142")
        assertTrue(r.isPickupSms)
        r.address.forEach { addr ->
            assertFalse("地址不应出现「柜柜」: $addr", addr.contains("柜柜"))
        }
    }

    @Test
    fun `凭码到落脚点型地址不应整条丢失`() {
        // 「请凭 X 到 <邮政局/超市/小屋>，…… 领取」里落脚点不以旧后缀表的
        // 「号楼/路/柜/号」收尾，曾导致取件码和快递公司都入库、地址却是空的。
        val r = parse(
            "【虚构邮局】您的虚构快递*8127已送达，请凭2-4-2956到虚构镇虚构邮政局，" +
                "虚构邮政局取款机旁边小屋领取，详见购物平台"
        )
        assertTrue(r.isPickupSms)
        assertTrue("应提取到「凭码到」型地址，实际=${r.address}", r.address.isNotEmpty())
        assertTrue(
            "地址应指向第一个落脚点，实际=${r.address}",
            r.address.any { it == "虚构镇虚构邮政局" }
        )
        r.address.forEach { addr ->
            assertFalse("不应吃进后半句多地点: $addr", addr.contains("取款机") || addr.contains("小屋"))
            assertFalse("不应吃进客服尾句: $addr", addr.contains("详见") || addr.contains("购物平台"))
        }
    }

    @Test
    fun `邮政局是地名不应被公司词清洗剔残`() {
        // 「邮政」在公司关键词表里，但「邮政局」是地名。不保护会剔成
        // 「虚构镇虚构局」——用户按这个地址找不到网点。
        val r = parse("【虚构速递】包裹已到虚构镇虚构邮政局，凭 5-2-7788 取货")
        assertTrue(r.isPickupSms)
        assertTrue(
            "地址应保留「邮政局」全称，实际=${r.address}",
            r.address.any { it.contains("邮政局") }
        )
        r.address.forEach { addr ->
            assertFalse("地址不应被剔成「虚构局」: $addr", addr.endsWith("虚构局"))
        }
    }

    @Test
    fun `凭码领取型短信不应被凭码到模式误伤`() {
        // 有「请凭」但没有「到 <落脚点>」，新模式必须完全不介入。
        val r = parse("【虚构速递】包裹已到虚构地标北30米路东，请凭 5-2-7788 领取")
        assertTrue(r.isPickupSms)
        assertTrue(
            "地址仍应是方位距离型，实际=${r.address}",
            r.address.any { it.endsWith("路东") }
        )
        r.address.forEach { addr ->
            assertFalse("地址里不应出现引导词: $addr", addr.contains("凭") || addr.contains("领取"))
        }
    }

    // ---------- 无取件码的取件短信 ----------
    // 注意：hasNoCode 只在命中「凭手机 / 报姓名 / 身份证」这类明确的无码取件方式时才为真。
    // 「已放到门口」这种表述不会置位 —— 这是刻意设计，避免把异常投递当成正常无码取件。

    @Test
    fun `报手机尾号取件应被标记 hasNoCode`() {
        val r = parse("【菜鸟驿站】请报手机后四位取件，包裹已到示例小区驿站")
        assertTrue(r.isPickupSms)
        assertTrue(r.codes.isEmpty())
        assertTrue(r.hasNoCode)
    }

    @Test
    fun `已放到门口不属于无码取件标记`() {
        val r = parse("【驿小哥】您的包裹已放到门口，请记得查收")
        assertFalse("不应被当作无码取件", r.hasNoCode)
    }

    // ---------- 幂等与稳定性 ----------

    @Test
    fun `同一短信多次解析结果一致`() {
        val sms = "【菜鸟驿站】您的包裹已到达示例小区菜鸟驿站，取件码 3-1-2048，请及时领取"
        val first = parse(sms)
        repeat(20) { assertEquals(first, parse(sms)) }
    }

    @Test
    fun `空串与纯空白不崩溃`() {
        listOf("", "   ", "\n\t").forEach {
            val r = parse(it)
            assertFalse(r.isPickupSms)
        }
    }
}
