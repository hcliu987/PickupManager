/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地址定位数字指纹的回归测试。
 *
 * 语料全部是虚构地名，不含任何真实短信或住址。这里守的是一条产品红线：
 * 「相似度再高，也不能把 A 地点改写成 B 地点」。改写发生在三个入库通道
 * 共用的 AddressMatcher.matchAndPredictAddress，一旦放行就会同时污染
 * PickupItem.address 与 saved_addresses.displayName。
 */
class AddressLocationKeyTest {

    @Test
    fun `方位距离不同的地址必须判定为冲突`() {
        assertTrue(
            AddressLocationKey.conflicts(
                "虚构地标北30米路东",
                "虚构地标北50米路东五金机电"
            )
        )
    }

    @Test
    fun `楼号柜号不同的地址必须判定为冲突`() {
        assertTrue(AddressLocationKey.conflicts("虚构镇5号柜", "虚构镇6号柜"))
        assertTrue(AddressLocationKey.conflicts("虚构小区3号楼", "虚构小区8号楼"))
        assertTrue(AddressLocationKey.conflicts("虚构路12号", "虚构路21号"))
    }

    @Test
    fun `定位数字一致时允许归并`() {
        assertFalse(
            AddressLocationKey.conflicts(
                "虚构镇5号柜",
                "虚构镇中通快递5号柜虚构快递柜"
            )
        )
        assertFalse(AddressLocationKey.conflicts("虚构地标北30米路东", "虚构地标北30米路东五金机电"))
    }

    @Test
    fun `前导零不影响定位数字`() {
        assertEquals(setOf("5"), AddressLocationKey.numbers("虚构镇05号柜"))
        assertFalse(AddressLocationKey.conflicts("虚构镇5号柜", "虚构镇05号柜"))
    }

    @Test
    fun `一边省略数字时允许归并`() {
        assertFalse(AddressLocationKey.conflicts("虚构大道", "虚构大道18号"))
        assertFalse(AddressLocationKey.conflicts("虚构大道18号", "虚构大道"))
    }

    @Test
    fun `两边都无数字时不拦截`() {
        assertFalse(AddressLocationKey.conflicts("虚构大道驿站", "虚构大道"))
    }

    @Test
    fun `候选过滤只剔除数字冲突项`() {
        val cached = listOf(
            "虚构地标北30米路东",      // 30 vs 50，冲突
            "虚构镇6号柜",            // 6 vs 50，冲突
            "虚构地标北50米路东",      // 数字一致，保留
            "虚构大道"                // 无数字，保留
        )
        assertEquals(
            listOf("虚构地标北50米路东", "虚构大道"),
            AddressLocationKey.eligibleCandidates("虚构地标北50米路东五金机电", cached)
        )
    }

    @Test
    fun `真实场景的相似缓存不应参与命中`() {
        // 复现真机观察到的那条脏缓存：tag 是 50、displayName 却是 30 米。
        // 修复后这条脏记录的 displayName 也不该再被拿去改写 50 米的新地址。
        val dirtyDisplayName = "虚构地标北30米路东"
        assertTrue(
            AddressLocationKey.conflicts(
                "虚构地标北50米路东五金机电",
                dirtyDisplayName
            )
        )
        assertTrue(
            AddressLocationKey.eligibleCandidates(
                "虚构地标北50米路东五金机电",
                listOf(dirtyDisplayName)
            ).isEmpty()
        )
    }
}
