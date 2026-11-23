/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import android.util.Log
import java.util.regex.Pattern

/**
 * 取件码提取器 V3 - 优化版
 * - 多步正则替换策略
 * - 清理预处理机制
 * - 自定义规则系统
 * - 预编译正则表达式提升性能
 * - 多策略投票机制提高准确率
 */
object PickupCodeExtractorV3 {
    
    private const val TAG = "PickupCodeExtractorV3"

    // 内存优化：isValidCode 中排除日期/手机号的形态正则，原每次调用都重新 Regex(...)。
    private val R_INVALID_DATE = Regex("\\d{4}-\\d{1,2}-\\d{1,2}")
    private val R_INVALID_PHONE = Regex("1[3-9]\\d{9}")

    // ==================== 配置项 ====================
    
    /**
     * 功能开关配置
     */
    object Config {
        var enableMultiStepExtraction = true    // 启用多步提取
        var enablePreprocessing = true          // 启用预处理
        var enableCustomRules = true            // 启用自定义规则
        var enableParallelExecution = true      // 启用并行执行
        var enableVotingMechanism = true        // 启用投票机制
        var enablePerformanceLogging = true     // 启用性能日志
    }
    
    // ==================== 数据类 ====================
    
    /**
     * 提取结果
     */
    data class ExtractionResult(
        val codes: List<String> = emptyList(),
        val stationName: String? = null,
        val companyName: String? = null,
        val address: String? = null,
        val confidence: Double = 0.0,
        val strategy: String = "",
        val processingTimeMs: Long = 0L
    )
    
    /**
     * 自定义规则
     */
    data class CustomRule(
        val id: String,
        val name: String,
        val pattern: String,
        val priority: Int = 0,
        val isEnabled: Boolean = true
    )
    
    // ==================== 预编译正则表达式 ====================
    
    /**
     * 预编译的正则表达式缓存
     */
    private val regexCache = mutableMapOf<String, Pattern>()
    
    /**
     * 获取预编译的正则表达式
     */
    private fun getCachedPattern(pattern: String): Pattern {
        return regexCache.getOrPut(pattern) { Pattern.compile(pattern) }
    }
    
    // ==================== 预处理模块 ====================
    
    /**
     * 短信预处理
     * 借鉴otphelper的清理预处理机制
     */
    object Preprocessor {
        
        // 需要清理的模式
        private val CLEANUP_PATTERNS = listOf(
            Pattern.compile("https?://[\\w.-]+(?:/[\\w.-]*)*"),  // URL
            Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9-]{0,61}\\.[a-zA-Z]{2,}"),  // 域名
            Pattern.compile("[\"\\u201c\\u201d]"),  // 中文引号
            Pattern.compile("\\(\\d{1,3}\\)"),  // 括号内数字（如区号）
            Pattern.compile("\\s{2,}")  // 多余空格
        )
        
        /**
         * 清理短信内容
         */
        fun clean(text: String): String {
            var result = text
            for (pattern in CLEANUP_PATTERNS) {
                result = pattern.matcher(result).replaceAll(" ")
            }
            return result.trim()
        }
        
        /**
         * 快速判断是否为取件短信（预过滤）
         */
        fun quickFilter(text: String): Boolean {
            val keywords = listOf(
                "取件", "取件码", "快递", "驿站", "丰巢", "包裹",
                "提货码", "取货码", "快递码", "取件号", "取件凭证"
            )
            return keywords.any { text.contains(it, ignoreCase = true) }
        }
    }
    
    // ==================== 多步提取模块 ====================
    
    /**
     * 多步正则替换提取
     * 借鉴SmsForwarder的分步处理思想
     */
    object MultiStepExtractor {
        // 内存优化：以下模式原在每次 extract() 调用时重新 Pattern.compile，批量导入时反复编译。
        // 提取为对象级常量（object 单例，仅编译一次）以削减 GC 压力与短命对象。
        private val STATION_PATTERNS = listOf(
            Pattern.compile("([\\u4e00-\\u9fa5]{2,4}驿站)"),
            Pattern.compile("(菜鸟驿站|妈妈驿站|兔喜生活|驿小哥|快宝驿站)"),
            Pattern.compile("(丰巢智能柜|丰巢快递柜|速递易|近邻宝|云柜)"),
            Pattern.compile("([\\u4e00-\\u9fa5]{2,6}(?:店|站|点|处))")
        )
        private val QUOTED_CODE_PATTERN = Pattern.compile("[\"\\u201c\\u201d]([A-Za-z]?\\d{1,3}[-.]\\d{1,3}[-.]\\d{1,6}|\\d{3,8})[\"\\u201c\\u201d]")
        private val PREFIX_CODE_PATTERNS = listOf(
            Pattern.compile("(?:取件码|取件凭证|提货码|取件号)\\s*[为是:：]?\\s*([A-Za-z]?\\d{1,3}[-.]\\d{1,3}[-.]\\d{1,6}|\\d{3,8})"),
            Pattern.compile("(?:请凭|可凭|凭|输入)\\s*([A-Za-z]?\\d{1,3}[-.]\\d{1,3}[-.]\\d{1,6}|\\d{3,8})"),
            Pattern.compile("(?:到店|到)\\s*([A-Za-z]?\\d{1,3}[-.]\\d{1,3}[-.]\\d{1,6}|\\d{3,8})")
        )
        private val GENERIC_CODE_PATTERNS = listOf(
            Pattern.compile("[A-Z]\\d{1,2}-\\d{1,2}-\\d{3,5}"),
            Pattern.compile("\\d{1,2}-\\d{1,2}-\\d{3,5}"),
            Pattern.compile("[A-Z]\\d{1,2}-\\d{1,5}"),
            Pattern.compile("\\d{2}-\\d{3,4}"),
            Pattern.compile("\\d{4,6}")
        )
        private val COMPANY_PATTERNS = listOf(
            Pattern.compile("(顺丰|中通|圆通|韵达|申通|极兔|京东|邮政|EMS|德邦|菜鸟|百世|天天)"),
            Pattern.compile("(圆通快递|中通快递|韵达快递|申通快递|极兔速递|京东快递|顺丰快递|德邦快递|邮政快递)")
        )
        private val ADDRESS_PATTERNS = listOf(
            Pattern.compile("(?:到|在|位于)([\\u4e00-\\u9fa5a-zA-Z\\d][\\u4e00-\\u9fa5a-zA-Z\\d\\-]{1,18}(?:号楼|栋|幢|座|单元|室|店|站|点|驿站|柜|门|层|房|区|巷|路|街|道|弄|里|园|苑|坊|阁|居|庭|邸|府|郡|城|镇|村|号))"),
            Pattern.compile("地址[：:]([^，。；;]{3,50})"),
            Pattern.compile("([\\u4e00-\\u9fa5][\\u4e00-\\u9fa5a-zA-Z\\d\\-]{1,19}(?:小区|花园|家园|公寓|大厦|大楼|广场|中心|新城|社区))")
        )

        /**
         * 执行多步提取
         */
        fun extract(text: String): ExtractionResult {
            val startTime = System.currentTimeMillis()
            var workingText = text
            
            // 第一步：提取驿站/快递点信息
            val stationInfo = extractStationInfo(workingText)
            if (stationInfo != null) {
                workingText = workingText.replace(stationInfo, " ")
            }
            
            // 第二步：提取取件码
            val codes = extractCodesMultiStep(workingText, text)
            
            // 第三步：提取快递公司
            val company = extractCompany(text)
            
            // 第四步：提取地址
            val address = extractAddress(text, codes)
            
            val processingTime = System.currentTimeMillis() - startTime
            
            return ExtractionResult(
                codes = codes,
                stationName = stationInfo,
                companyName = company,
                address = address,
                confidence = calculateConfidence(codes, stationInfo, company),
                strategy = "multi_step",
                processingTimeMs = processingTime
            )
        }
        
        /**
         * 提取驿站信息
         */
        private fun extractStationInfo(text: String): String? {
            val stationPatterns = STATION_PATTERNS
            
            for (pattern in stationPatterns) {
                val matcher = pattern.matcher(text)
                if (matcher.find()) {
                    return matcher.group(1)
                }
            }
            return null
        }
        
        /**
         * 多步提取取件码
         */
        private fun extractCodesMultiStep(cleanedText: String, originalText: String): List<String> {
            val codes = mutableListOf<String>()
            
            // 策略1：带引号的取件码
            val quotedPattern = QUOTED_CODE_PATTERN
            val quotedMatcher = quotedPattern.matcher(originalText)
            while (quotedMatcher.find()) {
                // R5 / L-21：`Matcher.group()` 是 Java 平台类型（`String!`），Kotlin 不做空检查
                // 就交给需要非空 `String` 的位置。今天正则第 1 组必然存在所以不炸，
                // 但只要有人把该组改成可选 `(...)?`，立刻变成一条没有任何提示的运行时 NPE。
                // 与 R2 修的 L-18 是同一个家族的问题，只是这个文件当时没被重编译、警告没露面。
                quotedMatcher.group(1)?.let { codes.add(it) }
            }
            
            // 策略2：引导词+取件码
            if (codes.isEmpty()) {
                val prefixPatterns = PREFIX_CODE_PATTERNS
                for (pattern in prefixPatterns) {
                    val matcher = pattern.matcher(originalText)
                    while (matcher.find()) {
                        // R5 / L-21：同上，`group(1)` 是平台类型，先做空安全再使用
                        matcher.group(1)?.let { code ->
                            if (isValidCode(code) && !codes.contains(code)) {
                                codes.add(code)
                            }
                        }
                    }
                    if (codes.isNotEmpty()) break
                }
            }
            
            // 策略3：通用格式匹配
            if (codes.isEmpty()) {
                val genericPatterns = GENERIC_CODE_PATTERNS
                for (pattern in genericPatterns) {
                    val matcher = pattern.matcher(cleanedText)
                    while (matcher.find()) {
                        val code = matcher.group()
                        if (isValidCode(code) && !codes.contains(code)) {
                            codes.add(code)
                        }
                    }
                    if (codes.isNotEmpty()) break
                }
            }
            
            return codes.distinct()
        }
        
        /**
         * 提取快递公司
         */
        private fun extractCompany(text: String): String? {
            val companyPatterns = COMPANY_PATTERNS
            for (pattern in companyPatterns) {
                val matcher = pattern.matcher(text)
                if (matcher.find()) {
                    return matcher.group(1)
                }
            }
            return null
        }
        
        /**
         * 提取地址
         */
        private fun extractAddress(text: String, codes: List<String>): String? {
            var cleanedText = text
            // 移除取件码
            for (code in codes) {
                cleanedText = cleanedText.replace(code, "")
            }
            
            val addressPatterns = ADDRESS_PATTERNS
            
            for (pattern in addressPatterns) {
                val matcher = pattern.matcher(cleanedText)
                if (matcher.find()) {
                    return matcher.group(1)
                }
            }
            return null
        }
        
        /**
         * 计算置信度
         */
        private fun calculateConfidence(codes: List<String>, station: String?, company: String?): Double {
            var confidence = 0.0
            if (codes.isNotEmpty()) confidence += 0.5
            if (station != null) confidence += 0.25
            if (company != null) confidence += 0.25
            return confidence
        }
    }
    
    // ==================== 自定义规则模块 ====================
    
    /**
     * 自定义规则引擎：允许用户自己写正则来匹配短信。
     *
     * ## R5 / M-06：关于原来那句"借鉴取件码APP的用户自定义规则思想"
     *
     * 原文只写了"取件码APP"，**没有仓库地址、没有版本、没有许可证名**。
     * 这对 GPL 系合规是个问题：能定位到具体作品与条款才算可核查的归属，
     * 一个叫不出来的名字既不能确认也无从证伪 —— 它比不写更糟，
     * 因为读者会以为"已经声明过了"。
     *
     * 本轮**不擅自填入一个猜测的上游**（那等于编造出处）。
     * 改成的表述是事实：**"用户用正则自定义解析规则"是这类工具里的通用做法**
     * （DMS/短信转发器类应用普遍提供），并非某一家的独创设计；
     * 本实现（`CustomRuleEngine` 数据结构 + 用户手动录入正则）
     * 为独立编写，未复制任何上游代码。
     *
     * 如果原作者确实记得当初参考的是哪一个应用，**请补上仓库地址与许可证**；
     * 补不了的话，就保持现在这种"只描述想法、不认领未核实的出处"的写法。
     */
    object CustomRuleEngine {
        
        private val customRules = mutableListOf<CustomRule>()
        
        /**
         * 添加自定义规则
         */
        fun addRule(rule: CustomRule) {
            customRules.add(rule)
            customRules.sortByDescending { it.priority }
        }
        
        /**
         * 移除自定义规则
         */
        fun removeRule(ruleId: String) {
            customRules.removeAll { it.id == ruleId }
        }
        
        /**
         * 获取所有规则
         */
        fun getRules(): List<CustomRule> = customRules.toList()
        
        /**
         * 使用自定义规则提取
         */
        fun extractWithRules(text: String): List<String> {
            val codes = mutableListOf<String>()
            
            for (rule in customRules.filter { it.isEnabled }) {
                try {
                    val pattern = getCachedPattern(rule.pattern)
                    val matcher = pattern.matcher(text)
                    while (matcher.find()) {
                        val code = if (matcher.groupCount() > 0) matcher.group(1) else matcher.group()
                        if (isValidCode(code) && !codes.contains(code)) {
                            codes.add(code)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "自定义规则执行失败: ${rule.name}", e)
                }
            }
            
            return codes
        }
        
        /**
         * 加载默认规则
         */
        fun loadDefaultRules() {
            val defaultRules = listOf(
                CustomRule("cainiao", "菜鸟驿站", "取件码[：:]?\\s*([0-9]{2}-[0-9]-[0-9]{4})", 100),
                CustomRule("fengchao", "丰巢", "取件码[：:]?\\s*([0-9]{4,6})", 100),
                CustomRule("jd", "京东", "([0-9]{1,2}-[0-9]{1,2}-[0-9]{3,5})", 100),
                CustomRule("yt", "圆通", "C[0-9]{2}-[0-9]-[0-9]{5}", 100),
                CustomRule("jt", "极兔", "B[0-9]-[0-9]-[0-9]{5}", 100),
                CustomRule("generic", "通用规则", "(?:取件码|取件号|提货码)[：:]?\\s*([A-Za-z]?\\d{1,3}[-.]\\d{1,3}[-.]\\d{1,6}|\\d{3,8})", 50)
            )
            defaultRules.forEach { addRule(it) }
        }
    }
    
    // ==================== 投票机制模块 ====================
    
    /**
     * 多策略投票机制
     */
    object VotingMechanism {
        
        /**
         * 执行投票
         */
        fun vote(text: String, results: List<ExtractionResult>): ExtractionResult {
            if (results.isEmpty()) {
                return ExtractionResult(strategy = "empty")
            }
            
            if (results.size == 1) {
                return results[0]
            }
            
            // 收集所有提取到的取件码
            val allCodes = mutableMapOf<String, Int>()
            for (result in results) {
                for (code in result.codes) {
                    allCodes[code] = (allCodes[code] ?: 0) + 1
                }
            }
            
            // 选择出现次数最多的取件码
            val winningCodes = allCodes.entries
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key }
            
            // 选择置信度最高的结果作为基础
            val bestResult = results.maxByOrNull { it.confidence } ?: results[0]
            
            return bestResult.copy(
                codes = winningCodes,
                strategy = "voted"
            )
        }
    }
    
    // ==================== 主提取函数 ====================
    
    /**
     * 提取取件码（主入口）
     * 
     * @param smsBody 短信内容
     * @param senderNumber 发送号码
     * @return 提取结果
     */
    fun extract(smsBody: String?, senderNumber: String? = null): ExtractionResult {
        if (smsBody.isNullOrBlank()) {
            return ExtractionResult(strategy = "empty_input")
        }
        
        val startTime = System.currentTimeMillis()
        
        // 预处理
        val cleanedText = if (Config.enablePreprocessing) {
            Preprocessor.clean(smsBody)
        } else {
            smsBody
        }
        
        // 快速过滤
        if (Config.enablePreprocessing && !Preprocessor.quickFilter(cleanedText)) {
            return ExtractionResult(strategy = "quick_filtered")
        }
        
        val results = mutableListOf<ExtractionResult>()
        
        // 策略1：多步提取
        if (Config.enableMultiStepExtraction) {
            val multiStepResult = MultiStepExtractor.extract(cleanedText)
            results.add(multiStepResult)
        }
        
        // 策略2：自定义规则提取
        if (Config.enableCustomRules && CustomRuleEngine.getRules().isNotEmpty()) {
            val customCodes = CustomRuleEngine.extractWithRules(cleanedText)
            if (customCodes.isNotEmpty()) {
                results.add(
                    ExtractionResult(
                        codes = customCodes,
                        strategy = "custom_rules",
                        confidence = 0.7
                    )
                )
            }
        }
        
        // 策略3：使用现有解析器（PickupSmsParserV2）
        val existingResult = PickupSmsParserV2.parse(cleanedText, senderNumber)
        if (existingResult.isPickup()) {
            results.add(
                ExtractionResult(
                    codes = existingResult.codes,
                    stationName = existingResult.stations.firstOrNull(),
                    companyName = existingResult.courier,
                    address = existingResult.address,
                    confidence = when (existingResult.confidence) {
                        "CONFIRMED" -> 0.9
                        "PROBABLE" -> 0.7
                        "POSSIBLE" -> 0.5
                        else -> 0.3
                    },
                    strategy = "existing_parser"
                )
            )
        }
        
        // 投票机制选择最终结果
        val finalResult = if (Config.enableVotingMechanism && results.size > 1) {
            VotingMechanism.vote(cleanedText, results)
        } else {
            results.maxByOrNull { it.codes.size * 100 + (it.confidence * 100).toInt() }
                ?: ExtractionResult(strategy = "no_result")
        }
        
        val totalTime = System.currentTimeMillis() - startTime
        
        if (Config.enablePerformanceLogging) {
            Log.d(TAG, "提取完成: codes=${finalResult.codes}, strategy=${finalResult.strategy}, time=${totalTime}ms")
        }
        
        return finalResult.copy(processingTimeMs = totalTime)
    }
    
    /**
     * 批量提取
     */
    fun extractBatch(smsList: List<String>): List<ExtractionResult> {
        return smsList.map { extract(it) }
    }
    
    // ==================== 工具函数 ====================
    
    /**
     * 验证取件码是否有效
     */
    private fun isValidCode(code: String?): Boolean {
        if (code == null || code.length < 3 || code.length > 15) return false
        if (code.matches(R_INVALID_DATE)) return false  // 排除日期格式
        if (code.matches(R_INVALID_PHONE)) return false  // 排除手机号
        if (code.endsWith("-")) return false
        return true
    }
    
    /**
     * 初始化默认规则
     */
    fun initialize() {
        CustomRuleEngine.loadDefaultRules()
        Log.d(TAG, "PickupCodeExtractorV3 初始化完成")
    }
    
    /**
     * 重置配置
     */
    fun resetConfig() {
        Config.enableMultiStepExtraction = true
        Config.enablePreprocessing = true
        Config.enableCustomRules = true
        Config.enableParallelExecution = true
        Config.enableVotingMechanism = true
        Config.enablePerformanceLogging = true
    }
}
