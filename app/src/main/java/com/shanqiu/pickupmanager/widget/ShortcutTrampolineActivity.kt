/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.widget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.shanqiu.pickupmanager.ui.ComposeMainActivity

/**
 * 快捷方式跳板 Activity
 *
 * 处理长按应用图标弹出的快捷菜单中"添加小部件"选项的点击事件。
 *
 * 工作流程：
 * 1. 用户长按应用图标 → 系统弹出快捷菜单
 * 2. 用户选择某尺寸 → 系统启动此 Activity
 * 3. 调用 WidgetPinHelper.requestPinWidget()
 * 4. 根据结果：
 *    - PIN_REQUESTED: 系统会弹出确认对话框（原生Android），finish()
 *    - SHOW_GUIDE: 打开主界面并显示操作引导对话框
 *    - INVALID_SIZE: 显示错误 Toast，finish()
 *
 * 兼容性：
 * - 国产品牌 ROM (MIUI/EMUI/ColorOS等): 始终走 SHOW_GUIDE 路径
 * - 原生 Android: 走 requestPinAppWidget 路径
 * - 三星 OneUI: 尝试 requestPinAppWidget
 */
class ShortcutTrampolineActivity : Activity() {

    companion object {
        private const val TAG = "ShortcutTrampoline"
        const val ACTION_ADD_WIDGET = "com.shanqiu.pickupmanager.action.ADD_WIDGET"
        const val EXTRA_WIDGET_SIZE = "widget_size"

        // 传递给 ComposeMainActivity 的参数
        const val EXTRA_SHOW_WIDGET_GUIDE = "show_widget_guide"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action
        val widgetSize = intent?.getStringExtra(EXTRA_WIDGET_SIZE)
            ?: WidgetPinHelper.getDefaultSize()

        Log.d(TAG, "快捷方式触发: action=$action, widgetSize=$widgetSize")

        when (action) {
            ACTION_ADD_WIDGET -> {
                handleAddWidget(widgetSize)
            }
            else -> {
                Log.w(TAG, "未知快捷方式动作: $action")
                openMainActivity()
            }
        }
    }

    /**
     * 处理"添加小部件"快捷方式
     */
    private fun handleAddWidget(widgetSize: String) {
        val displayName = WidgetPinHelper.getSizeDisplayName(widgetSize)
        Log.d(TAG, "处理添加小部件: size=$widgetSize, displayName=$displayName")

        when (WidgetPinHelper.requestPinWidget(this, widgetSize)) {
            WidgetPinHelper.PinResult.PIN_REQUESTED -> {
                // 原生 Android：系统会弹出确认对话框
                Toast.makeText(
                    this,
                    "请在桌面确认添加${displayName}小部件",
                    Toast.LENGTH_SHORT
                ).show()
                Log.d(TAG, "requestPinAppWidget 已发送，等待用户确认")
                finish()
            }

            WidgetPinHelper.PinResult.SHOW_GUIDE -> {
                // 国产品牌 ROM：打开主界面显示操作引导对话框
                Log.d(TAG, "打开主界面显示操作引导对话框")
                openMainActivityWithGuide(widgetSize)
                // R4 / L-14：这里原先写着"不调用 finish()，让主界面自然显示"，
                // 但 `openMainActivityWithGuide()` 内部**就会** startActivity + finish()。
                // 本 Activity 是纯跳板（Theme.Translucent），启动主界面后自己结束才是
                // 正确行为 —— 留着它只会在返回栈里多出一层空白页。注释已改为与实现一致。
            }

            WidgetPinHelper.PinResult.INVALID_SIZE -> {
                Toast.makeText(this, "不支持的尺寸: $widgetSize", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /**
     * 打开主界面并传递小部件引导参数
     */
    private fun openMainActivityWithGuide(widgetSize: String) {
        try {
            val intent = Intent(this, ComposeMainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_SHOW_WIDGET_GUIDE, true)
                putExtra(EXTRA_WIDGET_SIZE, widgetSize)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "打开主界面失败", e)
            // 兜底：显示 Toast 引导
            showFallbackGuide(widgetSize)
        }
        // 跳板 Activity 的职责到 startActivity 为止 —— 无论成功与否都结束自己，
        // 否则用户按返回会回到这个空白页（见 SHOW_GUIDE 分支的 R4 / L-14 说明）。
        finish()
    }

    /**
     * 打开主界面（兜底方案）
     */
    private fun openMainActivity() {
        try {
            val intent = Intent(this, ComposeMainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "打开主界面失败", e)
        }
        finish()
    }

    /**
     * 兜底引导：当主界面启动失败时，用 Toast 显示操作步骤
     */
    private fun showFallbackGuide(widgetSize: String) {
        val displayName = WidgetPinHelper.getSizeDisplayName(widgetSize)
        val steps = WidgetPinHelper.getAddSteps()
        val stepText = steps.mapIndexed { i, step -> "${i + 1}. $step" }.joinToString("\n")
        val message = "添加${displayName}小部件：\n$stepText"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
