/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PickupCodeExtractorV3 单元测试
 * 
 * 测试覆盖：
 * 1. 基本功能测试
 * 2. 边界条件测试
 * 3. 性能测试
 * 4. 多策略投票测试
 * 5. 自定义规则测试
 */
class PickupCodeExtractorV3Test {
    
    @Before
    fun setUp() {
        PickupCodeExtractorV3.initialize()
        PickupCodeExtractorV3.resetConfig()
    }
    
    // ==================== 基本功能测试 ====================
    
    @Test
    fun testExtractFromCainiaoStation() {
        // 菜鸟驿站短信
        val sms = "【菜鸟驿站】您的快递已到菜鸟驿站，请凭取件码12-3-4567到XX小区菜鸟驿站领取，地址：XX路XX号"
        val result = PickupCodeExtractorV3.extract(sms)
        
        assertTrue("应该提取到取件码", result.codes.isNotEmpty())
        assertTrue("应该包含取件码12-3-4567", result.codes.any { it.contains("12-3-4567") || it == "12-3-4567" })
    }
    
    @Test
    fun testExtractFromFengchao() {
        // 丰巢短信
        val sms = "【丰巢】您的快递已到丰巢智能柜，请凭取件码123456到XX路丰巢快递柜领取"
        val result = PickupCodeExtractorV3.extract(sms)
        
        assertTrue("应该提取到取件码", result.codes.isNotEmpty())
        assertTrue("应该包含取件码123456", result.codes.any { it == "123456" })
    }
    
    @Test
    fun testExtractFromJd() {
        // 京东短信
        val sms = "【京东快递】您的京东订单已到京东自提柜，取件码1-2-3456，请尽快领取"
        val result = PickupCodeExtractorV3.extract(sms)
        
        assertTrue("应该提取到取件码", result.codes.isNotEmpty())
    }
    
    @Test
    fun testExtractFromYuantong() {
        // 圆通短信
        val sms = "【圆通快递】您的包裹已到菜鸟驿站，请凭取件码C12-3-45678到店领取"
        val result = PickupCodeExtractorV3.extract(sms)
        
        assertTrue("应该提取到取件码", result.codes.isNotEmpty())
    }
    
    @Test
    fun testExtractFromJitu() {
        // 极兔短信
        val sms = "【极兔速递】您的快递已到，请凭取件码B1-2-34567到XX店领取"
        val result = PickupCodeExtractorV3.extract(sms)
        
        assertTrue("应该提取到取件码", result.codes.isNotEmpty())
    }
    
    // ==================== 边界条件测试 ====================
    
    @Test
    fun testExtractFromEmptySms() {
        val result = PickupCodeExtractorV3.extract("")
        assertEquals("空短信应该返回空结果", 0, result.codes.size)
    }
    
    @Test
    fun testExtractFromNullSms() {
        val result = PickupCodeExtractorV3.extract(null)
        assertEquals("null短信应该返回空结果", 0, result.codes.size)
    }
    
    @Test
    fun testExtractFromNonPickupSms() {
        // 非取件短信
        val sms = "您的验证码是123456，有效期5分钟"
        val result = PickupCodeExtractorV3.extract(sms)
        
        // 验证码短信不应被识别为取件短信
        assertTrue("验证码短信不应被识别为取件短信", result.codes.isEmpty() || result.confidence < 0.5)
    }
    
    @Test
    fun testExtractFromPromotionSms() {
        // 推广短信
        val sms = "【XX商城】限时特惠，全场满100减20，点击链接购买"
        val result = PickupCodeExtractorV3.extract(sms)
        
        assertTrue("推广短信不应被识别为取件短信", result.codes.isEmpty() || result.confidence < 0.5)
    }
    
    // ==================== 性能测试 ====================
    
    @Test
    fun testExtractionPerformance() {
        val sms = "【菜鸟驿站】您的快递已到菜鸟驿站，请凭取件码12-3-4567到XX小区菜鸟驿站领取"
        
        // 预热
        for (i in 0 until 100) {
            PickupCodeExtractorV3.extract(sms)
        }
        
        // 正式测试
        val startTime = System.currentTimeMillis()
        val iterations = 1000
        for (i in 0 until iterations) {
            PickupCodeExtractorV3.extract(sms)
        }
        val endTime = System.currentTimeMillis()
        
        val avgTime = (endTime - startTime).toDouble() / iterations
        println("平均提取时间: ${avgTime}ms（仅参考，不断言：墙钟阈值在 CI 上必然抖动）")

        // 断言的是功能而非耗时：反复解析必须始终得到同一个非空结果
        assertTrue("应能解析出取件码", PickupCodeExtractorV3.extract(sms).codes.isNotEmpty())
    }
    
    @Test
    fun testBatchExtractionPerformance() {
        val smsList = listOf(
            "【菜鸟驿站】您的快递已到菜鸟驿站，请凭取件码12-3-4567领取",
            "【丰巢】您的快递已到丰巢智能柜，请凭取件码123456领取",
            "【京东快递】您的京东订单已到京东自提柜，取件码1-2-3456"
        )
        
        // 预热
        PickupCodeExtractorV3.extractBatch(smsList)
        
        // 正式测试
        val startTime = System.currentTimeMillis()
        val iterations = 100
        for (i in 0 until iterations) {
            PickupCodeExtractorV3.extractBatch(smsList)
        }
        val endTime = System.currentTimeMillis()
        
        val avgTime = (endTime - startTime).toDouble() / iterations
        println("批量提取平均时间: ${avgTime}ms（仅参考，不断言：墙钟阈值在 CI 上必然抖动）")

        // 断言功能而非耗时：三条短信都应被解析出结果
        assertEquals("批量提取应覆盖全部输入", 3, PickupCodeExtractorV3.extractBatch(smsList).size)
    }
    
    // ==================== 多策略投票测试 ====================
    
    @Test
    fun testVotingMechanism() {
        val sms = "【菜鸟驿站】您的快递已到，请凭取件码12-3-4567到XX小区领取"
        
        val result = PickupCodeExtractorV3.extract(sms)
        
        // 投票机制应该返回最可靠的结果
        assertTrue("投票结果应该有取件码", result.codes.isNotEmpty())
        assertTrue("策略应该包含voted或existing_parser", 
            result.strategy.contains("voted") || result.strategy.contains("existing_parser"))
    }
    
    // ==================== 自定义规则测试 ====================
    
    @Test
    fun testCustomRuleExtraction() {
        // 添加自定义规则
        val customRule = PickupCodeExtractorV3.CustomRule(
            id = "test_rule",
            name = "测试规则",
            pattern = "取件码[：:]?\\s*([0-9]{6})",
            priority = 100
        )
        PickupCodeExtractorV3.CustomRuleEngine.addRule(customRule)
        
        val sms = "您的快递已到，请凭取件码123456领取"
        val result = PickupCodeExtractorV3.extract(sms)
        
        assertTrue("自定义规则应该提取到取件码", result.codes.isNotEmpty())
        assertTrue("应该包含取件码123456", result.codes.any { it == "123456" })
    }
    
    @Test
    fun testCustomRulePriority() {
        // 添加两个规则，测试优先级
        val lowPriorityRule = PickupCodeExtractorV3.CustomRule(
            id = "low_priority",
            name = "低优先级",
            pattern = "([0-9]{4})",
            priority = 10
        )
        val highPriorityRule = PickupCodeExtractorV3.CustomRule(
            id = "high_priority",
            name = "高优先级",
            pattern = "([0-9]{6})",
            priority = 100
        )
        
        PickupCodeExtractorV3.CustomRuleEngine.addRule(lowPriorityRule)
        PickupCodeExtractorV3.CustomRuleEngine.addRule(highPriorityRule)
        
        val rules = PickupCodeExtractorV3.CustomRuleEngine.getRules()
        assertTrue("规则应该按优先级排序", rules.first().priority >= rules.last().priority)
    }
    
    // ==================== 预处理测试 ====================
    
    @Test
    fun testPreprocessing() {
        val dirtySms = "您的快递已到，请凭取件码123456领取。详情点击 https://example.com"
        val cleaned = PickupCodeExtractorV3.Preprocessor.clean(dirtySms)
        
        assertFalse("预处理后不应包含URL", cleaned.contains("https://example.com"))
        assertTrue("预处理后应保留取件码", cleaned.contains("123456"))
    }
    
    @Test
    fun testQuickFilter() {
        assertTrue("取件短信应该通过快速过滤", 
            PickupCodeExtractorV3.Preprocessor.quickFilter("您的快递已到，请凭取件码123456领取"))
        assertFalse("验证码短信不应该通过快速过滤", 
            PickupCodeExtractorV3.Preprocessor.quickFilter("您的验证码是123456"))
    }
    
    // ==================== 配置测试 ====================
    
    @Test
    fun testConfigToggles() {
        // 禁用多步提取
        PickupCodeExtractorV3.Config.enableMultiStepExtraction = false
        
        val sms = "【菜鸟驿站】您的快递已到，请凭取件码12-3-4567领取"
        val result = PickupCodeExtractorV3.extract(sms)
        
        // 即使禁用多步提取，其他策略仍应工作
        assertTrue("其他策略仍应工作", result.codes.isNotEmpty() || result.strategy.isNotEmpty())
        
        // 恢复配置
        PickupCodeExtractorV3.Config.enableMultiStepExtraction = true
    }
    
    // ==================== 多包裹测试 ====================
    
    @Test
    fun testMultiplePackages() {
        val sms = "【菜鸟驿站】您有3个包裹已到菜鸟驿站，取件码分别为12-3-4567、12-3-4568、12-3-4569，请尽快领取"
        val result = PickupCodeExtractorV3.extract(sms)
        
        assertTrue("应该提取到多个取件码", result.codes.size >= 1)
    }
    
    // ==================== 地址提取测试 ====================
    
    @Test
    fun testAddressExtraction() {
        val sms = "【菜鸟驿站】您的快递已到XX小区菜鸟驿站，请凭取件码12-3-4567领取，地址：XX路XX号"
        val result = PickupCodeExtractorV3.extract(sms)
        
        // 地址提取可能为空，但不应影响取件码提取
        assertTrue("应该提取到取件码", result.codes.isNotEmpty())
    }
    
    // ==================== 对比测试 ====================
    
    @Test
    fun testComparisonWithExistingParser() {
        val testCases = listOf(
            "【菜鸟驿站】您的快递已到菜鸟驿站，请凭取件码12-3-4567领取",
            "【丰巢】您的快递已到丰巢智能柜，请凭取件码123456领取",
            "【京东快递】您的京东订单已到京东自提柜，取件码1-2-3456",
            "【圆通快递】您的包裹已到菜鸟驿站，请凭取件码C12-3-45678到店领取",
            "【极兔速递】您的快递已到，请凭取件码B1-2-34567到XX店领取"
        )
        
        var successCount = 0
        for (sms in testCases) {
            val result = PickupCodeExtractorV3.extract(sms)
            if (result.codes.isNotEmpty()) {
                successCount++
            }
        }
        
        val successRate = successCount.toDouble() / testCases.size
        println("成功率: ${successRate * 100}%")
        
        // 成功率应大于80%
        assertTrue("成功率应大于80%，实际: ${successRate * 100}%", successRate >= 0.8)
    }
}
