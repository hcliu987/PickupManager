/*
 * 码上取 PickupManager —— 安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 启动时更新检查。
 *
 * 隐私约定：本模块只向 Gitee 公开 API 发一次 GET，读取仓库根的 version.json，
 * 不上传任何取件数据、设备标识或个人信息。网络失败一律静默忽略，绝不影响 App 正常使用。
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"

    // Gitee contents API：公开仓库无需令牌，国内直连可用。
    // 返回 JSON 的 content 字段是文件内容的 Base64。
    private const val VERSION_URL =
        "https://gitee.com/api/v5/repos/884024720/pickup-manager/contents/version.json"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val changelog: String,
        val apkUrl: String
    )

    /**
     * 在后台线程检查是否有新版本。
     * @param currentCode 当前应用的 versionCode
     * @return 有更新则返回 UpdateInfo；无更新或任何失败均返回 null
     */
    suspend fun check(currentCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(VERSION_URL).openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "PickupManager-UpdateCheck")
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "更新检查跳过：HTTP $code")
                conn.disconnect()
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            val encoded = json.optString("content", "")
            if (encoded.isBlank()) {
                Log.d(TAG, "更新检查跳过：content 为空")
                return@withContext null
            }
            // Gitee 的 content 可能含换行，去掉后再解码
            val fileText = String(
                Base64.decode(encoded.replace("\n", ""), Base64.DEFAULT),
                Charsets.UTF_8
            )
            val v = JSONObject(fileText)
            val serverCode = v.optInt("versionCode", 0)
            if (serverCode <= currentCode) {
                Log.d(TAG, "已是最新：server=$serverCode current=$currentCode")
                return@withContext null
            }
            UpdateInfo(
                versionCode = serverCode,
                versionName = v.optString("versionName", ""),
                changelog = v.optString("changelog", ""),
                apkUrl = v.optString(
                    "apkUrl",
                    "https://gitee.com/884024720/pickup-manager/releases/download/latest/pickupmanager.apk"
                )
            )
        } catch (e: Exception) {
            // 无网络 / 超时 / 解析失败：静默忽略，不影响用户
            Log.d(TAG, "更新检查跳过：${e.message}")
            null
        }
    }

    /** 当前应用版本，供对比与显示。 */
    fun currentVersion(context: Context): Pair<Int, String> = try {
        val p = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        Pair(p.versionCode, p.versionName ?: "")
    } catch (e: Exception) {
        Pair(0, "")
    }
}
