/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import android.util.Log
import com.shanqiu.pickupmanager.data.model.PickupItem
import java.util.regex.Pattern

data class PickupResult(
    var isPickupSms: Boolean = false,
    var confidence: String = "NOT_PICKUP",
    var totalScore: Int = 0,
    var scoreDetails: MutableMap<String, Int> = mutableMapOf(),
    var courier: String? = null,
    var codes: MutableList<String> = mutableListOf(),
    var codeLevels: MutableList<String> = mutableListOf(),
    var stations: MutableList<String> = mutableListOf(),
    var lockers: MutableList<String> = mutableListOf(),
    var address: String? = null,
    var timeLimit: String? = null,
    var trackingTail: String? = null,
    var packageCount: Int? = null,
    var courierPhone: String? = null,
    var rawSms: String? = null,
    var senderNumber: String? = null,
    var vetoReason: String? = null,
    var inferredShelf: String? = null
) {
    fun isPickup(): Boolean = isPickupSms && confidence == "CONFIRMED"
    
    fun isPickupProbable(): Boolean = isPickupSms && (confidence == "CONFIRMED" || confidence == "PROBABLE")
    
    fun getPrimaryCode(): String? = if (codes.isNotEmpty()) codes[0] else null
    
    fun getAllCodesStr(): String = codes.joinToString(", ")
    
    fun getPickupPoint(): String? {
        val points = mutableListOf<String>()
        points.addAll(stations)
        points.addAll(lockers)
        return if (points.isNotEmpty()) points.joinToString(" / ") else null
    }
}

object PickupSmsParserV2 {
    
    private const val TAG = "PickupSmsParserV2"
    
    private const val THRESHOLD_CONFIRMED = 60
    private const val THRESHOLD_PROBABLE = 40
    private const val THRESHOLD_POSSIBLE = 25

    private val VETO_PATTERNS = listOf(
        Pattern.compile("\\d{4,8}\\s*(为|是)?\\s*(验证码|校验码|动态码|verification|otp|OTP)"),
        Pattern.compile("(验证码|校验码|动态码)\\s*[为是:]?\\s*\\d{4,8}"),
        Pattern.compile("(登录|注册|找回密码|重置密码|绑定手机|修改密码|安全验证|身份验证).{0,15}(码|code|Code)"),
        Pattern.compile("(安全码|两步验证|双重验证|MFA|Google验证)"),
        Pattern.compile("(账户|余额|交易|转账|银行|信用卡|借记卡|贷款|理财|基金|证券|储蓄卡)"),
        Pattern.compile("(支付宝|微信支付|云闪付|Apple\\s*Pay).{0,10}(元|余额|支付|扣款|退款)"),
        Pattern.compile("\\d+\\.\\d{2}\\s*元.{0,10}(扣款|入账|支出|收入|退款|消费)"),
        Pattern.compile("(推广|折扣|抽奖|红包|点击领取|限时特价|买一送一|免费领取|仅需|立减|满减|优惠券|折扣券)"),
        Pattern.compile("(回复TD|回复T退订|退订|取消订阅|回TD|回T)"),
        Pattern.compile("(航班|机票|火车票|高铁票|动车票|登机牌|值机|行程单)"),
        Pattern.compile("(酒店|民宿|入住|退房|预订成功|房间号)"),
        Pattern.compile("(核酸|健康码|行程码|预约挂号|体检|就诊)"),
        Pattern.compile("(中国移动|中国联通|中国电信).{0,10}(话费|流量|套餐|元|办理|到期)"),
        Pattern.compile("(社保|公积金|税务|个税|发票|报税|住房公积金)"),
        Pattern.compile("(考试|成绩|录取|报名|学分|期末|期中)"),
        Pattern.compile("(房租|物业费|水电费|燃气费|缴费).{0,10}(元|到期|逾期)"),
        Pattern.compile("(面试|简历|offer|录用|入职|招聘|求职)"),
        Pattern.compile("(点击链接|http[s]?://|www\\.|下载APP|扫码下载|注册即送)"),
        Pattern.compile("(已签收|签收成功|已由.{0,5}签收|本人签收|代签收)"),
        Pattern.compile("(已到达驿站多日|已到站多日|存放多日)")
    )
    
    private val SIG_STATION = arrayOf(
        "菜鸟驿站", "妈妈驿站", "兔喜生活", "兔喜驿站", "兔喜",
        "驿小哥", "快宝驿站", "小兵驿站", "蓝店", "熊猫快收",
        "快递超市", "百世邻里", "易收发", "快弟驿站",
        "极兔驿站", "喵站", "韵达快递超市", "德邦驿站",
        "顺丰驿站", "邮乐购", "多多代收点", "多多买菜",
        "美团优选", "淘菜菜", "代收点", "京东自提"
    )
    
    private val SIG_LOCKER = arrayOf(
        "丰巢智能柜", "丰巢快递柜", "丰巢柜", "丰巢",
        "速递易", "近邻宝", "云柜", "日日顺乐家",
        "格格货栈", "易邮柜", "1号柜", "菜鸟智能柜", "菜鸟柜",
        "京东自提柜", "兔喜柜", "快递e栈", "蜜罐", "极兔柜",
        "邮政柜", "自提柜", "快递柜", "智能柜"
    )
    
    private val SIG_COURIER = arrayOf(
        "顺丰快递", "顺丰速运", "顺丰",
        "中通快递", "中通",
        "圆通快递", "圆通速递", "圆通",
        "韵达快递", "韵达速递", "韵达",
        "申通快递", "申通",
        "极兔快递", "极兔速递", "极兔",
        "京东快递", "京东物流", "京东",
        "邮政快递", "邮政速递", "邮政", "EMS",
        "德邦快递", "德邦物流", "德邦",
        "菜鸟", "百世快递", "百世",
        "天天快递", "天天",
        "宅急送", "优速", "跨越", "安能", "速尔", "国通",
        "DHL", "FedEx", "联邦快递", "UPS", "TNT"
    )
    
    private val BODY_STATION_WORDS = arrayOf(
        "菜鸟驿站", "妈妈驿站", "兔喜生活", "兔喜驿站", "兔喜",
        "驿小哥", "快宝驿站", "小兵驿站", "蓝店", "熊猫快收",
        "快递超市", "百世邻里", "易收发", "快弟驿站",
        "极兔驿站", "喵站", "多多代收点", "多多买菜", "美团优选", "淘菜菜",
        "代收点", "驿站"
    )
    
    private val BODY_LOCKER_WORDS = arrayOf(
        "丰巢智能柜", "丰巢快递柜", "丰巢柜", "丰巢",
        "速递易", "近邻宝", "云柜", "日日顺乐家",
        "易邮柜", "1号柜", "菜鸟柜", "京东自提柜",
        "兔喜柜", "蜜罐", "极兔柜", "邮政柜",
        "自提柜", "快递柜", "智能柜"
    )
    
    private val BODY_COURIER_WORDS = arrayOf(
        "顺丰", "中通", "圆通", "韵达", "申通", "极兔", "京东",
        "邮政", "EMS", "德邦", "菜鸟", "百世",
        "天天", "宅急送", "优速", "跨越", "安能", "速尔",
        "DHL", "FedEx", "UPS", "TNT"
    )
    
    private val PACKAGE_WORDS = arrayOf(
        "包裹", "快件", "快递", "运单", "取件码", "提货码",
        "个包裹", "件快递", "个件", "个快递", "件包裹"
    )
    
    private val STRONG_ACTIONS = arrayOf(
        "凭码取件", "来取", "取件", "提货", "可凭", "请凭",
        "凭码", "请到", "领取快递", "前往领取", "到店领取"
    )
    
    private val MEDIUM_ACTIONS = arrayOf(
        "领取", "到站", "已到", "已到达", "到货",
        "投递", "存放", "已投递", "签收",
        "请尽快领取", "请及时领取", "请尽快取"
    )
    
    private val HIGH_RISK_WORDS = arrayOf(
        "已发货", "已揽收", "运输中", "确认收货", "去评价", "查看物流", "物流详情",
        "退款成功", "退款申请", "售后",
        "骑手正在配送", "预计送达", "订单已接单", "外卖", "跑腿费",
        "充值成功", "话费到账",
        "恭喜您", "您已中奖", "获得奖励", "免费送"
    )
    
    private val LIGHT_RISK_WORDS = arrayOf(
        "您的订单", "已发货", "物流信息", "点击查看"
    )
    
    private val P_SIGNATURE = Pattern.compile("[【\\[](.*?)[】\\]]")
    
    // ==================== 内存优化（#42）热路径正则常量 ====================
    // 以下正则原在函数内（tryExtractMoreCodes / hasFullTrackingButNoCode /
    // isValidCode / inferShelfInfo）每次解析短信时重新编译，批量导入上千条
    // 短信会累积大量短命 Pattern/Regex 对象，加剧 GC 与内存抖动。
    // 提取为对象级常量后只编译一次，热路径零分配。

    /** tryExtractMoreCodes 通用取件码形态 */
    private val P_MORE_CODES = Pattern.compile(
        "[A-Za-z]?\\d{1,3}[-.]\\d{1,3}[-.]\\d{1,6}|" +
        "[A-Za-z][-.]\\d{1,3}[-.]\\d{1,3}|" +
        "\\d{2,4}[-.]\\d{3,6}|" +
        "\\d{1,3}[-.]\\d{1,5}|" +
        "[A-Za-z]\\d{2,7}|" +
        "\\d{3,8}"
    )

    /** hasFullTrackingButNoCode 存在取件码标识 */
    private val P_HAS_CODE = Pattern.compile(
        "(?:取件码|取件凭证|提货码|取件号|凭码|请凭|可凭)\\s*[为是:：]?\\s*" +
        "([A-Za-z]?\\d{1,3}[-.]\\d{1,3}[-.]\\d{1,6}|" +
        "[A-Za-z][-.]\\d{1,3}[-.]\\d{1,3}|" +
        "\\d{2,4}[-.]\\d{3,6}|" +
        "\\d{1,3}[-.]\\d{1,5}|" +
        "[A-Za-z]\\d{2,7}|" +
        "\\d{3,8})"
    )

    /** inferShelfInfo 货架层级解析（如 1-2-3 → 1货架-第2层-3） */
    private val P_SHELF_INFO = Pattern.compile("([A-Za-z]?\\d{1,3})-(\\d{1,3})-(\\d{1,6})")

    /** isValidCode 排除「日期」形态 */
    private val R_INVALID_DATE = Regex("\\d{4}-\\d{1,2}-\\d{1,2}")

    /** isValidCode 排除「手机号」形态 */
    private val R_INVALID_PHONE = Regex("1[3-9]\\d{9}")

    /** isValidCode 排除「尾连空白」形态 */
    private val R_INVALID_TRAILING_SPACE = Regex("\\d{1,3}-\\d{1,3}-\\s")

    /** isValidCode 正向识别「取件码常见形态」 */
    private val R_VALID_CODE = Regex("\\d{2,4}[-.]\\d{3,6}")
    
    private val P_CODE_LABEL = Pattern.compile(
        "(取件码|取件凭证|提货码|取件号|取件密码|编码|码号|取件编号)\\s*[为是:：]?\\s*" +
        "([A-Za-z]?\\d{1,3}[-.]\\d{1,3}[-.]\\d{1,6}|" +
        "[A-Za-z][-.]\\d{1,3}[-.]\\d{1,3}|" +
        "\\d{2,4}[-.]\\d{3,6}|" +
        "\\d{1,3}[-.]\\d{1,5}|" +
        "[A-Za-z]\\d{2,7}|" +
        "\\d{3,8})"
    )
    
    private val P_CODE_PIN = Pattern.compile(
        "(?:请|可)?\\s*凭\\s*" +
        "([A-Za-z]?\\d{1,3}[-.]\\d{1,3}[-.]\\d{1,6}|" +
        "[A-Za-z][-.]\\d{1,3}[-.]\\d{1,3}|" +
        "\\d{2,4}[-.]\\d{3,6}|" +
        "\\d{1,3}[-.]\\d{1,5}|" +
        "[A-Za-z]\\d{2,7}|" +
        "\\d{3,8})" +
        "\\s*(来取|取件|到店|前往|领取)"
    )
    
    private val P_CODE_MULTI = Pattern.compile(
        "(取件码|取件凭证|提货码)\\s*[为是:：]?\\s*" +
        "([A-Za-z]?\\d{1,3}[-.]\\d{1,3}[-.]\\d{1,6}|" +
        "[A-Za-z][-.]\\d{1,3}[-.]\\d{1,3}|" +
        "\\d{2,4}[-.]\\d{3,6}|" +
        "\\d{1,3}[-.]\\d{1,5}|" +
        "[A-Za-z]\\d{2,7}|" +
        "\\d{3,8})" +
        "(?:\\s*[,，、]\\s*" +
        "([A-Za-z]?\\d{1,3}[-.]\\d{1,3}[-.]\\d{1,6}|" +
        "[A-Za-z][-.]\\d{1,3}[-.]\\d{1,3}|" +
        "\\d{2,4}[-.]\\d{3,6}|" +
        "\\d{1,3}[-.]\\d{1,5}|" +
        "[A-Za-z]\\d{2,7}|" +
        "\\d{3,8}))+"
    )
    
    private val P_CODE_TWO_PART = Pattern.compile(
        "(?:取件码|取件凭证|提货码|取件号)?\\s*[为是:：]?\\s*" +
        "(\\d{2,4}[-.]\\d{3,6})" +
        "(?=\\s*(?:到|至|于)\\s*[\\u4e00-\\u9fa5])"
    )
    
    private val P_CODE_TWO_PART_PIN = Pattern.compile(
        "(?:请|可)?\\s*凭\\s*" +
        "(\\d{2,4}[-.]\\d{3,6})" +
        "\\s*(?:到|至|于)\\s*[\\u4e00-\\u9fa5]"
    )
    
    private val P_CODE_TO_ADDR = Pattern.compile(
        "(\\d{2,4}[-.]\\d{3,6})\\s*(?:到|至|于)\\s*[\\u4e00-\\u9fa5]"
    )
    
    private val P_MULTI_CODES_TO_ADDR = Pattern.compile(
        "(?:取件码|取件凭证|提货码)?\\s*[为是:：]?\\s*" +
        "(\\d{2,4}[-.]\\d{3,6})" +
        "(?:\\s*[,，、和与及]\\s*" +
        "(\\d{2,4}[-.]\\d{3,6}))+" +
        "\\s*(?:到|至|于)\\s*[\\u4e00-\\u9fa5]"
    )
    
    private val P_MULTI_CODES_LIST = Pattern.compile(
        "(?:取件码|取件凭证|提货码)\\s*[为是:：]?\\s*" +
        "([A-Za-z]?\\d{1,3}[-.]\\d{1,3}[-.]\\d{1,6}|" +
        "[A-Za-z][-.]\\d{1,3}[-.]\\d{1,3}|" +
        "\\d{2,4}[-.]\\d{3,6}|" +
        "\\d{1,3}[-.]\\d{1,5}|" +
        "[A-Za-z]\\d{2,7}|" +
        "\\d{3,8})" +
        "(?:\\s*[,，、和与及]\\s*" +
        "([A-Za-z]?\\d{1,3}[-.]\\d{1,3}[-.]\\d{1,6}|" +
        "[A-Za-z][-.]\\d{1,3}[-.]\\d{1,3}|" +
        "\\d{2,4}[-.]\\d{3,6}|" +
        "\\d{1,3}[-.]\\d{1,5}|" +
        "[A-Za-z]\\d{2,7}|" +
        "\\d{3,8}))+"
    )
    
    private val P_MULTI_CODES_END = Pattern.compile(
        "([A-Za-z]?\\d{1,3}[-.]\\d{1,3}[-.]\\d{1,6}|" +
        "[A-Za-z][-.]\\d{1,3}[-.]\\d{1,3}|" +
        "\\d{2,4}[-.]\\d{3,6}|" +
        "\\d{1,3}[-.]\\d{1,5}|" +
        "[A-Za-z]\\d{2,7}|" +
        "\\d{3,8})" +
        "\\s*[,，、和与及]\\s*" +
        "([A-Za-z]?\\d{1,3}[-.]\\d{1,3}[-.]\\d{1,6}|" +
        "[A-Za-z][-.]\\d{1,3}[-.]\\d{1,3}|" +
        "\\d{2,4}[-.]\\d{3,6}|" +
        "\\d{1,3}[-.]\\d{1,5}|" +
        "[A-Za-z]\\d{2,7}|" +
        "\\d{3,8})" +
        "(?:\\s*[,，、和与及]\\s*" +
        "([A-Za-z]?\\d{1,3}[-.]\\d{1,3}[-.]\\d{1,6}|" +
        "[A-Za-z][-.]\\d{1,3}[-.]\\d{1,3}|" +
        "\\d{2,4}[-.]\\d{3,6}|" +
        "\\d{1,3}[-.]\\d{1,5}|" +
        "[A-Za-z]\\d{2,7}|" +
        "\\d{3,8}))*" +
        "(?:\\s*[。！？,，]|\\s*$)"
    )
    
    private val P_CODE_CELL = Pattern.compile(
        "(格口|格号|柜号|箱号|架号|第\\d+架|第\\d+箱|门号)\\s*[为是:：]?\\s*(\\d{1,4})"
    )
    
    private val P_CODE_FORMAT = Pattern.compile(
        "[A-Za-z]?\\d{1,3}[-.]\\d{1,3}[-.]\\d{1,6}|" +
        "[A-Za-z][-.]\\d{1,3}[-.]\\d{1,3}|" +
        "\\d{2,4}[-.]\\d{3,6}|" +
        "\\d{1,3}[-.]\\d{1,5}|" +
        "[A-Za-z]\\d{2,7}|" +
        "\\d{3,8}"
    )
    
    private val P_INCOMPLETE_CODE = Pattern.compile("\\d{1,3}-\\d{1,3}-\\s")
    
    private val P_ADDR_FULL = Pattern.compile(
        "(?:已到|已到达|已抵达|到达|到|已至|至)\\s*" +
        "([\\u4e00-\\u9fa5A-Za-z0-9]{2,15}(?:小区|花园|园|苑|城|庄|村|公寓|大厦|广场|府|郡|庭|阁|居|湾|都|里)" +
        "\\s*(?:\\d+号楼?|\\d+栋|第\\d+号楼?|第\\d+栋)?" +
        "(?:\\s*(?:\\d+单元|第\\d+单元))?" +
        "(?:\\s*(?:\\d{1,4}室?|地下室))?)"
    )
    
    private val P_ADDR_SIMPLE = Pattern.compile(
        "([\\u4e00-\\u9fa5A-Za-z0-9]{2,15}(?:小区|花园|园|苑|城|庄|村|公寓|大厦|广场|府|郡|庭|阁|居|湾|都|里)" +
        "\\s*(?:\\d+号楼?|\\d+栋|第\\d+号楼?|第\\d+栋)?" +
        "(?:\\s*(?:\\d+单元|第\\d+单元))?" +
        "(?:\\s*(?:\\d{1,4}室?|地下室))?)"
    )
    
    private val P_ADDR_BUILDING = Pattern.compile(
        "(\\d+号楼?\\d*单元?\\d*室?|\\d+栋\\d*单元?\\d*室?|第\\d+号楼?)"
    )
    
    private val P_ADDR_SHOP = Pattern.compile(
        "([\\u4e00-\\u9fa5A-Za-z0-9]{2,20}(?:店|站|点|处))"
    )
    
    private val P_ADDR_COLON = Pattern.compile(
        "地址\\s*[为是:：]\\s*([^，。,\\s]{3,50})"
    )
    
    private val P_TIME_LIMIT = Pattern.compile(
        "(?:请于|请在|请)?\\s*(\\d{1,2}[:点时]\\d{0,2}分?(?:之前|以前|前|内)|\\d{1,2}小时内|(?:今天|明天|当日)\\s*\\d{1,2}[:点时]\\d{0,2}?)"
    )
    
    private val P_TRACKING_TAIL = Pattern.compile(
        "(?:运单|快递单|单号|快递)\\s*(?:尾号|号尾|后四位|编号)?\\s*[为是:]?\\s*(\\d{4,})"
    )
    
    private val P_PKG_COUNT = Pattern.compile(
        "(?:有|共|总计|合计)\\s*(\\d+)\\s*(?:个|件|份)?\\s*(?:包裹|快件|快递|件)"
    )
    
    private val P_COURIER_PHONE = Pattern.compile(
        "(?:电话|联系|手机|tel|TEL|联系电话)\\s*[为是:]?\\s*(1[3-9]\\d{9})"
    )
    
    private val P_TRACKING_EXCLUDE = Pattern.compile(
        "(运单号|快递单号|单号|订单号)\\s*[为是:]?\\s*(\\S+)"
    )
    
    private val P_TAIL_NUMBER = Pattern.compile(
        "(?:运单|快递单|订单|快递|包裹)\\s*(?:尾号|号尾|后四位|后几位|编号|尾数)\\s*[为是:]?\\s*(\\d{3,8})"
    )
    
    private val P_TAIL_NUMBER_OF = Pattern.compile(
        "(?:运单|快递单|订单|快递)?\\s*(?:尾号|号尾)?\\s*[为是为是:]?\\s*(\\d{3,8})\\s*(?:的包裹|的快件|的快递|包裹|快件|快递)"
    )
    
    private val P_TRACKING_PART = Pattern.compile(
        "(?:运单|快递单|订单|单号)\\s*(\\d{3,8})\\s*(?:的包裹|的快件|的快递|包裹|快件|快递)?"
    )
    
    private val P_YOUR_PACKAGE = Pattern.compile(
        "您的(\\d{3,8})\\s*(?:包裹|快件|快递)"
    )
    
    private val P_PACKAGE_ARRIVED = Pattern.compile(
        "(\\d{3,8})\\s*(?:的包裹|的快件|的快递)?\\s*(?:已到达|已到|到达)"
    )
    
    private val P_COURIER_NUMBER = Pattern.compile(
        "您的(?:京东|顺丰|中通|圆通|韵达|申通|极兔|邮政|EMS|德邦|菜鸟|百世|天天)?(?:快递|物流)?\\s*(\\d{3,8})"
    )
    
    private val P_BEFORE_PIN = Pattern.compile(
        "(\\d{3,8})\\s*(?:已到达|已到|到达|到|可凭|请凭)"
    )
    
    private val P_FULL_TRACKING = Pattern.compile(
        "(?:运单|快递单|单号|订单)?\\s*(\\d{12,20})"
    )
    
    private val P_QUOTED_CODE = Pattern.compile(
        "[\"\\u201c\\u201d]([A-Za-z]?\\d{1,3}[-.]\\d{1,3}[-.]\\d{1,6}|[A-Za-z][-.]\\d{1,3}[-.]\\d{1,3}|\\d{2,4}[-.]\\d{3,6}|\\d{1,3}[-.]\\d{1,5}|[A-Za-z]\\d{2,7}|\\d{3,8})[\"\\u201c\\u201d]"
    )
    
    private val P_QUOTED_TAIL = Pattern.compile(
        "[\"\\u201c\\u201d](\\d{4,8})[\"\\u201c\\u201d]"
    )
    
    fun parse(smsBody: String?, senderNumber: String? = null): PickupResult {
        val r = PickupResult()
        r.rawSms = smsBody
        r.senderNumber = senderNumber
        
        if (smsBody.isNullOrBlank()) return r
        val text = smsBody.trim()
        
        for (veto in VETO_PATTERNS) {
            if (veto.matcher(text).find()) {
                r.vetoReason = veto.pattern()
                r.confidence = "NOT_PICKUP"
                r.scoreDetails["VETO"] = -999
                Log.d(TAG, "一票否决: ${veto.pattern()}")
                return r
            }
        }
        
        var score = 0
        
        val sigScore = calcSignatureScore(text)
        score += sigScore
        r.scoreDetails["A签名"] = sigScore
        
        val compScore = calcCompanyScore(text)
        score += compScore
        r.scoreDetails["B公司"] = compScore
        
        val pkgWordScore = calcPackageWordScore(text)
        score += pkgWordScore
        r.scoreDetails["C包裹"] = pkgWordScore
        
        val actionScore = calcActionScore(text)
        score += actionScore
        r.scoreDetails["D动作"] = actionScore
        
        val codeScore = calcCodeScore(text, r)
        score += codeScore
        r.scoreDetails["E取件码"] = codeScore
        
        val extraScore = calcExtraScore(text, r)
        score += extraScore
        r.scoreDetails["H附加"] = extraScore
        
        val locScore = calcLocationScore(text, r)
        score += locScore
        r.scoreDetails["F位置"] = locScore
        
        val timeScore = calcTimeScore(text, r)
        score += timeScore
        r.scoreDetails["G时间"] = timeScore
        
        val penalty = calcPenalty(text)
        score -= penalty
        r.scoreDetails["X扣分"] = -penalty
        
        r.totalScore = score
        
        when {
            score >= THRESHOLD_CONFIRMED -> {
                r.confidence = "CONFIRMED"
                r.isPickupSms = true
            }
            score >= THRESHOLD_PROBABLE -> {
                r.confidence = "PROBABLE"
                r.isPickupSms = true
            }
            score >= THRESHOLD_POSSIBLE -> {
                r.confidence = "POSSIBLE"
                r.isPickupSms = false
            }
            else -> {
                r.confidence = "NOT_PICKUP"
                r.isPickupSms = false
            }
        }
        
        if (r.isPickupSms && r.codes.isEmpty()) {
            r.isPickupSms = false
            r.confidence = "NOT_PICKUP"
        }
        
        if (r.isPickupSms && hasFullTrackingButNoCode(text)) {
            r.isPickupSms = false
            r.confidence = "NOT_PICKUP"
            r.vetoReason = "仅包含运单号，无取件码"
            Log.d(TAG, "排除: 仅包含运单号，无取件码")
        }
        
        val pkgCount = r.packageCount
        if (r.isPickupSms && pkgCount != null && pkgCount > 1 && r.codes.size < pkgCount) {
            Log.d(TAG, "包裹数量验证: 声明${pkgCount}个包裹，仅识别到${r.codes.size}个取件码，尝试补充识别")
            val additionalCodes = tryExtractMoreCodes(text, r.codes, pkgCount)
            if (additionalCodes.isNotEmpty()) {
                r.codes.addAll(additionalCodes)
                Log.d(TAG, "补充识别到${additionalCodes.size}个取件码: $additionalCodes")
            }
        }
        
        if (r.codes.isNotEmpty()) {
            r.inferredShelf = inferShelfInfo(r.codes[0])
        }
        
        if (r.courier == null) {
            r.courier = extractCourierFromBody(text)
        }
        
        Log.d(TAG, "解析结果: score=$score, confidence=${r.confidence}, codes=${r.codes}")
        
        return r
    }
    
    private fun tryExtractMoreCodes(text: String, existingCodes: List<String>, expectedCount: Int): List<String> {
        val additionalCodes = mutableListOf<String>()
        val needed = expectedCount - existingCodes.size
        
        val m = P_MORE_CODES.matcher(text)
        while (m.find() && additionalCodes.size < needed) {
            val code = m.group()
            if (isValidCode(code) && !existingCodes.contains(code) && !additionalCodes.contains(code)) {
                additionalCodes.add(code)
            }
        }
        
        return additionalCodes
    }
    
    private fun hasFullTrackingButNoCode(text: String): Boolean {
        val m = P_FULL_TRACKING.matcher(text)
        if (m.find()) {
            if (!P_HAS_CODE.matcher(text).find()) {
                return true
            }
        }
        return false
    }
    
    private fun calcSignatureScore(text: String): Int {
        val m = P_SIGNATURE.matcher(text)
        if (!m.find()) return 0
        val sig = m.group(1) ?: return 0
        
        for (s in SIG_STATION) {
            if (sig.contains(s)) return 35
        }
        for (s in SIG_LOCKER) {
            if (sig.contains(s)) return 30
        }
        for (s in SIG_COURIER) {
            if (sig.contains(s)) return 30
        }
        return 5
    }
    
    private fun calcCompanyScore(text: String): Int {
        var score = 0
        var hasStation = false
        var hasLocker = false
        var hasCourier = false
        
        for (s in BODY_STATION_WORDS) {
            if (text.contains(s)) { hasStation = true; break }
        }
        for (s in BODY_LOCKER_WORDS) {
            if (text.contains(s)) { hasLocker = true; break }
        }
        for (s in BODY_COURIER_WORDS) {
            if (text.contains(s)) { hasCourier = true; break }
        }
        
        if (hasStation) score += 20
        if (hasLocker) score += 15
        if (hasCourier) score += 15
        return minOf(score, 35)
    }
    
    private fun calcPackageWordScore(text: String): Int {
        var count = 0
        for (w in PACKAGE_WORDS) {
            if (text.contains(w)) count++
        }
        return minOf(count * 10, 20)
    }
    
    private fun calcActionScore(text: String): Int {
        for (a in STRONG_ACTIONS) {
            if (text.contains(a)) return 25
        }
        for (a in MEDIUM_ACTIONS) {
            if (text.contains(a)) return 15
        }
        if (text.contains("取")) return 8
        return 0
    }
    
    private fun calcCodeScore(text: String, r: PickupResult): Int {
        var score = 0
        val allCodes = mutableListOf<String>()
        val allLevels = mutableListOf<String>()
        val excludedCodes = mutableSetOf<String>()
        
        val exm = P_TRACKING_EXCLUDE.matcher(text)
        while (exm.find()) {
            exm.group(2)?.let { excludedCodes.add(it) }
        }
        
        val tailm = P_TAIL_NUMBER.matcher(text)
        while (tailm.find()) {
            tailm.group(1)?.let { excludedCodes.add(it) }
        }
        
        val tailofm = P_TAIL_NUMBER_OF.matcher(text)
        while (tailofm.find()) {
            tailofm.group(1)?.let { excludedCodes.add(it) }
        }
        
        val trackingpartm = P_TRACKING_PART.matcher(text)
        while (trackingpartm.find()) {
            trackingpartm.group(1)?.let { excludedCodes.add(it) }
        }
        
        val yourpackagem = P_YOUR_PACKAGE.matcher(text)
        while (yourpackagem.find()) {
            yourpackagem.group(1)?.let { excludedCodes.add(it) }
        }
        
        val packagearrivedm = P_PACKAGE_ARRIVED.matcher(text)
        while (packagearrivedm.find()) {
            packagearrivedm.group(1)?.let { excludedCodes.add(it) }
        }
        
        val courierm = P_COURIER_NUMBER.matcher(text)
        while (courierm.find()) {
            courierm.group(1)?.let { excludedCodes.add(it) }
        }
        
        val beforepinm = P_BEFORE_PIN.matcher(text)
        while (beforepinm.find()) {
            beforepinm.group(1)?.let { excludedCodes.add(it) }
        }
        
        val fulltrackingm = P_FULL_TRACKING.matcher(text)
        while (fulltrackingm.find()) {
            fulltrackingm.group(1)?.let { excludedCodes.add(it) }
        }
        
        val quotedtailm = P_QUOTED_TAIL.matcher(text)
        while (quotedtailm.find()) {
            quotedtailm.group(1)?.let { excludedCodes.add(it) }
        }
        
        val incm = P_INCOMPLETE_CODE.matcher(text)
        while (incm.find()) {
            excludedCodes.add(incm.group().trim())
        }
        
        var m = P_QUOTED_CODE.matcher(text)
        while (m.find()) {
            val code = m.group(1)
            if (code != null && isValidCode(code) && !excludedCodes.contains(code) && !allCodes.contains(code)) {
                allCodes.add(code)
                allLevels.add("S")
                score = maxOf(score, 35)
            }
        }
        
        m = P_CODE_LABEL.matcher(text)
        while (m.find()) {
            val code = m.group(2)
            if (code != null && isValidCode(code) && !excludedCodes.contains(code)) {
                allCodes.add(code)
                allLevels.add("S")
                score = maxOf(score, 35)
            }
        }
        
        m = P_CODE_TWO_PART.matcher(text)
        while (m.find()) {
            val code = m.group(1)
            if (code != null && isValidCode(code) && !excludedCodes.contains(code) && !allCodes.contains(code)) {
                allCodes.add(code)
                allLevels.add("S")
                score = maxOf(score, 35)
            }
        }
        
        m = P_CODE_TWO_PART_PIN.matcher(text)
        while (m.find()) {
            val code = m.group(1)
            if (code != null && isValidCode(code) && !excludedCodes.contains(code) && !allCodes.contains(code)) {
                allCodes.add(code)
                allLevels.add("A")
                score = maxOf(score, 30)
            }
        }
        
        m = P_MULTI_CODES_TO_ADDR.matcher(text)
        while (m.find()) {
            val region = m.group()
            val cm = P_CODE_FORMAT.matcher(region)
            while (cm.find()) {
                val code = cm.group()
                if (isValidCode(code) && !excludedCodes.contains(code) && !allCodes.contains(code)) {
                    allCodes.add(code)
                    allLevels.add("S")
                    score += 35
                }
            }
        }
        
        m = P_CODE_TO_ADDR.matcher(text)
        while (m.find()) {
            val code = m.group(1)
            if (code != null && isValidCode(code) && !excludedCodes.contains(code) && !allCodes.contains(code)) {
                allCodes.add(code)
                allLevels.add("A")
                score += 30
            }
        }
        
        m = P_CODE_PIN.matcher(text)
        while (m.find()) {
            val code = m.group(1)
            if (code != null && isValidCode(code) && !excludedCodes.contains(code) && !allCodes.contains(code)) {
                allCodes.add(code)
                allLevels.add("A")
                score += 30
            }
        }
        
        m = P_CODE_MULTI.matcher(text)
        while (m.find()) {
            val region = m.group()
            val cm = P_CODE_FORMAT.matcher(region)
            while (cm.find()) {
                val code = cm.group()
                if (isValidCode(code) && !excludedCodes.contains(code) && !allCodes.contains(code)) {
                    allCodes.add(code)
                    allLevels.add("S")
                    score += 35
                }
            }
        }
        
        m = P_MULTI_CODES_LIST.matcher(text)
        while (m.find()) {
            val region = m.group()
            val cm = P_CODE_FORMAT.matcher(region)
            while (cm.find()) {
                val code = cm.group()
                if (isValidCode(code) && !excludedCodes.contains(code) && !allCodes.contains(code)) {
                    allCodes.add(code)
                    allLevels.add("S")
                    score += 35
                }
            }
        }
        
        m = P_MULTI_CODES_END.matcher(text)
        while (m.find()) {
            val region = m.group()
            val cm = P_CODE_FORMAT.matcher(region)
            while (cm.find()) {
                val code = cm.group()
                if (isValidCode(code) && !excludedCodes.contains(code) && !allCodes.contains(code)) {
                    allCodes.add(code)
                    allLevels.add("S")
                    score += 35
                }
            }
        }
        
        m = P_CODE_CELL.matcher(text)
        while (m.find()) {
            val code = m.group(2)
            if (code != null && !allCodes.contains(code)) {
                allCodes.add(code)
                allLevels.add("S")
                score = maxOf(score, 30)
            }
        }
        
        if (allCodes.isEmpty()) {
            var hasPkgContext = false
            for (w in PACKAGE_WORDS) {
                if (text.contains(w)) { hasPkgContext = true; break }
            }
            if (hasPkgContext) {
                m = P_CODE_FORMAT.matcher(text)
                while (m.find()) {
                    val code = m.group()
                    if (isValidCode(code) && !excludedCodes.contains(code)) {
                        allCodes.add(code)
                        allLevels.add("B")
                        score = maxOf(score, 20)
                    }
                }
            }
        }
        
        r.codes = allCodes
        r.codeLevels = allLevels
        return score
    }
    
    private fun isValidCode(code: String?): Boolean {
        if (code == null || code.length < 3 || code.length > 15) return false
        if (code.matches(R_INVALID_DATE)) return false
        if (code.matches(R_INVALID_PHONE)) return false
        if (code.endsWith("-")) return false
        if (code.matches(R_INVALID_TRAILING_SPACE)) return false
        if (code.matches(R_VALID_CODE)) return true
        return true
    }
    
    private fun calcLocationScore(text: String, r: PickupResult): Int {
        var score = 0
        var cleanedText = text
        
        for (code in r.codes) {
            cleanedText = cleanedText.replace(code, " ")
        }
        
        var m = P_ADDR_FULL.matcher(cleanedText)
        if (m.find()) {
            r.address = m.group(1)
            return 20
        }
        
        m = P_ADDR_SIMPLE.matcher(text)
        if (m.find()) {
            val addr = m.group(1) ?: ""
            var isValidAddr = true
            for (code in r.codes) {
                if (addr.contains(code) || code.contains(addr)) {
                    isValidAddr = false
                    break
                }
            }
            if (isValidAddr && addr.isNotBlank()) {
                r.address = addr
                return 18
            }
        }
        
        m = P_ADDR_COLON.matcher(text)
        if (m.find()) {
            r.address = m.group(1)
            return 15
        }
        
        m = P_ADDR_BUILDING.matcher(cleanedText)
        if (m.find()) {
            r.address = m.group(1)
            score = 15
        }
        
        m = P_ADDR_SHOP.matcher(text)
        if (m.find() && score < 15) {
            r.address = m.group(1)
            score = 10
        }
        
        return score
    }
    
    private fun calcTimeScore(text: String, r: PickupResult): Int {
        val m = P_TIME_LIMIT.matcher(text)
        if (m.find()) {
            r.timeLimit = m.group(1)
            return 10
        }
        if (text.contains("尽快") || text.contains("及时")) return 5
        return 0
    }
    
    private fun calcExtraScore(text: String, r: PickupResult): Int {
        var score = 0
        
        var m = P_PKG_COUNT.matcher(text)
        if (m.find()) {
            try {
                r.packageCount = m.group(1)?.toInt()
                score += 5
            } catch (e: NumberFormatException) {}
        }
        
        m = P_TRACKING_TAIL.matcher(text)
        if (m.find()) {
            r.trackingTail = m.group(1)
            score += 5
        }
        
        m = P_COURIER_PHONE.matcher(text)
        if (m.find()) {
            r.courierPhone = m.group(1)
            score += 5
        }
        
        return score
    }
    
    private fun calcPenalty(text: String): Int {
        var penalty = 0
        for (w in HIGH_RISK_WORDS) {
            if (text.contains(w)) penalty += 25
        }
        for (w in LIGHT_RISK_WORDS) {
            if (text.contains(w)) penalty += 10
        }
        return minOf(penalty, 50)
    }
    
    private fun extractCourierFromBody(text: String): String? {
        val order = arrayOf(
            "顺丰", "中通", "圆通", "韵达", "申通", "极兔", "京东",
            "邮政", "EMS", "德邦", "菜鸟", "百世",
            "天天", "宅急送", "优速", "跨越", "安能", "速尔",
            "DHL", "FedEx", "UPS", "TNT",
            "多多买菜", "多多", "美团优选", "淘菜菜"
        )
        for (c in order) {
            if (text.contains(c)) return c
        }
        return null
    }
    
    private fun inferShelfInfo(code: String): String? {
        val m = P_SHELF_INFO.matcher(code)
        if (m.find()) {
            return "${m.group(1)}货架-第${m.group(2)}层-${m.group(3)}"
        }
        return null
    }
    
    fun toPickupItem(result: PickupResult, receiveTime: Long = System.currentTimeMillis()): PickupItem? {
        if (!result.isPickupProbable()) return null

        return PickupItem(
            pickupCode = result.getPrimaryCode() ?: "未知",
            address = result.address ?: "未知地址",
            addressTag = generateAddressTag(result.address ?: "未知地址"),
            courierCompany = result.courier ?: "快递",
            trackingNumber = result.trackingTail ?: "",
            receiveTime = receiveTime,
            expireTime = receiveTime + (7L * 24 * 60 * 60 * 1000),
            status = PickupItem.STATUS_PENDING,
            rawSms = result.rawSms ?: "",
            senderPhone = result.senderNumber ?: "",
            phoneNumber = result.courierPhone ?: ""
        )
    }

    fun toPickupItems(result: PickupResult, receiveTime: Long = System.currentTimeMillis()): List<PickupItem> {
        if (!result.isPickupProbable()) return emptyList()

        return result.codes.map { code ->
            PickupItem(
                pickupCode = code,
                address = result.address ?: "未知地址",
                addressTag = generateAddressTag(result.address ?: "未知地址"),
                courierCompany = result.courier ?: "快递",
                trackingNumber = result.trackingTail ?: "",
                receiveTime = receiveTime,
                expireTime = receiveTime + (7L * 24 * 60 * 60 * 1000),
                status = PickupItem.STATUS_PENDING,
                rawSms = result.rawSms ?: "",
                senderPhone = result.senderNumber ?: "",
                phoneNumber = result.courierPhone ?: ""
            )
        }
    }
    
    /**
     * 生成地址标签。
     *
     * R3 / H-13：本方法原本是**第四套**独立实现，而且与另外几套有可观察的差异 ——
     * 例如对「示例小区3号楼驿站」它返回楼号组的 `3号`（而不是 `3号楼`），
     * 对驿站它返回 `驿站`（而不是驿站全名）。同一个概念产出不同字符串，
     * 意味着按地址标签做的排序与"常用驿站"关联必然对不上。
     * 现统一转发到 [AddressTag.of]。
     */
    fun generateAddressTag(address: String): String = AddressTag.of(address)
    
    fun isPickupSms(message: String): Boolean {
        val result = parse(message)
        return result.isPickup()
    }
    
    fun parseSms(message: String, senderPhone: String = "", receiveTime: Long = System.currentTimeMillis()): PickupItem? {
        val result = parse(message, senderPhone)
        return toPickupItem(result, receiveTime)
    }
    
    fun parseSmsMultipleCodes(message: String, senderPhone: String = "", receiveTime: Long = System.currentTimeMillis()): List<PickupItem> {
        val result = parse(message, senderPhone)
        return toPickupItems(result, receiveTime)
    }
    
    fun analyzeSms(message: String): PickupResult {
        return parse(message)
    }
}
