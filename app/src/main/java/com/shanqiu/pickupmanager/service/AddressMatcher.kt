/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.service

import android.util.Log
import com.shanqiu.pickupmanager.data.db.PickupItemDao
import com.shanqiu.pickupmanager.data.db.SavedAddressDao
import com.shanqiu.pickupmanager.data.model.SavedAddress
import com.shanqiu.pickupmanager.util.AddressTag

/**
 * 地址匹配结果
 */
data class AddressMatchResult(
    val standardizedAddress: String,
    val addressTag: String,
    val confidence: Float,
    val source: String
)

/**
 * 地址的「定位数字指纹」：把门牌、楼号、柜号、方位距离里的数字抽出来。
 *
 * ## 修的是：字符相似度把两个不同地点判成同一个
 *
 * [AddressMatcher.calculateSimilarity] 只看编辑距离，而驿站地址里**最关键的
 * 定位信息恰恰是数字**。「虚构地标北30米路东」与「虚构地标北50米路东五金机电」
 * 只差 5 次编辑，归一化相似度约 0.62，稳稳越过 [AddressMatcher.findBestMatch]
 * 的 0.5 阈值。后果不只是显示文案被换掉：
 *
 * 1. 三个入库通道都会用 `standardizedAddress` 覆写 `PickupItem.address`，
 *    用户会拿着 30 米的地址去找 50 米那家驿站；
 * 2. 同一通道随后还会把覆写后的地址当成 `displayName` 写回 `saved_addresses`，
 *    留下一条 `tag=北50 / displayName=北30米` 自相矛盾的缓存，污染后续所有匹配。
 *
 * 这里刻意不去调阈值或改相似度算法：地址里的数字属于**互斥标识**，
 * 两边都带数字且指纹不同时，无论字面多像都必须拒绝归并，回退到本条短信
 * 解析出的原地址。只有一边带数字时（「东坡大道」vs「东坡大道18号」）仍允许
 * 归并，因为那是同一地点的常见省略写法，误伤它反而会让常用驿站碎片化。
 *
 * 抽成独立纯对象而不是 [AddressMatcher] 的实例方法，是为了让 JVM 单测不必
 * 构造两个 Room DAO —— 本项目测试依赖只有 JUnit，没有 mock 框架。
 */
object AddressLocationKey {
    private val NUMBER = Regex("\\d+")

    /** 去前导零，让「5号柜」和「05号柜」视为同一个定位数字。 */
    fun numbers(address: String): Set<String> =
        NUMBER.findAll(address)
            .map { it.value.trimStart('0').ifEmpty { "0" } }
            .toSet()

    fun conflicts(a: String, b: String): Boolean {
        val left = numbers(a)
        val right = numbers(b)
        return left.isNotEmpty() && right.isNotEmpty() && left != right
    }

    /** 只剩定位数字不冲突的候选，才允许参与相似度归并。 */
    fun eligibleCandidates(target: String, candidates: List<String>): List<String> =
        candidates.filterNot { conflicts(target, it) }
}

/**
 * 地址匹配器
 * 提供地址相似度计算、标签生成与标准化
 */
class AddressMatcher(
    private val pickupItemDao: PickupItemDao,
    private val savedAddressDao: SavedAddressDao
) {
    
    companion object {
        private const val TAG = "AddressMatcher"

        /**
         * 内存优化：standardizeAddress 每次调用都通过 "\\s".toRegex() 重新编译空白正则，
         * 而地址标准化在入库时按条短信执行。提取为 companion 级常量只编译一次。
         */
        private val WS_REGEX = Regex("\\s")
    }
    
    private var initialized = false
    private var cachedAddresses: List<SavedAddress> = emptyList()
    
    /**
     * 初始化缓存
     */
    suspend fun initialize() {
        if (initialized) return
        try {
            refreshCache()
            initialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AddressMatcher", e)
        }
    }
    
    /**
     * 刷新地址缓存
     */
    suspend fun refreshCache() {
        try {
            cachedAddresses = savedAddressDao.getAllAddressesList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh cache", e)
        }
    }
    
    /**
     * 计算两个地址的相似度
     * @return 相似度值，范围0.0-1.0
     */
    fun calculateSimilarity(addr1: String, addr2: String): Float {
        if (addr1 == addr2) return 1.0f
        if (addr1.isEmpty() && addr2.isEmpty()) return 1.0f
        if (addr1.isEmpty() || addr2.isEmpty()) return 0.0f

        // Levenshtein 距离归一化为相似度
        val len1 = addr1.length
        val len2 = addr2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (addr1[i - 1] == addr2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }

        val maxLen = maxOf(len1, len2)
        return if (maxLen == 0) 1.0f else 1.0f - dp[len1][len2].toFloat() / maxLen
    }
    
    /**
     * 生成地址标签。
     *
     * R3 / H-13：算法已收敛到 [com.shanqiu.pickupmanager.util.AddressTag.of]。
     * 本方法保留为转发，是因为它被多处调用，而删除公开方法属于接口变更；
     * 保留转发可以让"只有一个算法"这件事在不破坏调用点的前提下成立。
     * 选择 `AddressTag` 作为标准算法而不是另起一套，是因为 `saved_addresses`
     * 里的历史键就是按本方法生成的 —— 换算法会让老用户的已保存驿站全部失联。
     */
    fun generateAddressTag(address: String): String = AddressTag.of(address)

    /**
     * 标准化地址
     */
    fun standardizeAddress(address: String): String {
        var result = address

        // 移除空白字符
        result = result.replace(WS_REGEX, "")

        // 统一常见词汇
        val replacements = mapOf(
            "栋" to "号楼",
            "座" to "号楼",
            "驿超市" to "驿站",
            "菜鸟驿站" to "驿站"
        )

        for ((from, to) in replacements) {
            result = result.replace(from, to)
        }

        return result
    }
    
    /**
     * 判断两个地址是否匹配（相似度超过阈值）
     */
    fun isMatch(addr1: String, addr2: String, threshold: Float = 0.7f): Boolean {
        return calculateSimilarity(addr1, addr2) >= threshold
    }
    
    /**
     * 从地址列表中找到最匹配的地址
     */
    fun findBestMatch(target: String, candidates: List<String>): Pair<String, Float>? {
        if (candidates.isEmpty()) return null
        
        var bestMatch = candidates[0]
        var bestScore = calculateSimilarity(target, candidates[0])
        
        for (i in 1 until candidates.size) {
            val score = calculateSimilarity(target, candidates[i])
            if (score > bestScore) {
                bestScore = score
                bestMatch = candidates[i]
            }
        }
        
        return if (bestScore > 0.5f) Pair(bestMatch, bestScore) else null
    }
    
    /**
     * 匹配并预测地址
     */
    suspend fun matchAndPredictAddress(
        rawAddress: String,
        senderPhone: String? = null,
        courierCompany: String? = null
    ): AddressMatchResult {
        if (rawAddress.isBlank()) {
            return AddressMatchResult(
                standardizedAddress = "",
                addressTag = "",
                confidence = 0f,
                source = "none"
            )
        }
        
        // 刷新缓存
        refreshCache()
        
        // 标准化地址
        val standardized = standardizeAddress(rawAddress)
        
        // 生成地址标签
        val tag = generateAddressTag(rawAddress)
        
        // 在缓存中查找匹配。候选先按定位数字过滤：数字不同的地址不可能是同一
        // 地点，光看编辑距离会把「北30米」和「北50米」判成一家（见 AddressLocationKey）。
        val eligible = AddressLocationKey.eligibleCandidates(
            standardized, cachedAddresses.map { it.displayName }
        )
        val cached = findBestMatch(standardized, eligible)
        
        return if (cached != null && cached.second > 0.5f) {
            // 命中缓存：返回匹配到的那条 displayName（完整地址），不是它的短标签。
            // 曾经这里取的是 addressTag，于是每次命中都把 displayName 覆写成越来越短的标签。
            AddressMatchResult(
                standardizedAddress = cached.first,
                addressTag = tag,
                confidence = cached.second,
                source = "cache"
            )
        } else {
            // 未找到匹配，使用标准化地址
            AddressMatchResult(
                standardizedAddress = if (standardized.isNotEmpty()) standardized else rawAddress,
                addressTag = tag,
                confidence = if (standardized.isNotEmpty()) 0.6f else 0.3f,
                source = "parse"
            )
        }
    }
}
