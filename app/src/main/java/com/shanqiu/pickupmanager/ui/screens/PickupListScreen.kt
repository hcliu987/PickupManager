/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import com.shanqiu.pickupmanager.ui.animation.LocalAnimationConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.shape.CircleShape
import com.shanqiu.pickupmanager.util.ScrollOptimizations
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.ImportExport
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanqiu.pickupmanager.data.model.PickupItem
import com.shanqiu.pickupmanager.data.model.RouteSettings
import com.shanqiu.pickupmanager.data.model.SavedAddress
import com.shanqiu.pickupmanager.data.model.ModeType
import com.shanqiu.pickupmanager.ui.components.PickupCard
import com.shanqiu.pickupmanager.ui.theme.ActionCopyColor
import com.shanqiu.pickupmanager.ui.theme.DarkActionCopyColor
import androidx.compose.foundation.layout.PaddingValues as FoundationPaddingValues
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.DpOffset
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickupListScreen(
    pendingItems: List<PickupItem>,
    pickedItems: List<PickupItem>,
    searchQuery: String,
    routeSettings: RouteSettings,
    savedAddresses: List<SavedAddress> = emptyList(),
    onSearchQueryChange: (String) -> Unit,
    onMenuItemClick: (MenuItemType) -> Unit,
    onPickClick: (PickupItem) -> Unit,
    onDeleteClick: (PickupItem) -> Unit,
    onShareClick: (PickupItem) -> Unit,
    onShareAllClick: (List<PickupItem>) -> Unit,
    onDeleteAllPicked: () -> Unit,
    onCountPicked: suspend () -> Int,
    onCardClick: (PickupItem) -> Unit,
    onRefresh: () -> Unit,
    isRefreshing: Boolean,
    filterType: Int = PickupItem.STATUS_PENDING,
    scrollToTopRequest: Int = 0,
    paddingValues: FoundationPaddingValues = FoundationPaddingValues(0.dp)
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    var showDeletePickedConfirm by remember { mutableStateOf(false) }
    var pickedToDeleteCount by remember { mutableIntStateOf(0) }
    val deletePickedScope = rememberCoroutineScope()
    val isDarkTheme = isSystemInDarkTheme()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val smoothFlingBehavior = ScrollOptimizations.rememberSmoothFlingBehavior()
    
    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest > 0) {
            listState.animateScrollToItem(0)
        }
    }
    
    // 性能优化：预计算路由排序映射，避免每次重组都计算
    val routeOrderCache = remember(routeSettings) {
        val currentMode = if (routeSettings.autoSwitch) {
            val calendar = Calendar.getInstance()
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
            if (routeSettings.workMode.applyDays.contains(dayOfWeek)) {
                ModeType.WORK_MODE
            } else {
                ModeType.VACATION_MODE
            }
        } else {
            routeSettings.currentMode
        }
        val routes = if (currentMode == ModeType.WORK_MODE) {
            routeSettings.workMode.routes
        } else {
            routeSettings.vacationMode.routes
        }
        routes.mapIndexed { index, route -> route.addressTag to index }.toMap()
    }
    
    // 地址名称解析函数：优先使用用户自定义名称，找不到时返回null以便使用item.address
    val resolveAddressName: (String) -> String? = remember(savedAddresses) {
        { addressTag ->
            savedAddresses.find { it.addressTag == addressTag }?.getDisplayText()
                ?: savedAddresses.find { it.addressTag == addressTag }?.displayName
        }
    }
    
    // 性能优化：使用 derivedStateOf 避免不必要的重组
    // 根据filterType和搜索条件处理数据
    val processedItems by remember(pendingItems, pickedItems, searchQuery, routeOrderCache) {
        derivedStateOf {
            val baseItems = if (filterType == PickupItem.STATUS_PENDING) pendingItems else pickedItems
            // 应用搜索过滤
            val filtered = if (searchQuery.isNotBlank()) {
                baseItems.filter { item ->
                    item.pickupCode.contains(searchQuery, ignoreCase = true) ||
                    item.address.contains(searchQuery, ignoreCase = true) ||
                    item.courierCompany.contains(searchQuery, ignoreCase = true)
                }
            } else {
                baseItems
            }
            // 对待取件应用路径排序
            if (filterType == PickupItem.STATUS_PENDING && routeOrderCache.isNotEmpty()) {
                filtered.sortedWith(
                    compareBy<PickupItem> { item ->
                        routeOrderCache[item.addressTag] ?: Int.MAX_VALUE
                    }.thenByDescending { it.receiveTime }
                )
            } else if (filterType == PickupItem.STATUS_PENDING) {
                filtered.sortedByDescending { it.receiveTime }
            } else {
                filtered
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        // 固定区域：标题栏
        TopAppBar(
            title = {
                val titleText = when (filterType) {
                    PickupItem.STATUS_PENDING -> "待取件"
                    PickupItem.STATUS_PICKED -> "已取件"
                    else -> "取件管理"
                }
                // 优化: 标题动画与Tab栏同步，使用300ms过渡
                // 数据已预加载,标题切换应该无感知
                AnimatedContent(
                    targetState = titleText,
                    transitionSpec = {
                        fadeIn(
                            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                        ) togetherWith fadeOut(
                            animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
                        )
                    },
                    label = "titleAnimation"
                ) { targetText ->
                    Text(
                        text = targetText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground
            ),
            actions = {
                // 一键分享全部待取件
                if (filterType == PickupItem.STATUS_PENDING && pendingItems.isNotEmpty()) {
                    IconButton(
                        onClick = { onShareAllClick(pendingItems) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = "分享全部",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // 一键删除全部已取件（放在已取页面顶部，不再藏进设置里）
                if (filterType == PickupItem.STATUS_PICKED && pickedItems.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            deletePickedScope.launch {
                                pickedToDeleteCount = onCountPicked()
                                showDeletePickedConfirm = true
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "删除全部已取件",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                
                Box {
                    ModernDropdownMenu(
                        expanded = isMenuExpanded,
                        onDismissRequest = { isMenuExpanded = false },
                        items = listOf(
                            MenuItemData(
                                label = "导入短信",
                                icon = Icons.Rounded.ImportExport,
                                iconBackgroundColor = ActionCopyColor,
                                action = MenuItemType.IMPORT_SMS
                            ),
                            MenuItemData(
                                label = "规则匹配",
                                icon = Icons.Outlined.AccountTree,
                                iconBackgroundColor = Color(0xFF6366F1),
                                action = MenuItemType.RULE_MATCHING
                            ),
                            MenuItemData(
                                label = "显示设置",
                                icon = Icons.Rounded.Settings,
                                iconBackgroundColor = Color(0xFF6366F1),
                                action = MenuItemType.SETTINGS
                            ),
                            MenuItemData(
                                label = "取件路径",
                                icon = Icons.Rounded.Route,
                                iconBackgroundColor = Color(0xFF14B8A6),
                                action = MenuItemType.ROUTE_SETTINGS
                            ),
                            MenuItemData(
                                label = "检查更新",
                                icon = Icons.Rounded.SystemUpdate,
                                iconBackgroundColor = Color(0xFF22C55E),
                                action = MenuItemType.CHECK_UPDATE
                            ),
                            MenuItemData(
                                label = "关于/帮助",
                                icon = Icons.AutoMirrored.Rounded.HelpOutline,
                                iconBackgroundColor = Color(0xFFF59E0B),
                                action = MenuItemType.ABOUT
                            )
                        ),
                        onItemClick = { item ->
                            isMenuExpanded = false
                            onMenuItemClick(item.action)
                        },
                        isDarkTheme = isDarkTheme
                    )
                    
                    IconButton(
                        onClick = { isMenuExpanded = !isMenuExpanded },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "更多选项",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        )
        
        // 固定区域：搜索框
        ModernSearchBar(
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        val pullToRefreshState = rememberPullToRefreshState()
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .nestedScroll(pullToRefreshState.nestedScrollConnection)
        ) {
            AnimatedContent(
                targetState = filterType,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally(
                            animationSpec = tween(
                                durationMillis = 350,
                                easing = FastOutSlowInEasing
                            ),
                            initialOffsetX = { fullWidth -> fullWidth }
                        ) + fadeIn(
                            animationSpec = tween(
                                durationMillis = 200,
                                easing = FastOutSlowInEasing
                            )
                        ) togetherWith slideOutHorizontally(
                            animationSpec = tween(
                                durationMillis = 300,
                                easing = FastOutSlowInEasing
                            ),
                            targetOffsetX = { fullWidth -> -fullWidth }
                        ) + fadeOut(
                            animationSpec = tween(
                                durationMillis = 200,
                                easing = FastOutSlowInEasing
                            )
                        )
                    } else {
                        slideInHorizontally(
                            animationSpec = tween(
                                durationMillis = 350,
                                easing = FastOutSlowInEasing
                            ),
                            initialOffsetX = { fullWidth -> -fullWidth }
                        ) + fadeIn(
                            animationSpec = tween(
                                durationMillis = 200,
                                easing = FastOutSlowInEasing
                            )
                        ) togetherWith slideOutHorizontally(
                            animationSpec = tween(
                                durationMillis = 300,
                                easing = FastOutSlowInEasing
                            ),
                            targetOffsetX = { fullWidth -> fullWidth }
                        ) + fadeOut(
                            animationSpec = tween(
                                durationMillis = 200,
                                easing = FastOutSlowInEasing
                            )
                        )
                    }.using(
                        SizeTransform(clip = false)
                    )
                },
                label = "cardListAnimation"
            ) { targetFilterType ->
                val items = remember(targetFilterType, pendingItems, pickedItems, searchQuery, routeOrderCache) {
                    val baseItems = if (targetFilterType == PickupItem.STATUS_PENDING) pendingItems else pickedItems
                    val filtered = if (searchQuery.isNotBlank()) {
                        baseItems.filter { item ->
                            item.pickupCode.contains(searchQuery, ignoreCase = true) ||
                            item.address.contains(searchQuery, ignoreCase = true) ||
                            item.courierCompany.contains(searchQuery, ignoreCase = true)
                        }
                    } else {
                        baseItems
                    }
                    if (targetFilterType == PickupItem.STATUS_PENDING && routeOrderCache.isNotEmpty()) {
                        filtered.sortedWith(
                            compareBy<PickupItem> { item ->
                                routeOrderCache[item.addressTag] ?: Int.MAX_VALUE
                            }.thenByDescending { it.receiveTime }
                        )
                    } else if (targetFilterType == PickupItem.STATUS_PENDING) {
                        filtered.sortedByDescending { it.receiveTime }
                    } else {
                        filtered
                    }
                }
                
                if (items.isEmpty()) {
                    ModernEmptyState(
                        filterType = targetFilterType,
                        onRefresh = onRefresh,
                        onImportSms = { onMenuItemClick(MenuItemType.IMPORT_SMS) }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = FoundationPaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        flingBehavior = smoothFlingBehavior
                    ) {
                        items(
                            items = items,
                            key = { item -> item.id },
                            contentType = { "pickup_card" }
                        ) { item ->
                            PickupCard(
                                item = item,
                                onPickClick = onPickClick,
                                onDeleteClick = onDeleteClick,
                                onShareClick = onShareClick,
                                onCardClick = onCardClick,
                                resolveAddressName = resolveAddressName
                            )
                        }
                    }
                }
            }
            
            val showRefreshIndicator = pullToRefreshState.progress > 0 || pullToRefreshState.isRefreshing || isRefreshing
            
            if (showRefreshIndicator) {
                PullToRefreshContainer(
                    state = pullToRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    contentColor = MaterialTheme.colorScheme.primary
                )
            }
            
            LaunchedEffect(isRefreshing) {
                if (isRefreshing) {
                    pullToRefreshState.startRefresh()
                } else {
                    pullToRefreshState.endRefresh()
                }
            }
            
            LaunchedEffect(pullToRefreshState.isRefreshing) {
                if (pullToRefreshState.isRefreshing && !isRefreshing) {
                    onRefresh()
                }
            }
        }
    }

    // 删除全部已取件的二次确认：写明只删已取、待取不受影响、不可撤销。
    if (showDeletePickedConfirm) {
        AlertDialog(
            onDismissRequest = { showDeletePickedConfirm = false },
            title = { Text("删除全部已取件？") },
            text = {
                Text(
                    if (pickedToDeleteCount > 0)
                        "将删除 $pickedToDeleteCount 条已取件记录，待取件不受影响。此操作不可撤销。"
                    else "当前没有已取件记录，无需删除。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeletePickedConfirm = false
                        if (pickedToDeleteCount > 0) onDeleteAllPicked()
                    }
                ) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePickedConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ModernSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        tonalElevation = 0.dp
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = "搜索取件码、地址、快递公司",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "搜索",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(22.dp)
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "清除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            interactionSource = interactionSource,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun ModernEmptyState(
    filterType: Int,
    onRefresh: () -> Unit,
    onImportSms: () -> Unit
) {
    val isDarkTheme = isSystemInDarkTheme()
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (filterType) {
                        PickupItem.STATUS_PENDING -> Icons.Filled.Inventory2
                        PickupItem.STATUS_PICKED -> Icons.Filled.TaskAlt
                        else -> Icons.AutoMirrored.Filled.List
                    },
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            
            Text(
                text = when (filterType) {
                    PickupItem.STATUS_PENDING -> "暂无待取件"
                    PickupItem.STATUS_PICKED -> "暂无已取件"
                    else -> "暂无数据"
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            
            Text(
                text = "下拉刷新或点击右上角导入短信",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )
            
            if (filterType == PickupItem.STATUS_PENDING) {
                Spacer(modifier = Modifier.height(12.dp))
                
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp)),
                    shape = RoundedCornerShape(14.dp),
                    color = if (isDarkTheme) DarkActionCopyColor else ActionCopyColor,
                    // R4 / H-11：这里原先写的是 `onClick = onRefresh`（只触发下拉刷新），
                    // 而按钮文案是「导入短信」、图标是 ImportExport —— 新用户第一次
                    // 打开、列表为空时按下它，**什么都不会发生**：既不导入，
                    // 也不刷新出任何东西（列表本来就是空的）。
                    // 现在真正去走导入那条路。
                    onClick = onImportSms
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ImportExport,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "导入短信",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}

enum class MenuItemType {
    IMPORT_SMS,
    RULE_MATCHING,
    SETTINGS,
    ROUTE_SETTINGS,
    ABOUT,
    CHECK_UPDATE
}

private data class MenuItemData(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val iconBackgroundColor: Color,
    val action: MenuItemType
)

@Composable
private fun ModernDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<MenuItemData>,
    onItemClick: (MenuItemData) -> Unit,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isDarkTheme) Color(0xFF1E1E1E) else Color(0xFFFAFAFA)
    
    var visible by remember { mutableStateOf(false) }
    var shouldShow by remember { mutableStateOf(false) }
    
    LaunchedEffect(expanded) {
        if (expanded) {
            shouldShow = true
            visible = true
        } else {
            visible = false
        }
    }
    
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "menuScale",
        finishedListener = {
            if (!visible) {
                shouldShow = false
            }
        }
    )
    
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "menuAlpha"
    )
    
    if (shouldShow) {
        androidx.compose.ui.window.Popup(
            alignment = androidx.compose.ui.Alignment.TopEnd,
            onDismissRequest = onDismissRequest,
            properties = androidx.compose.ui.window.PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onDismissRequest() }
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 48.dp, end = 8.dp)
                        .width(180.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                            transformOrigin = TransformOrigin(1f, 0f)
                        }
                        .shadow(
                            elevation = 16.dp,
                            shape = RoundedCornerShape(20.dp),
                            ambientColor = if (isDarkTheme) Color(0x50000000) else Color(0x15000000),
                            spotColor = if (isDarkTheme) Color(0x50000000) else Color(0x15000000)
                        ),
                    color = containerColor,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        items.forEachIndexed { index, item ->
                            ModernMenuItem(
                                item = item,
                                isDarkTheme = isDarkTheme,
                                onClick = { onItemClick(item) }
                            )
                            if (index < items.size - 1) {
                                Spacer(modifier = Modifier.height(2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernMenuItem(
    item: MenuItemData,
    isDarkTheme: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isPressed -> if (isDarkTheme) Color(0x15FFFFFF) else Color(0x10000000)
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 150),
        label = "bgColor"
    )
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(14.dp)),
        color = backgroundColor,
        onClick = onClick,
        interactionSource = interactionSource
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        item.iconBackgroundColor.copy(alpha = if (isDarkTheme) 0.2f else 0.15f),
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = item.iconBackgroundColor
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Text(
                text = item.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (isDarkTheme) Color.White else Color(0xFF1F2937)
            )
        }
    }
}
