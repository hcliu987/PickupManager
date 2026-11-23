/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.shanqiu.pickupmanager.R
import com.shanqiu.pickupmanager.ui.ComposeMainActivity

/**
 * 多品牌小部件适配器
 * 
 * 遵循各品牌官方开发者文档：
 * - 小米 MIUI/HyperOS: https://dev.mi.com/console/doc/detail?pId=2479
 * - 华为 HarmonyOS: https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/form-kit
 * - OPPO ColorOS: https://open.oppomobile.com/
 * - vivo OriginOS: https://dev.vivo.com.cn/documentCenter/doc/845
 * - 三星 OneUI: https://developer.samsung.com/
 */
object WidgetBrandAdapter {

    private const val TAG = "WidgetBrandAdapter"

    /**
     * 品牌/ROM 类型枚举
     */
    enum class BrandType {
        MIUI_HYPEROS,    // 小米/澎湃OS
        HARMONYOS,       // 华为/鸿蒙
        COLOROS,         // OPPO/ColorOS
        FUNTOUCH_ORIGIN, // vivo/FuntouchOS/OriginOS
        ONEUI,           // 三星/OneUI
        UNKNOWN          // 其他/原生
    }

    /**
     * 检测当前设备品牌/ROM类型
     */
    fun detectBrand(): BrandType {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val display = Build.DISPLAY.lowercase()

        return when {
            // 小米/澎湃OS检测
            manufacturer.contains("xiaomi") || brand.contains("xiaomi") ||
                    manufacturer.contains("redmi") || brand.contains("redmi") ||
                    manufacturer.contains("poco") || brand.contains("poco") ||
                    display.contains("miui") || display.contains("hyperos") -> {
                BrandType.MIUI_HYPEROS
            }

            // 华为/鸿蒙检测
            manufacturer.contains("huawei") || brand.contains("huawei") ||
                    manufacturer.contains("honor") || brand.contains("honor") ||
                    display.contains("harmonyos") || display.contains("emui") -> {
                BrandType.HARMONYOS
            }

            // OPPO/ColorOS检测
            manufacturer.contains("oppo") || brand.contains("oppo") ||
                    manufacturer.contains("realme") || brand.contains("realme") ||
                    manufacturer.contains("oneplus") || brand.contains("oneplus") ||
                    display.contains("coloros") -> {
                BrandType.COLOROS
            }

            // vivo/FuntouchOS/OriginOS检测
            manufacturer.contains("vivo") || brand.contains("vivo") ||
                    manufacturer.contains("iqoo") || brand.contains("iqoo") ||
                    display.contains("funtouch") || display.contains("origin") -> {
                BrandType.FUNTOUCH_ORIGIN
            }

            // 三星/OneUI检测
            manufacturer.contains("samsung") || brand.contains("samsung") -> {
                BrandType.ONEUI
            }

            else -> BrandType.UNKNOWN
        }
    }

    /**
     * 获取品牌名称（用于日志和调试）
     */
    fun getBrandName(): String {
        return when (detectBrand()) {
            BrandType.MIUI_HYPEROS -> "MIUI/HyperOS"
            BrandType.HARMONYOS -> "HarmonyOS"
            BrandType.COLOROS -> "ColorOS"
            BrandType.FUNTOUCH_ORIGIN -> "FuntouchOS/OriginOS"
            BrandType.ONEUI -> "OneUI"
            BrandType.UNKNOWN -> "Unknown"
        }
    }

    /**
     * 检查应用是否在白名单中（电池优化豁免）
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 获取品牌特定的刷新间隔（毫秒）
     * 
     * 各品牌官方文档要求：
     * - MIUI/HyperOS: 无强制限制，建议曝光刷新
     * - HarmonyOS: 定时刷新最短5分钟，每天最多50次
     * - ColorOS: 无官方文档，建议保守设置
     * - vivo OriginOS: 最短12小时
     * - 三星 OneUI: 无官方文档，建议保守设置
     */
    fun getRecommendedRefreshInterval(): Long {
        return when (detectBrand()) {
            BrandType.MIUI_HYPEROS -> 60_000L      // 1分钟（配合曝光刷新）
            BrandType.HARMONYOS -> 300_000L          // 5分钟（官方最短间隔）
            BrandType.COLOROS -> 600_000L            // 10分钟（保守设置）
            BrandType.FUNTOUCH_ORIGIN -> 43_200_000L // 12小时（官方要求）
            BrandType.ONEUI -> 600_000L              // 10分钟（保守设置）
            BrandType.UNKNOWN -> 300_000L            // 5分钟（默认）
        }
    }

    /**
     * 触发应用主动刷新小部件
     * 
     * 遵循各品牌官方文档：
     * - MIUI: 使用 miui.appwidget.action.APPWIDGET_UPDATE
     * - vivo: 使用标准 APPWIDGET_UPDATE
     * - 其他: 使用标准 APPWIDGET_UPDATE
     */
    fun triggerWidgetUpdate(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val brand = detectBrand()

        // 获取所有小部件ID
        val widgetProviders = listOf(
            PickupWidgetProvider::class.java,
            PickupWidgetProvider2x2::class.java,
            PickupWidgetProvider3x4::class.java,
            PickupWidgetProvider4x3::class.java,
            PickupWidgetProvider4x4::class.java,
            PickupWidgetProvider5x4::class.java,
            PickupWidgetProvider5x3::class.java
        )

        for (providerClass in widgetProviders) {
            val componentName = ComponentName(context, providerClass)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            if (appWidgetIds.isNotEmpty()) {
                // 发送更新广播
                val intent = Intent(context, providerClass).apply {
                    action = when (brand) {
                        BrandType.MIUI_HYPEROS -> "miui.appwidget.action.APPWIDGET_UPDATE"
                        else -> AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    }
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
                }
                context.sendBroadcast(intent)

                // 触发数据刷新
                for (appWidgetId in appWidgetIds) {
                    appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_pickup_list)
                }

                Log.d(TAG, "触发 ${providerClass.simpleName} 更新: ${appWidgetIds.size} 个")
            }
        }
    }

    /**
     * 创建打开应用的 PendingIntent
     * 
     * 遵循各品牌官方文档：
     * - 所有品牌都推荐使用 PendingIntent.getActivity 直接启动 Activity
     * - 不建议使用 BroadcastReceiver/Service 中转
     */
    fun createOpenAppPendingIntent(
        context: Context,
        appWidgetId: Int
    ): android.app.PendingIntent {
        val intent = Intent(context, ComposeMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("from_widget", true)
            // 添加来源标记，便于统计
            putExtra("widget_id", appWidgetId)
        }

        return android.app.PendingIntent.getActivity(
            context,
            appWidgetId + 5000,  // 唯一 requestCode
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 检查是否需要显示电池优化提示
     * 
     * 各品牌对后台限制不同：
     * - MIUI/HyperOS: 需要关闭电池优化 + 开启自启动
     * - HarmonyOS: 需要后台运行权限
     * - ColorOS: 需要关闭后台冻结
     * - vivo: 需要允许后台运行
     * - 三星: 需要设置为"永不休眠"
     */
    fun shouldShowBatteryOptimizationTip(context: Context): Boolean {
        return !isIgnoringBatteryOptimizations(context)
    }

    /**
     * 获取品牌特定的电池优化设置 Intent
     */
    fun getBatteryOptimizationIntent(context: Context): Intent {
        val brand = detectBrand()
        val intent = Intent()

        when (brand) {
            BrandType.MIUI_HYPEROS -> {
                // 小米：跳转到电池优化设置
                intent.action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            }
            BrandType.HARMONYOS -> {
                // 华为：跳转到应用启动管理
                intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                intent.data = Uri.parse("package:${context.packageName}")
            }
            BrandType.COLOROS -> {
                // OPPO：跳转到电池设置
                intent.action = Settings.ACTION_BATTERY_SAVER_SETTINGS
            }
            BrandType.FUNTOUCH_ORIGIN -> {
                // vivo：跳转到电池设置
                intent.action = Settings.ACTION_BATTERY_SAVER_SETTINGS
            }
            BrandType.ONEUI -> {
                // 三星：跳转到电池设置
                intent.action = Settings.ACTION_BATTERY_SAVER_SETTINGS
            }
            BrandType.UNKNOWN -> {
                // 通用：跳转到应用详情
                intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                intent.data = Uri.parse("package:${context.packageName}")
            }
        }

        return intent
    }

    /**
     * 获取品牌特定的权限说明文本
     */
    fun getPermissionDescription(context: Context): String {
        val brand = detectBrand()
        return when (brand) {
            BrandType.MIUI_HYPEROS -> "请在设置中关闭电池优化并开启自启动权限"
            BrandType.HARMONYOS -> "请在设置中允许后台运行"
            BrandType.COLOROS -> "请在设置中关闭后台冻结"
            BrandType.FUNTOUCH_ORIGIN -> "请在设置中允许后台运行"
            BrandType.ONEUI -> "请在电池设置中设置为从不休眠"
            BrandType.UNKNOWN -> "请在设置中允许后台运行"
        }
    }

    /**
     * 检查是否支持曝光刷新
     * 
     * 只有 MIUI/HyperOS 官方支持曝光刷新
     */
    fun supportsExposureRefresh(): Boolean {
        return detectBrand() == BrandType.MIUI_HYPEROS
    }

    /**
     * 检查是否需要处理三星自动删除验证码问题
     * 
     * 三星 OneUI 会自动删除包含验证码的短信
     */
    fun shouldHandleSamsungAutoDelete(): Boolean {
        return detectBrand() == BrandType.ONEUI
    }

    /**
     * 检查是否需要处理 OPPO 后台限制
     * 
     * OPPO ColorOS 对后台应用有严格限制
     */
    fun shouldHandleOPPOBackgroundLimit(): Boolean {
        return detectBrand() == BrandType.COLOROS
    }

    /**
     * 检查是否需要处理 vivo 平行世界问题
     * 
     * vivo OriginOS 平行世界可能不支持第三方小部件
     */
    fun shouldHandlevivoParallelWorld(): Boolean {
        return detectBrand() == BrandType.FUNTOUCH_ORIGIN
    }

    /**
     * 获取品牌特定的刷新策略说明
     */
    fun getRefreshStrategyDescription(): String {
        val brand = detectBrand()
        return when (brand) {
            BrandType.MIUI_HYPEROS -> "使用曝光刷新机制，用户滑动到桌面时自动刷新"
            BrandType.HARMONYOS -> "使用定时刷新，最短间隔5分钟，每天最多50次"
            BrandType.COLOROS -> "使用应用主动刷新，配合前台服务唤醒进程"
            BrandType.FUNTOUCH_ORIGIN -> "使用应用主动刷新，最短间隔12小时"
            BrandType.ONEUI -> "使用应用主动刷新，配合前台服务唤醒进程"
            BrandType.UNKNOWN -> "使用标准 Android 刷新机制"
        }
    }

    /**
     * 初始化品牌适配
     * 在应用启动时调用
     */
    fun init(context: Context) {
        val brand = detectBrand()
        Log.d(TAG, "检测到设备品牌: ${getBrandName()}")
        Log.d(TAG, "推荐刷新间隔: ${getRecommendedRefreshInterval() / 1000}秒")
        Log.d(TAG, "刷新策略: ${getRefreshStrategyDescription()}")
        Log.d(TAG, "支持曝光刷新: ${supportsExposureRefresh()}")
        Log.d(TAG, "需要处理三星自动删除: ${shouldHandleSamsungAutoDelete()}")
        Log.d(TAG, "需要处理OPPO后台限制: ${shouldHandleOPPOBackgroundLimit()}")
        Log.d(TAG, "需要处理vivo平行世界: ${shouldHandlevivoParallelWorld()}")
    }
}
