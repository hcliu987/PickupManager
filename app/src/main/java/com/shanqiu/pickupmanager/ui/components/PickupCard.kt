/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import com.shanqiu.pickupmanager.ui.animation.AnimationConfig
import com.shanqiu.pickupmanager.ui.animation.LocalAnimationConfig
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanqiu.pickupmanager.data.model.PickupItem
import com.shanqiu.pickupmanager.ui.theme.ActionCopyColor
import com.shanqiu.pickupmanager.ui.theme.ActionDeleteColor
import com.shanqiu.pickupmanager.ui.theme.ActionPickColor
import com.shanqiu.pickupmanager.ui.theme.ActionShareColor
import com.shanqiu.pickupmanager.ui.theme.DarkActionCopyColor
import com.shanqiu.pickupmanager.ui.theme.DarkActionDeleteColor
import com.shanqiu.pickupmanager.ui.theme.DarkActionPickColor
import com.shanqiu.pickupmanager.ui.theme.DarkActionShareColor
import com.shanqiu.pickupmanager.ui.theme.DarkStatusExpiredBg
import com.shanqiu.pickupmanager.ui.theme.DarkStatusExpiredText
import com.shanqiu.pickupmanager.ui.theme.DarkStatusPickedBg
import com.shanqiu.pickupmanager.ui.theme.DarkStatusPickedText
import com.shanqiu.pickupmanager.ui.theme.DarkStatusPendingBg
import com.shanqiu.pickupmanager.ui.theme.DarkStatusPendingText
import com.shanqiu.pickupmanager.ui.theme.DarkStatusWarningBg
import com.shanqiu.pickupmanager.ui.theme.DarkStatusWarningText
import com.shanqiu.pickupmanager.ui.theme.DarkStatusUrgentBg
import com.shanqiu.pickupmanager.ui.theme.DarkStatusUrgentText
import com.shanqiu.pickupmanager.ui.theme.StatusExpiredBg
import com.shanqiu.pickupmanager.ui.theme.StatusExpiredText
import com.shanqiu.pickupmanager.ui.theme.StatusPickedBg
import com.shanqiu.pickupmanager.ui.theme.StatusPickedText
import com.shanqiu.pickupmanager.ui.theme.StatusPendingBg
import com.shanqiu.pickupmanager.ui.theme.StatusPendingText
import com.shanqiu.pickupmanager.ui.theme.StatusWarningBg
import com.shanqiu.pickupmanager.ui.theme.StatusWarningText
import com.shanqiu.pickupmanager.ui.theme.StatusUrgentBg
import com.shanqiu.pickupmanager.ui.theme.StatusUrgentText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 性能优化：缓存SimpleDateFormat实例，避免每次重组都创建
private object DateFormatCache {
    val instance: SimpleDateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    val entryTimeFormat: SimpleDateFormat = SimpleDateFormat("MM月dd日HH:mm", Locale.getDefault())
}

// 性能优化：使用 @Immutable 标记稳定的数据类
@Immutable
private data class CardColors(
    val actionDelete: Color,
    val actionCopy: Color,
    val actionPick: Color,
    val actionShare: Color
)

// 性能优化：预计算颜色配置
private object CardColorProvider {
    fun getColors(isDarkTheme: Boolean): CardColors = if (isDarkTheme) {
        CardColors(DarkActionDeleteColor, DarkActionCopyColor, DarkActionPickColor, DarkActionShareColor)
    } else {
        CardColors(ActionDeleteColor, ActionCopyColor, ActionPickColor, ActionShareColor)
    }
}

@Composable
fun PickupCard(
    item: PickupItem,
    onPickClick: (PickupItem) -> Unit,
    onDeleteClick: (PickupItem) -> Unit,
    onShareClick: (PickupItem) -> Unit,
    onCardClick: (PickupItem) -> Unit,
    modifier: Modifier = Modifier,
    isScrolling: Boolean = false,
    resolveAddressName: ((String) -> String?)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val animationConfig = LocalAnimationConfig.current
    val coroutineScope = rememberCoroutineScope()
    
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cardColors = remember(isDarkTheme) { CardColorProvider.getColors(isDarkTheme) }
    
    var isExiting by remember(item.id) { mutableStateOf(false) }
    var exitAnimationType by remember(item.id) { mutableStateOf<AnimationType?>(null) }
    var pendingAction by remember(item.id) { mutableStateOf<(() -> Unit)?>(null) }
    
    val scale by animateFloatAsState(
        targetValue = when {
            isExiting && exitAnimationType == AnimationType.PENDING_TO_PICKED -> 0.85f
            isExiting && exitAnimationType == AnimationType.PICKED_TO_PENDING -> 1.05f
            else -> 1f
        },
        animationSpec = tween(
            durationMillis = animationConfig.cardStatusTransitionDuration,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        label = "cardScale"
    )
    
    val offsetY by animateFloatAsState(
        targetValue = when {
            isExiting && exitAnimationType == AnimationType.PENDING_TO_PICKED -> -30f
            isExiting && exitAnimationType == AnimationType.PICKED_TO_PENDING -> 30f
            else -> 0f
        },
        animationSpec = tween(
            durationMillis = animationConfig.cardStatusTransitionDuration,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        label = "cardOffsetY"
    )
    
    val timeText = remember(item.status, item.markedTime, item.receiveTime, item.restoreTime) {
        buildTimeText(item)
    }
    
    val pickButtonLabel = remember(item.status) {
        if (item.status == PickupItem.STATUS_PENDING) "已取" else "待取"
    }
    
    LaunchedEffect(isExiting) {
        if (isExiting) {
            delay(animationConfig.cardStatusTransitionDuration.toLong())
            pendingAction?.invoke()
            pendingAction = null
        }
    }
    
    AnimatedVisibility(
        visible = !isExiting,
        exit = shrinkVertically(
            animationSpec = tween(
                durationMillis = animationConfig.cardStatusTransitionDuration,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            ),
            shrinkTowards = Alignment.Top
        ) + fadeOut(
            animationSpec = tween(
                durationMillis = animationConfig.cardStatusTransitionDuration,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            )
        ),
        modifier = modifier
    ) {
        Card(
            modifier = Modifier
                .graphicsLayer {
                    this.scaleX = scale
                    this.scaleY = scale
                    this.translationY = offsetY
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) {
                    onCardClick(item)
                }
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 4.dp
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.pickupCode,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        letterSpacing = 1.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    ModernStatusChip(
                        status = item.status, 
                        isDarkTheme = isDarkTheme,
                        urgencyLevel = item.getUrgencyLevel(),
                        isScrolling = isScrolling
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = resolveAddressName?.invoke(item.addressTag) ?: item.address,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                
                Spacer(modifier = Modifier.height(10.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = getCourierIcon(item.courierCompany),
                            contentDescription = "快递",
                            modifier = Modifier.size(19.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = item.courierCompany.ifBlank { "快递" },
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = getTimeIcon(item.status),
                            contentDescription = "时间",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = timeText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            Color(0xFFE0E0E0)
                        )
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ModernActionButton(
                        icon = Icons.Rounded.Delete,
                        label = "删除",
                        color = cardColors.actionDelete,
                        onClick = { onDeleteClick(item) },
                        modifier = Modifier.weight(1f)
                    )
                    
                    ModernActionButton(
                        icon = Icons.Rounded.Share,
                        label = "分享",
                        color = cardColors.actionShare,
                        onClick = { onShareClick(item) },
                        modifier = Modifier.weight(1f)
                    )
                    
                    ModernActionButton(
                        icon = Icons.Rounded.Done,
                        label = pickButtonLabel,
                        color = cardColors.actionPick,
                        onClick = {
                            if (!isExiting) {
                                exitAnimationType = if (item.status == PickupItem.STATUS_PENDING) {
                                    AnimationType.PENDING_TO_PICKED
                                } else {
                                    AnimationType.PICKED_TO_PENDING
                                }
                                pendingAction = { onPickClick(item) }
                                isExiting = true
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

private enum class AnimationType {
    PENDING_TO_PICKED,
    PICKED_TO_PENDING
}

@Composable
private fun ModernStatusChip(
    status: Int, 
    isDarkTheme: Boolean, 
    urgencyLevel: PickupItem.UrgencyLevel = PickupItem.UrgencyLevel.NORMAL,
    isScrolling: Boolean = false
) {
    val (text, backgroundColor, textColor) = when (status) {
        PickupItem.STATUS_PENDING -> {
            val displayText = "待取件"
            when (urgencyLevel) {
                PickupItem.UrgencyLevel.URGENT -> {
                    if (isDarkTheme) {
                        Triple(displayText, DarkStatusUrgentBg, DarkStatusUrgentText)
                    } else {
                        Triple(displayText, StatusUrgentBg, StatusUrgentText)
                    }
                }
                PickupItem.UrgencyLevel.WARNING -> {
                    if (isDarkTheme) {
                        Triple(displayText, DarkStatusWarningBg, DarkStatusWarningText)
                    } else {
                        Triple(displayText, StatusWarningBg, StatusWarningText)
                    }
                }
                PickupItem.UrgencyLevel.NORMAL -> {
                    if (isDarkTheme) {
                        Triple(displayText, DarkStatusPendingBg, DarkStatusPendingText)
                    } else {
                        Triple(displayText, StatusPendingBg, StatusPendingText)
                    }
                }
            }
        }
        PickupItem.STATUS_PICKED -> {
            if (isDarkTheme) {
                Triple("已取件", DarkStatusPickedBg, DarkStatusPickedText)
            } else {
                Triple("已取件", StatusPickedBg, StatusPickedText)
            }
        }
        PickupItem.STATUS_EXPIRED -> {
            if (isDarkTheme) {
                Triple("已过期", DarkStatusExpiredBg, DarkStatusExpiredText)
            } else {
                Triple("已过期", StatusExpiredBg, StatusExpiredText)
            }
        }
        else -> {
            Triple("未知", Color(0xFFE2E8F0), Color(0xFF475569))
        }
    }
    
    Surface(
        modifier = Modifier
            .height(38.dp)
            .then(
                if (!isScrolling) {
                    Modifier.shadow(
                        elevation = 2.dp,
                        shape = RoundedCornerShape(10.dp),
                        ambientColor = backgroundColor.copy(alpha = 0.3f),
                        spotColor = backgroundColor.copy(alpha = 0.3f)
                    )
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(10.dp),
        color = backgroundColor
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        }
    }
}

@Composable
private fun ModernActionButton(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.15f),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(18.dp),
                tint = color
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = color
            )
        }
    }
}

/**
 * 计算两个日期相差的天数（基于日历天数，非24小时制）
 */
private fun daysBetween(timestamp1: Long, timestamp2: Long): Int {
    val cal1 = java.util.Calendar.getInstance().apply {
        timeInMillis = timestamp1
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    val cal2 = java.util.Calendar.getInstance().apply {
        timeInMillis = timestamp2
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    val diffMs = cal2.timeInMillis - cal1.timeInMillis
    return (diffMs / (24 * 60 * 60 * 1000)).toInt()
}

/**
 * 根据快递公司返回对应的图标
 */
private fun getCourierIcon(courierCompany: String): ImageVector {
    return when {
        courierCompany.contains("顺丰") -> Icons.Rounded.FlashOn
        courierCompany.contains("京东") -> Icons.Rounded.Storefront
        courierCompany.contains("中通") -> Icons.Rounded.LocalShipping
        courierCompany.contains("圆通") -> Icons.Rounded.DirectionsCar
        courierCompany.contains("申通") -> Icons.Rounded.Flight
        courierCompany.contains("韵达") -> Icons.Rounded.Inventory
        courierCompany.contains("邮政") || courierCompany.contains("EMS") -> Icons.Rounded.Business
        courierCompany.contains("菜鸟") -> Icons.Rounded.Inventory
        courierCompany.contains("极兔") -> Icons.Rounded.FlashOn
        courierCompany.contains("德邦") -> Icons.Rounded.LocalShipping
        else -> Icons.Rounded.LocalShipping
    }
}

/**
 * 根据时间状态返回对应的图标
 */
private fun getTimeIcon(status: Int): ImageVector {
    return when (status) {
        PickupItem.STATUS_PICKED -> Icons.Rounded.History
        else -> Icons.Rounded.AccessTime
    }
}

private fun buildTimeText(item: PickupItem, status: Int = item.status): String {
    val now = System.currentTimeMillis()
    
    return when {
        status == PickupItem.STATUS_PICKED -> {
            // 已取件：昨日、前日，超过3天显示日期
            val daysAgo = daysBetween(item.markedTime, now)
            when {
                daysAgo == 0 -> "取件：今天"
                daysAgo == 1 -> "取件：昨日"
                daysAgo == 2 -> "取件：前日"
                else -> "取件：${DateFormatCache.instance.format(Date(item.markedTime))}"
            }
        }
        else -> {
            // 待取件：今天、昨天、前天，超过3天显示日期
            val daysAgo = daysBetween(item.receiveTime, now)
            when {
                daysAgo == 0 -> "入库：今天 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.receiveTime))}"
                daysAgo == 1 -> "入库：昨天 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.receiveTime))}"
                daysAgo == 2 -> "入库：前天 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.receiveTime))}"
                else -> "入库：${DateFormatCache.entryTimeFormat.format(Date(item.receiveTime))}"
            }
        }
    }
}
