/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import java.util.regex.Pattern

/**
 * 基于规则的取件码短信解析器 v3
 * 
 * 修复内容：
 * 1. 地址泛化能力：使用通用地址模式，不依赖特定小区名
 * 2. 添加缺失签名：兔喜生活、邮政电商标快
 * 3. 无取件码识别：明确标识无取件码的取件短信
 * 4. 数据质量检查：清洗数据，修复错误期望值
 * 5. 引号处理：支持凭"12-34567"这种带引号的取件码
 * 6. 措辞变化：支持"可凭"、"请用"、"使用"等多种引导词
 * 7. ASCII字母地址：支持"A2组团"、"B座"等含字母的地址
 * 8. 亮灯取件：支持"输入<码>或扫码亮灯取件"这类引导词编码
 * 9. 地址房间号：支持单元/室后的房间号（如104、301）
 * 10. 前瞻约束修复：放宽地址前瞻，支持数字（房间号）在后缀后
 * 
 * 优势：
 * - 无需ML模型，纯规则匹配
 * - 准确率请用 RuleBasedPickupParserAccuracyEval 实测，勿在注释里写无口径的数字
 * - 代码量小，易于维护
 * - 性能开销极低
 * - 支持所有主流快递公司
 */
object RuleBasedPickupParser {
    
    private const val TAG = "RuleBasedPickupParser"

    /**
     * 内存优化（#42）：以下正则原在函数内每次解析短信时重新编译，
     * 批量导入上千条短信会累积大量短命 Pattern/Regex 对象，加剧 GC 与内存抖动。
     * 提取为对象级常量后只编译一次，热路径零分配（Kotlin object 的 val 即静态单例）。
     */

    /** 提取【】或 [] 包裹的签名，如「【菜鸟驿站】」 */
    private val SIGNATURE_BRACKET_PATTERN =
        Pattern.compile("[【\\[]([^】\\]]+)[】\\]]")

    /** 取件码后面紧跟的地址（"地址:XXX" 或 "到XXX" 等格式） */
    private val ADDRESS_AFTER_CODE_PATTERN = Pattern.compile(
        "[，,、\\s]*(?:地址[：:]\\s*)?([^，。；;]{3,30}(?:号楼|栋|幢|座|单元|室|店|站|点|驿站|柜|门|层|房|区|巷|路|街|道|弄|里|园|苑|坊|阁|居|庭|村|号))"
    )

    /** 折叠连续重复的地址后缀（如「5号柜柜」→「5号柜」），不动「4号柜F01」这类正常间隔 */
    private val ADDRESS_SUFFIX_DUPLICATE_REGEX =
        Regex("([柜站点店楼室房区路街道])\\1+")

    /**
     * 短信里**显式标注**的「地址：」标签。
     *
     * 京东这类短信会先写「已送达 XX 服务站」，再另起一句「地址：详细位置」。
     * 只靠引导词/后缀推断，会把「您的快件尾号0159已送达铜山区利国镇服务站」
     * 整句提示语当成地址，而真正带标签的位置只是并列候选中的第二个。
     * 标签内容取到第一个句读为止，短链接与「取件和寄件可咨询」等尾句天然被挡在外面。
     */
    private val EXPLICIT_ADDRESS_PATTERN =
        Pattern.compile("地址[：:]\\s*([^，,。；;！!\\n]{2,60})")

    /** 「地址：…京东快递店 / …菜鸟驿站」这类门店落款不是位置描述，去掉后括号方位补充仍保留。 */
    private val STORE_TAIL_REGEX = Regex(
        "(?:[\\u4e00-\\u9fa5A-Za-z]{0,4}(?:快递|速递|物流|邮政|EMS))?" +
            "(?:店|服务站|服务点|网点|营业部|自提点|驿站)$"
    )

    /**
     * 关键修复：地址覆盖表
     *
     * 根因：用户在学习模式中手动纠正地址后，RuleBasedPickupParser 的正则
     * 仍然会匹配出错误地址（如"示例小区1号楼1单元101取运单尾号"）。
     * 因为 RuleBasedPickupParser 完全不读取 SelfLearningManager 的学习结果。
     *
     * 解决：维护一张「原始地址 → 纠正后地址」的映射表，持久化到 SP。
     * extractAddress() 完成正则匹配后，逐一查覆盖表替换。
     * 用户在学习/编辑模式保存纠正时调用 saveAddressOverride()。
     */
    private const val ADDRESS_OVERRIDE_PREFS = "address_overrides"
    private var addressOverrideMap: Map<String, String> = emptyMap()
    private var overridesLoaded = false

    /**
     * 加载地址覆盖表（应在 Application.onCreate 中调用）
     */
    fun loadAddressOverrides(context: android.content.Context) {
        try {
            val prefs = context.getSharedPreferences(ADDRESS_OVERRIDE_PREFS, android.content.Context.MODE_PRIVATE)
            val all = prefs.all
            addressOverrideMap = all.mapNotNull { (k, v) ->
                if (v is String) k to v else null
            }.toMap()
            overridesLoaded = true
            android.util.Log.d(TAG, "地址覆盖表已加载: ${addressOverrideMap.size} 条")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "加载地址覆盖表失败", e)
        }
    }

    /**
     * 保存一条地址纠正（原始地址 → 纠正后地址）
     *
     * @param context Context
     * @param originalAddress 正则匹配出的原始错误地址（如"示例小区1号楼1单元101取运单尾号"）
     * @param correctedAddress 用户手动纠正后的地址（如"示例小区1号楼1单元101"）
     */
    fun saveAddressOverride(context: android.content.Context, originalAddress: String, correctedAddress: String) {
        try {
            val prefs = context.getSharedPreferences(ADDRESS_OVERRIDE_PREFS, android.content.Context.MODE_PRIVATE)
            prefs.edit().putString(originalAddress, correctedAddress).apply()
            // 同步更新内存缓存
            addressOverrideMap = addressOverrideMap + (originalAddress to correctedAddress)
            overridesLoaded = true
            android.util.Log.d(TAG, "地址覆盖已保存: ${privacyRef(originalAddress)} → ${privacyRef(correctedAddress)}")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "保存地址覆盖失败", e)
        }
    }

    /**
     * 查询地址覆盖表
     *
     * 优先精确匹配，其次尝试前缀匹配（覆盖表中的 key 可能是地址的前缀）
     */
    private fun lookupAddressOverride(address: String): String? {
        if (!overridesLoaded || addressOverrideMap.isEmpty()) return null
        // 精确匹配
        addressOverrideMap[address]?.let { return it }
        // 前缀匹配：覆盖表中的 key 是 address 的子串
        for ((key, value) in addressOverrideMap) {
            if (address.startsWith(key) && key.length >= 4) return value
            if (key.startsWith(address) && address.length >= 4) return value
        }
        return null
    }
    
    // ==================== 数据类 ====================
    
    data class ParseResult(
        val isPickupSms: Boolean = false,
        val codes: List<String> = emptyList(),
        val company: List<String> = emptyList(),
        val address: List<String> = emptyList(),
        val trackingNumber: String? = null,
        val packageCount: Int? = null,
        val hasNoCode: Boolean = false,
        val rawSms: String = ""
    )
    
    // ==================== 签名-格式映射表 ====================
    
    private data class SignatureFormat(
        val codePatterns: List<Pattern>,
        val companyName: String
    )
    
    private val SIGNATURE_FORMATS = mapOf(
        // 菜鸟驿站系统
        "菜鸟驿站" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d-\\d{4}"), Pattern.compile("[A-Z]\\d-\\d-\\d{4}")),
            "菜鸟驿站"
        ),
        "菜鸟" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d-\\d{4}"), Pattern.compile("[A-Z]\\d-\\d-\\d{4}")),
            "菜鸟驿站"
        ),
        // 申通快递系统
        "申通快递" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d{3,4}"), Pattern.compile("\\d{2}-\\d-\\d{4}")),
            "申通快递"
        ),
        "申通" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d{3,4}"), Pattern.compile("\\d{2}-\\d-\\d{4}")),
            "申通快递"
        ),
        // 驿小哥系统
        "驿小哥" to SignatureFormat(
            listOf(Pattern.compile("\\d-\\d-\\d{3,4}")),
            "驿小哥"
        ),
        // 韵达快递系统
        "韵达快递" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d{3,4}")),
            "韵达快递"
        ),
        "韵达" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d{3,4}")),
            "韵达快递"
        ),
        "韵达速递" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d{3,4}")),
            "韵达快递"
        ),
        // 圆通快递系统（菜鸟入库 + 亮灯取件）
        // 修复：C 后面支持 1~2 位数字
        // 兼容格式：C4-6-11111（实测：1位）、C12-3-22222（2位）
        "圆通快递" to SignatureFormat(
            listOf(Pattern.compile("C\\d{1,2}-\\d-\\d{5}"), Pattern.compile("\\d{2}-\\d{3,4}"), Pattern.compile("\\d{4,6}")),
            "圆通快递"
        ),
        "圆通" to SignatureFormat(
            listOf(Pattern.compile("C\\d{1,2}-\\d-\\d{5}"), Pattern.compile("\\d{2}-\\d{3,4}"), Pattern.compile("\\d{4,6}")),
            "圆通快递"
        ),
        "圆通速递" to SignatureFormat(
            listOf(Pattern.compile("C\\d{1,2}-\\d-\\d{5}"), Pattern.compile("\\d{2}-\\d{3,4}"), Pattern.compile("\\d{4,6}")),
            "圆通快递"
        ),
        // 极兔速递系统
        "极兔速递" to SignatureFormat(
            listOf(Pattern.compile("B\\d-\\d-\\d{5}"), Pattern.compile("\\d{2}-\\d{3,4}")),
            "极兔速递"
        ),
        "极兔" to SignatureFormat(
            listOf(Pattern.compile("B\\d-\\d-\\d{5}"), Pattern.compile("\\d{2}-\\d{3,4}")),
            "极兔速递"
        ),
        // 多多代收点系统
        "多多代收点" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d{3,4}"), Pattern.compile("[A-Z]\\d-\\d-\\d{5}")),
            "多多代收点"
        ),
        "多多买菜" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d{3,4}"), Pattern.compile("[A-Z]\\d-\\d-\\d{5}")),
            "多多代收点"
        ),
        // 顺丰快递系统
        "顺丰快递" to SignatureFormat(
            listOf(Pattern.compile("[A-Z]\\d{1,2}-\\d-\\d{4}"), Pattern.compile("\\d{2}-\\d-\\d{4}"), Pattern.compile("\\d{4,6}")),
            "顺丰快递"
        ),
        "顺丰" to SignatureFormat(
            listOf(Pattern.compile("[A-Z]\\d{1,2}-\\d-\\d{4}"), Pattern.compile("\\d{2}-\\d-\\d{4}"), Pattern.compile("\\d{4,6}")),
            "顺丰快递"
        ),
        "顺丰速运" to SignatureFormat(
            listOf(Pattern.compile("[A-Z]\\d{1,2}-\\d-\\d{4}"), Pattern.compile("\\d{2}-\\d-\\d{4}"), Pattern.compile("\\d{4,6}")),
            "顺丰快递"
        ),
        // 中通快递系统
        "中通快递" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d-\\d{4}"), Pattern.compile("\\d{2}-\\d{3,4}")),
            "中通快递"
        ),
        "中通" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d-\\d{4}"), Pattern.compile("\\d{2}-\\d{3,4}")),
            "中通快递"
        ),
        "中通速递" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d-\\d{4}"), Pattern.compile("\\d{2}-\\d{3,4}")),
            "中通快递"
        ),
        // 兔喜生活系统（修复2：添加缺失签名 + 支持5位尾段取件码）
        "兔喜生活" to SignatureFormat(
            listOf(Pattern.compile("[A-Z]\\d-\\d{5}"), Pattern.compile("\\d{2}-\\d-\\d{4,5}")),
            "兔喜生活"
        ),
        "兔喜" to SignatureFormat(
            listOf(Pattern.compile("[A-Z]\\d-\\d{5}"), Pattern.compile("\\d{2}-\\d-\\d{4,5}")),
            "兔喜生活"
        ),
        "兔喜驿站" to SignatureFormat(
            listOf(Pattern.compile("[A-Z]\\d-\\d{5}"), Pattern.compile("\\d{2}-\\d-\\d{4,5}")),
            "兔喜生活"
        ),
        // 丰巢系统
        "丰巢" to SignatureFormat(
            listOf(Pattern.compile("\\d{4,6}")),
            "丰巢"
        ),
        "丰巢智能柜" to SignatureFormat(
            listOf(Pattern.compile("\\d{4,6}")),
            "丰巢"
        ),
        "丰巢快递柜" to SignatureFormat(
            listOf(Pattern.compile("\\d{4,6}")),
            "丰巢"
        ),
        // 京东系统（支持三段式取件码）
        "京东快递" to SignatureFormat(
            listOf(Pattern.compile("\\d{1,2}-\\d{1,2}-\\d{3,5}"), Pattern.compile("[A-Z]\\d-\\d{3,5}"), Pattern.compile("\\d{6,8}")),
            "京东快递"
        ),
        "京东物流" to SignatureFormat(
            listOf(Pattern.compile("\\d{1,2}-\\d{1,2}-\\d{3,5}"), Pattern.compile("[A-Z]\\d-\\d{3,5}"), Pattern.compile("\\d{6,8}")),
            "京东快递"
        ),
        "京东" to SignatureFormat(
            listOf(Pattern.compile("\\d{1,2}-\\d{1,2}-\\d{3,5}"), Pattern.compile("[A-Z]\\d-\\d{3,5}"), Pattern.compile("\\d{6,8}")),
            "京东快递"
        ),
        // 德邦系统
        "德邦快递" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d{3,4}"), Pattern.compile("\\d{4,6}")),
            "德邦快递"
        ),
        "德邦" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d{3,4}"), Pattern.compile("\\d{4,6}")),
            "德邦快递"
        ),
        "德邦物流" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d{3,4}"), Pattern.compile("\\d{4,6}")),
            "德邦快递"
        ),
        // 邮政系统（修复2：添加邮政电商标快）
        "邮政快递" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d{3,4}"), Pattern.compile("\\d{4,6}")),
            "邮政快递"
        ),
        "邮政电商标快" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d-\\d{4}")),
            "邮政快递"
        ),
        "EMS" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d{3,4}"), Pattern.compile("\\d{4,6}")),
            "EMS"
        ),
        "邮政" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d{3,4}"), Pattern.compile("\\d{4,6}")),
            "邮政快递"
        ),
        // 百世快递系统
        "百世快递" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d{3,4}")),
            "百世快递"
        ),
        "百世" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d{3,4}")),
            "百世快递"
        ),
        // 天天快递系统
        "天天快递" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d{3,4}")),
            "天天快递"
        ),
        "天天" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d{3,4}")),
            "天天快递"
        ),
        // 宅急送系统
        "宅急送" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d{3,4}"), Pattern.compile("\\d{4,6}")),
            "宅急送"
        ),
        // 优速快递系统
        "优速快递" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d{3,4}")),
            "优速快递"
        ),
        "优速" to SignatureFormat(
            listOf(Pattern.compile("\\d{2}-\\d{3,4}")),
            "优速快递"
        )
    )
    
    // ==================== 通用取件码正则（兜底） ====================
    // 修复：扩展支持8位数字、4段格式
    
    private val GENERIC_CODE_PATTERNS = listOf(
        Pattern.compile("[A-Z]\\d{1,4}-\\d{1,4}-\\d{1,4}-\\d{3,6}"),  // 字母+4段格式（扩展）
        Pattern.compile("\\d{1,4}-\\d{1,4}-\\d{1,4}-\\d{3,6}"),        // 4段格式（如 1-2-34-5678）
        Pattern.compile("[A-Z]\\d{1,4}-\\d{1,4}-\\d{3,6}"),            // 字母+数字-数字-数字（扩展）
        Pattern.compile("\\d{1,4}-\\d{1,4}-\\d{3,6}"),                 // 数字-数字-数字（如 1-23456、1-2-34567）
        Pattern.compile("[A-Z]\\d{1,4}-\\d{1,6}"),                     // 字母+数字-数字（扩展）
        Pattern.compile("\\d{2,4}-\\d{3,4}"),                          // 数字-数字（如 1234-5678）
        Pattern.compile("\\d{4,8}")                                    // 纯数字（扩展为4-8位）
    )
    
    // ==================== 非取件码类数字的预处理（修复 H-01） ====================
    // 缺陷：GENERIC_CODE_PATTERNS 里的 `\d{4,8}`、`\d{2,4}-\d{3,4}` 是**全文无约束**匹配，
    //      而驿站短信几乎必带一个联系电话。"咨询 18712345678" 会被切成 `187123`、`45678`
    //      两个"取件码"入库、上屏、一键复制、一键分享 —— 用户拿到的是别人的号码片段。
    //
    // 修法（而不是给正则打补丁）：抽取前先复制一份短信，把**已知不可能是取件码的数字**
    //      用等长的 U+FFFD 顶掉，只在副本上跑码形匹配。这样做的三个好处：
    //        1. 长度不变 → 正则里的位置约束、以及后续 `substring` 类操作不会整体错位；
    //        2. 不需要给 7 条码形正则各自加一层「邻近约束」，避免把真码也一起误杀；
    //        3. 命中的码里只要含 U+FFFD 就必然来自被顶掉的段落，一条 filter 就能兜住。
    //      这是短信解析里的通行做法（号码/日期/金额先归一化，再谈业务字段抽取）。
    //      刻意**不做**的事：不改动 AddressMatcher、extractTracking、cleanAddress 的输入，
    //      它们本来就需要真实数字（"1号楼""运单尾号3321"）。
    
    private val NON_CODE_NUMERIC_PATTERNS = listOf(
        // 中国大陆手机号：11 位，1 开头，第二位 3-9；容忍 +86 / 86 前缀与中间空格连字符
        Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d([- ]?)\\d{4}\\1\\d{4}(?!\\d)"),
        // 企业服务号 400/800 + 7 位
        Pattern.compile("(?<!\\d)[48]00[- ]?\\d{3}[- ]?\\d{4}(?!\\d)"),
        // 带区号座机：0 + 2~3 位区号 + 7~8 位
        Pattern.compile("(?<!\\d)0\\d{2,3}[- ]?\\d{7,8}(?!\\d)"),
        // 日期：只认 19xx/20xx 开头，避免把 "1234-5-6789" 这类四段取件码误吞
        Pattern.compile("(?<!\\d)(?:19|20)\\d{2}[-/.年]\\d{1,2}[-/.月]\\d{1,2}日?(?!\\d)"),
        // 时刻 18:30 / 18:30:00
        Pattern.compile("(?<!\\d)\\d{1,2}:\\d{2}(?::\\d{2})?(?!\\d)"),
        // 金额与时效：「1.50元」「¥12.5」「18 小时」「7 天」「5 分钟」「9 折」「85%」
        Pattern.compile("[¥￥$]\\s?\\d+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?\\s?(?:元|块钱|块|分钟|小时|天|个月|年|%|折)"),
        // 纯 11 位以上长数字串（运单号本体、银行流水号；取件码没有这么长的）
        Pattern.compile("(?<!\\d)\\d{11,}(?!\\d)")
    )
    
    /** 等长替换用的占位字符。选 U+FFFD 是因为它不可能是任何码形的组成字符。 */
    private const val REDACTED = '\uFFFD'
    
    /**
     * 把**已知不可能是取件码**的数字段落等长替换为占位字符，返回可直接跑码形正则的副本。
     *
     * 只用于取件码抽取；地址、运单号、包裹数量一律继续用原始短信。
     */
    private fun redactNonCodeNumerics(sms: String): String {
        var out = sms
        for (pattern in NON_CODE_NUMERIC_PATTERNS) {
            val matcher = pattern.matcher(out)
            if (!matcher.find()) continue
            val sb = StringBuilder(out)
            do {
                // matcher 的区间基于当前 out；sb 与 out 等长，下标通用
                for (i in matcher.start() until matcher.end()) {
                    sb.setCharAt(i, REDACTED)
                }
            } while (matcher.find())
            out = sb.toString()
        }
        return out
    }
    
    // ==================== 运单尾号正则 ====================
    
    private val TRACKING_PATTERNS = listOf(
        Pattern.compile("运单尾号(\\d{4,})"),   // 提取完整运单尾号（可能>4位）
        Pattern.compile("尾号(\\d{4,})"),       // 同上
        Pattern.compile("运单号(\\d{4,})"),
        Pattern.compile("单号(\\d{4,})")
    )
    
    // ==================== 地址正则（修复1：泛化地址模式，修复7：ASCII字母，修复8：扩展lookahead） ====================
    
    // 地址开头的**动作引导词**，用于清洗被正则一起吃进去的前缀。
    //
    // 这里只放两个字及以上的词，不放单字「在」「到」：ADDRESS_PATTERNS 的模式 3
    // 没有引导词锚点，会从任意汉字起匹配，所以「在水一方小区 15 号楼」这种真实
    // 小区名会被单字规则截成「水一方小区」。单字情形目前由 removeCompanyKeywords
    // 里的 trimStart('内','的','在','到') 处理（见那里的注释与 R2 文档 §8）。
    private val ADDRESS_LEADING_ACTION_WORDS = listOf("到达", "已到", "抵达", "位于")
    
    // 地址后缀停止词（用于清洗地址结尾的多余字符）
    private val ADDRESS_STOP_WORDS = listOf("取运单尾号", "运单尾号", "到店取件", "取件", "领取", "提取", "提信", "输到", "或扫码亮", "或扫码", "亮灯", "亮灯取", "亮灯领取", "扫码", "输入", "到店")

    // 尾句误判防护：取件码后面常跟「详询代收驿站」「详见购物平台」这类客服话术，
    // 它们恰好以「驿站 / 柜 / 点 / 店」等地址后缀收尾，会被 extractAddressAfterCode
    // 当成地址，而那个候选的优先级还最高。这些词不可能出现在真实地址里，
    // 在候选清洗阶段整条剔除即可，不必去动取件码后置地址的正则。
    private val ADDRESS_REJECT_WORDS = listOf(
        "详询", "咨询", "客服", "详情", "详见", "购物平台", "联系电话", "请勿"
    )

    private val ADDRESS_PATTERNS = listOf(
        // 模式1: 标准地址模式（修复：支持单元/室后的房间号）
        // 关键修改：在后缀后添加\d{0,4}捕获房间号（如104、12、301）
        // 前瞻约束放宽：允许数字（房间号）出现在后缀之后
        // 后缀表尾段补了「局/所/院/超市/商铺/商行/小屋/传达室」：邮政支局、
        // 代收点常用这些落脚点收尾（「已到XX邮政局」「已到XX超市」），旧表只认
        // 「号楼/路/柜/号」，会把整条地址丢掉。与模式 1e 是互补关系：这里覆盖
        // 「已到 XX」，1e 覆盖「凭码到 XX」，同时命中时由 distinct 去重。
        Pattern.compile("(?:到达|已到|抵达|到|在|位于)([\\u4e00-\\u9fa5a-zA-Z\\d][\\u4e00-\\u9fa5a-zA-Z\\d\\-]{1,18}(?:号楼|栋|幢|座|单元|室|店|站|点|驿站|柜|门|层|房|区|巷|路|街|道|弄|里|园|苑|坊|阁|居|庭|邸|府|郡|城|镇|村|号|超市|商铺|商行|小屋|传达室|局|所|院)\\d{0,4}(?=[，。,；;!！（()）)领取取件提取提信输到或扫码亮到店]|$))"),
        // 模式1b: 数字+室格式（如"12室"、"301室"）
        Pattern.compile("(?:到达|已到|抵达|到|在|位于)([\\u4e00-\\u9fa5a-zA-Z\\d][\\u4e00-\\u9fa5a-zA-Z\\d\\-]{1,18}(?:号楼|栋|幢|座|单元)\\d{1,4}室(?=[，。,；;!！（()）)领取取件提取提信输到或扫码亮到店]|$))"),
        // 模式1c: 快递柜增强模式（捕获号柜+格口信息）
        // 例如："7号柜F01智能柜" → "7号柜F01"，"三号柜20-31号格口" → "三号柜20-31号格口"
        Pattern.compile("(?:到达|已到|抵达|到|在|位于)([\\u4e00-\\u9fa5a-zA-Z\\d][\\u4e00-\\u9fa5a-zA-Z\\d\\-]{1,15}(?:号柜|组柜|快递柜|智能柜)(?:\\d{1,2}[-]\\d{1,2}(?:号格口|号柜|号口)?|(?:[A-Z]?\\d{1,4}(?:智能柜|格口)?))?(?=[，。,；;!！（()）)领取取件提取提信输到或扫码亮到店]|$))"),
        // 模式1d: 方位距离型地址
        // 驿站常用「地标 + 方位 + 距离 + 方位」描述位置，例如「已到虚构地标北30米路东」。
        // 这类串不以「号楼/路/苑/柜/号」收尾，模式 1 / 1b / 1c / 3 全部落空，
        // 结果是取件码与快递公司都进了库、地址整条丢失。这里单独兜住：
        // 必须有明确的动作引导词、以方位词为核心，前瞻再卡住标点或「凭/请/取」。
        // 方位词后允许 0~4 个汉字：真实短信里方位词后面还常常跟着快递公司名
        // （「…路南圆通」）或补充说明（「…路东五金机电」），若硬性要求方位词
        // 紧接标点，模式会退回模式 1 把地址截在「路」字上。多出来的汉字会在
        // removeCompanyKeywords 阶段被剔掉，不影响最终地址。
        Pattern.compile("(?:到达|已到|抵达|到|在|位于)([\\u4e00-\\u9fa5A-Za-z\\d]{2,20}[东西南北][\\u4e00-\\u9fa5]{0,4})(?=[，。；;,！!\\s凭请取]|$)"),
        // 模式1e: 「凭码到 <落脚点>」型地址
        // 邮政 / 代收点常见句式：「请凭 2-4-2956 到石门北利国镇邮政局，…小屋领取」。
        // 落脚点以「局 / 所 / 院 / 超市 / 小屋」收尾，而模式 1 的后缀表没有这些词、
        // 模式 1d 又强制要求方位词 + 距离、extractAddressAfterCode 的后缀表同样没有
        // 「局」，三条路会一起落空，结果是取件码与快递公司都进了库、地址整条丢失。
        // 这里借「凭 … 到」这个强锚点单独兜住，捕获到第一个句读 / 「领取」为止，
        // 不会把后半句的「详见购物平台」之类客服话术一起吃进来。
        Pattern.compile(
            "(?:请凭|可凭|凭)[^，。；;]{0,20}?(?:到|至|前往)" +
                "([\\u4e00-\\u9fa5A-Za-z\\d]{2,30}?" +
                "(?:局|所|院|柜|驿站|超市|商铺|商行|小屋|门卫|传达室|店|站|点|号楼|房|室|区|路|街|道|村|号))" +
                "(?=[，。；;,！!\\s领取]|$)"
        ),
        // 模式2: 地址引导模式
        Pattern.compile("地址[：:]([^，。；;]{3,50})"),
        // 模式3: 通用小区/建筑模式（以中文开头，支持单元号）
        Pattern.compile("([\\u4e00-\\u9fa5][\\u4e00-\\u9fa5a-zA-Z\\d\\-]{1,19}(?:小区|花园|家园|公寓|大厦|大楼|广场|中心|新城|社区|雅居|名邸|华庭|豪庭|公馆|院子|城|府|郡|苑|坊|阁|居|庭|园|山庄|别墅))\\d*号楼?(?:[一二三四五六七八九十\\d]+单元)?(?:\\d{1,4}室?)?")
    )
    
    // ==================== 包裹数量正则 ====================
    
    private val PACKAGE_COUNT_PATTERNS = listOf(
        Pattern.compile("您有(\\d+)个(?:包裹|快递|件)"),
        Pattern.compile("(\\d+)个(?:包裹|快递|件)")
    )
    
    // ==================== 引导词模式（修复6：扩展引导词） ====================
    // 修复：添加"取货码"、"提货码"引导词，支持"到店输入XXX"场景
    
    private val CODE_PREFIX_PATTERNS = listOf(
        // 引导词后紧跟的带连字符取件码，可跟一组以 、 , 分隔的后续码（多包裹短信）。
        // 各段位数一律宽松（1~4 / 1~6），不再按「3 段 / 4 段」拆成多条写死长度的正则：
        // 真实驿站码的形状极不稳定（1-23456、A2-1-3456、4-2-3、3-90、Z-8023 都出现过），
        // 一旦长度对不上就会退回通用模式，把码截断成最后一段纯数字。
        // 首段允许「纯数字」或「字母+0~4位数字」——Z-8023 这类字母后直接跟连字符的码真实存在。
        Pattern.compile("(?:凭|请凭|可凭|请用|使用|输入|码为|取件码[为是:：]?|取货码|提货码)\\s*[\"\\u201c]?((?:[A-Za-z]\\d{0,4}|\\d{1,4})(?:-\\d{1,6}){1,3}(?:\\s*[、,，]\\s*(?:[A-Za-z]\\d{0,4}|\\d{1,4})(?:-\\d{1,6}){1,3})*)[\"\\u201d]?"),
        // 引导词后的纯数字码
        Pattern.compile("(?:凭|请凭|可凭|请用|使用|输入|码为|取件码[为是:：]?|取货码|提货码)\\s*[\"\\u201c]?(\\d{3,8}(?:\\s*[、,，]\\s*\\d{3,8})*)[\"\\u201d]?"),
        // 到店输入XXX场景（特殊处理：输入XXX或扫码亮灯取件）
        Pattern.compile("到店输入(\\d{4,8})(?:或扫码|或扫|或亮灯|或凭|$)")
    )
    
    // ==================== 无取件码标记词（修复3：无取件码识别） ====================
    
    private val NO_CODE_MARKERS = listOf(
        Pattern.compile("凭手机"),
        Pattern.compile("凭身份"),
        Pattern.compile("身份证"),
        Pattern.compile("凭证件"),
        Pattern.compile("凭姓名"),
        Pattern.compile("报手机"),
        Pattern.compile("报姓名"),
        Pattern.compile("手机号取"),
        Pattern.compile("身份验证")
    )
    
    // ==================== 排除模式 ====================
    
    private val EXCLUDE_PATTERNS = listOf(
        Pattern.compile("\\d{6}.{0,4}(验证码|校验码|动态码)"),
        Pattern.compile("登录|注册|找回密码|重置密码"),
        // 不放裸「银行」：驿站名里常带银行（"某某银行大厅""××银行门口驿站"），
        // 实测会因此把整条取件短信判为非取件。金融语境由其余关键词已足够覆盖。
        Pattern.compile("账户|余额|交易|转账|银行卡|银行还款|支付"),
        Pattern.compile("推广|优惠|折扣|抽奖|红包|点击领取"),
        Pattern.compile("回复TD|退订|取消订阅"),
        Pattern.compile("订单|发货|物流更新|确认收货|评价"),
        Pattern.compile("航班|机票|火车票|酒店|出行"),
        Pattern.compile("健康|核酸|疫苗|预约|挂号")
    )
    
    // ==================== 主解析函数 ====================
    
    fun parse(sms: String): ParseResult {
        val result = ParseResult(rawSms = sms)
        
        // 第零步：排除非取件短信
        if (isExcluded(sms)) {
            return result
        }
        
        // 第一步：判断是否为取件短信
        if (!isPickupSms(sms)) {
            return result
        }
        
        // 第二步：提取签名
        val signature = extractSignature(sms)
        
        // 第三步：提取取件码
        val codes = extractCodes(sms, signature)
        
        // 第四步：提取快递公司
        val company = extractCompany(sms, signature)
        
        // 第五步：过滤运单尾号
        val trackingNumber = extractTracking(sms)
        
        // 第六步：提取地址（传入已识别的取件码，避免取件码被误识别为地址）
        val address = extractAddress(sms, codes)
        
        // 第七步：提取包裹数量
        val packageCount = extractPackageCount(sms)
        
        // 第八步：识别无取件码短信（修复3）
        val hasNoCode = if (codes.isEmpty() && isNoCodeSms(sms)) true else false
        
        return result.copy(
            isPickupSms = true,
            codes = codes,
            company = company,
            address = address,
            trackingNumber = trackingNumber,
            packageCount = packageCount,
            hasNoCode = hasNoCode
        )
    }
    
    // ==================== 判断逻辑 ====================
    
    private fun isExcluded(sms: String): Boolean {
        for (pattern in EXCLUDE_PATTERNS) {
            if (pattern.matcher(sms).find()) {
                return true
            }
        }
        return false
    }
    
    private fun isPickupSms(sms: String): Boolean {
        // 必须包含快递相关词
        val courierKeywords = arrayOf(
            "快递", "包裹", "快件", "取件", "驿站", "柜", "菜鸟",
            "顺丰", "中通", "圆通", "韵达", "申通", "极兔", "京东",
            "德邦", "邮政", "EMS", "兔喜", "驿小哥", "多多",
            "丰巢", "速递易", "近邻宝"
        )
        var hasCourier = false
        for (kw in courierKeywords) {
            if (sms.contains(kw)) {
                hasCourier = true
                break
            }
        }
        if (!hasCourier) return false
        
        // 必须包含取件动作词
        val actionKeywords = arrayOf(
            "取件", "取", "领取", "提货", "凭", "已到", "到站", "投递", "存放", "来取", "请用", "可凭", "输入", "亮灯"
        )
        for (kw in actionKeywords) {
            if (sms.contains(kw)) {
                return true
            }
        }
        return false
    }
    
    // ==================== 签名提取 ====================
    
    private fun extractSignature(sms: String): String? {
        val matcher = SIGNATURE_BRACKET_PATTERN.matcher(sms)
        return if (matcher.find()) matcher.group(1) else null
    }
    
    // ==================== 取件码提取 ====================
    
    /**
     * 提取取件码
     * 
     * 修复8：始终先执行引导词匹配，确保通过"输入"等引导词匹配的码
     * 不被后续的运单尾号过滤误删。例如"到店输入24680或扫码亮灯取件"。
     */
    private fun extractCodes(sms: String, signature: String?): List<String> {
        val codes = mutableListOf<String>()
        
        // 修复 H-01：码形匹配一律在「号码/日期/时刻/金额已被等长顶掉」的副本上做。
        // 原串 sms 继续用于运单尾号提取与地址提取，那两处本来就需要真实数字。
        val scannable = redactNonCodeNumerics(sms)
        
        // 策略0：引导词优先。短信里既然明写了「取件码为 / 凭 / 输入」，
        // 它后面那一段就是答案，比任何形状模板都可信；让签名模板与通用模式去猜，
        // 反而会因位数对不上而截断成最后一段纯数字（实测主要错误来源）。
        val codesByPrefix = extractCodesByPrefix(scannable)
        if (codesByPrefix.isNotEmpty()) {
            return dedupSubstringCodes(codesByPrefix.filterNot { it.contains(REDACTED) }.distinct())
        }
        
        // 策略1：无引导词时，按快递签名使用其对应格式
        if (signature != null && SIGNATURE_FORMATS.containsKey(signature)) {
            val format = SIGNATURE_FORMATS[signature]!!
            for (pattern in format.codePatterns) {
                val matcher = pattern.matcher(scannable)
                while (matcher.find()) {
                    codes.add(matcher.group())
                }
            }
        }
        
        // 策略2：签名匹配失败时使用（codesByPrefix 此时为空）
        if (codes.isEmpty()) {
            codes.addAll(extractCodesGeneric(scannable))
        }
        
        // 策略3：过滤运单尾号
        val filtered = filterTrackingNumbers(codes, sms, codesByPrefix)
        
        // 策略4：去重（C4-6-11111 与 11111 同时匹配时只保留前者）
        //        修复 H-01：含占位字符的候选必然来自被顶掉的号码/日期段，直接丢弃。
        val distinct = filtered.filterNot { it.contains(REDACTED) }.distinct()
        return dedupSubstringCodes(distinct)
    }

    /**
     * 去重：如果一个码是另一个码的子串，则保留更长的码
     * 例如：C4-6-11111 与 11111 同时匹配时只保留 C4-6-11111
     */
    private fun dedupSubstringCodes(codes: List<String>): List<String> {
        if (codes.size <= 1) return codes
        val sorted = codes.sortedByDescending { it.length }
        val result = mutableListOf<String>()
        for (code in sorted) {
            // 如果当前码不是已保留码的子串，则保留
            val isSubstring = result.any { other ->
                code != other && other.contains(code)
            }
            if (!isSubstring) {
                result.add(code)
            }
        }
        return result
    }
    
    /**
     * 引导词匹配。按模式优先级依次尝试，**命中即返回**：
     * 若继续跑弱模式，`凭5678-1234` 会同时产出 `5678-1234` 和 `5678` 两个码。
     */
    private fun extractCodesByPrefix(sms: String): List<String> {
        for (pattern in CODE_PREFIX_PATTERNS) {
            val codes = mutableListOf<String>()
            val matcher = pattern.matcher(sms)
            while (matcher.find()) {
                val group = matcher.group(1) ?: continue
                // 一个捕获组里可能含多个码（多包裹短信），按分隔符拆开
                group.split('、', '，', ',')
                    .map { it.trim().trim('"', '“', '”') }
                    .filter { it.isNotEmpty() }
                    .forEach { codes.add(it) }
            }
            if (codes.isNotEmpty()) return codes
        }
        return emptyList()
    }
    
    private fun extractCodesGeneric(sms: String): List<String> {
        val codes = mutableListOf<String>()
        
        for (pattern in GENERIC_CODE_PATTERNS) {
            val matcher = pattern.matcher(sms)
            while (matcher.find()) {
                codes.add(matcher.group())
            }
        }
        
        return codes
    }
    
    /**
     * 过滤运单尾号
     * 修复8：如果某个码是通过引导词（输入、取件码、凭等）匹配到的，则不受运单尾号过滤
     */
    private fun filterTrackingNumbers(codes: List<String>, sms: String, codesByPrefix: List<String> = emptyList()): List<String> {
        // 提取运单尾号
        val trackingNumbers = mutableSetOf<String>()
        for (pattern in TRACKING_PATTERNS) {
            val matcher = pattern.matcher(sms)
            if (matcher.find()) {
                // group(1) 是 Java 平台类型，Kotlin 无法静态证明它非空；
                // 显式判空而不是直接 add()，否则一旦正则改动让该组变成可选，
                // 这里就是一个不带任何提示的 NPE。
                matcher.group(1)?.let { trackingNumbers.add(it) }
            }
        }
        
        // 过滤掉与运单尾号相同的码（但保留引导词匹配到的码）
        return codes.filter { code ->
            // 如果这个码是通过引导词匹配到的，直接保留，不进行运单尾号过滤
            if (code in codesByPrefix) {
                return@filter true
            }
            
            var isTracking = false
            for (tn in trackingNumbers) {
                if (code == tn || (code.all { it.isDigit() } && tn in code)) {
                    isTracking = true
                    break
                }
            }
            !isTracking
        }
    }
    
    // ==================== 快递公司提取 ====================
    
    // ==================== 快递公司签名提取 ====================
    
    private val COMPANY_BRACKET_PATTERN = Pattern.compile("^【([^】]+)】")
    
    private fun extractCompany(sms: String, signature: String?): List<String> {
        val companies = mutableListOf<String>()
        
        // 修复：从短信开头的【...】括号中提取快递公司/驿站名称
        // 例如：【某某驿站】→ "某某驿站"，【兔喜生活】→ "兔喜生活"
        val bracketMatcher = COMPANY_BRACKET_PATTERN.matcher(sms)
        if (bracketMatcher.find()) {
            val bracketName = bracketMatcher.group(1)
            if (bracketName != null && bracketName.isNotEmpty()) {
                // 检查是否是已知的快递公司/驿站
                val knownCompany = findKnownCompany(bracketName)
                if (knownCompany != null) {
                    companies.add(knownCompany)
                } else {
                    // 未知公司，直接使用括号中的名称
                    companies.add(bracketName)
                }
            }
        }
        
        // 从签名中提取
        if (signature != null) {
            if (SIGNATURE_FORMATS.containsKey(signature)) {
                if (companies.none { it == SIGNATURE_FORMATS[signature]!!.companyName }) {
                    companies.add(SIGNATURE_FORMATS[signature]!!.companyName)
                }
            } else {
                for ((key, format) in SIGNATURE_FORMATS) {
                    if (signature.contains(key) && companies.none { it == format.companyName }) {
                        companies.add(format.companyName)
                        break
                    }
                }
            }
        }
        
        // 从短信内容中提取
        val companyKeywords = arrayOf(
            "顺丰快递", "顺丰速运", "顺丰",
            "中通快递", "中通",
            "圆通快递", "圆通速递", "圆通",
            "韵达快递", "韵达速递", "韵达",
            "申通快递", "申通",
            "极兔速递", "极兔快递", "极兔",
            "京东快递", "京东物流", "京东",
            "德邦快递", "德邦物流", "德邦",
            "邮政快递", "邮政速递", "EMS", "邮政",
            "菜鸟驿站", "菜鸟",
            "兔喜生活", "兔喜",
            "驿小哥",
            "多多代收点", "多多买菜"
        )
        
        for (kw in companyKeywords) {
            if (sms.contains(kw) && companies.none { it.contains(kw) || kw.contains(it) }) {
                companies.add(kw)
            }
        }
        
        return companies
    }
    
    /**
     * 查找已知的快递公司
     */
    private fun findKnownCompany(bracketName: String): String? {
        for ((key, format) in SIGNATURE_FORMATS) {
            if (bracketName.contains(key)) {
                return format.companyName
            }
        }
        return null
    }
    
    // ==================== 快递公司关键词列表（用于地址清洗） ====================
    
    private val COMPANY_KEYWORDS = listOf(
        "顺丰", "中通", "圆通", "韵达", "申通", "极兔", "京东", "德邦", "邮政", "EMS",
        "菜鸟", "兔喜", "驿小哥", "多多", "熊猫", "妈妈", "速递", "快递"
    )

    /** 已知驿站 / 快递柜品牌白名单。
     *
     *  刻意**不**用「任意 1–3 汉字」当品牌：那个宽松写法会把
     *  「中通快递兔喜5号柜」里的「递兔喜5号柜」也当成品牌保护段，
     *  于是承运方「中通快递」只删得掉「中通」，地址里留下一个孤零零的「快递」
     *  （实测输出「铜山利国快递兔喜5号柜兔喜快递柜」）。
     *  用白名单后只保护「兔喜5号柜」「兔喜快递柜」，承运方能被完整剔除。 */
    private val LOCKER_BRANDS = listOf(
        "中邮速递易", "兔喜生活", "妈妈驿站", "驿小哥", "邮乐购", "近邻宝",
        "速递易", "格格货栈", "日日顺", "丰巢", "菜鸟", "兔喜", "欢猫",
        "多多", "熊猫", "格格",
    )

    private val LOCKER_BRAND_PATTERN = Pattern.compile(
        "(?:" + LOCKER_BRANDS.sortedByDescending { it.length }.joinToString("|") +
            ")(\\d*号柜|组柜|快递柜|智能柜|柜)"
    )

    // 「邮政局 / 邮电所 / 邮局」是**地名**（营业网点），不是混进地址里的快递公司名。
    // 不保护的话，上面的「邮政」会把「石门北利国镇邮政局」剔成「石门北利国镇局」——
    // 用户按这个地址是找不到驿站的。清洗前先换成不含中文的占位符，末了再还原。
    private val PROTECTED_PLACE_WORDS = listOf("邮政支局", "邮政局", "邮电所", "邮政所", "邮局")

    // ==================== 地址提取 ====================
    
    /**
     * 提取取件地址
     * 修复：先移除取件码再匹配地址，避免取件码被误识别为地址的一部分
     * 修复：支持多地址去重、双地址选择、公司名清洗
     */
    private fun extractAddress(sms: String, codes: List<String> = emptyList()): List<String> {
        // 显式「地址：」标签是短信自己给出的取件位置，优先级最高。
        // 命中后只走这一处：否则「您的快件尾号0159已送达…服务站」这类提示语
        // 也会被引导词/码后置模式推成候选，并列时列表第一位反而是那句提示语。
        extractExplicitAddress(sms)?.let { explicit ->
            val result = cleanAddressCandidates(listOf(explicit), sms)
            if (result.isNotEmpty()) return result
        }

        val addresses = mutableListOf<String>()
        
        // 关键修复：先从短信中移除所有取件码，避免取件码被匹配为地址
        var cleanedSms = sms
        for (code in codes) {
            cleanedSms = cleanedSms.replace(code, "")
        }
        
        // 同时移除通用取件码模式（防止遗漏）
        for (pattern in GENERIC_CODE_PATTERNS) {
            cleanedSms = pattern.matcher(cleanedSms).replaceAll("")
        }
        
        // 再移除运单尾号
        for (pattern in TRACKING_PATTERNS) {
            cleanedSms = pattern.matcher(cleanedSms).replaceAll("")
        }
        
        // 修复：提取取件码后面紧跟的地址（优先级最高）
        // 例如："取件码为1-23456，地址:幸福路综合楼楼下" → 优先使用取件码后的地址
        val codePostAddress = extractAddressAfterCode(sms, codes)
        if (codePostAddress != null) {
            addresses.add(codePostAddress)
        }
        
        // 用户规则（2026-11-10）：引导词（已到达/已到/到达/抵达/到/在/位于）之后，
        // 一直到逗号或字符串结束即为地址。比后缀表+前瞻更准，优先于下面通用模式。
        extractAddressByActionWord(cleanedSms)?.let { addresses.add(it) }
        
        for (pattern in ADDRESS_PATTERNS) {
            val matcher = pattern.matcher(cleanedSms)
            while (matcher.find()) {
                matcher.group(1)?.let { addresses.add(it) }
            }
        }
        
        // 去重、清洗、覆盖表、择优：与显式标签分支共用同一条链，避免两套口径。
        return cleanAddressCandidates(addresses, sms)
    }

    /** 地址候选的统一清洗链。显式标签与常规推断两条路径都必须走这里。 */
    private fun cleanAddressCandidates(candidates: List<String>, sms: String): List<String> {
        // 去重并过滤（移除纯数字或太短的匹配）
        // 修复：降低最短长度限制为2，支持短地址（如"104国道"）
        val cleaned = candidates
            .map { it.trim() }
            .filter { it.length >= 2 && !it.all { c -> c.isDigit() } }
            .map { addr -> cleanAddress(addr) }  // 清洗地址中的多余字符
            .map { addr -> removeCompanyKeywords(addr) }  // 清洗快递公司名
            .filter { addr -> ADDRESS_REJECT_WORDS.none { addr.contains(it) } }  // 剔除客服尾句
            .map { addr -> lookupAddressOverride(addr) ?: addr }  // 应用地址覆盖表
            .filter { it.length >= 2 }  // 再次过滤清洗后的地址
            .distinct()
            .toMutableList()

        // 双地址选择逻辑 - 如果存在包含关系，保留更详细的地址
        return selectBestAddresses(cleaned).map { appendParentheticalIfAny(sms, it) }
    }

    /** 提取「地址：」标签后的位置，并去掉结尾的门店落款。 */
    private fun extractExplicitAddress(sms: String): String? {
        val matcher = EXPLICIT_ADDRESS_PATTERN.matcher(sms)
        if (!matcher.find()) return null
        val raw = matcher.group(1)?.trim() ?: return null
        if (raw.length < 2) return null
        return raw.replace(STORE_TAIL_REGEX, "").trim().takeIf { it.length >= 2 }
    }
    
    /**
     * 提取取件码后面紧跟的地址
     * 例如："取件码为1-23456，地址:幸福路综合楼楼下" → "幸福路综合楼楼下"
     */
    private fun extractAddressAfterCode(sms: String, codes: List<String>): String? {
        for (code in codes) {
            val codeIndex = sms.indexOf(code)
            if (codeIndex >= 0) {
                // 找到取件码后面的文本
                val afterCode = sms.substring(codeIndex + code.length)
                // 匹配取件码后面的地址（通常是"到XXX"或"，地址：XXX"格式）
                val addressPattern = ADDRESS_AFTER_CODE_PATTERN
                val matcher = addressPattern.matcher(afterCode)
                if (matcher.find()) {
                    return matcher.group(1)
                }
            }
        }
        return null
    }
    
    /**
     * 用户规则提取地址：引导词（已到达/已到/到达/抵达/到/在/位于）之后，
     * 一直到逗号或字符串结束的部分作为地址。
     * 例："已到达铜山利国中通快递兔喜5号柜兔喜快递柜，取件码…"
     *   → "铜山利国中通快递兔喜5号柜兔喜快递柜"
     * 例："已到东坡大道(利国实验小学南六十米路东)，请凭…"
     *   → "东坡大道(利国实验小学南六十米路东)"
     */
    private fun extractAddressByActionWord(sms: String): String? {
        val pattern = Pattern.compile("(?:已到达|已到|到达|抵达|到|在|位于)\\s*([^，,。；;！!]{2,40})")
        val matcher = pattern.matcher(sms)
        if (matcher.find()) {
            return matcher.group(1)?.trim()
        }
        return null
    }
    
    /**
     * 选择最优地址
     * 如果地址A包含地址B，则只保留更详细的地址A
     */
    private fun selectBestAddresses(addresses: MutableList<String>): List<String> {
        if (addresses.size <= 1) return addresses
        
        val result = mutableListOf<String>()
        for (addr in addresses) {
            var isSubset = false
            for (other in addresses) {
                if (addr != other && other.contains(addr) && other.length > addr.length) {
                    isSubset = true
                    break
                }
            }
            if (!isSubset) {
                result.add(addr)
            }
        }
        return result
    }
    
    /**
     * 清洗地址中的快递公司关键词
     * 例如："圆通中通极兔快递幸福超市内" → "幸福超市内"
     */
    private fun removeCompanyKeywords(address: String): String {
        var cleaned = address

        // 地名型词组先占位，避免被公司词清洗连带删掉。
        val placeholders = mutableListOf<Pair<String, String>>()
        PROTECTED_PLACE_WORDS.forEachIndexed { index, word ->
            if (cleaned.contains(word)) {
                val token = "\u0001P$index\u0002"
                cleaned = cleaned.replace(word, token)
                placeholders += token to word
            }
        }

        // 保护「品牌 +（数字）号柜 / 快递柜 / 智能柜」中的驿站品牌，避免被 COMPANY_KEYWORDS 误删。
        // 例："兔喜5号柜兔喜快递柜"若直接删"兔喜"，会变成"5号柜快递柜"丢失驿站名；
        // 而承运方"中通快递"不在本模式内，仍会被正常删掉。
        val lockerPlaceholders = mutableListOf<Pair<String, String>>()
        val lockerSegs = mutableListOf<String>()
        val lockerMatcher = LOCKER_BRAND_PATTERN.matcher(cleaned)
        while (lockerMatcher.find()) {
            // Java Matcher.group() 是可空平台类型；find() 成功时必不为 null，用 let 收窄以免告警
            lockerMatcher.group(0)?.let { lockerSegs.add(it) }
        }
        lockerSegs.forEachIndexed { index, seg ->
            val token = "\u0001K$index\u0002"
            cleaned = cleaned.replace(seg, token)
            lockerPlaceholders += token to seg
        }

        for (keyword in COMPANY_KEYWORDS) {
            cleaned = cleaned.replace(keyword, "")
        }
        // 移除多余的"内"字开头
        // 注意：这里的单字 '在' / '到' 会截断「在水一方小区」这类真实小区名。
        // 属于已知且暂不修的低危项（见 R2 文档 §8）：改掉它需要判断"这个字
        // 到底是不是引导词"，而 ADDRESS_PATTERNS 的模式 3 没有引导词锚点、
        // 拿不到这个信息；在正确区分之前，宁可保留现有行为也不要引入新的截断规则。
        cleaned = cleaned.trimStart('内', '的', '在', '到')
        // 公司名从地址中间被剔除后可能留下叠在一起的后缀字：
        // 「虚构镇虚构快递5号柜虚构快递柜」→「虚构镇5号柜柜」。
        // 只折叠连续重复的地址后缀，不动「4号柜F01」这类正常间隔。
        cleaned = cleaned.replace(ADDRESS_SUFFIX_DUPLICATE_REGEX, "$1")
        placeholders.forEach { (token, word) -> cleaned = cleaned.replace(token, word) }
        lockerPlaceholders.forEach { (token, word) -> cleaned = cleaned.replace(token, word) }
        return cleaned.trim()
    }
    
    /**
     * 清洗地址中的多余字符
     * 
     * 1. 移除开头的动作引导词（如"已到示例小区3号楼" → "示例小区3号楼"）
     * 2. 移除开头的"达"字（如"达12号楼" → "12号楼"）
     * 3. 移除结尾的停止词（如"示例小区取件" → "示例小区"）
     * 4. 移除结尾的标点
     *
     * 第 1 条是 R2 补的。在此之前 `ADDRESS_PREFIX_WORDS` 这个常量被声明、
     * 被注释说明为"用于清洗地址开头的多余字符"，但**全文没有任何地方用它** ——
     * 于是 ADDRESS_PATTERNS 的模式 3（无引导词锚点的通用小区模式）从「已」字
     * 起匹配，把动作词一起塞进了地址："已到示例小区3号楼"直通数据库，
     * 用户看到的是「已到示例小区3号楼」而不是「示例小区3号楼」。
     * 这也是 H-13（地址标签三套生成规则）之外，地址质量单独的一条缺陷。
     */
    private fun cleanAddress(address: String): String {
        var cleaned = address
        
        // 1. 移除开头的动作引导词（长词优先，循环以处理嵌套，如"到达示例小区"）
        var stripped = true
        while (stripped) {
            stripped = false
            for (word in ADDRESS_LEADING_ACTION_WORDS.sortedByDescending { it.length }) {
                if (cleaned.startsWith(word) && cleaned.length > word.length) {
                    cleaned = cleaned.substring(word.length)
                    stripped = true
                    break
                }
            }
        }
        
        // 2. 移除开头的"达"字（"达12号楼" → "12号楼"）
        //    单独处理而不并进上表：单字"达"不在引导词表里，但它是"到达"被前瞻
        //    截断后的常见残留。
        while (cleaned.isNotEmpty() && cleaned[0] == '达') {
            cleaned = cleaned.substring(1)
        }
        
        // 3. 移除结尾的停止词
        for (stopWord in ADDRESS_STOP_WORDS) {
            if (cleaned.endsWith(stopWord)) {
                cleaned = cleaned.substring(0, cleaned.length - stopWord.length)
                break
            }
        }
        
        // 4. 移除结尾的非地址字符（标点符号等）。
        //    右括号必须单独判断：地址本身可能带括号补充说明，例如
        //    「东坡大道(利国实验小学南六十米路东)」。无差别 trimEnd 会把配对好的 ')' 剥掉，
        //    留下「东坡大道(利国实验小学南六十米路东」这种半截地址；而下游的
        //    appendParentheticalIfAny 见地址已含 '(' 就直接返回，右括号再也补不回来。
        while (cleaned.isNotEmpty()) {
            val last = cleaned.last()
            if (last == ')' || last == '）') {
                val open = if (last == ')') '(' else '（'
                val openCount = cleaned.count { it == open }
                val closeCount = cleaned.count { it == last }
                // 有与之配对的左括号 → 属于地址，保留并停止剥离
                if (openCount >= closeCount) break
                cleaned = cleaned.dropLast(1)
                continue
            }
            if (last in "，。；,;、！!（(：:") {
                cleaned = cleaned.dropLast(1)
                continue
            }
            break
        }

        return cleaned.trim()
    }

    /**
     * 括号补全：若地址在原始短信中紧跟你「(...)」或「（...）」的位置补充说明，
     * 则把括号里的内容补回地址，使「东坡大道」显示为完整的
     * 「东坡大道(利国实验小学南六十米路东)」。
     *
     * 仅当地址本身不含括号、且其后紧跟一个闭合括号（内容 ≤40 字、不含取件类尾词）时才补全，
     * 避免误把「您的包裹(共2件)」之类与位置无关的内容拼进地址。
     */
    private fun appendParentheticalIfAny(sms: String, address: String): String {
        if (address.isEmpty() || address.contains('(') || address.contains('（')) return address
        val idx = sms.indexOf(address)
        if (idx < 0) return address
        var i = idx + address.length
        while (i < sms.length && (sms[i] == ' ' || sms[i] == '　')) i++
        if (i >= sms.length) return address
        val open = sms[i]
        if (open != '(' && open != '（') return address
        val close = if (open == '(') ')' else '）'
        val end = sms.indexOf(close, i + 1)
        if (end < 0) return address
        val inside = sms.substring(i + 1, end).trim()
        if (inside.isEmpty() || inside.length > 40) return address
        if (inside.any { it in "取件领取包裹客服详情点击回复" }) return address
        return "$address$open$inside$close"
    }

    // ==================== 包裹数量提取 ====================
    
    private fun extractPackageCount(sms: String): Int? {
        for (pattern in PACKAGE_COUNT_PATTERNS) {
            val matcher = pattern.matcher(sms)
            if (matcher.find()) {
                return matcher.group(1)?.toIntOrNull()
            }
        }
        return null
    }
    
    // ==================== 运单尾号提取 ====================
    
    private fun extractTracking(sms: String): String? {
        for (pattern in TRACKING_PATTERNS) {
            val matcher = pattern.matcher(sms)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }
    
    // ==================== 无取件码识别（修复3） ====================
    
    private fun isNoCodeSms(sms: String): Boolean {
        for (pattern in NO_CODE_MARKERS) {
            if (pattern.matcher(sms).find()) {
                return true
            }
        }
        return false
    }
}
