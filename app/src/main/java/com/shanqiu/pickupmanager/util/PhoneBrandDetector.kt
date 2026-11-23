/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import android.os.Build
import android.util.Log

object PhoneBrandDetector {
    
    private const val TAG = "PhoneBrandDetector"
    
    enum class PhoneBrand {
        XIAOMI,
        HUAWEI,
        HONOR,
        OPPO,
        REALME,
        VIVO,
        IQOO,
        ONEPLUS,
        SAMSUNG,
        MEIZU,
        GOOGLE,
        OTHER
    }
    
    fun detectBrand(): PhoneBrand {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        
        Log.d(TAG, "Manufacturer: $manufacturer, Brand: $brand")
        
        return when {
            manufacturer.contains("xiaomi") || brand.contains("xiaomi") || brand.contains("redmi") -> {
                PhoneBrand.XIAOMI
            }
            manufacturer.contains("huawei") -> {
                PhoneBrand.HUAWEI
            }
            manufacturer.contains("honor") -> {
                PhoneBrand.HONOR
            }
            manufacturer.contains("oppo") -> {
                PhoneBrand.OPPO
            }
            manufacturer.contains("realme") -> {
                PhoneBrand.REALME
            }
            manufacturer.contains("vivo") -> {
                PhoneBrand.VIVO
            }
            manufacturer.contains("iqoo") -> {
                PhoneBrand.IQOO
            }
            manufacturer.contains("oneplus") -> {
                PhoneBrand.ONEPLUS
            }
            manufacturer.contains("samsung") -> {
                PhoneBrand.SAMSUNG
            }
            manufacturer.contains("meizu") -> {
                PhoneBrand.MEIZU
            }
            manufacturer.contains("google") -> {
                PhoneBrand.GOOGLE
            }
            else -> {
                PhoneBrand.OTHER
            }
        }
    }
    
    fun isXiaomi(): Boolean {
        return detectBrand() == PhoneBrand.XIAOMI
    }
    
    fun isHuawei(): Boolean {
        return detectBrand() == PhoneBrand.HUAWEI
    }
    
    fun isHonor(): Boolean {
        return detectBrand() == PhoneBrand.HONOR
    }
    
    fun isOppo(): Boolean {
        val brand = detectBrand()
        return brand == PhoneBrand.OPPO || brand == PhoneBrand.REALME || brand == PhoneBrand.ONEPLUS
    }
    
    fun isVivo(): Boolean {
        val brand = detectBrand()
        return brand == PhoneBrand.VIVO || brand == PhoneBrand.IQOO
    }
    
    fun isSamsung(): Boolean {
        return detectBrand() == PhoneBrand.SAMSUNG
    }
    
    fun needsVerificationCodeProtectionGuide(): Boolean {
        val brand = detectBrand()
        return brand == PhoneBrand.HUAWEI || 
               brand == PhoneBrand.HONOR || 
               brand == PhoneBrand.OPPO || 
               brand == PhoneBrand.REALME || 
               brand == PhoneBrand.ONEPLUS ||
               brand == PhoneBrand.VIVO ||
               brand == PhoneBrand.IQOO
    }
    
    fun needsNotificationSmsPermission(): Boolean {
        return detectBrand() == PhoneBrand.XIAOMI
    }
    
    /**
     * 是否需要智能休眠/后台冻结适配
     * 魅族 Flyme 的"智能休眠"功能会在应用后台后迅速冻结进程
     */
    fun needsSmartSleepWorkaround(): Boolean {
        return detectBrand() == PhoneBrand.MEIZU
    }
    
    fun getBrandDisplayName(): String {
        return when (detectBrand()) {
            PhoneBrand.XIAOMI -> "小米"
            PhoneBrand.HUAWEI -> "华为"
            PhoneBrand.HONOR -> "荣耀"
            PhoneBrand.OPPO -> "OPPO"
            PhoneBrand.REALME -> "realme"
            PhoneBrand.VIVO -> "vivo"
            PhoneBrand.IQOO -> "iQOO"
            PhoneBrand.ONEPLUS -> "一加"
            PhoneBrand.SAMSUNG -> "三星"
            PhoneBrand.MEIZU -> "魅族"
            PhoneBrand.GOOGLE -> "Google Pixel"
            PhoneBrand.OTHER -> "其他"
        }
    }
}
