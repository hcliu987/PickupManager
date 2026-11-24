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
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shanqiu.pickupmanager.R

@Composable
fun AboutDialog(
    onDismiss: () -> Unit,
    onCheckUpdate: () -> Unit = {}
) {
    var visible by remember { mutableStateOf(false) }
    val appContext = LocalContext.current
    val versionName = remember {
        runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull() ?: "未知"
    }
    
    LaunchedEffect(Unit) {
        visible = true
    }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + scaleIn(
                initialScale = 0.9f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .shadow(
                        elevation = 24.dp,
                        shape = RoundedCornerShape(28.dp),
                        ambientColor = Color(0xFFE0E0E0).copy(alpha = 0.3f),
                        spotColor = Color(0xFFE0E0E0).copy(alpha = 0.3f)
                    ),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .shadow(
                                elevation = 8.dp,
                                shape = RoundedCornerShape(16.dp),
                                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            )
                            .clip(RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_app_icon),
                            contentDescription = "码上取",
                            modifier = Modifier.size(72.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "码上取",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    Text(
                        text = "版本 $versionName",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = onCheckUpdate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "下载最新版 APK（Gitee 直链）",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AboutSection(
                            title = "简介",
                            content = "一款专注于本地数据处理的工具，帮助您高效管理短信中的快递取件信息。"
                        )
                        
                        AboutSection(
                            title = "特点",
                            items = listOf(
                                "取件数据纯本地处理，网络权限仅用于检查更新",
                                "自动解析取件短信中的取件码与驿站",
                                "取件信息直接显示在桌面小部件上",
                                "按地址分类、可纠正识别错的地址"
                            )
                        )
                        
                        AboutSection(
                            title = "隐私保护",
                            // R5 / H-06 + H-09：这一节原先有三处问题，逐条改正：
                            // ① 原文写"数据无法离开设备" —— 这是**错的**。应用里有
                            //    ShareUtils 的 Intent.ACTION_SEND 分享通道（取件码+地址+快递公司
                            //    交给微信/QQ 等），数据确实可以离开本应用。改为准确表述。
                            // ② 从未告知用户应用声明了 NotificationListenerService
                            //    （可读取本机全部通知），而这是本应用最强的能力，必须明说。
                            // ③ "其他应用读不到"的表述过于笼统：对**数据库**成立，
                            //    但对用户主动点"分享"后交出去的内容不成立。
                            items = listOf(
                                "更新检查：启动时向 Gitee 公开 API 发一次 GET 读取版本号，只发固定请求、不上传任何取件数据；可点「不再提示此版本」关闭",
                                "⚠️ 但数据**可以**离开本应用：你点击「分享」时，取件码、地址、快递公司" +
                                    "会经由系统分享面板交给你自己选择的应用（微信/QQ 等）。" +
                                    "不点分享就不会发生，本应用也不会在后台调用它",
                                "通知使用权（可选，需你在系统设置里手动授予）：短信权限被系统回收时，" +
                                    "应用会从短信应用的通知里读取取件信息作为备用通道。" +
                                    "授予它等于允许本应用读取本机全部通知内容，请自行权衡",
                                "不收集任何统计分析或崩溃上报数据",
                                "仅保存被判定为取件短信的内容（含该条短信原文，供查看与复制）",
                                "所有数据存放在设备本地的应用私有数据库，其它应用无法直接读取",
                                "可在列表里逐条删除记录",
                                "需要全部清除时：系统设置里清除应用数据，或直接卸载"
                            )
                        )
                        
                        AboutSection(
                            title = "版本信息",
                            content = "当前版本：$versionName"
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(20.dp))
                    
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 4.dp,
                            pressedElevation = 8.dp
                        )
                    ) {
                        Text(
                            text = "确定",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutSection(
    title: String,
    content: String? = null,
    items: List<String>? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp)
            )
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        content?.let {
            Text(
                text = it,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }
        
        items?.let { itemList ->
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemList.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(16.dp)
                        )
                        Text(
                            text = item,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
        }
    }
}
