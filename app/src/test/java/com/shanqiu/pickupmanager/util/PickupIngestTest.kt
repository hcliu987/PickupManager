/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3 回归用例：取件码归一、同码判定口径、三通道统一入口。
 *
 * 这些用例守的是三条**用户能直接感觉到**的行为：
 * 1. 用户删掉的记录不许复活（H-02）；
 * 2. 同码的新包裹不许被静默吞掉（H-03）；
 * 3. 同一条短信在三条采集通道下必须得到同样的结果（H-04）。
 *
 * 所有短信内容均为虚构。
 */
class PickupCodeNormalizerTest {

    private val n = PickupCodeNormalizer::normalizePickupCode

    // ---------- 归一化的每一条规则 ----------

    @Test
    fun `全角数字字母与连字符被归一为半角`() {
        assertEquals("A2-1-3456", n("Ａ２-１-３４５６"))
        assertEquals("C4-6-11111", n("Ｃ４－６－１１１１１"))
    }

    @Test
    fun `所有空白字符被去掉`() {
        assertEquals("A2-1-3456", n("A2 - 1 - 3456"))
        assertEquals("A2-1-3456", n(" A2-1-3456 "))
        assertEquals("A2-1-3456", n("A2\u3000-\u30001\u3000-\u30003456"))
        assertEquals("A2-1-3456", n("A2\t-\n1-\t3456"))
    }

    @Test
    fun `各种长得很像连字符的字符被收敛成 ASCII 连字符`() {
        // U+FF0D 全角 / U+2013 EN DASH / U+2014 EM DASH / U+2212 MINUS / U+30FC 片假名长音
        listOf('\uFF0D', '\u2013', '\u2014', '\u2212', '\u30FC', '\u2010').forEach { d ->
            assertEquals("字符 $d 未被归一", "1-2-3", n("1${d}2${d}3"))
        }
    }

    @Test
    fun `字母统一大写`() {
        assertEquals("A2-1-3456", n("a2-1-3456"))
        assertEquals("A2-1-3456", n("A2-1-3456"))
    }

    @Test
    fun `连续与首尾连字符被收敛`() {
        assertEquals("A2-1", n("A2--1"))
        assertEquals("A2-1", n("-A2-1-"))
        assertEquals("A2-1", n("A2---1"))
    }

    @Test
    fun `纯数字码原样保留`() {
        assertEquals("40142", n("40142"))
        assertEquals("40142", n(" 40142 "))
    }

    // ---------- 刻意不做的事（防止后人"顺手"加上） ----------

    @Test
    fun `不删连字符_因为有无连字符是两个不同字段`() {
        assertNotEquals(n("1234-5678"), n("12345678"))
    }

    @Test
    fun `不去前导零_因为无法证明等价`() {
        assertNotEquals(n("0012"), n("12"))
    }

    @Test
    fun `不做末几位截断_截断正是把手机号切成码的成因`() {
        assertEquals("18712345678", n("18712345678"))
    }

    @Test
    fun `空输入返回空串而不是崩溃`() {
        assertEquals("", n(""))
        assertEquals("", n("   "))
        assertEquals("", n("---"))
        assertEquals("", n("－"))
    }

    @Test
    fun `归一化是幂等的`() {
        listOf("Ａ２-１-３４５６", "a2 - 1 - 3456", " A2--1 ", "40142").forEach { raw ->
            val once = n(raw)
            assertEquals("对 $raw 不幂等", once, n(once))
        }
    }
}

/**
 * 同码判定口径（[PickupCodeMergePolicy]）。
 *
 * 这里测的是**纯函数**版本；数据库里的 SQL 用的是同一对窗口端点
 * （由 `windowAround()` 算出后作为参数传入），所以只要这里对，
 * SQL 侧就不会出现第二套口径。
 */
class PickupCodeMergePolicyTest {

    private val HOUR = 60L * 60 * 1000

    @Test
    fun `窗口内同码判为同一包裹`() {
        val t = 1_700_000_000_000L
        assertTrue(PickupCodeMergePolicy.isSameParcel("A2-1-3456", t, "A2-1-3456", t))
        assertTrue(PickupCodeMergePolicy.isSameParcel("A2-1-3456", t, "A2-1-3456", t + HOUR))
        assertTrue(PickupCodeMergePolicy.isSameParcel("A2-1-3456", t, "A2-1-3456", t - HOUR))
        // 边界：恰好 6 小时算同一包裹（闭区间）
        assertTrue(PickupCodeMergePolicy.isSameParcel("A2-1-3456", t, "A2-1-3456", t + 6 * HOUR))
    }

    @Test
    fun `窗口外同码判为新包裹_这是 H-03 的核心`() {
        val t = 1_700_000_000_000L
        assertFalse(PickupCodeMergePolicy.isSameParcel("A2-1-3456", t, "A2-1-3456", t + 7 * HOUR))
        assertFalse(PickupCodeMergePolicy.isSameParcel("40142", t, "40142", t + 24 * HOUR))
        assertFalse(PickupCodeMergePolicy.isSameParcel("40142", t, "40142", t + 30L * 24 * HOUR))
    }

    @Test
    fun `时间再近不同码也判为不同包裹`() {
        val t = 1_700_000_000_000L
        assertFalse(PickupCodeMergePolicy.isSameParcel("A2-1-3456", t, "A2-1-3457", t))
        assertFalse(PickupCodeMergePolicy.isSameParcel("40142", t, "40143", t))
    }

    @Test
    fun `码在被比较前先归一_所以通道差异不再造成漏判`() {
        val t = 1_700_000_000_000L
        // 广播通道写出半角、通知通道读出全角 —— 归一后必须认出是同一个包裹
        assertTrue(PickupCodeMergePolicy.isSameParcel("A2-1-3456", t, "Ａ２－１－３４５６", t + 2 * HOUR))
        assertTrue(PickupCodeMergePolicy.isSameParcel("A2-1-3456", t, "a2 - 1 - 3456", t + 2 * HOUR))
    }

    @Test
    fun `窗口端点是闭区间且与常量自洽`() {
        val t = 1_700_000_000_000L
        val (from, to) = PickupCodeMergePolicy.windowAround(t)
        assertEquals(PickupCodeMergePolicy.DUPLICATE_WINDOW_MILLIS, t - from)
        assertEquals(PickupCodeMergePolicy.DUPLICATE_WINDOW_MILLIS, to - t)
        // 与留档表（DeletedPickupItemDao.isDeleted）的 6 小时容差必须一致，
        // 两处口径不同会让"删掉的复活"和"重复入库"以互相矛盾的方式出现。
        assertEquals(6 * 60 * 60 * 1000L, PickupCodeMergePolicy.DUPLICATE_WINDOW_MILLIS)
    }
}

/**
 * 三通道统一入口（[PickupIngest]）。
 *
 * 重点不是"能解析出码"，而是**同一输入必须得到同一产出**，
 * 以及**取件码出门就已经是归一形态**（下游所有按码比较的逻辑都依赖这一点）。
 */
class PickupIngestTest {

    private val t = 1_700_000_000_000L

    @Test
    fun `产出里的取件码已经归一`() {
        val out = PickupIngest.parse(
            "【菜鸟驿站】您的包裹已到达示例小区菜鸟驿站，取件码 Ａ２－１－３４５６，请及时领取",
            senderPhone = "10690000000000",
            receiveTime = t
        )
        assertEquals(PickupIngest.Engine.RULE_BASED, out.engine)
        assertEquals(listOf("A2-1-3456"), out.items.map { it.pickupCode })
    }

    @Test
    fun `全角短信能走到解析器_这是输入规范化的意义`() {
        // 这条守的是"下游归一化器有没有机会执行"。解析器的字符类只认 ASCII，
        // 不在入口把全角拉回半角的话，全角短信会被整条丢弃，
        // normalizePickupCode 根本没被调用过。
        val fullWidth = "【丰巢】您在示例小区的快件已存入３号柜，取件码 ４０１４２，免费存放 18 小时"
        val out = PickupIngest.parse(fullWidth, senderPhone = "10655555", receiveTime = t)
        assertEquals(
            "全角短信应被正常识别，实际 engine=${out.engine}",
            listOf("40142"),
            out.items.map { it.pickupCode }
        )
    }

    @Test
    fun `排版破折号不被当成取件码分隔符_只有全角连字符才转`() {
        // 中文正文里的 `——`（U+2014）是标点，不是减号。
        // 输入规范化只映射全角 ASCII 形态，不做"看起来像连字符"的收敛，
        // 否则「示例小区——3号楼」会被改成「示例小区--3号楼」，把句子改坏。
        val text = "【菜鸟驿站】包裹已到示例小区——3号楼，取件码 3-1-2048，请及时领取"
        val out = PickupIngest.parse(text, senderPhone = "10655555", receiveTime = t)
        assertEquals(listOf("3-1-2048"), out.items.map { it.pickupCode })
        assertTrue(
            "原文里的破折号必须原样保留",
            out.items.single().rawSms.contains("——")
        )
    }

    @Test
    fun `rawSms 存的是用户原文而不是规范化后的副本`() {
        val original = "【丰巢】您在示例小区的快件已存入３号柜，取件码 ４０１４２，免费存放 18 小时"
        val out = PickupIngest.parse(original, senderPhone = "10655555", receiveTime = t)
        assertEquals("详情页的'查看原文'必须能看到原始短信", original, out.items.single().rawSms)
    }

    @Test
    fun `地址标签由统一生成器产出而不是原始地址`() {
        val out = PickupIngest.parse(
            "【菜鸟驿站】您的包裹已到达示例小区1号楼菜鸟驿站，取件码 3-1-2048，请及时领取",
            senderPhone = "10690000000000",
            receiveTime = t
        )
        val item = out.items.single()
        assertEquals("1号楼", item.addressTag)
        // 旧实现写的是 addressTag = 原始地址整串，长度不可能这么短
        assertNotEquals(item.address, item.addressTag)
    }

    @Test
    fun `过期时间是接收时间加保留天数`() {
        val out = PickupIngest.parse(
            "【菜鸟驿站】您的包裹已到达示例小区菜鸟驿站，取件码 3-1-2048，请及时领取",
            senderPhone = "10690000000000",
            receiveTime = t
        )
        assertEquals(
            t + PickupIngest.DEFAULT_KEEP_DAYS * 24L * 60 * 60 * 1000,
            out.items.single().expireTime
        )
    }

    @Test
    fun `无取件码的取件短信产出空列表但标记 hasNoCode`() {
        val out = PickupIngest.parse(
            "【菜鸟驿站】请凭手机号取件，包裹已到示例小区驿站",
            senderPhone = "10690000000000",
            receiveTime = t
        )
        assertTrue(out.hasNoCode)
        assertTrue("无码短信不应产出记录", out.items.isEmpty())
    }

    @Test
    fun `非取件短信产出空列表且 hasNoCode 为假`() {
        val out = PickupIngest.parse(
            "【某银行】您尾号1234的账户发生一笔交易，详情请查手机银行。",
            senderPhone = "95588",
            receiveTime = t
        )
        assertTrue(out.items.isEmpty())
        assertFalse(out.hasNoCode)
        assertEquals(PickupIngest.Engine.NONE, out.engine)
    }

    @Test
    fun `空白输入不崩溃`() {
        listOf("", "   ", "\n").forEach {
            val out = PickupIngest.parse(it, "", t)
            assertTrue(out.isEmpty)
            assertEquals(PickupIngest.Engine.NONE, out.engine)
        }
    }

    @Test
    fun `同一条短信重复解析结果完全一致`() {
        val sms = "【中通快递】取件码 4-5-6789，请到示例小区1号楼1单元101取运单尾号 3321"
        val first = PickupIngest.parse(sms, "10655555", t)
        repeat(10) { assertEquals(first, PickupIngest.parse(sms, "10655555", t)) }
    }

    @Test
    fun `一条短信多个取件码时不再重复产出同一个码`() {
        val out = PickupIngest.parse(
            "【韵达快递】您有2个包裹已到示例小区驿站，取件码分别为 1-2-3456、1-2-3456",
            senderPhone = "10655555",
            receiveTime = t
        )
        assertEquals(
            "同一个码重复出现时应只保留一条",
            out.items.map { it.pickupCode }.distinct().size,
            out.items.size
        )
    }

    /**
     * 6 条真实短信留出集（用户提供）。其中 4 条的【】签名是**代收点品牌**
     * （兔喜生活 / 妈妈驿站 / 邮乐购 / 欢猫驿站），承运方写在正文里；
     * 另 2 条签名本身就是承运方。卡片上的公司名必须一律是承运方，
     * 绝不能把驿站名当成快递公司显示出来。
     */
    @Test
    fun `卡片公司名只显示真实承运快递_不显示驿站品牌`() {
        val cases = listOf(
            "【兔喜生活】您有包裹已到达铜山利国中通快递兔喜5号柜兔喜快递柜，取件码为526956" to "中通快递",
            "【妈妈驿站】取货码3-2-957，您有圆通快递包裹，已到利国镇金三角东100米路南圆通" to "圆通快递",
            "【极兔速递】包裹已到金三角北30米路东，请凭17-2-0148领取" to "极兔速递",
            "【申通快递】您有快递已到金三角北50米路东五金机电，取件码12-3-7643，详询代收驿站" to "申通快递",
            "【邮乐购】您的中国邮政快递*8127已送达，请凭2-4-2956到石门北利国镇邮政局，石门北利国邮政局取款机旁边小屋领取，详见购物平台" to "中国邮政",
            "【欢猫驿站】您的韵达快递包裹已到东坡大道(利国实验小学南六十米路东)，请凭17-8-2224取件" to "韵达快递",
        )
        cases.forEach { (sms, expected) ->
            val out = PickupIngest.parse(sms, "10655555", t)
            assertTrue("应解析出取件记录，实际 engine=${out.engine}，短信：$sms", out.items.isNotEmpty())
            out.items.forEach { item ->
                assertEquals("短信：$sms", expected, item.courierCompany)
            }
        }
    }

    /**
     * 规则匹配页与自动入库必须共用同一套公司口径：
     * 同一条短信，UI 分析显示的公司名 = 入库后卡片的公司名，
     * 否则用户粘贴短信会看到「兔喜生活」，存进去却变成「中通快递」。
     */
    @Test
    fun `规则匹配页公司规范器与入库结果一致_驿站签名不显示`() {
        val cases = listOf(
            "【兔喜生活】您有包裹已到达铜山利国中通快递兔喜5号柜兔喜快递柜，取件码为526956" to "中通快递",
            "【妈妈驿站】取货码3-2-957，您有圆通快递包裹，已到利国镇金三角东100米路南圆通" to "圆通快递",
            "【邮乐购】您的中国邮政快递*8127已送达，请凭2-4-2956到石门北利国镇邮政局取款机旁边小屋领取" to "中国邮政",
            "【欢猫驿站】您的韵达快递包裹已到东坡大道(利国实验小学南六十米路东)，请凭17-8-2224取件" to "韵达快递",
        )
        cases.forEach { (sms, expected) ->
            val parsed = RuleBasedPickupParser.parse(sms)
            // 规则匹配页走的入口
            val uiCompany = CourierCompanyResolver.resolve(sms, parsed.company)
            // 自动入库走的入口
            val storedCompany = PickupIngest.parse(sms, "10655555", t).items.first().courierCompany
            assertEquals("UI 公司名短信：$sms", expected, uiCompany)
            assertEquals("两处口径必须一致，短信：$sms", storedCompany, uiCompany)
        }
    }

    /** 地址里带括号补充说明时右括号不能被 trimEnd 剥掉；承运方要删干净、柜号品牌要留住。 */
    @Test
    fun `真实短信地址保留完整括号与柜号品牌`() {
        val tuxi = PickupIngest.parse(
            "【兔喜生活】您有包裹已到达铜山利国中通快递兔喜5号柜兔喜快递柜，取件码为526956",
            "10655555", t
        ).items.first()
        assertEquals("铜山利国兔喜5号柜兔喜快递柜", tuxi.address)

        val huanmao = PickupIngest.parse(
            "【欢猫驿站】您的韵达快递包裹已到东坡大道(利国实验小学南六十米路东)，请凭17-8-2224取件",
            "10655555", t
        ).items.first()
        assertEquals("东坡大道(利国实验小学南六十米路东)", huanmao.address)
    }

    /**
     * 京东短信（用户提供）：前半句「已送达…京东服务站」是干扰项，真正的位置由
     * 显式的「地址：」标签给出，末尾还挂着「京东快递店」门店落款与客服短链。
     * 旧实现会把「您的快件尾号0159已送达铜山区利国镇服务站」整句当成地址。
     */
    @Test
    fun `京东短信优先取显式地址标签_不被已送达提示语带偏`() {
        val sms = "【京东快递】取件码6-3-4314，您的快件尾号0159已送达铜山区利国镇京东服务站，地址：利国鑫都商业中心迪士尼孕婴百货南隔壁（原龙远钢铁厂对面）京东快递店，取件和寄件可咨询 3.cn/3-bOpMvj"
        val out = PickupIngest.parse(sms, "10655555", t)
        assertEquals(PickupIngest.Engine.RULE_BASED, out.engine)
        val item = out.items.single()
        assertEquals("6-3-4314", item.pickupCode)
        assertEquals("京东快递", item.courierCompany)
        assertEquals("0159", item.trackingNumber)
        assertEquals("利国鑫都商业中心迪士尼孕婴百货南隔壁（原龙远钢铁厂对面）", item.address)
        assertFalse("提示语不应混进地址", item.address.contains("快件尾号"))
        assertFalse("客服短链不应混进地址", item.address.contains("3.cn"))
    }
}
