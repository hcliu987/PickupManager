/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 权限设置引导步骤
 */
private enum class SetupStep(
    val title: String,
    val icon: ImageVector,
    val isRequired: Boolean  // true=必须授权才能继续, false=可选步骤可跳过
) {
    SMS("短信权限", Icons.Rounded.Sms, isRequired = true),
    NOTIFICATION("通知权限", Icons.Rounded.NotificationsActive, isRequired = false),
    BATTERY("关闭电池优化", Icons.Rounded.BatterySaver, isRequired = false),
    AUTO_START("开启自启动", Icons.Rounded.Security, isRequired = false),
    MIUI("系统权限", Icons.Rounded.Security, isRequired = false),
    XIAOMI_SMS_NOTIFY("通知类短信", Icons.Rounded.Sms, isRequired = false)
}

/**
 * 统一权限引导弹窗 v2
 *
 * 核心改进：
 * - 可选步骤点击"去设置"后不自动推进，依赖 onResume 重组检测
 * - 必须步骤授权后立即识别，"下一步"按钮立即可用
 * - 小米机型提供短信权限手动解锁的详细引导
 * - 清晰的权限用途说明，让用户理解为何需要每个权限
 *
 * @param recomposeTrigger 外部递增计数器，每次变化时强制重组检测权限状态
 */
@Composable
fun PermissionSetupDialog(
    isSmsGranted: () -> Boolean,
    isNotificationGranted: () -> Boolean,
    isBatteryOptimized: () -> Boolean,
    isXiaomi: Boolean,
    recomposeTrigger: Int,
    onRequestSms: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenXiaomiSmsNotifySettings: () -> Unit,
    onComplete: () -> Unit,
    onDismiss: () -> Unit,
    isAutoStartGranted: () -> Boolean = { true },
    onOpenAutoStartSettings: () -> Unit = {},
    /**
     * R4 / H-10①：短信权限是否已被**永久拒绝**（用户勾了"不再询问"，或系统策略直接拒绝）。
     *
     * 为 true 时 `onRequestSms` 再调用 `launcher.launch(...)` 也**不会出现任何界面**
     * （系统立刻回调 denied），表现就是"点『去授权』没反应、页面永久停在
     * 『授权后方可继续』"。此时唯一出路是 [onOpenAppSettings]。
     */
    isSmsPermanentlyDenied: () -> Boolean = { false },
    /** 打开本应用的系统设置页（权限被永久拒绝后的唯一出路）。 */
    onOpenAppSettings: () -> Unit = {}
) {
    // 读取 recomposeTrigger 强制重组（从设置返回后 incremented）
    @Suppress("UNUSED_EXPRESSION")
    recomposeTrigger

    val allSteps = remember(isXiaomi) {
        val list = mutableListOf(
            SetupStep.SMS,
            SetupStep.NOTIFICATION,
            SetupStep.BATTERY,
            SetupStep.AUTO_START
        )
        if (isXiaomi) {
            list.add(SetupStep.MIUI)
            list.add(SetupStep.XIAOMI_SMS_NOTIFY)
        }
        list
    }

    var currentStepIndex by remember { mutableIntStateOf(0) }
    val currentStep = allSteps[currentStepIndex]
    val isLastStep = currentStepIndex == allSteps.lastIndex

    // 实时检测当前步骤状态
    val isCurrentStepGranted = when (currentStep) {
        SetupStep.SMS -> isSmsGranted()
        SetupStep.NOTIFICATION -> isNotificationGranted()
        SetupStep.BATTERY -> isBatteryOptimized()
        SetupStep.AUTO_START -> isAutoStartGranted()
        SetupStep.MIUI -> true
        SetupStep.XIAOMI_SMS_NOTIFY -> false
    }

    // 计算已完成的步骤数
    val completedCount = allSteps.count { step ->
        when (step) {
            SetupStep.SMS -> isSmsGranted()
            SetupStep.NOTIFICATION -> isNotificationGranted()
            SetupStep.BATTERY -> isBatteryOptimized()
            SetupStep.AUTO_START -> isAutoStartGranted()
            SetupStep.MIUI -> currentStepIndex > allSteps.indexOf(SetupStep.MIUI)
            SetupStep.XIAOMI_SMS_NOTIFY -> currentStepIndex > allSteps.indexOf(SetupStep.XIAOMI_SMS_NOTIFY)
        }
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.94f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 顶部标题区
                Text(
                    text = "权限设置",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "完成 $completedCount / ${allSteps.size} 项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 步骤指示器
                StepIndicator(
                    steps = allSteps,
                    currentIndex = currentStepIndex,
                    isCompleted = { index ->
                        when (allSteps[index]) {
                            SetupStep.SMS -> isSmsGranted()
                            SetupStep.NOTIFICATION -> isNotificationGranted()
                            SetupStep.BATTERY -> isBatteryOptimized()
                            SetupStep.AUTO_START -> isAutoStartGranted()
                            SetupStep.MIUI -> currentStepIndex > allSteps.indexOf(SetupStep.MIUI)
                            SetupStep.XIAOMI_SMS_NOTIFY -> currentStepIndex > allSteps.indexOf(SetupStep.XIAOMI_SMS_NOTIFY)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(20.dp))

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                Spacer(modifier = Modifier.height(20.dp))

                // 步骤内容（带动画切换）
                AnimatedVisibility(
                    visible = true,
                    enter = slideInHorizontally(
                        animationSpec = spring(Spring.StiffnessMediumLow),
                        initialOffsetX = { it }
                    ) + fadeIn(),
                    exit = slideOutHorizontally(
                        animationSpec = spring(Spring.StiffnessMediumLow),
                        targetOffsetX = { -it }
                    )
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StepIcon(isGranted = isCurrentStepGranted, step = currentStep)

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = currentStep.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // 权限描述
                        Text(
                            text = getPermissionDescription(currentStep),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )

                        // 对于必须步骤且未授权，显示"为什么需要"提示
                        if (currentStep.isRequired && !isCurrentStepGranted) {
                            Spacer(modifier = Modifier.height(12.dp))
                            WhyNeededTip(step = currentStep, isXiaomi = isXiaomi)
                        }

                        // 小米短信权限特殊引导：手动解锁说明
                        if (currentStep == SetupStep.XIAOMI_SMS_NOTIFY && isXiaomi) {
                            Spacer(modifier = Modifier.height(12.dp))
                            XiaomiSmsGuide()
                        }

                        // 已授权标记
                        if (isCurrentStepGranted) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "已授权",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 按钮区
                StepButtons(
                    step = currentStep,
                    isGranted = isCurrentStepGranted,
                    isLastStep = isLastStep,
                    // R4 / H-10①：只有"必须步骤 + 已被永久拒绝"才为 true。
                    // 该情形下主按钮不再是"去授权"（弹不出任何界面），而是"去系统设置"。
                    mustOpenAppSettings = currentStep == SetupStep.SMS && isSmsPermanentlyDenied(),
                    onPrimaryAction = {
                        when (currentStep) {
                            SetupStep.SMS -> {
                                if (isSmsPermanentlyDenied()) onOpenAppSettings() else onRequestSms()
                            }
                            SetupStep.NOTIFICATION -> onOpenNotificationSettings()
                            SetupStep.BATTERY -> onOpenBatterySettings()
                            SetupStep.AUTO_START -> onOpenAutoStartSettings()
                            SetupStep.MIUI -> {
                                // 纯信息步骤，直接进入下一步
                                if (isLastStep) onComplete()
                                else currentStepIndex++
                            }
                            SetupStep.XIAOMI_SMS_NOTIFY -> onOpenXiaomiSmsNotifySettings()
                        }
                    },
                    onNext = {
                        if (isLastStep) onComplete()
                        else currentStepIndex++
                    },
                    onSkip = {
                        if (isLastStep) onComplete()
                        else currentStepIndex++
                    },
                    onDismiss = onDismiss
                )
            }
        }
    }
}

// ==================== 子组件 ====================

@Composable
private fun StepIcon(isGranted: Boolean, step: SetupStep) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(
                if (isGranted) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.primaryContainer
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = step.icon,
            contentDescription = null,
            tint = if (isGranted) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(36.dp)
        )
    }
}

@Composable
private fun WhyNeededTip(step: SetupStep, isXiaomi: Boolean) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Rounded.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "为什么需要此权限？",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = getWhyNeededText(step, isXiaomi),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
private fun XiaomiSmsGuide() {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "小米手机需手动操作",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "如果系统提示「为了系统安全，暂未授权」：\n" +
                            "1. 点击下方「去设置」按钮\n" +
                            "2. 找到「权限管理」→「其他权限」\n" +
                            "3. 将「通知类短信」设为「始终允许」\n" +
                            "4. 返回后即可正常读取取件短信",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f),
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight
                )
            }
        }
    }
}

/**
 * 步骤指示器 - 紧凑横向排列
 */
@Composable
private fun StepIndicator(
    steps: List<SetupStep>,
    currentIndex: Int,
    isCompleted: (Int) -> Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, step ->
            if (index > 0) {
                val lineColor = when {
                    isCompleted(index - 1) || index <= currentIndex ->
                        MaterialTheme.colorScheme.primary
                    else ->
                        MaterialTheme.colorScheme.outlineVariant
                }
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(2.dp)
                        .background(lineColor, RoundedCornerShape(1.dp))
                )
            }

            val isCurrent = index == currentIndex
            val completed = isCompleted(index)

            Box(
                modifier = Modifier
                    .size(if (isCurrent) 32.dp else 26.dp)
                    .then(
                        if (completed) {
                            Modifier.clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        } else if (isCurrent) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        } else {
                            Modifier.clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (completed) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                } else if (isCurrent) {
                    Icon(
                        step.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                } else {
                    Icon(
                        step.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

/**
 * 按钮区域
 *
 * 规则：
 * - 必须步骤（仅 SMS）：未授权时「去授权」+ 禁用「授权后方可继续」，已授权时「下一步」
 * - 可跳过步骤（通知 / 电池 / 自启动 / 小米专属）：Android 13+ 起通知只用于小部件点击的
 *   前台服务反馈，本应用不发内容型提醒，因此绝不能把它设成必须项 —— 曾经它是。
 * - 可选步骤：未授权时「去设置」+「跳过」，已授权时「下一步」
 * - 可选步骤点击「去设置」后不自动推进，等 onResume 重组检测
 */
@Composable
private fun StepButtons(
    step: SetupStep,
    isGranted: Boolean,
    isLastStep: Boolean,
    onPrimaryAction: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
    /** R4 / H-10①：主按钮是否应改为"去系统设置"（权限已被永久拒绝）。 */
    mustOpenAppSettings: Boolean = false
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when {
            // ========== 必须步骤 & 未授权 ==========
            step.isRequired && !isGranted -> {
                Button(
                    onClick = onPrimaryAction,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (mustOpenAppSettings) "去系统设置开启" else "去授权",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp))
                }

                if (mustOpenAppSettings) {
                    // R4 / H-10①：这里原来是一颗**禁用**的"授权后方可继续"按钮，
                    // 而上面那颗"去授权"在权限被永久拒绝时不会弹出任何界面 ——
                    // 两颗按钮一起把用户锁死在"点不动"的页面上。
                    // 现在把真实原因和出路写出来。
                    Text(
                        text = "短信权限已被系统拒绝，且不会再弹出授权窗口" +
                            "（通常是你之前选择过“不再询问”）。\n" +
                            "请点上方的「去系统设置开启」，在应用权限里手动打开「短信」权限后返回本页。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    // 禁用按钮，提示用户必须授权
                    Button(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = false,
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Text("授权后方可继续", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            // ========== 可选步骤 & 未授权 ==========
            !step.isRequired && !isGranted -> {
                Button(
                    onClick = onPrimaryAction,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(getPrimaryButtonLabel(step), style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp))
                }

                OutlinedButton(
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isLastStep) "跳过并完成" else "跳过",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            // ========== 已授权 ==========
            else -> {
                Button(
                    onClick = onNext,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(
                        text = if (isLastStep) "完成设置" else "下一步",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp))
                }
            }
        }

        // 底部关闭链接
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "稍后设置",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * 获取权限用途描述（用户友好、说明必要性）
 */
private fun getPermissionDescription(step: SetupStep): String {
    return when (step) {
        SetupStep.SMS ->
            "需要读取快递柜发给你的取件码短信，\n自动识别并整理你的包裹取件信息"
        SetupStep.NOTIFICATION ->
            "仅用于点击桌面小部件按钮时系统要求的那条反馈通知，\n本应用不会发取件提醒或过期提醒（可跳过）"
        SetupStep.BATTERY ->
            "关闭电池优化后，桌面小部件可以\n实时刷新取件状态，不会延迟更新"
        SetupStep.AUTO_START ->
            "开启自启动后，即使应用被系统清理，\n也能自动重启并让桌面小部件正常工作"
        SetupStep.MIUI ->
            "小米手机需要额外开启通知读取权限，\n确保后台能正常接收取件短信"
        SetupStep.XIAOMI_SMS_NOTIFY ->
            "小米/红米手机需要此权限才能读取\n106开头的快递短信，否则无法自动识别"
    }
}

/**
 * "为什么需要"说明文字
 */
private fun getWhyNeededText(step: SetupStep, isXiaomi: Boolean): String {
    return when (step) {
        SetupStep.SMS ->
            "快递柜发给你的取件码短信（如菜鸟驿站、丰巢等）都在手机短信中。" +
                    "开启此权限后，应用会自动扫描短信提取取件码，" +
                    "你不再需要手动翻找短信。" +
                    if (isXiaomi) "\n\n提示：小米手机可能需要额外手动开启「通知类短信」权限才能读取部分短信。" else ""
        SetupStep.NOTIFICATION ->
            "本应用不发送任何内容型提醒：没有「新包裹到达」通知，也没有「即将过期」通知，\n" +
                    "取件信息一律通过桌面小部件呈现（这是刻意的设计）。\n" +
                    "这里要的权限只服务于一种场景：你点击小部件上的按钮时，\n" +
                    "系统要求执行该操作的前台服务必须挂一条通知（Android 13+ 强制）。\n" +
                    "拒绝它，应用与小部件都能正常用，只是点击时少一条进度提示。"
        else -> ""
    }
}

private fun getPrimaryButtonLabel(step: SetupStep): String {
    return when (step) {
        SetupStep.SMS -> "去授权"
        SetupStep.NOTIFICATION -> "去设置"
        SetupStep.BATTERY -> "去关闭优化"
        SetupStep.AUTO_START -> "去开启自启动"
        SetupStep.MIUI -> "我知道了"
        SetupStep.XIAOMI_SMS_NOTIFY -> "去手动设置"
    }
}
