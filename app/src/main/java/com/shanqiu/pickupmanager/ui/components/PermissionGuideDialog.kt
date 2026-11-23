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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shanqiu.pickupmanager.util.PhoneBrandDetector
import com.shanqiu.pickupmanager.util.SmsPermissionChecker

/**
 * 权限引导弹窗
 * 
 * 显示缺失的权限列表，提供授权按钮
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PermissionGuideDialog(
    result: SmsPermissionChecker.PermissionCheckResult,
    onDismiss: () -> Unit,
    onPermissionClick: (SmsPermissionChecker.PermissionType) -> Unit,
    onRetry: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        visible = true
    }
    
    val brandName = PhoneBrandDetector.getBrandDisplayName()
    val criticalCount = result.missingPermissions.count { it.isCritical }
    val nonCriticalCount = result.missingPermissions.count { !it.isCritical }
    
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
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
                    .fillMaxWidth(0.92f)
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
                    // 图标
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .shadow(
                                elevation = 8.dp,
                                shape = CircleShape,
                                ambientColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                                spotColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
                            )
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
                                    )
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 标题
                    Text(
                        text = "权限未完全授权",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // 副标题
                    Text(
                        text = "软件运行于「$brandName」手机，以下权限未授权可能影响到软件正常运行",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                    
                    Spacer(modifier = Modifier.height(20.dp))
                    
                    // 缺失权限列表 - 横排按钮
                    Text(
                        text = "需要授权的权限 (${result.missingPermissions.size}项)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // 使用 FlowRow 实现横排布局
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        result.missingPermissions.forEach { permission ->
                            PermissionChip(
                                permission = permission,
                                onClick = { onPermissionClick(permission.permissionType) }
                            )
                        }
                    }
                    
                    // 权限说明
                    if (result.missingPermissions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        val firstPermission = result.missingPermissions.first()
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = firstPermission.displayName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = firstPermission.description,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                    
                    // 关键权限提示
                    if (criticalCount > 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "⚠️ $criticalCount 项关键权限未授权，软件将无法正常提取快递取件码",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // 底部按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shadowElevation = 2.dp,
                            onClick = onDismiss
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "稍后再说",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        
                        Button(
                            onClick = onRetry,
                            modifier = Modifier
                                .weight(1f)
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
                                text = "重新检测",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 权限标签组件
 */
@Composable
private fun PermissionChip(
    permission: SmsPermissionChecker.MissingPermission,
    onClick: () -> Unit
) {
    val icon = when (permission.permissionType) {
        SmsPermissionChecker.PermissionType.READ_SMS -> Icons.AutoMirrored.Rounded.Message
        SmsPermissionChecker.PermissionType.RECEIVE_SMS -> Icons.Rounded.Notifications
        SmsPermissionChecker.PermissionType.NOTIFICATION_SMS -> Icons.Rounded.Notifications
        SmsPermissionChecker.PermissionType.BLANK_PASS -> Icons.Rounded.Settings
        SmsPermissionChecker.PermissionType.PERMISSION_REVOKE -> Icons.Rounded.Info
        SmsPermissionChecker.PermissionType.VERIFICATION_CODE_PROTECT -> Icons.Rounded.Settings
        SmsPermissionChecker.PermissionType.VIVO_VERIFICATION_PROTECT -> Icons.Rounded.Settings
        SmsPermissionChecker.PermissionType.SAMSUNG_AUTO_DELETE_SMS -> Icons.Rounded.Settings
        SmsPermissionChecker.PermissionType.POST_NOTIFICATIONS -> Icons.Rounded.Notifications
        SmsPermissionChecker.PermissionType.BATTERY_OPTIMIZATION -> Icons.Rounded.Settings
        SmsPermissionChecker.PermissionType.AUTO_START -> Icons.Rounded.Settings
    }
    
    val containerColor = if (permission.isCritical) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
    }
    
    val contentColor = if (permission.isCritical) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    
    Surface(
        modifier = Modifier
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = contentColor
            )
            Text(
                text = permission.displayName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = contentColor
            )
            Icon(
                imageVector = if (permission.isCritical) Icons.Rounded.Close else Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (permission.isCritical) MaterialTheme.colorScheme.error else Color(0xFF4CAF50)
            )
        }
    }
}
