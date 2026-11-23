/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import android.util.Log

/**
 * 短信模式学习器 - 从用户粘贴的短信中自动学习规则
 * 
 * 功能：
 * 1. 分析短信内容，提取取件码模式
 * 2. 识别驿站/快递公司名称
 * 3. 提取地址模式
 * 4. 生成自定义规则
 * 
 * 无需联网，完全本地处理
 */
object SmsPatternLearner {
    
    private const val TAG = "SmsPatternLearner"
    
    /**
     * 学习结果
     */
    data class LearningResult(
        val success: Boolean,
        val pickupCodePattern: String? = null,
        val stationName: String? = null,
        val companyName: String? = null,
        val addressPattern: String? = null,
        val confidence: Float = 0f,
        val message: String = ""
    )
    
    /**
     * 从短信中学习规则
     * 
     * @param smsContent 短信内容
     * @param userProvidedCode 用户提供的取件码（可选，用于辅助学习）
     * @return 学习结果
     */
    fun learnFromSms(
        smsContent: String,
        userProvidedCode: String? = null
    ): LearningResult {
        if (smsContent.isBlank()) {
            return LearningResult(false, message = "短信内容为空")
        }
        
        try {
            // 第一步：提取取件码
            val codeResult = extractPickupCode(smsContent, userProvidedCode)
            
            // 第二步：提取驿站信息
            val stationName = extractStationName(smsContent)
            
            // 第三步：提取快递公司
            val companyName = extractCompanyName(smsContent)
            
            // 第四步：提取地址
            val addressPattern = extractAddressPattern(smsContent)
            
            // 计算置信度
            val confidence = calculateConfidence(codeResult, stationName, companyName)
            
            // 生成规则
            if (codeResult != null) {
                return LearningResult(
                    success = true,
                    pickupCodePattern = codeResult,
                    stationName = stationName,
                    companyName = companyName,
                    addressPattern = addressPattern,
                    confidence = confidence,
                    message = "学习成功！已自动提取取件码规则"
                )
            } else {
                return LearningResult(
                    success = false,
                    stationName = stationName,
                    companyName = companyName,
                    addressPattern = addressPattern,
                    confidence = confidence,
                    message = "未能自动识别取件码，请手动指定"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "学习失败", e)
            return LearningResult(false, message = "学习过程出错: ${e.message}")
        }
    }
    
    /**
     * 提取取件码
     */
    private fun extractPickupCode(sms: String, userProvidedCode: String?): String? {
        // 如果用户提供了取件码，优先使用
        if (!userProvidedCode.isNullOrBlank()) {
            return normalizeCode(userProvidedCode)
        }
        
        // 自动识别取件码
        val codePatterns = listOf(
            // 带引号的取件码
            Regex("[\"\\u201c\\u201d]([A-Za-z]?\\d{1,3}[-.]\\d{1,3}[-.]\\d{1,6}|\\d{3,8})[\"\\u201c\\u201d]"),
            // 引导词+取件码
            Regex("(?:取件码|取件凭证|提货码|取件号)\\s*[为是:：]?\\s*([A-Za-z]?\\d{1,3}[-.]\\d{1,3}[-.]\\d{1,6}|\\d{3,8})"),
            // 通用格式
            Regex("[A-Z]\\d{1,2}-\\d{1,2}-\\d{3,5}"),
            Regex("\\d{1,2}-\\d{1,2}-\\d{3,5}"),
            Regex("[A-Z]\\d{1,2}-\\d{1,5}"),
            Regex("\\d{2}-\\d{3,4}"),
            Regex("\\d{4,6}")
        )
        
        for (pattern in codePatterns) {
            val match = pattern.find(sms)
            if (match != null) {
                val code = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                if (isValidCode(code)) {
                    return normalizeCode(code)
                }
            }
        }
        
        return null
    }
    
    /**
     * 提取驿站名称
     */
    private fun extractStationName(sms: String): String? {
        val stationPatterns = listOf(
            Regex("([\\u4e00-\\u9fa5]{2,4}驿站)"),
            Regex("(菜鸟驿站|妈妈驿站|兔喜生活|驿小哥|快宝驿站)"),
            Regex("(丰巢智能柜|丰巢快递柜|速递易|近邻宝|云柜)"),
            Regex("([\\u4e00-\\u9fa5]{2,6}(?:店|站|点|处))")
        )
        
        for (pattern in stationPatterns) {
            val match = pattern.find(sms)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }
    
    /**
     * 提取快递公司
     */
    private fun extractCompanyName(sms: String): String? {
        val companyPatterns = listOf(
            Regex("(顺丰|中通|圆通|韵达|申通|极兔|京东|邮政|EMS|德邦|菜鸟|百世|天天)"),
            Regex("(圆通快递|中通快递|韵达快递|申通快递|极兔速递|京东快递|顺丰快递|德邦快递|邮政快递)")
        )
        for (pattern in companyPatterns) {
            val match = pattern.find(sms)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }
    
    /**
     * 提取地址模式
     */
    private fun extractAddressPattern(sms: String): String? {
        val addressPatterns = listOf(
            Regex("(?:到|在|位于)([\\u4e00-\\u9fa5a-zA-Z\\d][\\u4e00-\\u9fa5a-zA-Z\\d\\-]{1,18}(?:号楼|栋|幢|座|单元|室|店|站|点|驿站|柜|门|层|房|区|巷|路|街|道|弄|里|园|苑|坊|阁|居|庭|邸|府|郡|城|镇|村|号))"),
            Regex("地址[：:]([^，。；;]{3,50})"),
            Regex("([\\u4e00-\\u9fa5][\\u4e00-\\u9fa5a-zA-Z\\d\\-]{1,19}(?:小区|花园|家园|公寓|大厦|大楼|广场|中心|新城|社区))")
        )
        
        for (pattern in addressPatterns) {
            val match = pattern.find(sms)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }
    
    /**
     * 计算置信度
     */
    private fun calculateConfidence(
        code: String?,
        station: String?,
        company: String?
    ): Float {
        var confidence = 0f
        if (code != null) confidence += 0.5f
        if (station != null) confidence += 0.25f
        if (company != null) confidence += 0.25f
        return confidence
    }
    
    /**
     * 标准化取件码
     */
    private fun normalizeCode(code: String): String {
        return code.trim().replace(" ", "")
    }
    
    /**
     * 验证取件码是否有效
     */
    private fun isValidCode(code: String?): Boolean {
        if (code == null || code.length < 3 || code.length > 15) return false
        if (code.matches(Regex("\\d{4}-\\d{1,2}-\\d{1,2}"))) return false
        if (code.matches(Regex("1[3-9]\\d{9}"))) return false
        if (code.endsWith("-")) return false
        return true
    }
    
    /**
     * 生成正则表达式规则
     */
    fun generateRegexPattern(code: String): String {
        return when {
            code.matches(Regex("[A-Z]\\d{1,2}-\\d{1,2}-\\d{3,5}")) -> 
                "[A-Z]\\d{1,2}-\\d{1,2}-\\d{3,5}"
            code.matches(Regex("\\d{1,2}-\\d{1,2}-\\d{3,5}")) -> 
                "\\d{1,2}-\\d{1,2}-\\d{3,5}"
            code.matches(Regex("[A-Z]\\d{1,2}-\\d{1,5}")) -> 
                "[A-Z]\\d{1,2}-\\d{1,5}"
            code.matches(Regex("\\d{2}-\\d{3,4}")) -> 
                "\\d{2}-\\d{3,4}"
            code.matches(Regex("\\d{4,6}")) -> 
                "\\d{4,6}"
            else -> Regex.escape(code)
        }
    }
    
    /**
     * 创建自定义规则
     */
    fun createCustomRule(
        ruleName: String,
        smsContent: String,
        pickupCode: String
    ): PickupCodeExtractorV3.CustomRule? {
        return try {
            val pattern = generateRegexPattern(pickupCode)
            PickupCodeExtractorV3.CustomRule(
                id = "learned_${System.currentTimeMillis()}",
                name = ruleName,
                pattern = pattern,
                priority = 100,
                isEnabled = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "创建规则失败", e)
            null
        }
    }
}
