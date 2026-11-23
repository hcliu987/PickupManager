/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import com.shanqiu.pickupmanager.ui.animation.AnimationConfig
import com.shanqiu.pickupmanager.ui.animation.LocalAnimationConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.shanqiu.pickupmanager.data.model.PickupItem
import com.shanqiu.pickupmanager.data.model.SavedAddress
import com.shanqiu.pickupmanager.ui.theme.ActionCopyColor
import com.shanqiu.pickupmanager.ui.theme.DarkActionCopyColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickupDetailDialog(
    item: PickupItem,
    onDismiss: () -> Unit,
    onCopyClick: () -> Unit,
    onAddressSave: ((String) -> Unit)? = null,
    savedAddresses: List<SavedAddress> = emptyList()
) {
    val isDarkTheme = !MaterialTheme.colorScheme.background.toString().contains("F5")
    
    // 优先使用SavedAddress中的地址，否则使用item.address
    val resolvedAddress = remember(item, savedAddresses) {
        savedAddresses.find { it.addressTag == item.addressTag }?.getDisplayText()
            ?: item.address
    }
    
    var editedAddress by remember { mutableStateOf(resolvedAddress) }
    var isEditingAddress by remember { mutableStateOf(false) }
    var showSaveButton by remember { mutableStateOf(false) }

    // R4 / M-10（附带）：`editedAddress` 只在首次组合时被初始化过一次，而
    // `resolvedAddress` 会随 item / savedAddresses 变化。弹窗被复用于另一张卡片时，
    // 只读分支会显示上一张卡片的地址。这里在解析出的地址变化时重新对齐编辑缓冲，
    // 并顺带退出编辑态 —— 避免把上一张卡片的编辑内容带到新卡片上。
    LaunchedEffect(resolvedAddress) {
        editedAddress = resolvedAddress
        showSaveButton = false
        isEditingAddress = false
    }
    
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "取件详情",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = "【原始短信】",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 0.5.sp
                        )
                        
                        Spacer(modifier = Modifier.height(10.dp))
                        
                        Text(
                            text = item.rawSms,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 22.sp,
                            letterSpacing = 0.2.sp
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                ModernDetailRow(
                    icon = null,
                    label = "取件码",
                    value = item.pickupCode,
                    isMonospace = true,
                    isHighlight = true
                )
                
                Spacer(modifier = Modifier.height(14.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "地址",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.width(72.dp)
                    )

                    if (isEditingAddress && onAddressSave != null) {
                        // 地址编辑框 - 占满整行宽度
                        androidx.compose.foundation.text.BasicTextField(
                            value = editedAddress,
                            onValueChange = {
                                editedAddress = it
                                showSaveButton = it != resolvedAddress
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 80.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            singleLine = false,
                            maxLines = 6
                        )
                    } else {
                        Text(
                            text = editedAddress,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        
                        if (onAddressSave != null) {
                            Spacer(modifier = Modifier.width(4.dp))
                            
                            androidx.compose.material3.TextButton(
                                onClick = { isEditingAddress = true },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 8.dp,
                                    vertical = 4.dp
                                ),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text(
                                    text = "编辑",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(14.dp))
                
                ModernDetailRow(
                    icon = null,
                    label = "快递公司",
                    value = item.courierCompany.ifBlank { "未知" }
                )
                
                Spacer(modifier = Modifier.height(14.dp))
                
                val receiveDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(Date(item.receiveTime))
                ModernDetailRow(
                    icon = null,
                    label = "入库时间",
                    value = receiveDate
                )
                
                Spacer(modifier = Modifier.height(14.dp))
                
                val daysStored = item.getStoredDays()
                ModernDetailRow(
                    icon = null,
                    label = "已入库",
                    value = "$daysStored 天"
                )
                
                Spacer(modifier = Modifier.height(28.dp))
                
                // 底部按钮区域：编辑时显示 [取消] + [保存]，否则显示"复制取件码"
                //
                // R4 / M-10：原判定是 `showSaveButton && onAddressSave != null && isEditingAddress`，
                // 而 `showSaveButton` 只在"输入文本 ≠ 原地址"时为真。于是用户进入编辑态后
                // 把地址改回原样（或点开编辑又没动），保存按钮就消失了、落到 else 变成
                // "复制取件码" —— **编辑态没有任何出口**：既不能保存、也不能取消，
                // 关闭整个弹窗则这次编辑被静默丢弃。
                //
                // 现在判定只看"是否处于编辑态"：编辑态必定渲染 [取消] + [保存]，
                // 保存按钮仅在确实有改动时可用（无改动时置灰但可见）。
                if (isEditingAddress && onAddressSave != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 取消：丢弃编辑框内容，退回只读态
                        androidx.compose.material3.TextButton(
                            onClick = {
                                editedAddress = resolvedAddress
                                showSaveButton = false
                                isEditingAddress = false
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                text = "取消",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // 保存地址修改按钮（编辑模式，有改动时才可点）
                        Surface(
                            modifier = Modifier
                                .weight(2f)
                                .height(52.dp)
                                .clip(RoundedCornerShape(16.dp)),
                            shape = RoundedCornerShape(16.dp),
                            color = if (showSaveButton) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            enabled = showSaveButton,
                            onClick = {
                                onAddressSave(editedAddress)
                                isEditingAddress = false
                                showSaveButton = false
                            }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Save,
                                    contentDescription = "保存",
                                    modifier = Modifier.size(20.dp),
                                    tint = if (showSaveButton) {
                                        Color.White
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = if (showSaveButton) "保存地址修改" else "没有改动",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (showSaveButton) {
                                        Color.White
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // 复制取件码按钮（非编辑模式）
                    var isPressed by remember { mutableStateOf(false) }
                    val animationConfig = LocalAnimationConfig.current
                    
                    val scale by animateFloatAsState(
                        targetValue = if (isPressed) animationConfig.buttonPressScale else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        ),
                        label = "scale"
                    )
                    
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .scale(scale)
                            .clip(RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        color = if (isDarkTheme) DarkActionCopyColor else ActionCopyColor,
                        onClick = {
                            isPressed = true
                            onCopyClick()
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = "复制",
                                modifier = Modifier.size(20.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "复制取件码",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernDetailRow(
    label: String,
    value: String,
    icon: ImageVector? = null,
    isMonospace: Boolean = false,
    isHighlight: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.width(72.dp)
        )
        
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            
            Text(
                text = value,
                fontSize = if (isHighlight) 18.sp else 14.sp,
                fontWeight = if (isHighlight || isMonospace) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isHighlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontFamily = if (isMonospace) androidx.compose.ui.text.font.FontFamily.Monospace else androidx.compose.ui.text.font.FontFamily.Default,
                maxLines = if (isHighlight) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
