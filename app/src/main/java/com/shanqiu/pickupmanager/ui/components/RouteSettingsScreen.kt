/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.ui.components

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.shanqiu.pickupmanager.data.model.ModeType
import com.shanqiu.pickupmanager.data.model.PickupRoute
import com.shanqiu.pickupmanager.data.model.RouteMode
import com.shanqiu.pickupmanager.data.model.RouteSettings
import com.shanqiu.pickupmanager.data.model.SavedAddress
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.shanqiu.pickupmanager.ui.theme.DarkColorScheme
import com.shanqiu.pickupmanager.ui.theme.LightColorScheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteSettingsScreen(
    routeSettings: RouteSettings,
    savedAddresses: List<SavedAddress>,
    onDismiss: () -> Unit,
    onSave: (RouteSettings) -> Unit,
    onDeleteAddress: (SavedAddress) -> Unit,
    onUpdateAddressOrder: (List<SavedAddress>) -> Unit,
    onUpdateAddressCustomName: (Long, String?) -> Unit,
    onAddressSave: ((String, String) -> Unit)? = null
) {
    // 使用柔和颜色方案，减少高亮度白色，提升可读性
    val softColorScheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF64B5F6),
            onPrimary = Color(0xFF0D47A1),
            primaryContainer = Color(0xFF1E3A5F),
            onPrimaryContainer = Color(0xFFBBDEFB),
            secondary = Color(0xFF81C784),
            onSecondary = Color(0xFF1B5E20),
            secondaryContainer = Color(0xFF1B5E20),
            onSecondaryContainer = Color(0xFFC8E6C9),
            background = Color(0xFF0D1421),
            onBackground = Color(0xFFE2E8F0),
            surface = Color(0xFF1A2332),
            onSurface = Color(0xFFE2E8F0),
            surfaceVariant = Color(0xFF2A3A4D),
            onSurfaceVariant = Color(0xFF94A3B8),
            outline = Color(0xFF3D4F5F),
            outlineVariant = Color(0xFF2A3A4D)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF1976D2),
            onPrimary = Color(0xFFF5F5F5),
            primaryContainer = Color(0xFFE3F2FD),
            onPrimaryContainer = Color(0xFF0D47A1),
            secondary = Color(0xFF388E3C),
            onSecondary = Color(0xFFF5F5F5),
            secondaryContainer = Color(0xFFE8F5E9),
            onSecondaryContainer = Color(0xFF1B5E20),
            background = Color(0xFFF8F9FA),
            onBackground = Color(0xFF333333),
            surface = Color(0xFFFAFBFC),
            onSurface = Color(0xFF333333),
            surfaceVariant = Color(0xFFF1F3F5),
            onSurfaceVariant = Color(0xFF5A6570),
            outline = Color(0xFFDDE1E6),
            outlineVariant = Color(0xFFE8EAED)
        )
    }
    
    MaterialTheme(colorScheme = softColorScheme) {
    val isDarkTheme = isSystemInDarkTheme()
    
    var hasChanges by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    
    // Track original settings to detect changes
    val originalSettings = remember { routeSettings }
    
    var currentMode by remember { mutableStateOf(routeSettings.currentMode) }
    var autoSwitch by remember { mutableStateOf(routeSettings.autoSwitch) }
    
    val workRoutes = remember { mutableStateListOf(*routeSettings.workMode.routes.toTypedArray()) }
    val vacationRoutes = remember { mutableStateListOf(*routeSettings.vacationMode.routes.toTypedArray()) }
    
    val workApplyDays = remember { mutableStateOf(routeSettings.workMode.applyDays) }
    val vacationApplyDays = remember { mutableStateOf(routeSettings.vacationMode.applyDays) }
    
    val currentRoutes = if (currentMode == ModeType.WORK_MODE) workRoutes else vacationRoutes
    val currentApplyDays = if (currentMode == ModeType.WORK_MODE) workApplyDays else vacationApplyDays
    
    // Check if settings have changed
    fun checkChanges(): Boolean {
        return currentMode != originalSettings.currentMode ||
               autoSwitch != originalSettings.autoSwitch ||
               workRoutes.toList() != originalSettings.workMode.routes ||
               vacationRoutes.toList() != originalSettings.vacationMode.routes ||
               workApplyDays.value != originalSettings.workMode.applyDays ||
               vacationApplyDays.value != originalSettings.vacationMode.applyDays
    }
    
    BackHandler(enabled = true) {
        hasChanges = checkChanges()
        if (hasChanges) {
            showSaveDialog = true
        } else {
            onDismiss()
        }
    }
    
    // 修复全面屏手势首次失效：通过 DisposableEffect 立即向 Activity 注册返回回调
    // 跳过 Compose BackHandler 的帧级延迟，确保覆盖层在进入瞬间就能响应返回手势
    //
    // R4 / H-12：**这段回调原先直接调 onDismiss()**，而它与上面那个负责
    // "保存更改？"确认的 BackHandler 注册在同一个 dispatcher 上、后注册先执行
    // （LIFO），于是手势返回时它先跑，未保存的修改被**静默丢弃**、确认弹窗
    // 根本不弹。它自己的注释写着"若已注册则由其接管"，但代码里没有任何接管逻辑。
    //
    // 修法：两条注册路径共用同一份判定逻辑（经 rememberUpdatedState 取最新闭包，
    // 避免 DisposableEffect(Unit) 捕获首帧的过期状态）。这样"谁先执行"不再影响结果。
    val onBackInvoked = rememberUpdatedState {
        hasChanges = checkChanges()
        if (hasChanges) {
            showSaveDialog = true
        } else {
            onDismiss()
        }
    }
    // 单独取值再 as?：原先写成 `LocalContext.current as ComponentActivity`，
    // 一旦宿主不是 ComponentActivity（Preview、或未来被放进别的容器）就是
    // ClassCastException 崩溃。类型不匹配时安静地跳过手动注册，BackHandler 仍在。
    val activity = LocalContext.current as? ComponentActivity
    DisposableEffect(Unit) {
        if (activity == null) {
            onDispose { }
        } else {
            val callback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    onBackInvoked.value()
                }
            }
            activity.onBackPressedDispatcher.addCallback(callback)
            onDispose { callback.remove() }
        }
    }
    
    val addressesForMode = remember(savedAddresses, currentRoutes.toList()) {
        savedAddresses.filter { addr ->
            currentRoutes.none { it.addressTag == addr.addressTag }
        }
    }
    
    var editingAddress by remember { mutableStateOf<SavedAddress?>(null) }
    var deletingAddress by remember { mutableStateOf<SavedAddress?>(null) }
    var customNameInput by remember { mutableStateOf("") }
    var addressInput by remember { mutableStateOf("") }
    
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var dragStartY by remember { mutableStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    val isDragging = draggingIndex != null
    
    val itemHeight = with(LocalDensity.current) { 80.dp.toPx() }
    
    // 当开始编辑时，初始化地址输入
    LaunchedEffect(editingAddress) {
        if (editingAddress != null && addressInput.isEmpty()) {
            addressInput = editingAddress!!.displayName
        }
    }
    
    if (editingAddress != null) {
        ModernEditDialog(
            address = editingAddress!!,
            customNameInput = customNameInput,
            addressInput = addressInput,
            onCustomNameChange = { customNameInput = it },
            onAddressChange = { addressInput = it },
            onDismiss = { 
                editingAddress = null
                addressInput = ""
            },
            onSave = {
                // 如果地址发生变化，更新所有相同标签的物品
                if (addressInput.isNotBlank() && addressInput != editingAddress!!.displayName) {
                    onAddressSave?.invoke(editingAddress!!.addressTag, addressInput)
                }
                onUpdateAddressCustomName(
                    editingAddress!!.id,
                    customNameInput.ifBlank { null }
                )
                editingAddress = null
                addressInput = ""
            }
        )
    }
    
    if (deletingAddress != null) {
        ModernDeleteDialog(
            address = deletingAddress!!,
            onDismiss = { deletingAddress = null },
            onConfirm = {
                onDeleteAddress(deletingAddress!!)
                deletingAddress = null
            }
        )
    }
    
    // Save changes dialog
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("保存更改") },
            text = { Text("您有未保存的更改，是否保存？") },
            confirmButton = {
                TextButton(onClick = {
                    val newSettings = RouteSettings(
                        workMode = RouteMode(
                            modeType = ModeType.WORK_MODE,
                            routes = workRoutes.toList(),
                            applyDays = workApplyDays.value.toSet()
                        ),
                        vacationMode = RouteMode(
                            modeType = ModeType.VACATION_MODE,
                            routes = vacationRoutes.toList(),
                            applyDays = vacationApplyDays.value.toSet()
                        ),
                        currentMode = currentMode,
                        autoSwitch = autoSwitch
                    )
                    onSave(newSettings)
                    showSaveDialog = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSaveDialog = false
                    onDismiss()
                }) {
                    Text("不保存")
                }
            }
        )
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "取件路径设置",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        hasChanges = checkChanges()
                        if (hasChanges) {
                            showSaveDialog = true
                        } else {
                            onDismiss()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                actions = {
                    TextButton(onClick = {
                        val newSettings = RouteSettings(
                            workMode = RouteMode(
                                modeType = ModeType.WORK_MODE,
                                routes = workRoutes.toList(),
                                applyDays = workApplyDays.value.toSet()
                            ),
                            vacationMode = RouteMode(
                                modeType = ModeType.VACATION_MODE,
                                routes = vacationRoutes.toList(),
                                applyDays = vacationApplyDays.value.toSet()
                            ),
                            currentMode = currentMode,
                            autoSwitch = autoSwitch
                        )
                        onSave(newSettings)
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("保存")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            state = lazyListState,
            userScrollEnabled = !isDragging
        ) {
            item {
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    ModernModeSelector(
                        currentMode = currentMode,
                        onModeChange = { currentMode = it },
                        isDarkTheme = isDarkTheme
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            item {
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + slideInVertically(
                        animationSpec = tween(delayMillis = 100)
                    ),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    ModernAutoSwitchSetting(
                        autoSwitch = autoSwitch,
                        onAutoSwitchChange = { autoSwitch = it },
                        isDarkTheme = isDarkTheme
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            item {
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + slideInVertically(
                        animationSpec = tween(delayMillis = 200)
                    ),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    ModernApplyDaysSetting(
                        applyDays = currentApplyDays.value,
                        modeType = currentMode,
                        onDaysChange = { currentApplyDays.value = it },
                        isDarkTheme = isDarkTheme
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "已添加的取件地点",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "(${currentRoutes.size})",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "长按拖拽排序 · 靠前的地点优先匹配",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            if (currentRoutes.isEmpty() && addressesForMode.isEmpty()) {
                item {
                    ModernEmptyRouteState()
                }
            } else {
                itemsIndexed(currentRoutes.toList()) { index, route ->
                    val savedAddr = savedAddresses.find { it.addressTag == route.addressTag }
                    
                    val isDragging = draggingIndex == index
                    val scale by animateFloatAsState(
                        targetValue = if (isDragging) 1.02f else 1f,
                        animationSpec = spring(stiffness = Spring.StiffnessHigh),
                        label = "scale"
                    )
                    
                    val elevation by animateFloatAsState(
                        targetValue = if (isDragging) 8f else 2f,
                        animationSpec = spring(stiffness = Spring.StiffnessHigh),
                        label = "elevation"
                    )
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isDragging) {
                                    Modifier
                                        .offset { IntOffset(0, dragOffsetY.roundToInt()) }
                                        .zIndex(1f)
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        ModernDraggableRouteItem(
                            route = route,
                            savedAddress = savedAddr,
                            index = index + 1,
                            isDragging = isDragging,
                            scale = scale,
                            elevation = elevation,
                            isDarkTheme = isDarkTheme,
                            onRemove = {
                                if (index < currentRoutes.size) {
                                    currentRoutes.removeAt(index)
                                }
                            },
                            onEdit = if (savedAddr != null) {
                                {
                                    editingAddress = savedAddr
                                    customNameInput = savedAddr.customDisplayName ?: ""
                                }
                            } else null,
                            onDragStart = { offset ->
                                draggingIndex = index
                                dragStartY = offset
                                dragOffsetY = 0f
                            },
                            onDrag = { dragAmount ->
                                dragOffsetY += dragAmount.y
                            },
                            onDragEnd = {
                                val offsetInItems = dragOffsetY / itemHeight
                                val targetPos = (index + offsetInItems).roundToInt()
                                    .coerceIn(0, currentRoutes.lastIndex)
                                
                                if (targetPos != index && targetPos in 0..currentRoutes.lastIndex) {
                                    val item = currentRoutes.removeAt(index)
                                    currentRoutes.add(targetPos, item)
                                    
                                    val updatedAddresses = currentRoutes.mapIndexed { idx, route ->
                                        savedAddresses.find { it.addressTag == route.addressTag }?.copy(
                                            sortOrder = idx
                                        ) ?: return@mapIndexed null
                                    }.filterNotNull()
                                    
                                    if (updatedAddresses.isNotEmpty()) {
                                        onUpdateAddressOrder(updatedAddresses)
                                    }
                                }
                                
                                draggingIndex = null
                                dragOffsetY = 0f
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                if (addressesForMode.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "可添加的取件地点",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "(${addressesForMode.size})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "点击 + 添加到取件路径",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    
                    itemsIndexed(addressesForMode) { _, address ->
                        ModernAddableAddressItem(
                            address = address,
                            isDarkTheme = isDarkTheme,
                            onAdd = {
                                val newRoute = PickupRoute(
                                    id = java.util.UUID.randomUUID().toString(),
                                    addressTag = address.addressTag,
                                    displayName = address.getDisplayText(),
                                    order = currentRoutes.size
                                )
                                currentRoutes.add(newRoute)
                            },
                            onEdit = {
                                editingAddress = address
                                customNameInput = address.customDisplayName ?: ""
                            },
                            onDelete = {
                                deletingAddress = address
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModernModeSelector(
    currentMode: ModeType,
    onModeChange: (ModeType) -> Unit,
    isDarkTheme: Boolean
) {
    Column {
        Text(
            text = "当前模式",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            SegmentedButton(
                selected = currentMode == ModeType.WORK_MODE,
                onClick = { onModeChange(ModeType.WORK_MODE) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = 0,
                    count = 2
                ),
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Work,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            ) {
                Text("上班模式")
            }
            
            SegmentedButton(
                selected = currentMode == ModeType.VACATION_MODE,
                onClick = { onModeChange(ModeType.VACATION_MODE) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = 1,
                    count = 2
                ),
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.BeachAccess,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            ) {
                Text("休假模式")
            }
        }
    }
}

@Composable
private fun ModernAutoSwitchSetting(
    autoSwitch: Boolean,
    onAutoSwitchChange: (Boolean) -> Unit,
    isDarkTheme: Boolean
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (isPressed) 4.dp else 2.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.08f),
                spotColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.08f)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        ),
        onClick = { onAutoSwitchChange(!autoSwitch) },
        interactionSource = interactionSource
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "自动切换模式",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (autoSwitch) "周末自动切至休假模式" else "手动切换上班/休假模式",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            
            Switch(
                checked = autoSwitch,
                onCheckedChange = onAutoSwitchChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    }
}

@Composable
private fun ModernApplyDaysSetting(
    applyDays: Set<Int>,
    modeType: ModeType,
    onDaysChange: (Set<Int>) -> Unit,
    isDarkTheme: Boolean
) {
    val dayNames = listOf("日", "一", "二", "三", "四", "五", "六")
    val modeName = if (modeType == ModeType.WORK_MODE) "上班" else "休假"
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.08f),
                spotColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.08f)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                text = "${modeName}模式生效日期",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    dayNames.forEachIndexed { index, dayName ->
                        ModernDayChip(
                            dayName = dayName,
                            isSelected = applyDays.contains(index),
                            isDarkTheme = isDarkTheme,
                            onClick = {
                                val newDays = if (applyDays.contains(index)) {
                                    applyDays - index
                                } else {
                                    applyDays + index
                                }
                                onDaysChange(newDays)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernDayChip(
    dayName: String,
    isSelected: Boolean,
    isDarkTheme: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.1f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "dayChipScale"
    )
    
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    
    Box(
        modifier = Modifier
            .size(34.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = dayName,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                   else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModernDraggableRouteItem(
    route: PickupRoute,
    savedAddress: SavedAddress?,
    index: Int,
    isDragging: Boolean,
    scale: Float,
    elevation: Float,
    isDarkTheme: Boolean,
    onRemove: () -> Unit,
    onEdit: (() -> Unit)?,
    onDragStart: (Float) -> Unit,
    onDrag: (dragAmount: Offset) -> Unit,
    onDragEnd: () -> Unit
) {
    val backgroundColor = if (isDragging) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    
    val borderColor = if (isDragging) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    } else {
        Color.Transparent
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .shadow(
                elevation = elevation.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.08f),
                spotColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.08f)
            )
            .border(
                width = if (isDragging) 1.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDragging) 4.dp else 1.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(backgroundColor)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        if (isDragging) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                onDragStart(offset.y)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount)
                            },
                            onDragEnd = {
                                onDragEnd()
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.DragHandle,
                    contentDescription = "拖拽排序",
                    tint = if (isDragging) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(10.dp))
            
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        if (isDragging) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = index.toString(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDragging) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    }
                )
            }
            
            Spacer(modifier = Modifier.width(10.dp))
            
            Text(
                text = savedAddress?.getDisplayText() ?: route.displayName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            
            if (onEdit != null) {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "编辑名称",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Rounded.RemoveCircleOutline,
                    contentDescription = "从路径移出",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ModernAddableAddressItem(
    address: SavedAddress,
    isDarkTheme: Boolean,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "addItemScale"
    )
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.08f),
                spotColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.08f)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    )
                    .border(
                        1.5.dp,
                        MaterialTheme.colorScheme.primary,
                        CircleShape
                    )
                    .clickable(onClick = onAdd),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "添加",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(10.dp))
            
            Text(
                text = address.getDisplayText(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = "编辑名称",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "彻底删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ModernEmptyRouteState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "📦",
            fontSize = 56.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "暂无取件地点",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "导入短信后将自动识别并保存地址",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ModernEditDialog(
    address: SavedAddress,
    customNameInput: String,
    addressInput: String,
    onCustomNameChange: (String) -> Unit,
    onAddressChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = "编辑地址",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "原始地址: ${address.displayName}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = addressInput,
                    onValueChange = onAddressChange,
                    label = { Text("取件地址") },
                    placeholder = { Text("如: 示例小区菜鸟驿站") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = customNameInput,
                    onValueChange = onCustomNameChange,
                    label = { Text("自定义名称") },
                    placeholder = { Text("如: 幸福驿站、5号楼") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "留空则使用原始地址名称",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun ModernDeleteDialog(
    address: SavedAddress,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = "彻底删除地址",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text("确定要彻底删除此地址吗？")
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = address.getDisplayText(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "删除后，该地址将从数据库中永久移除，但不会影响已有的取件记录。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = "彻底删除",
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
