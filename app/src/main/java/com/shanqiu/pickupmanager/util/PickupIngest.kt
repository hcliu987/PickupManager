/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import com.shanqiu.pickupmanager.data.model.PickupItem

/**
 * 地址标签的**唯一**生成器。
 *
 * ## 修的是 H-13：同一个概念有四套互不兼容的实现
 *
 * 基线审计发现"地址标签"有三套生成规则，实际清点是**四套**，而且第五种写法
 * 更粗暴 —— 直接把原始地址当标签：
 *
 * | 位置 | 规则 |
 * |---|---|
 * | `AddressMatcher.generateAddressTag()` | 楼号 → 小区名 → 驿站名 → 前 6 字 |
 * | `PickupRepository.generateSimpleAddressTag()` | 楼号 → 前 **8** 字 |
 * | `PickupSmsParserV2.generateAddressTag()` | 又一套独立实现 |
 * | `RuleMatchingScreen:819` | `finalAddress.take(2)` —— 前 **2** 字 |
 * | `SmsReceiver:188`、`PickupRepository:294` | `addressTag = 原始地址`（长度不限） |
 *
 * 而 `addressTag` 不是展示用的美化字段，它是**取件路径排序**与
 * `saved_addresses`（常用驿站）的**关联键**。四套规则意味着：
 * 手动添加的记录永远匹配不上已保存的地址，既不显示用户设的自定义名，
 * 也不参与路线排序 —— 用户会认为"我明明设过"。
 *
 * 这里把算法收敛成一份，并**刻意选择** `AddressMatcher` 那一套作为标准：
 * 因为 `saved_addresses` 里已经存在历史数据，它的键就是按那套算法生成的。
 * 换算法会让老用户的已保存地址全部失联，这是不可接受的迁移代价。
 */
object AddressTag {

    const val FALLBACK = "其他"

    /** 前 N 字的兜底长度。取 6 是沿用 `AddressMatcher` 的历史值（见上文）。 */
    private const val FALLBACK_PREFIX_LEN = 6

    private val BUILDING = Regex("(\\d+)[号楼栋座]")
    private val COMMUNITY = Regex("(.{2,8})(小区|花园|公寓|家园|苑|城|院)")
    private val STATION = Regex("(.{2,6})(驿站|快递柜|菜鸟|丰巢)")

    fun of(address: String): String {
        val addr = address.trim()
        if (addr.isEmpty()) return FALLBACK

        BUILDING.find(addr)?.let { return it.groupValues[1] + "号楼" }

        COMMUNITY.find(addr)?.let { m ->
            if (m.groupValues.size > 2) return m.groupValues[1] + m.groupValues[2]
        }

        STATION.find(addr)?.let { return it.value }

        return if (addr.length <= FALLBACK_PREFIX_LEN) addr else addr.substring(0, FALLBACK_PREFIX_LEN)
    }
}

/**
 * 卡片上的「快递公司」统一规范器：只输出**真实承运公司**，绝不输出驿站/门店品牌。
 *
 * ## 为什么要单独做这一层
 *
 * 短信开头的【】是**发件签名，不是承运方**。实测 6 条真实短信里有 4 条的签名是
 * 代收点品牌：【兔喜生活】/【妈妈驿站】/【邮乐购】/【欢猫驿站】，而承运方写在正文里
 * （中通快递 / 圆通快递 / 中国邮政快递 / 韵达快递）。
 * 老链路直接取 `ParseResult.company.firstOrNull()`，也就是签名映射出来的品牌名，
 * 于是卡片显示「兔喜生活」而不是「中通快递」——用户按快递公司找件时答非所问。
 *
 * 解析顺序：**短信全文（含签名）里的承运方 → 解析器给出的候选 → 兜底「快递」**。
 * 全文扫描同时覆盖两种形态：签名就是承运方（【极兔速递】/【申通快递】），
 * 以及签名是驿站、正文才是承运方。同一位置有多个命中时取更长的关键词
 * （「中国邮政快递」优先于「邮政」），始终取出现位置最靠前的那个。
 *
 * 刻意**不**改 `RuleBasedPickupParser.extractCompany()`：那个返回值被现有单测直接断言，
 * 且 `SIGNATURE_FORMATS` 同时决定取件码格式，动它属于高风险重构。
 * 这里只在入库前覆写展示字段，三条采集通道（实时广播 / 批量导入 / 通知兜底）
 * 因为都走 [PickupIngest.parse]，自动一起生效。
 */
object CourierCompanyResolver {

    private const val FALLBACK = "快递"

    /** 关键词 → 统一展示名。顺序无关，运行时按出现位置与长度择优。 */
    private val CARRIERS: List<Pair<String, String>> = listOf(
        "中国邮政快递" to "中国邮政",
        "中国邮政" to "中国邮政",
        "邮政电商标快" to "中国邮政",
        "邮政速递" to "中国邮政",
        "邮政快递" to "中国邮政",
        "EMS" to "EMS",
        "邮政" to "中国邮政",
        "极兔速递" to "极兔速递",
        "极兔快递" to "极兔速递",
        "极兔" to "极兔速递",
        "中通快递" to "中通快递",
        "中通速递" to "中通快递",
        "中通" to "中通快递",
        "圆通速递" to "圆通快递",
        "圆通快递" to "圆通快递",
        "圆通" to "圆通快递",
        "韵达快递" to "韵达快递",
        "韵达速递" to "韵达快递",
        "韵达" to "韵达快递",
        "申通快递" to "申通快递",
        "申通" to "申通快递",
        "顺丰速运" to "顺丰快递",
        "顺丰快递" to "顺丰快递",
        "顺丰" to "顺丰快递",
        "京东物流" to "京东快递",
        "京东快递" to "京东快递",
        "京东" to "京东快递",
        "德邦物流" to "德邦快递",
        "德邦快递" to "德邦快递",
        "德邦" to "德邦快递",
        "百世快递" to "百世快递",
        "百世" to "百世快递",
    )

    /** 「邮政局 / 邮电所」是**地名**，不代表这条短信由邮政承运，先占掉再扫描。 */
    private val PLACE_WORDS = listOf("邮政支局", "邮政局", "邮电所", "邮政所", "邮局")

    fun resolve(smsText: String, parsedCandidates: List<String>): String {
        val scannable = PLACE_WORDS.fold(smsText) { acc, word -> acc.replace(word, "\u0001") }
        match(scannable)?.let { return it }
        for (candidate in parsedCandidates) {
            match(candidate)?.let { return it }
        }
        return FALLBACK
    }

    private fun match(text: String): String? {
        var bestIndex = Int.MAX_VALUE
        var bestLength = 0
        var bestName: String? = null
        for ((keyword, displayName) in CARRIERS) {
            val index = text.indexOf(keyword)
            if (index < 0) continue
            if (index < bestIndex || (index == bestIndex && keyword.length > bestLength)) {
                bestIndex = index
                bestLength = keyword.length
                bestName = displayName
            }
        }
        return bestName
    }
}

/**
 * **唯一**的入库前置处理：一条短信 → 若干条可落库的 [PickupItem]。
 *
 * ## 修的是 H-04：三条采集通道三套流程
 *
 * 改造前的实际状况（基线审计逐条核对过）：
 *
 * | | 解析器 | 取件码归一 | 地址标签 | senderPhone |
 * |---|---|---|---|---|
 * | `SmsReceiver`（实时广播） | 规则引擎 → V2 回落 | 无 | 原始地址 | 有 |
 * | `PickupRepository.doImportSms`（批量导入） | 规则引擎 → V2 回落 | 无 | AddressMatcher | 有 |
 * | `PickupNotificationListener`（通知兜底） | **只有 V2** | 无 | V2 自己的 | **空串** |
 *
 * 后果是**同一条短信在三条通道下给出不同结果**：取件码串不同（H-02 的病灶）、
 * 地址质量不同、标签根本对不上。用户看不到"通道"这个概念，只看到
 * "我收到的取件记录怎么和导入进来的不一样"。
 *
 * 本对象把"解析 + 归一 + 打标签"这段确定性逻辑收敛成一份，
 * 三条通道一律调用它。**不做**的事：不碰数据库、不碰地址覆盖表
 * （那是 `AddressMatcher` 的职责，属于需要 DB 的增强步骤，由调用方在拿到
 * [PickupIngest.Outcome.items] 之后统一施加）。
 */
object PickupIngest {

    /** 取件记录在列表里的默认保留天数，三条通道必须一致。 */
    const val DEFAULT_KEEP_DAYS: Int = 7

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /** 这条短信是被哪个引擎解析出来的。用于日志与回归对比，不参与业务判断。 */
    enum class Engine { RULE_BASED, FALLBACK_V2, NONE }

    data class Outcome(
        val items: List<PickupItem>,
        val engine: Engine,
        /** 是取件短信但明确表达"无取件码"（凭手机 / 报姓名 / 身份证）。 */
        val hasNoCode: Boolean,
        /** 规则引擎认为这是取件短信。通知通道的关键词预筛据此放宽。 */
        val isPickupSms: Boolean,
        val rawSms: String,
    ) {
        val isEmpty: Boolean get() = items.isEmpty()
    }

    /**
     * 解析一条**已拼装完整**的短信。
     *
     * 调用方必须先把多段短信按 (发件人, 时间戳) 拼回一条再传进来 ——
     * 见 `SmsReceiver.onReceive` 里的分组逻辑与 `SmsReceiver` 类注释。
     * 逐片解析会把取件码从中间切断，这不是本函数能补救的。
     */
    fun parse(
        smsText: String,
        senderPhone: String,
        receiveTime: Long,
        keepDays: Int = DEFAULT_KEEP_DAYS,
    ): Outcome {
        if (smsText.isBlank()) {
            return Outcome(emptyList(), Engine.NONE, hasNoCode = false, isPickupSms = false, rawSms = smsText)
        }

        // R3：先做**输入规范化**，再做业务匹配。
        // 解析器的字符类只认 ASCII（`[A-Za-z]` / `\d`），而部分通知与短信确实会
        // 用全角字母数字（"取件码 Ａ２－１－３４５６"）。不转的话这类短信会被整条丢弃，
        // 用户看到的是"没被识别"——而下游的取件码归一化器**根本没有机会执行**。
        // 只转字母数字，不动标点（正则的前瞻里本来就同时列了全角与半角标点）。
        val scannable = PickupCodeNormalizer.normalizeFullWidthAscii(smsText)

        // 策略 1：规则引擎（引导词 → 签名格式 → 通用码形）
        val ruleResult = try {
            RuleBasedPickupParser.parse(scannable)
        } catch (e: Throwable) {
            // 解析器内部已尽量兜底；这里再挡一次，避免一条畸形短信把整批处理带崩
            android.util.Log.e("PickupIngest", "规则引擎抛出异常，转兜底解析器", e)
            null
        }

        // 卡片上的公司名统一由规范器给出：不管解析器把【兔喜生活】这类签名排在第一位，
        // 落库的一定是真实承运方（或兜底「快递」）。两个解析分支共用同一个值。
        val courierCompany = CourierCompanyResolver.resolve(smsText, ruleResult?.company.orEmpty())

        if (ruleResult != null && ruleResult.isPickupSms) {
            if (ruleResult.codes.isNotEmpty()) {
                val address = ruleResult.address.firstOrNull().orEmpty()
                val items = ruleResult.codes
                    .mapNotNull { raw ->
                        val code = PickupCodeNormalizer.normalizePickupCode(raw)
                        if (code.isEmpty()) null else buildItem(
                            code = code,
                            address = address,
                            company = courierCompany,
                            trackingNumber = ruleResult.trackingNumber.orEmpty(),
                            // rawSms 一律存**用户收到的原文**，绝不是规范化后的副本 ——
                            // 详情页的"查看原文"必须能看到原始短信。
                            rawSms = smsText,
                            senderPhone = senderPhone,
                            receiveTime = receiveTime,
                            keepDays = keepDays,
                        )
                    }
                    .distinctBy { it.pickupCode }
                if (items.isNotEmpty()) {
                    return Outcome(items, Engine.RULE_BASED, hasNoCode = false, isPickupSms = true, rawSms = smsText)
                }
            } else if (ruleResult.hasNoCode) {
                // 明确的无码取件短信：不落库，但必须把这个判断传出去，
                // 否则通知通道会把"凭手机号取件"当成普通短信继续跑 V2。
                return Outcome(emptyList(), Engine.RULE_BASED, hasNoCode = true, isPickupSms = true, rawSms = smsText)
            }
        }

        // 策略 2：兜底解析器（同为纯规则，不是模型）
        val fallbackItems = try {
            PickupSmsParserV2.parseSmsMultipleCodes(scannable, senderPhone, receiveTime)
        } catch (e: Throwable) {
            android.util.Log.e("PickupIngest", "兜底解析器抛出异常", e)
            emptyList()
        }

        val normalized = fallbackItems.mapNotNull { raw ->
            val code = PickupCodeNormalizer.normalizePickupCode(raw.pickupCode)
            if (code.isEmpty()) null else raw.copy(
                pickupCode = code,
                // 与规则引擎分支保持一致：卡片不显示驿站/门店品牌
                courierCompany = courierCompany,
                addressTag = AddressTag.of(raw.address),
                expireTime = raw.receiveTime + keepDays * DAY_MILLIS,
                // 同规则引擎分支：rawSms 存原文，不存规范化副本
                rawSms = smsText,
            )
        }.distinctBy { it.pickupCode }

        return Outcome(
            items = normalized,
            engine = if (normalized.isEmpty()) Engine.NONE else Engine.FALLBACK_V2,
            hasNoCode = normalized.isEmpty() && (ruleResult?.hasNoCode == true),
            isPickupSms = normalized.isNotEmpty() || (ruleResult?.isPickupSms == true),
            rawSms = smsText,
        )
    }

    private fun buildItem(
        code: String,
        address: String,
        company: String,
        trackingNumber: String,
        rawSms: String,
        senderPhone: String,
        receiveTime: Long,
        keepDays: Int,
    ): PickupItem = PickupItem(
        pickupCode = code,
        address = address,
        // 标签统一由 AddressTag 生成。改造前这里写的是原始地址整串，
        // 于是同一个驿站因短信措辞不同而产生无数个"标签"，路线排序直接失效。
        addressTag = AddressTag.of(address),
        courierCompany = company,
        trackingNumber = trackingNumber,
        receiveTime = receiveTime,
        expireTime = receiveTime + keepDays * DAY_MILLIS,
        rawSms = rawSms,
        senderPhone = senderPhone,
    )
}
