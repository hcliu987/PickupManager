/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.shanqiu.pickupmanager.util.PickupSummaryNotifier
import com.shanqiu.pickupmanager.util.PreferenceManager

/**
 * 设置弹窗 - 简化版（已移除短信分析工具入口）
 *
 * 问题 21：新增「清空全部数据」入口，带二次确认。
 * 清空本身是破坏性且不可撤销的操作，所以确认文案把影响范围写全，
 * 并明确"不会自动重新导入短信"，避免用户以为清空功能坏了。
 *
 * 2026-11-09：新增通知栏汇总的两个开关。它们**即时生效**（不像显示天数要等保存），
 * 因为用户改完通常立刻下拉通知栏看效果；文案里明确写出这一点，避免与下方
 * 「取消 / 保存」按钮的语义打架。取件码开关默认关闭，防止锁屏泄露。
 */
@Composable
fun SettingsDialog(
    currentDays: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
    onClearAll: () -> Unit = {}
) {
    val context = LocalContext.current
    var sliderValue by remember { mutableStateOf(currentDays.toFloat()) }
    var summaryEnabled by remember {
        mutableStateOf(PreferenceManager.isSummaryNotificationEnabled(context))
    }
    var showCode by remember {
        mutableStateOf(PreferenceManager.isNotificationShowCode(context))
    }
    var showClearConfirm by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                Text(
                    text = "显示设置",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(20.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(16.dp)
                ) {
                    Text(
                        text = "默认显示天数",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "首次导入短信时，仅显示最近 ${sliderValue.toInt()} 天内的取件码",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "3天",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            valueRange = 3f..30f,
                            steps = 27,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )

                        Text(
                            text = "30天",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${sliderValue.toInt()} 天",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 通知栏汇总：两个开关即时生效，所以刷卡的同时就刷新通知。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(16.dp)
                ) {
                    Text(
                        text = "通知栏汇总（开关即时生效）",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "下拉通知栏常驻一条待取件数量，点开进入应用；没有待取件时自动消失",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "显示通知",
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Switch(
                            checked = summaryEnabled,
                            onCheckedChange = { enabled ->
                                summaryEnabled = enabled
                                PreferenceManager.setSummaryNotificationEnabled(context, enabled)
                                PickupSummaryNotifier.refresh(context)
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "显示取件码（锁屏他人可见）",
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                            color = if (summaryEnabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Switch(
                            checked = showCode && summaryEnabled,
                            enabled = summaryEnabled,
                            onCheckedChange = { show ->
                                showCode = show
                                PreferenceManager.setNotificationShowCode(context, show)
                                PickupSummaryNotifier.refresh(context)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("取消")
                    }

                    Button(
                        onClick = { onSave(sliderValue.toInt()) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("保存")
                    }
                }

                // 数据管理区：与上面的显示设置明确分区，避免误触
                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(onClick = { showClearConfirm = true }) {
                        Text(
                            text = "清空全部数据",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }

    // 二次确认：独立于设置弹窗的组合，确认后先关设置页再执行清空
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = {
                Text(
                    text = "清空全部数据？",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "将删除本机保存的全部取件记录、已删留档、保存的地址，以及所有设置" +
                        "（显示天数、取件路线、地址纠正、权限记录、小部件快照）。" +
                        "此操作不可撤销。\n\n" +
                        "清空后不会自动重新导入短信；如需重新导入，请在菜单中手动选择「导入短信」。",
                    fontSize = 14.sp,
                    textAlign = TextAlign.Start
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        onDismiss()
                        onClearAll()
                    }
                ) {
                    Text("确认清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}
