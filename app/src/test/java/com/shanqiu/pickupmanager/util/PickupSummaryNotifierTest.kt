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
 * 通知栏文案的隐私红线测试。
 *
 * 只测纯函数 [PickupSummaryNotifier.buildContent]，不需要 Android 环境：
 * 通知渠道、PendingIntent、NotificationManager 都属于系统侧行为，留给真机验证。
 * 语料全部是虚构取件码与虚构公司名。
 */
class PickupSummaryNotifierTest {

    private val codes = listOf("5-2-7788", "3-1-2048", "40142")

    @Test
    fun `没有待取件时给出空态文案`() {
        val c = PickupSummaryNotifier.buildContent(emptyList(), null, showCode = false)
        assertEquals("暂无待取件", c.title)
        assertFalse(c.text.contains("5-2-7788"))
    }

    @Test
    fun `默认只显示数量不泄露取件码或公司`() {
        val c = PickupSummaryNotifier.buildContent(codes, "虚构速递", showCode = false)
        assertEquals("待取 3 件", c.title)
        assertFalse("脱敏模式下不应出现取件码: ${c.text}", c.text.contains("5-2-7788"))
        assertFalse("脱敏模式下不应出现公司名: ${c.text}", c.text.contains("虚构速递"))
        assertTrue(c.text.contains("打开码上取"))
    }

    @Test
    fun `显式开启后显示最新取件码与快递公司`() {
        val c = PickupSummaryNotifier.buildContent(codes, "虚构速递", showCode = true)
        assertEquals("待取 3 件", c.title)
        assertTrue(c.text.contains("5-2-7788"))
        assertTrue(c.text.contains("虚构速递"))
    }

    @Test
    fun `多件时标出剩余件数且只显示最新一个码`() {
        val c = PickupSummaryNotifier.buildContent(codes, "虚构速递", showCode = true)
        assertTrue("应提示另有 2 件: ${c.text}", c.text.contains("另有 2 件"))
        assertFalse("不应把第二个码也拼进通知: ${c.text}", c.text.contains("3-1-2048"))
        assertFalse("不应把第三个码也拼进通知: ${c.text}", c.text.contains("40142"))
    }

    @Test
    fun `单件时不出现另有字样`() {
        val c = PickupSummaryNotifier.buildContent(listOf("40142"), "虚构速递", showCode = true)
        assertEquals("待取 1 件", c.title)
        assertFalse(c.text.contains("另有"))
    }

    @Test
    fun `公司名为空或空白时不留下悬空分隔符`() {
        listOf(null, "", "   ").forEach { company ->
            val c = PickupSummaryNotifier.buildContent(codes, company, showCode = true)
            assertFalse("公司名为空时不应出现 · : ${c.text}", c.text.contains("·"))
        }
    }

    @Test
    fun `标题数量始终与待取件列表一致`() {
        assertEquals(
            "暂无待取件",
            PickupSummaryNotifier.buildContent(emptyList(), null, showCode = false).title
        )
        (1..8).forEach { n ->
            val list = List(n) { "9-9-90${it % 10}" }
            assertEquals(
                "待取 $n 件",
                PickupSummaryNotifier.buildContent(list, null, showCode = false).title
            )
        }
    }
}
