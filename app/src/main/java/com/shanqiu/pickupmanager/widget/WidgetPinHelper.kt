/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.widget

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.shanqiu.pickupmanager.util.PhoneBrandDetector

/**
 * 小部件固定助手
 *
 * 核心发现：国产品牌 ROM（MIUI/HyperOS/EMUI/ColorOS/FuntouchOS）上
 * requestPinAppWidget() 虽然返回 true，但实际不弹出确认对话框，
 * 小部件也不会被添加到桌面。
 *
 * 因此策略改为：
 * - 国产品牌 ROM: 直接显示操作引导对话框（清晰的三步操作说明）
 * - 原生 Android (Pixel/Generic): 使用 requestPinAppWidget() API
 * - Android 8.0 以下: 显示操作引导对话框
 */
object WidgetPinHelper {

    private const val TAG = "WidgetPinHelper"

    /**
     * 国产品牌 ROM 列表 - 这些 ROM 上 requestPinAppWidget() 不可靠
     */
    private val CHINESE_ROM_BRANDS = setOf(
        PhoneBrandDetector.PhoneBrand.XIAOMI,
        PhoneBrandDetector.PhoneBrand.HUAWEI,
        PhoneBrandDetector.PhoneBrand.HONOR,
        PhoneBrandDetector.PhoneBrand.OPPO,
        PhoneBrandDetector.PhoneBrand.REALME,
        PhoneBrandDetector.PhoneBrand.ONEPLUS,
        PhoneBrandDetector.PhoneBrand.VIVO,
        PhoneBrandDetector.PhoneBrand.IQOO,
        PhoneBrandDetector.PhoneBrand.MEIZU
    )

    private val WIDGET_PROVIDERS = mapOf(
        "3x3" to "com.shanqiu.pickupmanager.widget.PickupWidgetProvider",
        "3x4" to "com.shanqiu.pickupmanager.widget.PickupWidgetProvider3x4",
        "4x3" to "com.shanqiu.pickupmanager.widget.PickupWidgetProvider4x3",
        "4x4" to "com.shanqiu.pickupmanager.widget.PickupWidgetProvider4x4",
        "5x4" to "com.shanqiu.pickupmanager.widget.PickupWidgetProvider5x4",
        "5x3" to "com.shanqiu.pickupmanager.widget.PickupWidgetProvider5x3"
    )

    private val SIZE_DISPLAY_NAMES = mapOf(
        "3x3" to "3×3 标准",
        "3x4" to "3×4 紧凑",
        "4x3" to "4×3 宽屏",
        "4x4" to "4×4 中等",
        "5x4" to "5×4 大屏",
        "5x3" to "5×3 超宽"
    )

    /**
     * 各品牌小部件添加的步骤说明
     */
    private val BRAND_ADD_STEPS = mapOf(
        PhoneBrandDetector.PhoneBrand.XIAOMI to listOf(
            "长按桌面空白处",
            "点击「小部件」",
            "找到「码上取」并选择尺寸",
            "拖拽到桌面即可"
        ),
        PhoneBrandDetector.PhoneBrand.HUAWEI to listOf(
            "长按桌面空白处",
            "点击「桌面小部件」",
            "找到「码上取」并选择尺寸",
            "拖拽到桌面即可"
        ),
        PhoneBrandDetector.PhoneBrand.HONOR to listOf(
            "长按桌面空白处",
            "点击「桌面小部件」",
            "找到「码上取」并选择尺寸",
            "拖拽到桌面即可"
        ),
        PhoneBrandDetector.PhoneBrand.OPPO to listOf(
            "长按桌面空白处",
            "点击「小部件」",
            "找到「码上取」并选择尺寸",
            "拖拽到桌面即可"
        ),
        PhoneBrandDetector.PhoneBrand.REALME to listOf(
            "长按桌面空白处",
            "点击「小部件」",
            "找到「码上取」并选择尺寸",
            "拖拽到桌面即可"
        ),
        PhoneBrandDetector.PhoneBrand.ONEPLUS to listOf(
            "长按桌面空白处",
            "点击「小部件」",
            "找到「码上取」并选择尺寸",
            "拖拽到桌面即可"
        ),
        PhoneBrandDetector.PhoneBrand.VIVO to listOf(
            "长按桌面空白处",
            "点击「桌面小部件」",
            "找到「码上取」并选择尺寸",
            "拖拽到桌面即可"
        ),
        PhoneBrandDetector.PhoneBrand.IQOO to listOf(
            "长按桌面空白处",
            "点击「桌面小部件」",
            "找到「码上取」并选择尺寸",
            "拖拽到桌面即可"
        ),
        PhoneBrandDetector.PhoneBrand.SAMSUNG to listOf(
            "长按桌面空白处",
            "点击「小部件」",
            "找到「码上取」并选择尺寸",
            "拖拽到桌面即可"
        ),
        PhoneBrandDetector.PhoneBrand.MEIZU to listOf(
            "长按桌面空白处",
            "点击「小部件」",
            "找到「码上取」并选择尺寸",
            "拖拽到桌面即可"
        )
    )

    /**
     * 添加小部件的结果
     */
    enum class PinResult {
        /** requestPinAppWidget 已发送，等待系统确认对话框 */
        PIN_REQUESTED,
        /** 需要显示操作引导对话框 */
        SHOW_GUIDE,
        /** 不支持的尺寸 */
        INVALID_SIZE
    }

    /**
     * 请求将指定尺寸的小部件固定到桌面
     */
    fun requestPinWidget(activity: Activity, widgetSize: String): PinResult {
        val providerClassName = WIDGET_PROVIDERS[widgetSize]
        if (providerClassName == null) {
            Log.w(TAG, "未知的小部件尺寸: $widgetSize")
            return PinResult.INVALID_SIZE
        }

        val brand = PhoneBrandDetector.detectBrand()
        Log.d(TAG, "请求固定小部件: size=$widgetSize, brand=$brand")

        // 国产品牌 ROM：requestPinAppWidget 不可靠，直接走引导对话框
        if (brand in CHINESE_ROM_BRANDS) {
            Log.d(TAG, "国产品牌ROM，跳过requestPinAppWidget，直接显示引导对话框")
            return PinResult.SHOW_GUIDE
        }

        // 三星：requestPinAppWidget 在 OneUI 上通常可用
        if (brand == PhoneBrandDetector.PhoneBrand.SAMSUNG) {
            return tryNativePinWidget(activity, providerClassName, widgetSize)
        }

        // 原生 Android / 其他：尝试 requestPinAppWidget
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val appWidgetManager = AppWidgetManager.getInstance(activity)
            if (appWidgetManager.isRequestPinAppWidgetSupported) {
                return tryNativePinWidget(activity, providerClassName, widgetSize)
            }
        }

        // 不支持 API，显示引导
        return PinResult.SHOW_GUIDE
    }

    /**
     * 尝试使用原生 requestPinAppWidget API
     */
    private fun tryNativePinWidget(
        activity: Activity,
        providerClassName: String,
        widgetSize: String
    ): PinResult {
        return try {
            val componentName = ComponentName(activity.packageName, providerClassName)
            val appWidgetManager = AppWidgetManager.getInstance(activity)

            val successCallback = PendingIntent.getBroadcast(
                activity,
                widgetSize.hashCode(),
                Intent("com.shanqiu.pickupmanager.action.WIDGET_PINNED").apply {
                    putExtra("widget_size", widgetSize)
                    setPackage(activity.packageName)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            appWidgetManager.requestPinAppWidget(componentName, null, successCallback)

            val displayName = SIZE_DISPLAY_NAMES[widgetSize] ?: widgetSize
            Log.d(TAG, "requestPinAppWidget 已发送: $displayName")
            PinResult.PIN_REQUESTED
        } catch (e: SecurityException) {
            Log.e(TAG, "requestPinAppWidget 权限不足", e)
            PinResult.SHOW_GUIDE
        } catch (e: Exception) {
            Log.e(TAG, "requestPinAppWidget 失败", e)
            PinResult.SHOW_GUIDE
        }
    }

    /**
     * 获取当前品牌的添加步骤说明
     */
    fun getAddSteps(brand: PhoneBrandDetector.PhoneBrand = PhoneBrandDetector.detectBrand()): List<String> {
        return BRAND_ADD_STEPS[brand] ?: listOf(
            "长按桌面空白处",
            "点击「小部件」",
            "找到「码上取」并选择尺寸",
            "拖拽到桌面即可"
        )
    }

    /**
     * 获取当前品牌小部件菜单的名称
     */
    fun getWidgetMenuName(brand: PhoneBrandDetector.PhoneBrand = PhoneBrandDetector.detectBrand()): String {
        return when (brand) {
            PhoneBrandDetector.PhoneBrand.HUAWEI,
            PhoneBrandDetector.PhoneBrand.HONOR,
            PhoneBrandDetector.PhoneBrand.VIVO,
            PhoneBrandDetector.PhoneBrand.IQOO -> "桌面小部件"
            else -> "小部件"
        }
    }

    fun getAvailableSizes(): List<String> = WIDGET_PROVIDERS.keys.toList()
    fun getSizeDisplayName(size: String): String = SIZE_DISPLAY_NAMES[size] ?: size
    fun getDefaultSize(): String = "3x3"

    fun isPinWidgetSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported
        }
        return false
    }
}
