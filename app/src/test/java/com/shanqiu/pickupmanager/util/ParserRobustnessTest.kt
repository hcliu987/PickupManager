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
 * R2 健壮性用例：正则代价、空输入、异常形状。
 *
 * 解析是在 `SmsReceiver.onReceive` 里同步跑的（广播比 `goAsync` 之前），
 * 也是批量导入时每条短信都要跑的。所以**单条解析的耗时必须是常数级** ——
 * 一次灾难性回溯在这里的后果不是"慢"，是 ANR。
 */
class ParserRobustnessTest {

    private fun parse(sms: String) = RuleBasedPickupParser.parse(sms)

    @Test
    fun `超长且大量诱导词的文本不得触发灾难性回溯`() {
        // 故意构造最恶劣的输入：反复出现地址引导词前缀 + 反复出现地址后缀表里的字，
        // 让 ADDRESS_PATTERNS 的每一个起点都能走进最深的回溯分支。
        val evil = buildString {
            repeat(400) {
                append("在到达已到抵达某个未命名的地方之号码楼栋幢座单元室店站点驿站柜门层房区巷路街道弄里园苑坊阁居庭邸府郡城镇村")
            }
        }
        // 先热一次 JIT，再计时，避免把类加载算进去
        parse("【菜鸟驿站】示例小区，取件码 3-1-2048，请及时领取")
        val start = System.nanoTime()
        parse(evil)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("单条解析耗时 ${ms}ms，疑似灾难性回溯（上限 3000ms）", ms < 3000)
    }

    @Test
    fun `反复解析一千条短信的总耗时应与条数近似线性`() {
        val sms = "【菜鸟驿站】您的包裹已到达示例小区菜鸟驿站，取件码 3-1-2048，请及时领取，咨询18712345678"
        parse(sms)
        val start = System.nanoTime()
        repeat(1000) { parse(sms) }
        val ms = (System.nanoTime() - start) / 1_000_000
        // 1000 条 3 秒 = 平均 3ms/条，已远宽于实测值；这里只拦"数量级不对"
        assertTrue("1000 条解析耗时 ${ms}ms，明显偏慢", ms < 3000)
    }

    @Test
    fun `只有空白与标点不崩溃且不产出码`() {
        listOf("", " ", "\n", "\t\t", "。，、；：！？", "【】", "——", "…………").forEach {
            val r = parse(it)
            assertFalse("空输入不应识别为取件短信: ${it.replace("\n", "\\n")}", r.isPickupSms)
            assertTrue(r.codes.isEmpty())
        }
    }

    @Test
    fun `截断的多段短信不崩溃`() {
        // 多段短信被 SmsReceiver 拼装失败时可能只留下一半，任何一半都不能让解析器抛异常
        listOf(
            "【菜鸟驿站】您的包裹已到达示例小",
            "区菜鸟驿站，取件码 3-1-2048，请及时领取",
            "取件码",
            "取件码 ",
            "取件码 -",
            "取件码 - - -",
            "凭",
            "输入"
        ).forEach { parse(it) }
    }

    @Test
    fun `取件码两侧夹杂全角引号与空格仍可提取`() {
        val r = parse("【菜鸟驿站】取件码“ 5-6-7890 ”，请及时领取")
        assertTrue("实际=${r.codes}", r.codes.contains("5-6-7890"))
    }

    @Test
    fun `同一号码出现两次也只影响一次`() {
        val r = parse("【丰巢】快件已存入示例小区3号柜，取件码40142，客服18712345678，再次提示18712345678")
        assertEquals(listOf("40142"), r.codes)
    }

    @Test
    fun `号码被顶掉后留下的占位字符不得出现在任何输出字段`() {
        val r = parse("【驿收发】包裹已到示例小区快递柜，编号8823，咨询18712345678，2026-09-20前取")
        val all = buildString {
            append(r.codes.joinToString())
            append(r.address.joinToString())
            append(r.company.joinToString())
            append(r.trackingNumber ?: "")
        }
        assertFalse("输出里出现了内部占位字符 U+FFFD：$all", all.contains('\uFFFD'))
    }

    @Test
    fun `地址不应带着动作引导词一起入库`() {
        listOf(
            "【中通快递】取件码 4-5-6789，请到示例小区1号楼1单元101取运单尾号 3321",
            "【圆通速递】取件码 6-2-1188，已到示例小区3号楼驿站",
            "【韵达】取件码 1-2-3456，位于示例小区西门驿站"
        ).forEach { sms ->
            val r = parse(sms)
            r.address.forEach { addr ->
                listOf("到达", "已到", "抵达", "位于").forEach { prefix ->
                    assertFalse("地址里混进了引导词「$prefix」：$addr  ← $sms", addr.startsWith(prefix))
                }
                assertFalse("地址里混进了取件码：$addr", addr.contains("-"))
            }
        }
    }
}
