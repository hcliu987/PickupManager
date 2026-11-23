/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * 规则引擎在人工标注集上的准确率评测 —— **不是断言测试，是度量工具**。
 *
 * 设计意图：README 第 3 节问题 3 讨论「AI 层要不要接通」时，需要一个可比较的基线数字。
 * 没有这个数字，任何关于「规则引擎够不够用」的判断都只是感觉。
 *
 * 数据集**不在仓库里**：它由真实短信组成，属于个人隐私，公开仓库永远不该出现。
 * 想复现这个评测，请在自己设备上准备一份同格式的标注集，然后通过环境变量指过来：
 *
 * ```
 * PICKUP_EVAL_DATASET=/path/to/eval_set.tsv ./gradlew :app:testDebugUnitTest \
 *     --tests "*RuleBasedPickupParserAccuracyEval"
 * ```
 *
 * 每行四个字段，制表符分隔：`短信内容 \t 取件码 \t 取件地址 \t 快递公司`
 * 未设置环境变量或文件不存在时，本用例自动跳过，不会影响 CI 与他人构建。
 *
 * 报告默认只有聚合数字。逐条样例（含真实短信原文与取件码）需要显式设
 * `PICKUP_EVAL_SHOW_SAMPLES=1` 才打印 —— 测试报告很容易被整个 zip 贴进 Issue。
 */
class RuleBasedPickupParserAccuracyEval {

    private data class Row(val sms: String, val code: String, val address: String, val company: String)

    /**
     * 样例原文是真实短信。测试报告虽然落在已被 gitignore 的 `app/build/` 里，
     * 但人会整个 zip 起来贴进 Issue 或聊天窗口 —— 所以默认只输出指纹，
     * 真要人眼核对时显式设 `PICKUP_EVAL_SHOW_SAMPLES=1`。
     */
    private val showSamples: Boolean
        get() = System.getenv("PICKUP_EVAL_SHOW_SAMPLES") == "1"

    @Test
    fun `在标注集上度量规则引擎并打印报告`() {
        val path = System.getenv("PICKUP_EVAL_DATASET")
        val file = path?.let(::File)
        Assume.assumeTrue("未设置 PICKUP_EVAL_DATASET，跳过准确率评测", file != null && file.exists())

        val rows = file!!.readLines()
            .filter { it.isNotBlank() }
            .map { it.split('\t') }
            .filter { it.size >= 4 }
            .map { Row(it[0], it[1].trim(), it[2].trim(), it[3].trim()) }

        var classified = 0
        var codeExpected = 0
        var codeHit = 0
        var codeExtra = 0
        var addressGot = 0
        var addressOverlap = 0
        val misses = mutableListOf<String>()

        for (row in rows) {
            val r = RuleBasedPickupParser.parse(row.sms)
            if (r.isPickupSms) classified++

            if (row.code.isNotEmpty()) {
                codeExpected++
                val got = r.codes.map { it.replace(" ", "").uppercase() }
                // 标注本身可能含多个取件码（多包裹短信），用 、，,/ 分隔；要求全部命中
                val want = row.code.split('、', '，', ',', '/', ' ')
                    .map { it.trim().replace(" ", "").uppercase() }.filter { it.isNotEmpty() }
                if (want.isNotEmpty() && got.containsAll(want)) {
                    codeHit++
                } else if (showSamples && misses.size < 12) {
                    misses += "期望=$want 实际=${r.codes} | ${row.sms.take(60)}"
                }
                if (got.size > want.size) codeExtra++
            }

            if (row.address.isNotEmpty()) {
                val got = r.address.firstOrNull().orEmpty()
                if (got.isNotEmpty()) addressGot++
                if (got.isNotEmpty() && (got.contains(row.address) || row.address.contains(got))) addressOverlap++
            }
        }

        fun pct(n: Int, d: Int) = if (d == 0) "n/a" else "%.1f%% (%d/%d)".format(100.0 * n / d, n, d)

        println()
        println("=========== RuleBasedPickupParser 准确率评测 ===========")
        println("标注集        ${rows.size} 条")
        println("识别为取件短信 ${pct(classified, rows.size)}")
        println("取件码命中    ${pct(codeHit, codeExpected)}")
        println("多码误报      ${pct(codeExtra, codeExpected)}")
        println("地址提取非空  ${pct(addressGot, codeExpected)}")
        println("地址与标注重叠 ${pct(addressOverlap, codeExpected)}")
        if (misses.isNotEmpty()) {
            println("------------------ 前 ${misses.size} 条未命中 ------------------")
            misses.forEach { println("  $it") }
        } else if (!showSamples) {
            println("未命中 ${codeExpected - codeHit} 条，样例已省略（含真实短信与取件码，需 PICKUP_EVAL_SHOW_SAMPLES=1 才打印）")
        }
        println("========================================================")
        println()
    }

    /**
     * 反向验证：非取件短信被误判为取件短信的比例。
     *
     * 放宽任何排除规则都可能把垃圾抬进列表，所以每次调整 `EXCLUDE_PATTERNS`
     * 或引导词之后都要跑这个 —— 只看正样本命中率会得出越来越乐观的假象。
     *
     * 通过 `PICKUP_EVAL_NEGATIVES` 指向一个每行一条短信原文的文本文件；未设置则跳过。
     */
    @Test
    fun `在负样本上度量误判率`() {
        val path = System.getenv("PICKUP_EVAL_NEGATIVES")
        val file = path?.let(::File)
        Assume.assumeTrue("未设置 PICKUP_EVAL_NEGATIVES，跳过误判率评测", file != null && file.exists())

        val rows = file!!.readLines().filter { it.isNotBlank() }
        val falsePositives = rows.filter { RuleBasedPickupParser.parse(it).isPickupSms }
        val withBank = rows.filter { it.contains("银行") }
        val bankFalsePositives = withBank.filter { RuleBasedPickupParser.parse(it).isPickupSms }

        println()
        println("=========== RuleBasedPickupParser 误判率评测 ===========")
        println("负样本        ${rows.size} 条")
        println("误判为取件短信 ${"%.2f%% (%d/%d)".format(100.0 * falsePositives.size / rows.size, falsePositives.size, rows.size)}")
        println("含「银行」的负样本 ${withBank.size} 条，其中误判 ${bankFalsePositives.size} 条")
        if (showSamples) {
            falsePositives.take(8).forEach { println("  误判样例: ${it.take(70)}") }
        } else if (falsePositives.isNotEmpty()) {
            println("误判样例已省略（含真实短信，需 PICKUP_EVAL_SHOW_SAMPLES=1 才打印）")
        }
        println("========================================================")
        println()
    }
}
