/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import com.shanqiu.pickupmanager.ui.animation.AnimationConfig
import com.shanqiu.pickupmanager.ui.animation.LocalAnimationConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.scale
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
import com.shanqiu.pickupmanager.ui.theme.ActionDeleteColor
import com.shanqiu.pickupmanager.ui.theme.DarkActionDeleteColor
import com.shanqiu.pickupmanager.ui.theme.Green400
import com.shanqiu.pickupmanager.ui.theme.Green500
import com.shanqiu.pickupmanager.ui.theme.LightPrimary
import com.shanqiu.pickupmanager.ui.theme.LightSurface
import com.shanqiu.pickupmanager.ui.theme.Red400
import com.shanqiu.pickupmanager.ui.theme.Red500
import com.shanqiu.pickupmanager.ui.theme.Slate100
import com.shanqiu.pickupmanager.ui.theme.Slate200
import com.shanqiu.pickupmanager.ui.theme.Slate300
import com.shanqiu.pickupmanager.ui.theme.Slate500
import com.shanqiu.pickupmanager.ui.theme.Slate600
import com.shanqiu.pickupmanager.ui.theme.Slate700
import com.shanqiu.pickupmanager.ui.theme.Slate800
import com.shanqiu.pickupmanager.ui.theme.Slate900
import kotlinx.coroutines.delay

@Composable
fun SportyConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "确认",
    cancelText: String = "取消",
    icon: ImageVector = Icons.Rounded.Warning,
    iconColor: Color = Red500,
    iconBackgroundColor: Color = Red400,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDarkTheme: Boolean = false
) {
    var visible by remember { mutableStateOf(false) }
    
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
            enter = fadeIn(
                animationSpec = tween(durationMillis = 200)
            ) + scaleIn(
                initialScale = 0.9f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ),
            exit = fadeOut(
                animationSpec = tween(durationMillis = 200)
            ) + scaleOut(
                targetScale = 0.95f,
                animationSpec = tween(durationMillis = 200)
            )
        ) {
            val surfaceColor = Color.White
            
            val titleColor = Slate900
            val messageColor = Slate700
            val cancelButtonColor = Slate100
            val cancelButtonTextColor = Slate700
            
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .shadow(
                        elevation = 24.dp,
                        shape = RoundedCornerShape(28.dp),
                        ambientColor = Color(0xFFE0E0E0).copy(alpha = 0.3f),
                        spotColor = Color(0xFFE0E0E0).copy(alpha = 0.3f)
                    ),
                shape = RoundedCornerShape(28.dp),
                color = surfaceColor
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
                                shape = CircleShape,
                                ambientColor = iconBackgroundColor.copy(alpha = 0.3f),
                                spotColor = iconBackgroundColor.copy(alpha = 0.3f)
                            )
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        iconBackgroundColor.copy(alpha = 0.15f),
                                        iconBackgroundColor.copy(alpha = 0.08f)
                                    )
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = iconColor
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(20.dp))
                    
                    Text(
                        text = title,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = titleColor,
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = message,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                        color = messageColor,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                    
                    Spacer(modifier = Modifier.height(28.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = cancelButtonColor,
                            shadowElevation = 2.dp,
                            onClick = {
                                visible = false
                                onDismiss()
                            }
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = cancelText,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = cancelButtonTextColor
                                )
                            }
                        }
                        
                        Button(
                            onClick = {
                                visible = false
                                onConfirm()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = iconColor,
                                contentColor = Color.White
                            ),
                            elevation = ButtonDefaults.buttonElevation(
                                defaultElevation = 4.dp,
                                pressedElevation = 8.dp
                            )
                        ) {
                            Text(
                                text = confirmText,
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

@Composable
fun SportySuccessToast(
    message: String,
    icon: ImageVector = Icons.Rounded.Check,
    iconColor: Color = Green500,
    iconBackgroundColor: Color = Green400,
    durationMillis: Long = 2000,
    onDismiss: () -> Unit,
    isDarkTheme: Boolean = false
) {
    var visible by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        visible = true
        delay(2000)
        visible = false
        delay(300)
        onDismiss()
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 100.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(
                animationSpec = tween(durationMillis = 200)
            ) + scaleIn(
                initialScale = 0.8f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ),
            exit = fadeOut(
                animationSpec = tween(durationMillis = 200)
            ) + scaleOut(
                targetScale = 0.9f,
                animationSpec = tween(durationMillis = 200)
            )
        ) {
            val surfaceColor = Color.White
            val onSurfaceColor = Slate700
            
            Surface(
                modifier = Modifier
                    .shadow(
                        elevation = 16.dp,
                        shape = RoundedCornerShape(20.dp),
                        ambientColor = Color(0xFFE0E0E0).copy(alpha = 0.3f),
                        spotColor = Color(0xFFE0E0E0).copy(alpha = 0.3f)
                    ),
                shape = RoundedCornerShape(20.dp),
                color = surfaceColor
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = 24.dp,
                        vertical = 16.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .shadow(
                                elevation = 6.dp,
                                shape = CircleShape,
                                ambientColor = iconBackgroundColor.copy(alpha = 0.3f),
                                spotColor = iconBackgroundColor.copy(alpha = 0.3f)
                            )
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        iconBackgroundColor.copy(alpha = 0.2f),
                                        iconBackgroundColor.copy(alpha = 0.1f)
                                    )
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = iconColor
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Text(
                        text = message,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = onSurfaceColor
                    )
                }
            }
        }
    }
}

@Composable
fun DeleteConfirmDialog(
    itemName: String = "这个取件",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDarkTheme: Boolean = false
) {
    val deleteColor = if (isDarkTheme) DarkActionDeleteColor else ActionDeleteColor
    
    SportyConfirmDialog(
        title = "确认删除",
        message = "删除后将无法恢复，确定要删除${itemName}吗？",
        confirmText = "删除",
        cancelText = "取消",
        icon = Icons.Rounded.Delete,
        iconColor = deleteColor,
        iconBackgroundColor = deleteColor,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        isDarkTheme = isDarkTheme
    )
}

@Composable
fun CopySuccessToast(
    message: String = "已复制到剪贴板",
    onDismiss: () -> Unit,
    isDarkTheme: Boolean = false
) {
    SportySuccessToast(
        message = message,
        icon = Icons.Rounded.Check,
        iconColor = Green500,
        iconBackgroundColor = Green400,
        onDismiss = onDismiss,
        isDarkTheme = isDarkTheme
    )
}
