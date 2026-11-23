/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自学习管理器 - 处理用户纠错和主动学习
 */
class SelfLearningManager private constructor(context: Context) {
    
    companion object {
        private const val TAG = "SelfLearningManager"
        private const val PREFS_NAME = "self_learning_prefs"
        private const val KEY_LEARNED_RULES = "learned_rules"
        private const val KEY_CORRECTION_HISTORY = "correction_history"
        const val CONFIDENCE_THRESHOLD = 0.6f
        const val MAX_LEARNED_RULES = 500
        const val MAX_HISTORY_SIZE = 1000

        /**
         * 内存优化：以下取件码形态正则原在 evaluateCodeConfidence / analyzeCodeFormat /
         * generatePattern 中每次调用都重新编译，批量导入与自学习时按取件码反复执行。
         * 提取为 companion 级常量后只编译一次（companion 仅随类加载一次）。
         */
        private val R_CODE_LETTER_NUM = Regex("[A-Z]\\d{2,3}")
        private val R_CODE_LETTER2_NUM = Regex("[A-Z]{2}\\d{1,3}")
        private val R_CODE_LETTER_DASH = Regex("[A-Z]-\\d{1,2}-\\d{3,5}")
        private val R_CODE_LETTER_DASH6 = Regex("[A-Z]-\\d{1,6}")
        private val R_CODE_NUM_DASH = Regex("\\d{1,2}-\\d{1,2}-\\d{3,5}")
        private val R_CODE_PURE_NUM = Regex("\\d{5,8}")

        @Volatile
        private var instance: SelfLearningManager? = null
        
        fun getInstance(context: Context): SelfLearningManager {
            return instance ?: synchronized(this) {
                instance ?: SelfLearningManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    data class CorrectionRecord(
        val timestamp: Long,
        val originalSms: String,
        val predictedCodes: List<String>,
        val correctedCodes: List<String>,
        val predictedAddress: String?,
        val correctedAddress: String?,
        val predictedCompany: String?,
        val correctedCompany: String?
    )
    
    data class LearnedRule(
        val id: String,
        val pattern: String,
        val codeFormat: String,
        val addressPattern: String?,
        val companyPattern: String?,
        val usageCount: Int,
        val successCount: Int,
        val lastUsed: Long
    )
    
    data class ConfidenceResult(
        val overallConfidence: Float,
        val codeConfidence: Float,
        val addressConfidence: Float,
        val companyConfidence: Float,
        val needsConfirmation: Boolean
    )
    
    fun recordCorrection(record: CorrectionRecord) {
        try {
            val history = getCorrectionHistory().toMutableList()
            history.add(0, record)
            if (history.size > MAX_HISTORY_SIZE) {
                history.subList(MAX_HISTORY_SIZE, history.size).clear()
            }
            saveCorrectionHistory(history)
            learnFromCorrection(record)
        } catch (e: Exception) {
            Log.e(TAG, "记录纠错失败", e)
        }
    }
    
    fun getCorrectionHistory(): List<CorrectionRecord> {
        return try {
            val json = prefs.getString(KEY_CORRECTION_HISTORY, "[]") ?: "[]"
            val array = JSONArray(json)
            (0 until array.length()).map { parseCorrectionRecord(array.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun learnFromCorrection(record: CorrectionRecord) {
        try {
            val rules = getLearnedRules().toMutableList()
            for (code in record.correctedCodes) {
                val format = analyzeCodeFormat(code)
                val pattern = generatePattern(code)
                val existingRule = rules.find { it.codeFormat == format }
                if (existingRule != null) {
                    val index = rules.indexOf(existingRule)
                    rules[index] = existingRule.copy(
                        usageCount = existingRule.usageCount + 1,
                        successCount = existingRule.successCount + 1,
                        lastUsed = System.currentTimeMillis()
                    )
                } else {
                    rules.add(0, LearnedRule(
                        id = "rule_${System.currentTimeMillis()}",
                        pattern = pattern,
                        codeFormat = format,
                        addressPattern = record.correctedAddress?.let { generateAddressPattern(it) },
                        companyPattern = record.correctedCompany,
                        usageCount = 1,
                        successCount = 1,
                        lastUsed = System.currentTimeMillis()
                    ))
                }
            }
            if (rules.size > MAX_LEARNED_RULES) {
                rules.sortWith(compareByDescending<LearnedRule> { it.successCount.toFloat() / maxOf(it.usageCount, 1) }
                    .thenByDescending { it.usageCount })
                rules.subList(MAX_LEARNED_RULES, rules.size).clear()
            }
            saveLearnedRules(rules)
        } catch (e: Exception) {
            Log.e(TAG, "学习失败", e)
        }
    }
    
    fun getLearnedRules(): List<LearnedRule> {
        return try {
            val json = prefs.getString(KEY_LEARNED_RULES, "[]") ?: "[]"
            val array = JSONArray(json)
            (0 until array.length()).map { parseLearnedRule(array.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun optimizeWithLearnedRules(
        sms: String,
        predictedCodes: List<String>,
        predictedAddress: String?,
        predictedCompany: String?
    ): Triple<List<String>, String?, String?> {
        val rules = getLearnedRules()
        if (rules.isEmpty()) return Triple(predictedCodes, predictedAddress, predictedCompany)
        
        val optimizedCodes = predictedCodes.toMutableList()
        var optimizedAddress = predictedAddress
        var optimizedCompany = predictedCompany
        
        for (rule in rules) {
            try {
                val regex = Regex(rule.pattern)
                val matches = regex.findAll(sms).map { it.value }.toList()
                for (match in matches) {
                    if (match !in optimizedCodes && isValidPickupCode(match)) {
                        optimizedCodes.add(match)
                    }
                }
                if (optimizedAddress == null && rule.addressPattern != null) {
                    val addressMatch = Regex(rule.addressPattern).find(sms)
                    if (addressMatch != null) optimizedAddress = addressMatch.value
                }
                if (optimizedCompany == null && rule.companyPattern != null) {
                    if (rule.companyPattern in sms) optimizedCompany = rule.companyPattern
                }
            } catch (e: Exception) {
                Log.e(TAG, "应用规则失败: ${rule.pattern}", e)
            }
        }
        return Triple(optimizedCodes, optimizedAddress, optimizedCompany)
    }
    
    fun evaluateConfidence(
        predictedCodes: List<String>,
        predictedAddress: String?,
        predictedCompany: String?
    ): ConfidenceResult {
        var codeConfidence = 0f
        if (predictedCodes.isNotEmpty()) {
            codeConfidence = predictedCodes.map { evaluateCodeConfidence(it) }.average().toFloat()
        }
        val addressConfidence = if (predictedAddress != null && predictedAddress.length >= 4) 0.8f else 0.3f
        val companyConfidence = if (predictedCompany != null) 0.9f else 0.2f
        val overall = (codeConfidence * 0.5f + addressConfidence * 0.25f + companyConfidence * 0.25f)
        return ConfidenceResult(overall, codeConfidence, addressConfidence, companyConfidence, overall < CONFIDENCE_THRESHOLD)
    }
    
    private fun evaluateCodeConfidence(code: String): Float {
        var score = 0.5f
        score += when (code.length) {
            in 3..8 -> 0.2f
            in 9..12 -> 0.1f
            else -> -0.1f
        }
        when {
            code.matches(R_CODE_LETTER_NUM) -> score += 0.2f
            code.matches(R_CODE_LETTER2_NUM) -> score += 0.2f
            code.matches(R_CODE_LETTER_DASH) -> score += 0.2f
            code.matches(R_CODE_NUM_DASH) -> score += 0.2f
            code.matches(R_CODE_PURE_NUM) -> score += 0.15f
        }
        val excludeWords = listOf("领取", "取件", "快递", "请", "小时", "有效", "逾期")
        if (excludeWords.any { it in code }) score -= 0.3f
        return score.coerceIn(0f, 1f)
    }
    
    fun addUserSample(sms: String, codes: List<String>, address: String?, company: String?) {
        recordCorrection(CorrectionRecord(
            System.currentTimeMillis(), sms, emptyList(), codes, null, address, null, company
        ))
    }
    
    fun getLearningStats(): Map<String, Any> {
        val rules = getLearnedRules()
        val history = getCorrectionHistory()
        val totalUsage = rules.sumOf { it.usageCount }
        val totalSuccess = rules.sumOf { it.successCount }
        return mapOf(
            "totalCorrections" to history.size,
            "totalRules" to rules.size,
            "totalUsage" to totalUsage,
            "avgSuccessRate" to if (totalUsage > 0) totalSuccess.toFloat() / totalUsage else 0f
        )
    }
    
    private fun analyzeCodeFormat(code: String): String = when {
        code.matches(R_CODE_LETTER_NUM) -> "LETTER_NUM"
        code.matches(R_CODE_LETTER2_NUM) -> "LETTER2_NUM"
        code.matches(R_CODE_LETTER_DASH) -> "LETTER_DASH_NUM_DASH_NUM"
        code.matches(R_CODE_LETTER_DASH6) -> "LETTER_DASH_NUM"
        code.matches(R_CODE_NUM_DASH) -> "NUM_DASH_NUM_DASH_NUM"
        code.matches(R_CODE_PURE_NUM) -> "PURE_NUM"
        else -> "UNKNOWN"
    }
    
    private fun generatePattern(code: String): String = when {
        code.matches(R_CODE_LETTER_NUM) -> "[A-Z]\\d{2,3}"
        code.matches(R_CODE_LETTER2_NUM) -> "[A-Z]{2}\\d{1,3}"
        code.matches(R_CODE_LETTER_DASH) -> "[A-Z]-\\d{1,2}-\\d{3,5}"
        code.matches(R_CODE_LETTER_DASH6) -> "[A-Z]-\\d{1,6}"
        code.matches(R_CODE_NUM_DASH) -> "\\d{1,2}-\\d{1,2}-\\d{3,5}"
        code.matches(R_CODE_PURE_NUM) -> "\\d{5,8}"
        else -> Regex.escape(code)
    }
    
    private fun generateAddressPattern(address: String): String {
        return Regex.escape(address).replace("\\d+", "\\\\d+")
    }
    
    private fun isValidPickupCode(code: String): Boolean {
        if (code.length < 2 || code.length > 12) return false
        if (!code.any { it.isDigit() }) return false
        if (code.all { it.isDigit() } && code.toIntOrNull() ?: 0 < 100) return false
        val excludeWords = listOf("领取", "取件", "快递", "请", "小时", "有效", "逾期", "包裹", "已到达")
        return excludeWords.none { it in code }
    }
    
    private fun parseCorrectionRecord(obj: JSONObject): CorrectionRecord {
        return CorrectionRecord(
            timestamp = obj.optLong("timestamp"),
            originalSms = obj.optString("originalSms"),
            predictedCodes = parseStringList(obj.optJSONArray("predictedCodes")),
            correctedCodes = parseStringList(obj.optJSONArray("correctedCodes")),
            predictedAddress = obj.optStringOrNull("predictedAddress"),
            correctedAddress = obj.optStringOrNull("correctedAddress"),
            predictedCompany = obj.optStringOrNull("predictedCompany"),
            correctedCompany = obj.optStringOrNull("correctedCompany")
        )
    }
    
    private fun parseLearnedRule(obj: JSONObject): LearnedRule {
        return LearnedRule(
            id = obj.optString("id"),
            pattern = obj.optString("pattern"),
            codeFormat = obj.optString("codeFormat"),
            addressPattern = obj.optStringOrNull("addressPattern"),
            companyPattern = obj.optStringOrNull("companyPattern"),
            usageCount = obj.optInt("usageCount"),
            successCount = obj.optInt("successCount"),
            lastUsed = obj.optLong("lastUsed")
        )
    }
    
    private fun parseStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { array.getString(it) }
    }

    /**
     * `JSONObject.optString(name, null)` 的类型安全替代。
     *
     * 原写法把字面量 `null` 传给了 Java 侧的平台类型参数（`String!`），
     * Kotlin 因此把实参推断成 `Nothing?`，全量重编译时逐条报：
     *
     *   `Java type mismatch: inferred type is 'kotlin.Nothing?',
     *    but 'kotlin.String' was expected`
     *
     * 与 L-18（`RuleBasedPickupParser`）、L-21（`PickupCodeExtractorV3`）
     * 是同一家族：**平台类型 + 不做空检查**。这里把返回值显式声明为 `String?`，
     * 既消掉警告，也把「键不存在、或值为 JSON null 时得到 null」这个语义
     * 写进签名，而不是靠调用点去猜。
     */
    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name)
    
    private fun saveCorrectionHistory(history: List<CorrectionRecord>) {
        val array = JSONArray()
        for (record in history) {
            val obj = JSONObject().apply {
                put("timestamp", record.timestamp)
                // 只落指纹，不落原文：这个字段全仓没有任何读取方（getLearningStats 只数条数），
                // 而"学习结果不影响解析"这条链路本身就是半成品（README 第 2 节）。
                // 明文存最多 1000 条短信正文，等于在本应用刻意清掉的两处（删除留档、小部件快照）
                // 之外，另开一个更大的原文留档。
                put("originalSms", privacyRef(record.originalSms))
                put("predictedCodes", JSONArray(record.predictedCodes))
                put("correctedCodes", JSONArray(record.correctedCodes))
                put("predictedAddress", record.predictedAddress ?: JSONObject.NULL)
                put("correctedAddress", record.correctedAddress ?: JSONObject.NULL)
                put("predictedCompany", record.predictedCompany ?: JSONObject.NULL)
                put("correctedCompany", record.correctedCompany ?: JSONObject.NULL)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_CORRECTION_HISTORY, array.toString()).apply()
    }
    
    private fun saveLearnedRules(rules: List<LearnedRule>) {
        val array = JSONArray()
        for (rule in rules) {
            val obj = JSONObject().apply {
                put("id", rule.id)
                put("pattern", rule.pattern)
                put("codeFormat", rule.codeFormat)
                put("addressPattern", rule.addressPattern ?: JSONObject.NULL)
                put("companyPattern", rule.companyPattern ?: JSONObject.NULL)
                put("usageCount", rule.usageCount)
                put("successCount", rule.successCount)
                put("lastUsed", rule.lastUsed)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_LEARNED_RULES, array.toString()).apply()
    }
}
