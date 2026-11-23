/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import android.content.Context
import android.content.SharedPreferences
import com.shanqiu.pickupmanager.data.model.ModeType
import com.shanqiu.pickupmanager.data.model.PickupRoute
import com.shanqiu.pickupmanager.data.model.RouteMode
import com.shanqiu.pickupmanager.data.model.RouteSettings
import org.json.JSONArray
import org.json.JSONObject

object PreferenceManager {
    private const val PREF_NAME = "pickup_manager_prefs"
    private const val KEY_FIRST_LAUNCH = "first_launch"
    private const val KEY_PERMISSION_CHECKED = "permission_checked"
    private const val KEY_FEATURE_INTRO_SHOWN = "feature_intro_shown"
    private const val KEY_DEFAULT_DISPLAY_DAYS = "default_display_days"
    private const val KEY_SMS_IMPORTED = "sms_imported"
    private const val KEY_ROUTE_SETTINGS = "route_settings"
    private const val KEY_SUMMARY_NOTIFICATION = "summary_notification_enabled"
    private const val KEY_NOTIFICATION_SHOW_CODE = "notification_show_code"
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
    
    fun isFirstLaunch(context: Context): Boolean {
        val prefs = getPrefs(context)
        val isFirst = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
        if (isFirst) {
            prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
        }
        return isFirst
    }
    
    fun isPermissionChecked(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PERMISSION_CHECKED, false)
    }
    
    fun setPermissionChecked(context: Context, checked: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PERMISSION_CHECKED, checked).apply()
    }
    
    fun isFeatureIntroShown(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_FEATURE_INTRO_SHOWN, false)
    }
    
    fun setFeatureIntroShown(context: Context, shown: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_FEATURE_INTRO_SHOWN, shown).apply()
    }
    
    fun getDefaultDisplayDays(context: Context): Int {
        return getPrefs(context).getInt(KEY_DEFAULT_DISPLAY_DAYS, 7)
    }
    
    fun setDefaultDisplayDays(context: Context, days: Int) {
        getPrefs(context).edit().putInt(KEY_DEFAULT_DISPLAY_DAYS, days.coerceIn(3, 30)).apply()
    }
    
    fun isSmsImported(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SMS_IMPORTED, false)
    }
    
    fun setSmsImported(context: Context, imported: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SMS_IMPORTED, imported).apply()
    }

    /**
     * 通知栏待取件汇总的总开关。默认开启；一键清空会把整个偏好文件清掉，
     * 于是回到这个默认值，符合「清空后等同新装」的语义。
     */
    fun isSummaryNotificationEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_SUMMARY_NOTIFICATION, true)

    fun setSummaryNotificationEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SUMMARY_NOTIFICATION, enabled).apply()
    }

    /** 是否允许在通知正文里显示取件码。默认关闭：锁屏可见性风险由用户自己决定。 */
    fun isNotificationShowCode(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_NOTIFICATION_SHOW_CODE, false)

    fun setNotificationShowCode(context: Context, show: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_NOTIFICATION_SHOW_CODE, show).apply()
    }

    fun getRouteSettings(context: Context): RouteSettings {
        val json = getPrefs(context).getString(KEY_ROUTE_SETTINGS, null) ?: return RouteSettings()
        return try {
            parseRouteSettings(json)
        } catch (e: Exception) {
            RouteSettings()
        }
    }
    
    fun setRouteSettings(context: Context, settings: RouteSettings) {
        val json = serializeRouteSettings(settings)
        getPrefs(context).edit().putString(KEY_ROUTE_SETTINGS, json).apply()
    }
    
    private fun parseRouteSettings(json: String): RouteSettings {
        val obj = JSONObject(json)
        
        val workMode = parseRouteMode(obj.optJSONObject("workMode") ?: JSONObject())
        val vacationMode = parseRouteMode(obj.optJSONObject("vacationMode") ?: JSONObject())
        val currentMode = try {
            ModeType.valueOf(obj.optString("currentMode", "WORK_MODE"))
        } catch (e: Exception) {
            ModeType.WORK_MODE
        }
        val autoSwitch = obj.optBoolean("autoSwitch", true)
        
        return RouteSettings(
            workMode = workMode,
            vacationMode = vacationMode,
            currentMode = currentMode,
            autoSwitch = autoSwitch
        )
    }
    
    private fun parseRouteMode(obj: JSONObject): RouteMode {
        val modeType = try {
            ModeType.valueOf(obj.optString("modeType", "WORK_MODE"))
        } catch (e: Exception) {
            ModeType.WORK_MODE
        }
        
        val routesArray = obj.optJSONArray("routes") ?: JSONArray()
        val routes = (0 until routesArray.length()).map { i ->
            parsePickupRoute(routesArray.getJSONObject(i))
        }
        
        val applyDaysArray = obj.optJSONArray("applyDays") ?: JSONArray()
        val applyDays = (0 until applyDaysArray.length()).map { i ->
            applyDaysArray.getInt(i)
        }.toSet()
        
        return RouteMode(
            modeType = modeType,
            routes = routes,
            applyDays = applyDays
        )
    }
    
    private fun parsePickupRoute(obj: JSONObject): PickupRoute {
        return PickupRoute(
            id = obj.optString("id", ""),
            addressTag = obj.optString("addressTag", ""),
            displayName = obj.optString("displayName", ""),
            order = obj.optInt("order", 0)
        )
    }
    
    private fun serializeRouteSettings(settings: RouteSettings): String {
        val obj = JSONObject()
        obj.put("workMode", serializeRouteMode(settings.workMode))
        obj.put("vacationMode", serializeRouteMode(settings.vacationMode))
        obj.put("currentMode", settings.currentMode.name)
        obj.put("autoSwitch", settings.autoSwitch)
        return obj.toString()
    }
    
    private fun serializeRouteMode(mode: RouteMode): JSONObject {
        val obj = JSONObject()
        obj.put("modeType", mode.modeType.name)
        
        val routesArray = JSONArray()
        mode.routes.forEach { route ->
            routesArray.put(serializePickupRoute(route))
        }
        obj.put("routes", routesArray)
        
        val applyDaysArray = JSONArray()
        mode.applyDays.forEach { applyDaysArray.put(it) }
        obj.put("applyDays", applyDaysArray)
        
        return obj
    }
    
    private fun serializePickupRoute(route: PickupRoute): JSONObject {
        val obj = JSONObject()
        obj.put("id", route.id)
        obj.put("addressTag", route.addressTag)
        obj.put("displayName", route.displayName)
        obj.put("order", route.order)
        return obj
    }
}
