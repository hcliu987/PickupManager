/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import com.shanqiu.pickupmanager.ui.theme.NavSelectedBg
import com.shanqiu.pickupmanager.ui.theme.NavSelectedText
import com.shanqiu.pickupmanager.ui.theme.NavUnselectedText
import com.shanqiu.pickupmanager.ui.theme.NavCardBg
import com.shanqiu.pickupmanager.ui.theme.DarkNavSelectedBg
import com.shanqiu.pickupmanager.ui.theme.DarkNavSelectedText
import com.shanqiu.pickupmanager.ui.theme.DarkNavUnselectedText
import com.shanqiu.pickupmanager.ui.theme.DarkNavCardBg
import com.shanqiu.pickupmanager.ui.theme.DarkNavBackground

@Composable
fun FloatingBottomNavigationBar(
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    pendingCount: Int = 0,
    modifier: Modifier = Modifier
) {
    // R4 / L-12：此处原有 `PerformanceMonitor.recordRecomposition("FloatingBottomNavigationBar")`。
    // 它的问题不是"多打一行日志"，而是**在 Composable 函数体（组合阶段）里写状态**：
    // `recordRecomposition` 内部对 `MutableStateFlow` 赋值并重建整个 Map，
    // 等于每次重组都分配一次、并且向 Compose 快照系统里写入一次状态。
    // 而该 StateFlow 在全仓没有任何读取方，收集到的数据也从无出口。
    // 整条性能监控（PerformanceMonitor）经复核已无其它引用，已随本轮一并删除。
    val darkTheme = isSystemInDarkTheme()
    val backgroundColor = if (darkTheme) DarkNavBackground else Color.White
    val cardBgColor = if (darkTheme) DarkNavCardBg else NavCardBg
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(20.dp),
            color = cardBgColor,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NavButton(
                    icon = if (selectedIndex == 0) Icons.Rounded.Inbox else Icons.Outlined.Inbox,
                    label = "待取",
                    count = pendingCount,
                    isSelected = selectedIndex == 0,
                    onClick = { onItemSelected(0) },
                    modifier = Modifier.weight(1f),
                    darkTheme = darkTheme
                )
                
                NavButton(
                    icon = if (selectedIndex == 1) Icons.Rounded.CheckCircle else Icons.Outlined.CheckCircle,
                    label = "已取",
                    count = null,
                    isSelected = selectedIndex == 1,
                    onClick = { onItemSelected(1) },
                    modifier = Modifier.weight(1f),
                    darkTheme = darkTheme
                )
            }
        }
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundColor)
                .windowInsetsPadding(WindowInsets.navigationBars)
        )
    }
}

@Composable
private fun NavButton(
    icon: ImageVector,
    label: String,
    count: Int?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    
    val selectedBgColor = if (darkTheme) DarkNavSelectedBg else NavSelectedBg
    val selectedTextColor = if (darkTheme) DarkNavSelectedText else NavSelectedText
    val unselectedTextColor = if (darkTheme) DarkNavUnselectedText else NavUnselectedText
    
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) selectedBgColor else Color.Transparent,
        animationSpec = tween(
            durationMillis = 200,
            easing = FastOutSlowInEasing
        ),
        label = "backgroundColor"
    )
    
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) selectedTextColor else unselectedTextColor,
        animationSpec = tween(
            durationMillis = 200,
            easing = FastOutSlowInEasing
        ),
        label = "contentColor"
    )
    
    val displayText = if (count != null && count > 0) {
        "$label($count)"
    } else {
        label
    }
    
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() },
        shape = RoundedCornerShape(14.dp),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(20.dp),
                tint = contentColor
            )
            
            Spacer(modifier = Modifier.width(6.dp))
            
            Text(
                text = displayText,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor
            )
        }
    }
}
