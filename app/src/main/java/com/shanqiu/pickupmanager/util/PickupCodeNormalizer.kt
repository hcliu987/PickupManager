/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

/**
 * 取件码的**唯一**归一化入口，以及"这是不是同一个包裹"的**唯一**判定口径。
 *
 * ## 为什么必须有这个文件
 *
 * 修 H-02 时发现：同一张取件码在不同采集通道下会得到**不同的字符串**。
 * 广播通道的解析结果可能是 `A2-1-3456`，而通知通道拿到的通知文本里
 * 全角字符没被转过（`Ａ２-１-３４５６`），批量导入的短信库里又可能带上空格
 * （`A2 - 1 - 3456`）。这三串在字符串层面互不相等，于是：
 *
 * - `deleted_pickup_items` 里存的是 `A2-1-3456`，而新记录查重时用的是
 *   `Ａ２-１-３４５６` → `isDeleted` 恒为 false → **用户删掉的记录原地复活**；
 * - `pickup_items` 的唯一索引也认不出这是同一张码 → 同一包裹入库两行，
 *   标掉一行另一行永远挂在待取页。
 *
 * 所以归一化不是"顺手清理一下"，它是**所有按取件码做的判断的前提**。
 * 只要有一处写裸码、一处写归一码，整套去重就是坏的 —— 因此本文件被设计成
 * 唯一入口：任何比较、落库、查重，必须先过 [normalizePickupCode]。
 */
object PickupCodeNormalizer {

    /**
     * 把取件码归一成"可以安全比较"的形态。
     *
     * 做四件事，每一件都对应一个真实出现过的差异来源：
     * 1. **全角转半角**：通知通道的文本常带全角数字/字母/连字符；
     * 2. **去掉所有空白**：驿站短信里 `A2 - 1 - 3456` 这种带空格的写法很常见；
     * 3. **统一连字符**：`－`(U+FF0D)、`—`(U+2014)、`–`(U+2013)、`‐`(U+2010)、
     *    `‑`(U+2011)、`‒`(U+2012)、`−`(U+2212) 全部收敛成 ASCII 的 `-`；
     * 4. **字母大写**：`a2-1-3456` 与 `A2-1-3456` 是同一张码。
     *
     * **刻意不做**的事：
     * - 不删连字符（`1234-5678` 与 `12345678` 在某些驿站是两个不同字段，合并会撞车）；
     * - 不去前导零（`0012` 与 `12` 可能是同一柜机的不同格口，无法证明等价）；
     * - 不做"取末 4 位"之类的截断（那正是 H-01 把号码切成码的成因）。
     *
     * 归一化只允许消除**确定的**书写差异，不允许做任何猜测性改写。
     */
    fun normalizePickupCode(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                // 空白（含全角空格 U+3000、不换行空格 U+00A0）直接丢弃
                ch.isWhitespace() || ch == '\u3000' || ch == '\u00A0' -> Unit
                ch in '０'..'９' -> sb.append('0' + (ch - '０'))
                ch in 'Ａ'..'Ｚ' -> sb.append('A' + (ch - 'Ａ'))
                ch in 'ａ'..'ｚ' -> sb.append('A' + (ch - 'ａ'))
                ch in DASH_LIKE -> sb.append('-')
                else -> sb.append(ch)
            }
        }
        // 连续连字符收敛成一个（`A2--1` → `A2-1`）
        val collapsed = StringBuilder(sb.length)
        for (ch in sb) {
            if (ch == '-' && collapsed.isNotEmpty() && collapsed.last() == '-') continue
            collapsed.append(ch)
        }
        // 首尾连字符去掉
        val trimmed = collapsed.toString().trim('-')
        return trimmed.uppercase()
    }

    /** 各种"看起来像连字符"的字符。ASCII 的 `-` 不在集合里（它本身就是目标形态）。 */
    private val DASH_LIKE: Set<Char> = setOf(
        '\uFF0D', // － 全角连字符
        '\u2010', // ‐ HYPHEN
        '\u2011', // ‑ NON-BREAKING HYPHEN
        '\u2012', // ‒ FIGURE DASH
        '\u2013', // – EN DASH
        '\u2014', // — EM DASH
        '\u2212', // − MINUS SIGN
        '\u30FC', // ー KATAKANA PROLONGED SOUND MARK（短信里被误当连字符用过）
    )

    /**
     * 把**全角 ASCII 字母与数字**转成半角，用于**喂给解析器之前**的文本规范化。
     *
     * ## 为什么必须有这一步（R3 自查发现的漏洞）
     *
     * [normalizePickupCode] 是在解析**之后**对取件码做归一。但如果解析器根本
     * 认不出全角码，归一化就没有输入 —— 白写。实测确认了这一点：
     *
     * ```
     * 输入：【菜鸟驿站】…取件码 Ａ２－１－３４５６，请及时领取
     * 结果：codes = []        ← 因为 CODE_PREFIX_PATTERNS 里的 [A-Za-z] 与 \d
     *                           都只认 ASCII（源文件里注明"修复7：ASCII字母"）
     * ```
     *
     * 也就是说，全角短信在旧代码里会被**整条丢弃**，用户看到的是"这条短信没被识别"。
     * 与其去改几十条正则把字符类扩成全角+半角（改动量大、容易漏、每条都可能引入
     * 新的回溯分支），不如在入口把文本拉回半角 —— 这是通行做法：
     * 先做输入规范化，再谈业务字段匹配。
     *
     * **只转字母、数字，以及全角连字符 `－`(U+FF0D)**，其余一律不动：
     * - 全角标点（，。；：）不转 —— 正则的前瞻里本来就同时列了全角与半角标点，
     *   转它们只会让 diff 变大、没有收益；
     * - 排版用破折号（`—` U+2014、`–` U+2013、`−` U+2212）**不转** ——
     *   中文正文里 `——` 是标点而不是减号，把它转成 `--` 会把句子改坏。
     *   这类"看起来像连字符"的字符只在 [normalizePickupCode] 内部收敛，
     *   因为那里处理的已经是一个取件码**值**，不是自然语言文本。
     *
     * 之所以必须包含 `－`：解析器的字符类只认 ASCII 的 `-`
     * （`(?:-\d{1,6}){1,3}`），全角连字符会让 `Ａ２－１－３４５６`
     * 在转完数字字母之后仍然匹配不上，等于白转。
     */
    fun normalizeFullWidthAscii(text: String): String {
        var changed = false
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val mapped = when {
                ch in '０'..'９' -> '0' + (ch - '０')
                ch in 'Ａ'..'Ｚ' -> 'A' + (ch - 'Ａ')
                ch in 'ａ'..'ｚ' -> 'a' + (ch - 'ａ')
                ch == '\uFF0D' -> '-'
                else -> ch
            }
            if (mapped != ch) changed = true
            sb.append(mapped)
        }
        return if (changed) sb.toString() else text
    }
}

/**
 * "两张取件码是不是同一个包裹"的**唯一**判定口径。
 *
 * ## 为什么是"时间窗"而不是"取件码唯一"
 *
 * 原设计的前提是"取件码是包裹的物理身份"（见 `PickupItemDao.insertOrUpdateItem`
 * 的注释），于是给 `pickupCode` 建了**全局唯一索引**。这个前提**不成立**：
 *
 * - 丰巢柜的取件码是 4 位，同一台柜机每天发放上千个，**当天就可能重复**；
 * - 驿站按当日序号发码，同一驿站隔几天出现同一个号是常态。
 *
 * 唯一索引 + `OnConflictStrategy.IGNORE` 的后果是**静默吞件**：新包裹不入库、
 * 不通知、列表里看不到，用户完全不知道有这个包裹 —— 而且没有任何日志会给用户看。
 * 这是本项目最严重的一条功能缺陷（H-03）。
 *
 * 改成"时间窗"后的语义：
 * - 同一张码、接收时间相差在 [DUPLICATE_WINDOW_MILLIS] 以内 → 判为**同一个包裹**
 *   （多通道重复采集，或同一条短信被解析两次）；
 * - 同一张码、时间相差超出窗口 → 判为**新包裹**，正常入库。
 *
 * 窗口取 6 小时：三条通道的时钟不同源（短信中心的 SCTS / 本机接收时间 /
 * `Notification.when`），相差几分钟到几小时都属正常，窗口必须覆盖这个抖动；
 * 而"同一张码被复用"在现实中至少隔一次投递周期，6 小时足以把它们分开。
 * 这个值与 `DeletedPickupItemDao.isDeleted` 的容差**刻意保持一致** ——
 * 两处口径不一致会让"删掉的复活"和"重复入库"以互相矛盾的方式出现。
 */
object PickupCodeMergePolicy {

    /** 判定为"同一个包裹"的最大时间差。6 小时 = 21,600,000 毫秒。 */
    const val DUPLICATE_WINDOW_MILLIS: Long = 6L * 60 * 60 * 1000

    /** 以给定接收时间为中心的时间窗，闭区间。调用方把它作为 SQL 参数传入，保证只有一处口径。 */
    fun windowAround(receiveTime: Long): Pair<Long, Long> =
        (receiveTime - DUPLICATE_WINDOW_MILLIS) to (receiveTime + DUPLICATE_WINDOW_MILLIS)

    /**
     * 纯函数版本的判定，供单元测试与需要本地判断的场景使用。
     * 数据库里的等价实现是
     * `receiveTime BETWEEN windowAround(t).first AND windowAround(t).second`
     * 且 `pickupCode` 已归一。
     */
    fun isSameParcel(
        codeA: String,
        timeA: Long,
        codeB: String,
        timeB: Long
    ): Boolean {
        if (PickupCodeNormalizer.normalizePickupCode(codeA) !=
            PickupCodeNormalizer.normalizePickupCode(codeB)
        ) return false
        return kotlin.math.abs(timeA - timeB) <= DUPLICATE_WINDOW_MILLIS
    }
}
