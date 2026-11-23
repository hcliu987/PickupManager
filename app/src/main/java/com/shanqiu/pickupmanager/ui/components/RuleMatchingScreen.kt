/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.ui.components

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanqiu.pickupmanager.data.model.PickupItem
import com.shanqiu.pickupmanager.util.AddressTag
import com.shanqiu.pickupmanager.util.CourierCompanyResolver
import com.shanqiu.pickupmanager.util.PickupCodeExtractorV3
import com.shanqiu.pickupmanager.util.RuleBasedPickupParser
import com.shanqiu.pickupmanager.util.SelfLearningManager
import com.shanqiu.pickupmanager.util.SmsPatternLearner

/**
 * 保存动作类型
 *
 * 关键修复：区分"插入"和"覆盖"两种语义
 *
 * INSERT：
 *   - 第一次提交时使用
 *   - 父级调用 addPickupItemWithDedup()，数据库会查重
 *   - 若重复，返回 Duplicate
 *   - 编辑模式下：弹出"确认覆盖"对话框
 *
 * OVERWRITE：
 *   - 用户在"确认覆盖"对话框中点击"确认覆盖"时使用
 *   - 父级调用 overwritePickupItem()，直接更新原记录
 *   - 不再做查重检查
 */
enum class SaveAction { INSERT, OVERWRITE }

/** 本文件日志标签（R4 / M-09 起用于记录"保存失败"这一类此前被静默吞掉的结局）。 */
private const val TAG_RMS = "RuleMatchingScreen"

/**
 * `onSave` 的**三态**结果（R4 / M-09）。
 *
 * 原先这个回调是 `(Boolean, String?)`，用两个值编码三种结局：
 *
 * | 结局 | 原编码 |
 * |---|---|
 * | `AddPickupResult.Inserted` | `(true, null)` |
 * | `AddPickupResult.Duplicate` | `(false, 已存在的取件码)` |
 * | `AddPickupResult.Error` | `(false, null)` |
 *
 * 而调用点（本文件原来的 `if (!success && dupCode != null)`）只区分
 * "是否 duplicate"，于是 `Error` 落到最后那个 `else` ——
 * **数据库写失败被当成保存成功**：弹窗正常关闭，用户以为存下了；
 * 更糟的是这次失败还会被写进 `SelfLearningManager.recordCorrection()`，
 * 当作"用户认可的正确样例"参与后续置信度计算。
 *
 * 换成语义显式、可穷举的枚举后，`when` 分支由编译器强制覆盖三种结局，
 * 这类"失败伪装成成功"不会再退化回来。
 */
enum class SaveOutcome { SUCCESS, DUPLICATE, FAILED }

/**
 * 规则匹配全屏页面
 * 
 * 功能：
 * - 粘贴短信进行识别分析
 * - 学习模式自动提取规则
 * - 显示识别结果（取件码、地址、快递公司）
 * - 置信度评估与展示
 * - 用户手动编辑纠错
 * - 自动学习用户纠错
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleMatchingScreen(
    savedAddresses: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (PickupItem, action: SaveAction, onResult: (SaveOutcome, String?) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val learningManager = remember { SelfLearningManager.getInstance(context) }
    
    var smsContent by remember { mutableStateOf("") }
    var ruleResult by remember { mutableStateOf<RuleBasedPickupParser.ParseResult?>(null) }
    // 与自动入库共用同一套快递公司规范器：只显示真实承运方，不显示「兔喜生活」这类驿站品牌
    var resolvedCourier by remember { mutableStateOf("") }
    var confidenceResult by remember { mutableStateOf<SelfLearningManager.ConfidenceResult?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var showResult by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    
    // 学习模式状态
    var isLearningMode by remember { mutableStateOf(false) }
    var learningResult by remember { mutableStateOf<SmsPatternLearner.LearningResult?>(null) }
    var isLearning by remember { mutableStateOf(false) }
    var showManualRuleDialog by remember { mutableStateOf(false) }
    var manualCodeInput by remember { mutableStateOf("") }
    var manualRuleNameInput by remember { mutableStateOf("") }
    
    var editedPickupCode by remember { mutableStateOf("") }
    var editedAddress by remember { mutableStateOf("") }
    var editedCourier by remember { mutableStateOf("") }
    
    var showAddressSuggestions by remember { mutableStateOf(false) }
    var showUncertainNotice by remember { mutableStateOf(false) }

    // 关键修复：重复取件码提示状态
    // 关键修复：RuleMatchingScreen 历史 bug 修复
    // 之前用 System.currentTimeMillis() 作为 receiveTime，导致同一条短信在
    // SmsReceiver 接收（用真实时间戳）和本界面手动添加（用当前时间）两条路径下
    // 产生两条重复卡片。
    // 修复后：通过 onResult 回调让父级完成去重检查，本界面根据回调结果显示提示
    var duplicateCode by remember { mutableStateOf<String?>(null) }
    var pendingCodeCount by remember { mutableStateOf(0) }
    var completedCodeCount by remember { mutableStateOf(0) }
    var hasAnyDuplicate by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    // R4 / M-09：本批次里有几条**写库失败**（SaveOutcome.FAILED）。
    // 与"重复"分开计数 —— 重复是用户可决策的（覆盖/放弃），失败只能重试，
    // 而且必须让用户看见，不能再静默关窗。
    var saveFailureCount by remember { mutableStateOf(0) }

    // 关键修复：编辑模式下的待覆盖项队列
    // 当用户在编辑模式下点击保存且检测到重复时，弹出"确认覆盖"对话框
    // 用户确认后才执行覆盖操作（更新原记录，而非插入新行）
    var overwriteQueue by remember { mutableStateOf<List<PickupItem>>(emptyList()) }
    var showOverwriteDialog by remember { mutableStateOf(false) }

    // 关键修复：学习模式编辑状态
    // 背景：学习模式之前只有"保存规则"和"重新学习"两个按钮，
    // 当系统识别地址错误时，用户无法纠正，只能反复点"重新学习"却得到相同错误结果。
    // 现在添加"编辑"按钮，可手动调整取件码格式、驿站名称、快递公司、地址信息后保存。
    var isLearningEditing by remember { mutableStateOf(false) }
    var editedPickupCodePattern by remember { mutableStateOf("") }
    var editedStationName by remember { mutableStateOf("") }
    var editedCompanyName by remember { mutableStateOf("") }
    var editedAddressPattern by remember { mutableStateOf("") }
    
    val scrollState = rememberScrollState()
    val stats = learningManager.getLearningStats()
    
    // 处理返回键
    BackHandler { onDismiss() }
    
    // 修复全面屏手势首次失效：通过 DisposableEffect 立即向 Activity 注册返回回调
    // 跳过 Compose BackHandler 的帧级延迟，确保覆盖层在进入瞬间就能响应返回手势
    val activity = context as ComponentActivity
    DisposableEffect(Unit) {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                onDismiss()
            }
        }
        activity.onBackPressedDispatcher.addCallback(callback)
        onDispose { callback.remove() }
    }
    
    LaunchedEffect(ruleResult) {
        if (ruleResult != null) {
            showResult = true
            editedPickupCode = ruleResult!!.codes.joinToString(", ")
            editedAddress = ruleResult!!.address.firstOrNull() ?: ""
            editedCourier = ruleResult!!.company.firstOrNull() ?: ""
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "规则匹配",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "已学习 ${stats["totalCorrections"]} 次纠错, ${stats["totalRules"]} 条规则",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            
            // 模式切换：分析模式 / 学习模式
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FilterChip(
                        selected = !isLearningMode,
                        onClick = {
                            isLearningMode = false
                            learningResult = null
                        },
                        label = { Text("分析模式", fontSize = 15.sp, fontWeight = FontWeight.Medium) },
                        modifier = Modifier.height(40.dp)
                    )

                    FilterChip(
                        selected = isLearningMode,
                        onClick = {
                            isLearningMode = true
                            ruleResult = null
                            showResult = false
                        },
                        label = { Text("学习模式", fontSize = 15.sp, fontWeight = FontWeight.Medium) },
                        modifier = Modifier.height(40.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 输入框
            OutlinedTextField(
                value = smsContent,
                onValueChange = {
                    smsContent = it
                    ruleResult = null
                    showResult = false
                    isEditing = false
                    showUncertainNotice = false
                    learningResult = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                placeholder = {
                    Text(
                        text = "请粘贴短信内容...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)
                ),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 20.sp
                )
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 分析/学习按钮
            var isAnalyzePressed by remember { mutableStateOf(false) }
            val analyzeScale by animateFloatAsState(
                targetValue = if (isAnalyzePressed) 0.96f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "analyzeScale"
            )
            
            Button(
                onClick = {
                    if (smsContent.isNotBlank()) {
                        if (isLearningMode) {
                            isLearning = true
                            // 关键修复：学习模式也使用 RuleBasedPickupParser 解析
                            // 之前用 SmsPatternLearner.learnFromSms() 有独立的地址正则，
                            // 导致跟分析模式结果不一致，且学习结果无法影响 RuleBasedPickupParser
                            val parseResult = RuleBasedPickupParser.parse(smsContent)
                            // 转换为 LearningResult 格式
                            val result = if (parseResult.codes.isNotEmpty()) {
                                SmsPatternLearner.LearningResult(
                                    success = true,
                                    pickupCodePattern = parseResult.codes.firstOrNull(),
                                    stationName = null,
                                    companyName = parseResult.company.firstOrNull(),
                                    addressPattern = parseResult.address.firstOrNull(),
                                    confidence = if (parseResult.codes.isNotEmpty()) 0.8f else 0.3f,
                                    message = "解析完成"
                                )
                            } else {
                                // 降级到 SmsPatternLearner
                                SmsPatternLearner.learnFromSms(smsContent)
                            }
                            learningResult = result
                            isLearning = false
                        } else {
                            isAnalyzing = true
                            val result = RuleBasedPickupParser.parse(smsContent)
                            ruleResult = result
                            // 统一到入库层口径：全文优先识别真实承运方，驿站签名不算
                            val company = CourierCompanyResolver.resolve(smsContent, result.company)
                            resolvedCourier = company
                            editedCourier = company
                            val confidence = learningManager.evaluateConfidence(
                                result.codes,
                                result.address.firstOrNull(),
                                company
                            )
                            confidenceResult = confidence
                            showUncertainNotice = confidence.needsConfirmation
                            isAnalyzing = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .scale(analyzeScale),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                enabled = smsContent.isNotBlank() && !isAnalyzing && !isLearning
            ) {
                if (isAnalyzing || isLearning) {
                    Text(
                        text = if (isLearning) "学习中..." else "分析中...",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Icon(
                        imageVector = if (isLearning) Icons.Outlined.Analytics else Icons.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isLearning) "开始学习" else "开始分析",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            
            // 学习模式结果展示
            // 使用 remember 保存上一次非空值，避免动画期间空指针
            var lastLearningResult by remember { mutableStateOf<SmsPatternLearner.LearningResult?>(null) }
            LaunchedEffect(learningResult) {
                if (learningResult != null) {
                    lastLearningResult = learningResult
                }
            }
            
            AnimatedVisibility(
                visible = isLearningMode && learningResult != null,
                enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    val learnResult = lastLearningResult ?: return@AnimatedVisibility
                    
                    // 学习结果卡片
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = if (learnResult.success) 
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        else 
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (learnResult.success) 
                                        Icons.Outlined.CheckCircle 
                                    else 
                                        Icons.AutoMirrored.Outlined.HelpOutline,
                                    contentDescription = null,
                                    tint = if (learnResult.success) 
                                        MaterialTheme.colorScheme.primary 
                                    else 
                                        MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (learnResult.success) "学习成功" else "需要手动指定",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (learnResult.success) 
                                        MaterialTheme.colorScheme.primary 
                                    else 
                                        MaterialTheme.colorScheme.error
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))

                            // 关键修复：编辑模式下显示可编辑 TextField，否则显示只读 Row
                            //
                            // R4 / L-04：这里原来四个字段都写成
                            // `if (learnResult.xxx != null || true)` —— 一个永真条件。
                            // 拆开看是两件事叠在一起：
                            // ① `if` 本身是死结构（条件恒真）；
                            // ② 留在里面的 `!= null` 判断是**假的**，读者会以为
                            //    "只有解析器学到了这个字段才允许编辑"。
                            // 实际语义很简单：编辑态的意图就是让用户补齐、纠正任何一个字段，
                            // 所以四个字段**恒定渲染**。直接去掉永真 `if`，把意图写进注释。
                            if (isLearningEditing) {
                                // 编辑模式：四个字段恒定可编辑（缺失的也允许用户手填）
                                EditableLearningField(
                                    label = "取件码格式",
                                    value = editedPickupCodePattern,
                                    icon = Icons.Outlined.Edit,
                                    onValueChange = { editedPickupCodePattern = it }
                                )

                                EditableLearningField(
                                    label = "驿站名称",
                                    value = editedStationName,
                                    icon = Icons.Outlined.LocationOn,
                                    onValueChange = { editedStationName = it }
                                )

                                EditableLearningField(
                                    label = "快递公司",
                                    value = editedCompanyName,
                                    icon = Icons.Outlined.Analytics,
                                    onValueChange = { editedCompanyName = it }
                                )

                                EditableLearningField(
                                    label = "地址信息",
                                    value = editedAddressPattern,
                                    icon = Icons.Outlined.LocationOn,
                                    onValueChange = { editedAddressPattern = it }
                                )
                            } else {
                                // 只读模式：原有的 LearningResultRow
                                if (learnResult.pickupCodePattern != null) {
                                    LearningResultRow(
                                        label = "取件码格式",
                                        value = learnResult.pickupCodePattern,
                                        icon = Icons.Outlined.Edit
                                    )
                                }

                                if (learnResult.stationName != null) {
                                    LearningResultRow(
                                        label = "驿站名称",
                                        value = learnResult.stationName,
                                        icon = Icons.Outlined.LocationOn
                                    )
                                }

                                if (learnResult.companyName != null) {
                                    LearningResultRow(
                                        label = "快递公司",
                                        value = learnResult.companyName,
                                        icon = Icons.Outlined.Analytics
                                    )
                                }

                                if (learnResult.addressPattern != null) {
                                    LearningResultRow(
                                        label = "地址信息",
                                        value = learnResult.addressPattern,
                                        icon = Icons.Outlined.LocationOn
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            ConfidenceIndicator(confidence = learnResult.confidence)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 操作按钮（纯文字，无图标）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 关键修复：编辑/取消编辑切换按钮（纯文字）
                        OutlinedButton(
                            onClick = {
                                if (isLearningEditing) {
                                    // 取消编辑：恢复只读模式
                                    isLearningEditing = false
                                } else {
                                    // 进入编辑模式：把当前结果填入编辑字段
                                    editedPickupCodePattern = learnResult.pickupCodePattern ?: ""
                                    editedStationName = learnResult.stationName ?: ""
                                    editedCompanyName = learnResult.companyName ?: ""
                                    editedAddressPattern = learnResult.addressPattern ?: ""
                                    isLearningEditing = true
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                text = if (isLearningEditing) "取消编辑" else "编辑",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        if (learnResult.success) {
                            // 保存规则按钮（纯文字）
                            Button(
                                onClick = {
                                    // 关键修复：编辑模式下使用编辑后的值保存规则
                                    val finalCode = if (isLearningEditing) editedPickupCodePattern else (learnResult.pickupCodePattern ?: "")
                                    val finalStation = if (isLearningEditing) editedStationName else (learnResult.stationName ?: "")
                                    val finalCompany = if (isLearningEditing) editedCompanyName else (learnResult.companyName ?: "")
                                    val finalAddress = if (isLearningEditing) editedAddressPattern else (learnResult.addressPattern ?: "")

                                    // 1. 创建取件码规则并添加到 CustomRuleEngine
                                    val rule = SmsPatternLearner.createCustomRule(
                                        ruleName = finalStation.ifBlank { finalCompany.ifBlank { "自定义规则" } },
                                        smsContent = smsContent,
                                        pickupCode = finalCode
                                    )
                                    if (rule != null) {
                                        PickupCodeExtractorV3.CustomRuleEngine.addRule(rule)
                                    }

                                    // 2. 记录用户学习样本
                                    learningManager.addUserSample(
                                        sms = smsContent,
                                        codes = listOfNotNull(finalCode.ifBlank { null }),
                                        address = finalAddress.ifBlank { null },
                                        company = finalCompany.ifBlank { null }
                                    )

                                    // 3. 关键修复：如果用户纠正了地址，保存地址覆盖
                                    //    这样下次 RuleBasedPickupParser 解析同源短信时
                                    //    会返回纠正后的地址而非正则匹配的错误地址
                                    val originalAddress = learnResult.addressPattern ?: ""
                                    if (isLearningEditing && originalAddress.isNotBlank() && finalAddress.isNotBlank() && originalAddress != finalAddress) {
                                        RuleBasedPickupParser.saveAddressOverride(context, originalAddress, finalAddress)
                                    }

                                    // 4. Toast 提示（解决"无任何操作成功或失败提示"问题）
                                    if (rule != null) {
                                        android.widget.Toast.makeText(
                                            context,
                                            if (isLearningEditing && originalAddress != finalAddress) "规则已保存，地址覆盖已更新"
                                            else "规则保存成功",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        android.widget.Toast.makeText(
                                            context,
                                            "取件码规则创建失败，请检查格式",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }

                                    isLearningEditing = false
                                    onDismiss()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("保存规则", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            }
                        } else {
                            // 手动创建按钮（纯文字）
                            OutlinedButton(
                                onClick = { showManualRuleDialog = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("手动创建", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            }
                        }

                        // 重新学习按钮（保持纯文字）
                        OutlinedButton(
                            onClick = {
                                // 关键修复：重新学习时退出编辑模式
                                isLearningEditing = false
                                learningResult = null
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("重新学习", fontSize = 15.sp)
                        }
                    }
                }
            }
            
            // 分析模式结果展示
            // 使用 remember 保存上一次非空值，避免动画期间空指针
            var lastRuleResult by remember { mutableStateOf<RuleBasedPickupParser.ParseResult?>(null) }
            var lastConfidenceResult by remember { mutableStateOf<SelfLearningManager.ConfidenceResult?>(null) }
            LaunchedEffect(ruleResult) {
                if (ruleResult != null) {
                    lastRuleResult = ruleResult
                    lastConfidenceResult = confidenceResult
                }
            }
            
            AnimatedVisibility(
                visible = !isLearningMode && showResult && ruleResult != null,
                enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    val result = lastRuleResult ?: return@AnimatedVisibility
                    val capturedConfidenceResult = lastConfidenceResult
                    val isRecognized = result.isPickupSms && result.codes.isNotEmpty()
                    
                    // 置信度徽章
                    ConfidenceBadge(
                        isRecognized = isRecognized,
                        hasNoCode = result.hasNoCode,
                        confidence = capturedConfidenceResult,
                        codesCount = result.codes.size
                    )
                    
                    if (showUncertainNotice) {
                        Text(
                            text = "识别结果不确定, 请核对后保存",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    if (isRecognized || result.isPickupSms) {
                        ResultDetailCard(
                            pickupCode = if (isEditing) editedPickupCode else result.codes.joinToString(", "),
                            address = if (isEditing) editedAddress else result.address.firstOrNull() ?: "",
                            courierCompany = if (isEditing) editedCourier else resolvedCourier,
                            trackingNumber = result.trackingNumber,
                            packageCount = result.packageCount,
                            hasNoCode = result.hasNoCode,
                            isEditing = isEditing,
                            onPickupCodeChange = { editedPickupCode = it },
                            onAddressChange = {
                                editedAddress = it
                                showAddressSuggestions = it.isNotEmpty()
                            },
                            onCourierChange = { editedCourier = it },
                            savedAddresses = savedAddresses,
                            showAddressSuggestions = showAddressSuggestions && isEditing,
                            onAddressSuggestionClick = { address ->
                                editedAddress = address
                                showAddressSuggestions = false
                            },
                            onDismissAddressSuggestions = {
                                showAddressSuggestions = false
                            }
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // 操作按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (!isEditing) {
                                OutlinedButton(
                                    onClick = {
                                        isEditing = true
                                        showAddressSuggestions = false
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("编辑", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            
                            var isSavePressed by remember { mutableStateOf(false) }
                            val saveScale by animateFloatAsState(
                                targetValue = if (isSavePressed) 0.96f else 1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                ),
                                label = "saveScale"
                            )
                            
                            val finalPickupCode = if (isEditing) editedPickupCode else result.codes.joinToString(", ")
                            val finalAddress = if (isEditing) editedAddress else result.address.firstOrNull() ?: ""
                            val finalCourier = if (isEditing) editedCourier else resolvedCourier
                            
                            Button(
                                onClick = {
                                    if (finalPickupCode.isNotBlank() && !isSaving) {
                                        val codeList = finalPickupCode
                                            .split(",", "，", "、")
                                            .map { it.trim() }
                                            .filter { it.isNotEmpty() }

                                        if (isEditing) {
                                            val originalCodes = result.codes
                                            val correctedCodes = codeList
                                            if (originalCodes != correctedCodes || editedAddress != (result.address.firstOrNull() ?: "") || editedCourier != resolvedCourier) {
                                                learningManager.recordCorrection(
                                                    SelfLearningManager.CorrectionRecord(
                                                        timestamp = System.currentTimeMillis(),
                                                        originalSms = smsContent,
                                                        predictedCodes = originalCodes,
                                                        correctedCodes = correctedCodes,
                                                        predictedAddress = result.address.firstOrNull(),
                                                        correctedAddress = editedAddress.ifBlank { null },
                                                        predictedCompany = result.company.firstOrNull(),
                                                        correctedCompany = editedCourier.ifBlank { null }
                                                    )
                                                )
                                            }
                                        }

                                        // R4 / M-09：三态结果分别处理，失败不再伪装成成功
                                        // 每次 onSave 回调更新计数器，全部完成后才 dismiss
                                        // SUCCESS    → 计入成功
                                        // DUPLICATE  → 计入重复，编辑模式下排队待覆盖
                                        // FAILED     → 计入失败，绝不 dismiss、绝不写学习样本
                                        isSaving = true
                                        pendingCodeCount = codeList.size
                                        completedCodeCount = 0
                                        hasAnyDuplicate = false
                                        saveFailureCount = 0
                                        duplicateCode = null
                                        overwriteQueue = emptyList()

                                        for (code in codeList) {
                                            val item = PickupItem(
                                                pickupCode = code,
                                                address = finalAddress,
                                                addressTag = AddressTag.of(finalAddress),
                                                courierCompany = finalCourier,
                                                rawSms = smsContent,
                                                receiveTime = System.currentTimeMillis(),
                                                expireTime = System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000L,
                                                status = PickupItem.STATUS_PENDING
                                            )
                                            onSave(item, SaveAction.INSERT) { outcome, dupCode ->
                                                completedCodeCount++
                                                when (outcome) {
                                                    SaveOutcome.SUCCESS -> Unit
                                                    SaveOutcome.FAILED -> {
                                                        saveFailureCount++
                                                    }
                                                    SaveOutcome.DUPLICATE -> {
                                                        hasAnyDuplicate = true
                                                        if (duplicateCode == null) {
                                                            duplicateCode = dupCode
                                                        }
                                                        // 编辑模式下：把待覆盖项加入队列，等所有 onSave
                                                        // 回调完成后弹"确认覆盖"对话框
                                                        if (isEditing) {
                                                            overwriteQueue = overwriteQueue + item
                                                        }
                                                    }
                                                }
                                                // 全部处理完毕后再决定是否 dismiss
                                                if (completedCodeCount >= pendingCodeCount) {
                                                    isSaving = false
                                                    when {
                                                        // 写库失败优先：既不能关窗，也不能记学习样本
                                                        saveFailureCount > 0 -> {
                                                            Log.w(
                                                                TAG_RMS,
                                                                "保存失败 $saveFailureCount/${pendingCodeCount} 条，保持界面打开并提示用户"
                                                            )
                                                        }
                                                        !hasAnyDuplicate -> {
                                                            learningManager.recordCorrection(
                                                                SelfLearningManager.CorrectionRecord(
                                                                    timestamp = System.currentTimeMillis(),
                                                                    originalSms = smsContent,
                                                                    predictedCodes = result.codes,
                                                                    correctedCodes = codeList,
                                                                    predictedAddress = result.address.firstOrNull(),
                                                                    correctedAddress = finalAddress.ifBlank { null },
                                                                    predictedCompany = result.company.firstOrNull(),
                                                                    correctedCompany = finalCourier.ifBlank { null }
                                                                )
                                                            )
                                                            onDismiss()
                                                        }
                                                        isEditing && overwriteQueue.isNotEmpty() -> {
                                                            // 编辑模式 + 有重复：弹出"确认覆盖"对话框
                                                            showOverwriteDialog = true
                                                        }
                                                    }
                                                    // 非编辑模式 + 有重复：由 duplicateCode 触发 AlertDialog
                                                }
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .scale(saveScale),
                                shape = RoundedCornerShape(14.dp),
                                enabled = finalPickupCode.isNotBlank() && !isSaving
                            ) {
                                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isEditing) "保存并学习" else "确认保存",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        
                        if (isEditing) {
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            TextButton(
                                onClick = {
                                    isEditing = false
                                    editedPickupCode = result.codes.joinToString(", ")
                                    editedAddress = result.address.firstOrNull() ?: ""
                                    editedCourier = resolvedCourier
                                },
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Text("取消编辑", fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // 关键修复：重复取件码提示对话框（仅在非编辑模式显示）
    // 当 RuleMatchingScreen 的 onSave 回调检测到任一取件码已存在时显示
    // 告知用户该取件码已在数据库中，避免用户误以为"没保存成功"
    // 编辑模式下：走"确认覆盖"对话框，不显示此框
    if (duplicateCode != null && !isEditing && saveFailureCount == 0) {
        AlertDialog(
            onDismissRequest = {
                duplicateCode = null
                hasAnyDuplicate = false
                onDismiss()
            },
            icon = {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text("取件码已存在", fontWeight = FontWeight.SemiBold)
            },
            text = {
                Text("取件码 \"${duplicateCode}\" 已经在列表中了。\n\n为避免重复卡片，未添加新记录。\n如果你想更新原记录的地址或快递公司信息，请先在主列表中点击该卡片进行编辑。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        duplicateCode = null
                        hasAnyDuplicate = false
                        onDismiss()
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("我知道了")
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }

    // R4 / M-09：写库失败必须让用户看见。
    //
    // 与"取件码已存在"是两回事：重复是用户可决策的（覆盖 / 放弃），
    // 失败只能重试。这里刻意**不关窗** —— 用户手工编辑的地址、快递公司
    // 还在输入框里，点掉提示就能直接再按一次保存。
    //
    // 修这条之前的行为是：`AddPickupResult.Error` → `(false, null)` →
    // 既不是"重复"也不进失败分支 → 直接 `onDismiss()` 并写一条学习样本，
    // 用户完全无从知晓记录没有落库。
    if (saveFailureCount > 0) {
        AlertDialog(
            onDismissRequest = { saveFailureCount = 0 },
            icon = {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text("保存失败", fontWeight = FontWeight.SemiBold)
            },
            text = {
                Text(
                    "有 $saveFailureCount 条记录没有写入数据库。\n\n" +
                    "你编辑的内容仍然在本页面上，本次也不会被记入学习样本。" +
                    "请直接重新点击保存；若反复失败，请检查设备存储空间是否已满。"
                )
            },
            confirmButton = {
                Button(
                    onClick = { saveFailureCount = 0 },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("知道了")
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }

    // 关键修复：编辑模式下的"确认覆盖"对话框
    // 当用户在编辑模式下手动确认修改结果，且与现有取件码重复时显示
    // 用户点击"确认覆盖"：用手动编辑的新数据更新原记录
    // 用户点击"取消"：不做任何修改，关闭弹窗
    if (showOverwriteDialog && overwriteQueue.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {
                showOverwriteDialog = false
                overwriteQueue = emptyList()
                duplicateCode = null
                hasAnyDuplicate = false
            },
            icon = {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text("取件码已存在", fontWeight = FontWeight.SemiBold)
            },
            text = {
                val firstItem = overwriteQueue.first()
                Text(
                    "取件码 \"${firstItem.pickupCode}\" 已经在列表中了。\n\n" +
                    "你本次手动编辑的内容（如地址、快递公司）将覆盖原记录，保留原始的入库时间、取件状态等。\n" +
                    "你的学习记录也会被保存。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        // 关键修复：执行覆盖操作
                        // 把队列中所有项目通过 onSave 重新提交，但此时父级应当用覆盖而非插入
                        // 这里改用特殊标记，告知父级用 overwritePickupItem
                        val queueCopy = overwriteQueue
                        showOverwriteDialog = false
                        overwriteQueue = emptyList()
                        duplicateCode = null
                        hasAnyDuplicate = false

                        // 用 isOverwrite=true 标记回调：父级应当执行覆盖
                        // 但当前 onSave 签名只支持 insert，所以这里再次走 onSave 路径
                        // 实际实现：在 MainScreen 的 onSave 回调中根据这个标志调用
                        // viewModel.overwritePickupItem 而非 addPickupItemWithDedup
                        isSaving = true
                        pendingCodeCount = queueCopy.size
                        completedCodeCount = 0
                        saveFailureCount = 0

                        for (item in queueCopy) {
                            onSave(item, SaveAction.OVERWRITE) { outcome, _ ->
                                completedCodeCount++
                                // R4 / M-09：覆盖路径同样区分"成功"与"写库失败"。
                                // 原先只看 success，失败时既不关窗也不提示，用户会以为
                                // 覆盖成功了；现在失败会计数并在下方弹出重试提示。
                                if (outcome == SaveOutcome.FAILED) {
                                    saveFailureCount++
                                }
                                if (completedCodeCount >= pendingCodeCount) {
                                    isSaving = false
                                    if (saveFailureCount == 0 && outcome == SaveOutcome.SUCCESS) {
                                        onDismiss()
                                    }
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("确认覆盖")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showOverwriteDialog = false
                        overwriteQueue = emptyList()
                        duplicateCode = null
                        hasAnyDuplicate = false
                    }
                ) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }

    // 手动创建规则弹窗
    if (showManualRuleDialog) {
        ManualRuleDialog(
            smsContent = smsContent,
            onDismiss = { showManualRuleDialog = false },
            onSave = { ruleName, pattern ->
                val rule = PickupCodeExtractorV3.CustomRule(
                    id = "manual_${System.currentTimeMillis()}",
                    name = ruleName,
                    pattern = pattern,
                    priority = 100,
                    isEnabled = true
                )
                PickupCodeExtractorV3.CustomRuleEngine.addRule(rule)
                showManualRuleDialog = false
                onDismiss()
            }
        )
    }
}

@Composable
private fun LearningResultRow(
    label: String,
    value: String,
    icon: ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(70.dp)
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 关键修复：学习模式可编辑字段
 *
 * 背景：之前学习模式只能显示识别结果，无法纠正。导致地址识别错误时，
 * 用户只能反复点"重新学习"却得到相同错误。
 *
 * 解决：在编辑模式下用 OutlinedTextField 替代 LearningResultRow，
 * 让用户能直接修改识别结果后再保存。
 */
@Composable
private fun EditableLearningField(
    label: String,
    value: String,
    icon: ImageVector,
    onValueChange: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(70.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            ),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )
    }
}

@Composable
private fun ConfidenceIndicator(confidence: Float) {
    val percentage = (confidence * 100).toInt()
    val color = when {
        percentage >= 80 -> MaterialTheme.colorScheme.primary
        percentage >= 50 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "置信度",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "$percentage%",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { confidence },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
private fun ConfidenceBadge(
    isRecognized: Boolean,
    hasNoCode: Boolean,
    confidence: SelfLearningManager.ConfidenceResult?,
    codesCount: Int
) {
    val (icon, color, label) = when {
        hasNoCode -> Triple(
            Icons.Outlined.Analytics,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "无取件码"
        )
        isRecognized -> {
            val confPct = ((confidence?.overallConfidence ?: 0f) * 100).toInt()
            val confLabel = when {
                confPct >= 90 -> "高"
                confPct >= 70 -> "中"
                else -> "低"
            }
            Triple(
                Icons.Outlined.CheckCircle,
                MaterialTheme.colorScheme.primary,
                "识别成功 · 置信度$confLabel(${confPct}%) · ${codesCount}个码"
            )
        }
        codesCount > 0 -> Triple(
            Icons.Rounded.Warning,
            MaterialTheme.colorScheme.tertiary,
            "部分识别 · ${codesCount}个码"
        )
        else -> Triple(
            Icons.Outlined.ErrorOutline,
            MaterialTheme.colorScheme.error,
            "未能识别"
        )
    }
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
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

@Composable
private fun ResultDetailCard(
    pickupCode: String,
    address: String,
    courierCompany: String,
    trackingNumber: String?,
    packageCount: Int?,
    hasNoCode: Boolean,
    isEditing: Boolean,
    onPickupCodeChange: (String) -> Unit,
    onAddressChange: (String) -> Unit,
    onCourierChange: (String) -> Unit,
    savedAddresses: List<String>,
    showAddressSuggestions: Boolean,
    onAddressSuggestionClick: (String) -> Unit,
    onDismissAddressSuggestions: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            if (isEditing) {
                OutlinedTextField(
                    value = pickupCode,
                    onValueChange = onPickupCodeChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("取件码", fontSize = 13.sp) },
                    placeholder = { Text("多个取件码用逗号分隔", fontSize = 14.sp) },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Box(modifier = Modifier.fillMaxWidth()) {
                    // 使用 InteractionSource 监听焦点变化
                    val addressInteractionSource = remember { MutableInteractionSource() }
                    val isAddressFocused by addressInteractionSource.collectIsFocusedAsState()
                    
                    // 监听焦点变化，失去焦点时隐藏推荐地址下拉菜单
                    LaunchedEffect(isAddressFocused) {
                        if (!isAddressFocused) {
                            onDismissAddressSuggestions()
                        }
                    }
                    
                    OutlinedTextField(
                        value = address,
                        onValueChange = onAddressChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("取件地址", fontSize = 13.sp) },
                        placeholder = { Text("请输入取件地址", fontSize = 14.sp) },
                        shape = RoundedCornerShape(12.dp),
                        singleLine = false,
                        maxLines = 2,
                        interactionSource = addressInteractionSource,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    
                    if (showAddressSuggestions && savedAddresses.isNotEmpty()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 60.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 8.dp
                        ) {
                            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                // 标题栏带关闭按钮
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "推荐地址",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    // 关闭按钮
                                    IconButton(
                                        onClick = { onDismissAddressSuggestions() },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "关闭",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                
                                savedAddresses.forEach { savedAddress ->
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onAddressSuggestionClick(savedAddress) },
                                        color = Color.Transparent
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.LocationOn,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = savedAddress,
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = courierCompany,
                    onValueChange = onCourierChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("快递公司", fontSize = 13.sp) },
                    placeholder = { Text("请输入快递公司", fontSize = 14.sp) },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            } else {
                DetailRow(
                    label = "取件码",
                    value = pickupCode.ifBlank { "未识别" },
                    isHighlight = true,
                    isMonospace = true,
                    icon = null
                )
                
                if (hasNoCode) {
                    Spacer(modifier = Modifier.height(6.dp))
                    DetailRow(
                        label = "提示",
                        value = "此短信无取件码, 可能需凭手机号取件",
                        isHighlight = false,
                        isMonospace = false,
                        icon = Icons.Outlined.Analytics
                    )
                }
                
                if (address.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    DetailRow(
                        label = "取件地址",
                        value = address,
                        isHighlight = false,
                        isMonospace = false,
                        icon = Icons.Outlined.LocationOn
                    )
                }
                
                if (courierCompany.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    DetailRow(
                        label = "快递公司",
                        value = courierCompany,
                        isHighlight = false,
                        isMonospace = false,
                        icon = null
                    )
                }
                
                if (trackingNumber != null || packageCount != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (trackingNumber != null) {
                            Text(
                                text = "运单尾号 ",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = trackingNumber,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        if (trackingNumber != null && packageCount != null) {
                            Spacer(modifier = Modifier.width(16.dp))
                        }
                        if (packageCount != null) {
                            Text(
                                text = "${packageCount}个包裹",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    isHighlight: Boolean,
    isMonospace: Boolean,
    icon: ImageVector?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .padding(top = 1.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = if (isHighlight) 17.sp else 14.sp,
                fontWeight = if (isHighlight) FontWeight.Bold else FontWeight.Normal,
                fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
                color = if (isHighlight) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualRuleDialog(
    smsContent: String,
    onDismiss: () -> Unit,
    onSave: (ruleName: String, pattern: String) -> Unit
) {
    var ruleName by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf("") }
    
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "手动创建规则",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = ruleName,
                    onValueChange = { ruleName = it },
                    label = { Text("规则名称") },
                    placeholder = { Text("如：菜鸟驿站") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("正则表达式") },
                    placeholder = { Text("如：取件码[：:]?\\s*([0-9]{4,})") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    minLines = 2
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (ruleName.isNotBlank() && pattern.isNotBlank()) {
                                onSave(ruleName, pattern)
                            }
                        },
                        enabled = ruleName.isNotBlank() && pattern.isNotBlank()
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }
}
