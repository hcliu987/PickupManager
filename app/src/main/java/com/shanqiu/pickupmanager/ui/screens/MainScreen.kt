/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shanqiu.pickupmanager.data.model.PickupItem
import com.shanqiu.pickupmanager.ui.components.CopySuccessToast
import com.shanqiu.pickupmanager.ui.components.DeleteConfirmDialog
import com.shanqiu.pickupmanager.ui.components.FeatureIntroDialog
import com.shanqiu.pickupmanager.ui.components.FloatingBottomNavigationBar
import com.shanqiu.pickupmanager.ui.components.PickupDetailDialog
import com.shanqiu.pickupmanager.ui.components.RouteSettingsScreen
import com.shanqiu.pickupmanager.ui.components.RuleMatchingScreen
import com.shanqiu.pickupmanager.ui.components.SaveOutcome
import com.shanqiu.pickupmanager.ui.components.SettingsDialog
import com.shanqiu.pickupmanager.ui.viewmodel.ComposePickupViewModel
import com.shanqiu.pickupmanager.util.ShareUtils

@Composable
fun MainScreen(
    onMenuItemClick: (MenuItemType) -> Unit,
    onImportSms: () -> Unit,
    onSettingsClick: () -> Unit = {},
    allowFeatureIntro: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val application = context.applicationContext as android.app.Application
    val view = LocalView.current
    val copyScope = rememberCoroutineScope()
    
    val viewModel: ComposePickupViewModel = viewModel(
        factory = ComposeViewModelFactory(application)
    )
    
    val snackbarHostState = remember { SnackbarHostState() }
    
    // 优化: 使用distinctUntilChanged避免不必要的状态更新
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filterType by viewModel.filterType.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val showDetailDialog by viewModel.showDetailDialog.collectAsState()
    val selectedItem by viewModel.selectedItem.collectAsState()
    val copySuccess by viewModel.copySuccess.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    val showFeatureIntro by viewModel.showFeatureIntro.collectAsState()
    val defaultDisplayDays by viewModel.defaultDisplayDays.collectAsState()
    val routeSettings by viewModel.routeSettings.collectAsState()
    val savedAddresses by viewModel.savedAddresses.collectAsState()
    
    // 获取缓存数据，用于Tab切换时与动画同步
    val pendingItemsCache by viewModel.pendingItemsCache.collectAsState()
    val pickedItemsCache by viewModel.pickedItemsCache.collectAsState()
    
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showRouteSettingsDialog by remember { mutableStateOf(false) }
    var showRuleMatchingDialog by remember { mutableStateOf(false) }
    var scrollToTopRequest by remember { mutableStateOf(0) }
    
    var showDeleteDialog by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<PickupItem?>(null) }
    var showCopyToast by remember { mutableStateOf(false) }
    
    // 优化: 使用rememberSaveable保存即时UI状态,实现跟手效果
    // 这样切换Tab时UI立即响应,无需等待ViewModel状态更新
    var instantFilterType by remember { mutableStateOf(filterType) }
    
    // 同步filterType变化 - 使用LaunchedEffect确保状态同步
    LaunchedEffect(filterType) {
        instantFilterType = filterType
    }
    
    val isDarkTheme = !MaterialTheme.colorScheme.background.toString().contains("F5")
    
    // ==================== 修复全屏覆盖层的返回手势问题 ====================
    // 原因: 覆盖层在 Scaffold 外部，BackHandler 在 Compose 中有帧级延迟。
    // 短停留时快速滑动返回，BackHandler 还未注册，导致手势丢失。
    // 解决: 通过 DisposableEffect 向 Activity 的 OnBackPressedDispatcher 立即注册回调，
    // 跳过 Compose 的帧延迟，确保首次手势即可响应。
    val activity = context as ComponentActivity
    val isOverlayActive = showRuleMatchingDialog || showRouteSettingsDialog
    
    DisposableEffect(isOverlayActive) {
        if (isOverlayActive) {
            val callback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // 由覆盖层内部的 BackHandler 处理
                    // 这里只做兜底：如果覆盖层未消费，则关闭之
                    if (showRuleMatchingDialog) {
                        showRuleMatchingDialog = false
                    } else if (showRouteSettingsDialog) {
                        showRouteSettingsDialog = false
                    }
                }
            }
            activity.onBackPressedDispatcher.addCallback(callback)
            onDispose { callback.remove() }
        } else {
            onDispose { /* nothing to dispose when inactive */ }
        }
    }
    
    LaunchedEffect(copySuccess) {
        if (copySuccess) {
            showCopyToast = true
            viewModel.resetCopySuccess()
        }
    }
    
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState)
            },
            bottomBar = {
                FloatingBottomNavigationBar(
                    selectedIndex = if (instantFilterType == PickupItem.STATUS_PENDING) 0 else 1,
                    pendingCount = pendingCount,
                    onItemSelected = { index ->
                        // 优化: 立即更新UI状态,实现瞬时切换
                        val newFilterType = if (index == 0) PickupItem.STATUS_PENDING else PickupItem.STATUS_PICKED
                        
                        // 立即更新本地状态,UI先响应
                        instantFilterType = newFilterType
                        
                        // 触发震动反馈
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        
                        // 异步更新ViewModel - 由于数据已预加载,这里会立即从缓存获取
                        viewModel.setFilterType(newFilterType)
                    }
                )
            },
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentWindowInsets = WindowInsets.navigationBars
        ) { paddingValues ->
            // 优化: 将固定元素（标题、搜索框）移到AnimatedContent外部
            // 只有卡片列表参与切换动画，提升性能和视觉体验
            PickupListScreen(
                pendingItems = pendingItemsCache,
                pickedItems = pickedItemsCache,
                searchQuery = searchQuery,
                routeSettings = routeSettings,
                savedAddresses = savedAddresses,
                onSearchQueryChange = { viewModel.setSearchQuery(it) },
                onMenuItemClick = { menuItemType ->
                    when (menuItemType) {
                        MenuItemType.SETTINGS -> showSettingsDialog = true
                        MenuItemType.ROUTE_SETTINGS -> showRouteSettingsDialog = true
                        MenuItemType.RULE_MATCHING -> showRuleMatchingDialog = true
                        else -> onMenuItemClick(menuItemType)
                    }
                },
                onPickClick = { item ->
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    if (item.status == PickupItem.STATUS_PENDING) {
                        viewModel.markAsPicked(item)
                    } else {
                        viewModel.markAsPending(item)
                    }
                },
                onDeleteClick = { item ->
                    itemToDelete = item
                    showDeleteDialog = true
                },
                onShareClick = { item ->
                    ShareUtils.sharePickupItems(context, listOf(item))
                },
                onShareAllClick = { items ->
                    ShareUtils.sharePickupItems(context, items)
                },
                onDeleteAllPicked = { viewModel.deleteAllPickedItems() },
                onCountPicked = { viewModel.countPickedItems() },
                onCardClick = { item ->
                    viewModel.showDetailDialog(item)
                },
                onRefresh = {
                    viewModel.refresh()
                },
                isRefreshing = isRefreshing,
                filterType = instantFilterType,
                scrollToTopRequest = scrollToTopRequest,
                paddingValues = paddingValues
            )
            
            if (showDetailDialog && selectedItem != null) {
                PickupDetailDialog(
                    item = selectedItem!!,
                    onDismiss = { viewModel.hideDetailDialog() },
                    onCopyClick = {
                        val itemToCopy = selectedItem ?: return@PickupDetailDialog
                        copyScope.launch {
                            // 列表投影的 rawSms 为空时按 id 兜底查完整原文，避免复制到空文本
                            val raw = viewModel.resolveRawSmsForCopy(itemToCopy)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("取件信息", raw))
                            viewModel.copyPickupCode(itemToCopy)
                            viewModel.hideDetailDialog()
                        }
                    },
                    onAddressSave = { newAddress ->
                        viewModel.updateItemAddress(selectedItem!!.id, newAddress)
                    },
                    savedAddresses = savedAddresses
                )
            }
        }
        
        if (showFeatureIntro && allowFeatureIntro) {
            FeatureIntroDialog(
                onDismiss = { viewModel.dismissFeatureIntro() },
                onSettingsClick = {
                    viewModel.dismissFeatureIntro()
                    showSettingsDialog = true
                }
            )
        }
        
        if (showSettingsDialog) {
            SettingsDialog(
                currentDays = defaultDisplayDays,
                onDismiss = { showSettingsDialog = false },
                onSave = { days ->
                    viewModel.setDefaultDisplayDays(days)
                    showSettingsDialog = false
                },
                onClearAll = { viewModel.clearAllData() },
                onCountCleanable = { viewModel.countCleanableItems() },
                onCleanOld = { viewModel.cleanOldItems() }
            )
        }
        
        if (showRouteSettingsDialog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                RouteSettingsScreen(
                routeSettings = routeSettings,
                savedAddresses = savedAddresses,
                onDismiss = { showRouteSettingsDialog = false },
                onSave = { settings ->
                    viewModel.setRouteSettings(settings)
                    showRouteSettingsDialog = false
                    scrollToTopRequest++
                },
                onDeleteAddress = { address ->
                    viewModel.deleteSavedAddress(address)
                },
                onUpdateAddressOrder = { addresses ->
                    viewModel.updateAddressOrder(addresses)
                },
                onUpdateAddressCustomName = { addressId, customName ->
                    viewModel.updateAddressCustomName(addressId, customName)
                },
                onAddressSave = { addressTag, newAddress ->
                    viewModel.updateAllItemsAddress(addressTag, newAddress)
                }
            )
            }
        }
        
        if (showDeleteDialog && itemToDelete != null) {
            DeleteConfirmDialog(
                itemName = "取件码 ${itemToDelete!!.pickupCode}",
                onConfirm = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    viewModel.deleteItem(itemToDelete!!)
                    itemToDelete = null
                },
                onDismiss = {
                    itemToDelete = null
                },
                isDarkTheme = isDarkTheme
            )
        }
        
        if (showCopyToast) {
            CopySuccessToast(
                message = "已复制到剪贴板",
                onDismiss = {
                    showCopyToast = false
                },
                isDarkTheme = isDarkTheme
            )
        }
        
        if (showRuleMatchingDialog) {
            val addressList = savedAddresses.map { it.displayName }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                RuleMatchingScreen(
                    savedAddresses = addressList,
                    onDismiss = { showRuleMatchingDialog = false },
                    onSave = { item, action, onResult ->
                        // 关键修复：根据 action 区分插入和覆盖两种语义
                        // - INSERT：第一次提交，去重检查，重复则返回失败让屏幕弹"已存在"/"确认覆盖"对话框
                        // - OVERWRITE：用户已确认覆盖，直接更新原记录
                        //
                        // R4 / M-09：结果改成三态 `SaveOutcome`。原先用 `(Boolean, String?)`
                        // 把 Inserted/Duplicate/Error 三种结局压成两个值，`Error` 与
                        // `Inserted` 在 UI 的判定里会落到同一分支 ⇒ 写库失败被当成成功。
                        when (action) {
                            com.shanqiu.pickupmanager.ui.components.SaveAction.INSERT -> {
                                viewModel.addPickupItemWithDedup(item) { result ->
                                    when (result) {
                                        is ComposePickupViewModel.AddPickupResult.Inserted -> {
                                            onResult(SaveOutcome.SUCCESS, null)
                                        }
                                        is ComposePickupViewModel.AddPickupResult.Duplicate -> {
                                            onResult(SaveOutcome.DUPLICATE, result.existing.pickupCode)
                                        }
                                        is ComposePickupViewModel.AddPickupResult.Error -> {
                                            onResult(SaveOutcome.FAILED, null)
                                        }
                                    }
                                }
                            }
                            com.shanqiu.pickupmanager.ui.components.SaveAction.OVERWRITE -> {
                                // 用户已在"确认覆盖"对话框中点击确认
                                // 用手动编辑的新数据更新原记录（保留 id、receiveTime、status）
                                viewModel.overwritePickupItem(
                                    pickupCode = item.pickupCode,
                                    address = item.address,
                                    addressTag = item.addressTag,
                                    courierCompany = item.courierCompany,
                                    trackingNumber = item.trackingNumber,
                                    rawSms = item.rawSms,
                                    notes = item.notes
                                ) { success ->
                                    onResult(
                                        if (success) SaveOutcome.SUCCESS else SaveOutcome.FAILED,
                                        if (success) null else item.pickupCode
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}
