/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.shanqiu.pickupmanager.R
import com.shanqiu.pickupmanager.data.repository.PickupRepository
import com.shanqiu.pickupmanager.ui.components.AboutDialog
import com.shanqiu.pickupmanager.ui.components.AddWidgetGuideDialog
import com.shanqiu.pickupmanager.ui.components.PermissionSetupDialog
import com.shanqiu.pickupmanager.ui.components.PermissionGuideDialog
import com.shanqiu.pickupmanager.ui.components.UpdateDialog
import com.shanqiu.pickupmanager.util.UpdateChecker
import com.shanqiu.pickupmanager.ui.screens.MainScreen
import com.shanqiu.pickupmanager.ui.screens.MenuItemType
import com.shanqiu.pickupmanager.ui.theme.PickupManagerTheme
import com.shanqiu.pickupmanager.ui.animation.provideAnimationConfig
import com.shanqiu.pickupmanager.ui.animation.AnimationPresets
import com.shanqiu.pickupmanager.util.BatteryOptimizationHelper
import com.shanqiu.pickupmanager.util.MiuiPermissionHelper
import com.shanqiu.pickupmanager.util.NotificationHelper
import com.shanqiu.pickupmanager.util.NotificationPermissionHelper
import com.shanqiu.pickupmanager.util.PermissionActionHelper
import com.shanqiu.pickupmanager.util.PermissionCacheManager
import com.shanqiu.pickupmanager.util.PermissionChecker
import com.shanqiu.pickupmanager.util.PhoneBrandDetector
import com.shanqiu.pickupmanager.util.PreferenceManager
import com.shanqiu.pickupmanager.util.SmsPermissionChecker
import com.shanqiu.pickupmanager.util.SmsPermissionManager
import com.shanqiu.pickupmanager.widget.ShortcutTrampolineActivity
import com.shanqiu.pickupmanager.widget.WidgetBrandAdapter
import kotlinx.coroutines.launch

class ComposeMainActivity : ComponentActivity() {
    
    private var isFirstLaunch = false
    private var isImporting = false
    private var showAboutDialogState by mutableStateOf(false)
    // 更新提示：启动时后台检查，发现新版本则显示弹窗
    private var updateInfo by mutableStateOf<UpdateChecker.UpdateInfo?>(null)
    private var showPermissionGuideDialog by mutableStateOf(false)
    private var newPermissionResult by mutableStateOf<SmsPermissionChecker.PermissionCheckResult?>(null)
    
    // 统一权限引导弹窗
    private var showPermissionSetupDialog by mutableStateOf(false)
    // 权限检测重组触发器：onResume 时递增，强制 PermissionSetupDialog 重新检测
    private var setupRecomposeKey by mutableIntStateOf(0)
    // 功能介绍弹窗许可：首次启动默认禁止，权限完成后延迟 2s 再开
    private var allowFeatureIntro by mutableStateOf(false)
    
    // 小部件添加引导
    private var showAddWidgetGuide by mutableStateOf(false)
    private var addWidgetGuideSize by mutableStateOf("3x3")
    
    // ========== 短信权限启动器 ==========
    private val requestSmsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // R4 / H-10①：无论结果如何，先落一个"已请求过"的标记。
        // 这是后面区分「从未请求」（该继续弹系统窗）与「已被永久拒绝 / 勾了不再询问」
        // （再弹也不会出现任何界面，必须改走系统设置）的唯一依据 ——
        // `shouldShowRequestPermissionRationale()` 对这两种情况都返回 false。
        PermissionCacheManager.markPermissionRequested(this)
        val allGranted = grants.isNotEmpty() && grants.values.all { it }
        // 用户从系统弹窗返回后，立即触发重组以检测权限状态变化
        // 修复：部分机型不会触发 onResume，必须在此回调中主动递增重组触发器
        setupRecomposeKey++
        Log.d(
            "PermSetup",
            "短信权限系统弹窗已返回，allGranted=$allGranted，重组触发器: $setupRecomposeKey"
        )
    }

    /**
     * 短信权限是否已被"永久拒绝"（R4 / H-10①）。
     *
     * 判定条件必须同时满足：
     * 1. READ_SMS 与 RECEIVE_SMS 都还没拿到；
     * 2. 我们**确实请求过**（[PermissionCacheManager.hasRequestedPermission]）；
     * 3. 系统不再允许展示请求弹窗（`shouldShowRequestPermissionRationale` 两者皆 false）。
     *
     * 只满足 1+3 是不够的 —— "从未请求过"也满足这两条，那时应该继续弹系统窗。
     * 满足三条时，再调 `launcher.launch(...)` 只会**立即返回且不显示任何界面**，
     * 用户的表现就是"点了『去授权』没反应、页面永远停在『授权后方可继续』"。
     * 此时唯一出路是引导用户去系统设置手动开启。
     */
    private fun isSmsPermanentlyDenied(): Boolean {
        val granted = { perm: String ->
            checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (granted(Manifest.permission.READ_SMS) || granted(Manifest.permission.RECEIVE_SMS)) {
            return false
        }
        if (!PermissionCacheManager.hasRequestedPermission(this)) return false
        val canAsk = shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS) ||
            shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS)
        return !canAsk
    }
    
    private fun requestHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window?.let { window ->
                val layoutParams = window.attributes
                layoutParams.preferredRefreshRate = 120f
                window.attributes = layoutParams
            }
        }
    }

    /**
     * 设置 RemoteViews 框架异常处理器
     */
    private fun setupRemoteViewsCrashHandler() {
        try {
            val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                if (isRemoteViewsFrameworkBug(throwable)) {
                    android.util.Log.e(
                        "PickupApp",
                        "捕获 RemoteViews 框架异常（已知 MIUI bug），已阻止进程崩溃",
                        throwable
                    )
                    return@setDefaultUncaughtExceptionHandler
                }
                originalHandler?.uncaughtException(thread, throwable)
            }
        } catch (e: Throwable) {
            android.util.Log.e("PickupApp", "设置 RemoteViews 异常处理器失败", e)
        }
    }

    private fun isRemoteViewsFrameworkBug(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is ArrayIndexOutOfBoundsException) {
                val stack = current.stackTrace
                if (stack.any {
                        it.className.contains("RemoteViews") ||
                        it.className.contains("AppWidgetManager") ||
                        it.className.contains("IRemoteViewsFactory")
                    }) {
                    return true
                }
            }
            current = current.cause
        }
        return false
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setupRemoteViewsCrashHandler()
        requestHighRefreshRate()
        
        NotificationHelper.createNotificationChannels(this)
        
        isFirstLaunch = PreferenceManager.isFirstLaunch(this)
        // 非首次启动：允许功能介绍弹窗立即显示；首次启动：等权限流程结束后再开
        allowFeatureIntro = !isFirstLaunch
        handleWidgetAction(intent)
        
        // 每次启动时检查权限状态，如果缺少关键权限则显示引导弹窗
        checkPermissionsOnStartup()

        // 启动时检查更新：后台拉一次 version.json，发现新版弹提示（失败静默）
        checkForUpdates()

        setContent {
            PickupManagerTheme {
                provideAnimationConfig(config = AnimationPresets.Standard) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MainScreen(
                            onMenuItemClick = { menuItem ->
                                handleMenuItemClick(menuItem)
                            },
                            onImportSms = {
                                checkSmsPermissionAndImport(isManualImport = true)
                            },
                            allowFeatureIntro = allowFeatureIntro
                        )
                        
                        // 关于弹窗
                        if (showAboutDialogState) {
                            AboutDialog(
                                onDismiss = { showAboutDialogState = false },
                                onCheckUpdate = {
                                    showAboutDialogState = false
                                    openReleasePage()
                                }
                            )
                        }

                        // 发现新版本提示弹窗
                        updateInfo?.let { info ->
                            UpdateDialog(
                                info = info,
                                onDownload = {
                                    val url = info.apkUrl
                                    updateInfo = null
                                    runCatching {
                                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        })
                                    }.onFailure {
                                        Toast.makeText(this@ComposeMainActivity, "无法打开浏览器下载", Toast.LENGTH_LONG).show()
                                    }
                                },
                                onDismiss = { updateInfo = null },
                                onSkipThisVersion = {
                                    PreferenceManager.setSkippedVersionCode(this@ComposeMainActivity, info.versionCode)
                                    updateInfo = null
                                }
                            )
                        }
                        
                        // 权限引导弹窗（手动导入时使用）
                        if (showPermissionGuideDialog && newPermissionResult != null) {
                            PermissionGuideDialog(
                                result = newPermissionResult!!,
                                onDismiss = {
                                    PermissionCacheManager.recordDismiss(this@ComposeMainActivity)
                                    showPermissionGuideDialog = false
                                    newPermissionResult = null
                                },
                                onPermissionClick = { permissionType ->
                                    // 跳转设置页面，由 onResume 重新检测实际权限状态
                                    PermissionActionHelper.handlePermissionAction(
                                        this@ComposeMainActivity,
                                        permissionType
                                    )
                                },
                                onRetry = {
                                    retryPermissionCheck()
                                }
                            )
                        }
                        
                        // 统一权限引导弹窗（首次启动）
                        if (showPermissionSetupDialog) {
                            PermissionSetupDialog(
                                isSmsGranted = {
                                    val status = SmsPermissionManager.checkSmsPermissionStatus(this@ComposeMainActivity)
                                    status.hasReadSmsPermission && status.hasReceiveSmsPermission
                                },
                                isNotificationGranted = {
                                    NotificationHelper.isNotificationPermissionGranted(this@ComposeMainActivity)
                                },
                                isBatteryOptimized = {
                                    !BatteryOptimizationHelper.isBatteryOptimized(this@ComposeMainActivity)
                                },
                                isXiaomi = PhoneBrandDetector.isXiaomi(),
                                recomposeTrigger = setupRecomposeKey,
                                onRequestSms = {
                                    requestSmsPermissionLauncher.launch(
                                        SmsPermissionManager.getRequiredPermissions()
                                    )
                                },
                                // R4 / H-10①：已被永久拒绝时，"去授权"再弹也不会出现界面，
                                // 必须改走系统设置。两者由同一个判定驱动，UI 上互斥。
                                isSmsPermanentlyDenied = { isSmsPermanentlyDenied() },
                                onOpenAppSettings = {
                                    try {
                                        startActivity(
                                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.fromParts("package", packageName, null)
                                            }
                                        )
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            this@ComposeMainActivity,
                                            "请手动前往 设置 → 应用 → 码上取 → 权限 开启短信权限",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                onOpenNotificationSettings = {
                                    openNotificationSettings()
                                },
                                onOpenBatterySettings = {
                                    try {
                                        val intent = BatteryOptimizationHelper.getBatteryOptimizationIntent(this@ComposeMainActivity)
                                        if (intent != null) {
                                            startActivity(intent)
                                        } else {
                                            BatteryOptimizationHelper.requestDisableBatteryOptimization(this@ComposeMainActivity)
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            this@ComposeMainActivity,
                                            "请手动前往设置 → 电池 → 码上取 → 关闭优化",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                onOpenXiaomiSmsNotifySettings = {
                                    openXiaomiSmsNotifySettings()
                                },
                                isAutoStartGranted = {
                                    PermissionChecker.checkAutoStartPermission(this@ComposeMainActivity)
                                },
                                onOpenAutoStartSettings = {
                                    openAutoStartSettings()
                                },
                                onComplete = {
                                    // R4 / H-10③：这里原先无条件写入 `saveResult(this, true)`，
                                    // 而弹窗判定"短信步骤通过"用的是**弱条件**（只查
                                    // READ_SMS + RECEIVE_SMS 两个 Android 权限），
                                    // 与 `SmsPermissionChecker.checkAllPermissions()` 的**强条件**
                                    // （`missingPermissions.none { it.isCritical }`，在 MIUI 上
                                    // 还包含不可检测的"通知类短信"）不是一回事。
                                    // 结果就是：弹窗认为走完了 → 往缓存里写 true →
                                    // 后续 30 分钟内启动流程全部跳过检测 → 实际仍是缺权限。
                                    // 现在缓存必须记录**当场强检测的真实结论**。
                                    val realVerdict = SmsPermissionChecker
                                        .checkAllPermissions(this@ComposeMainActivity)
                                        .isFullyGranted
                                    PermissionCacheManager.saveResult(
                                        this@ComposeMainActivity,
                                        realVerdict
                                    )
                                    PermissionCacheManager.resetDismissState(this@ComposeMainActivity)
                                    showPermissionSetupDialog = false
                                    importSms()
                                    // 权限完成后延迟 2 秒再弹出功能介绍页
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        allowFeatureIntro = true
                                    }, 2000)
                                },
                                onDismiss = {
                                    // 记录用户跳过弹窗，7天内不再显示
                                    PermissionCacheManager.recordDismiss(this@ComposeMainActivity)
                                    showPermissionSetupDialog = false
                                    // R5 / M-04②：「跳过」= 用户此刻不走授权流程。
                                    // 原先前无条件调 importSms()，等于刚被跳过就立刻
                                    // 去全表扫描收件箱。现在只在"用户其实已经自己
                                    // 在系统设置里授过权"这种情形下才顺手导入一次。
                                    if (hasBasicSmsPermission()) importSms()
                                }
                            )
                        }
                        
                        // 添加小部件引导
                        if (showAddWidgetGuide) {
                            AddWidgetGuideDialog(
                                widgetSize = addWidgetGuideSize,
                                onDismiss = { showAddWidgetGuide = false }
                            )
                        }
                    }
                }
            }
        }
        
        // 首次启动：显示统一权限引导弹窗
        if (isFirstLaunch) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!isFinishing) {
                    showPermissionSetupDialog = true
                }
            }, 400)
        }
        
        // 修复通知监听服务状态
        NotificationPermissionHelper.fixNotificationListenerState(this)
        
        // 检查快捷方式 Intent
        checkShortcutIntent(intent)
    }
    
    // ========== 旧方法保留（兼容） ==========
    
    /** @deprecated 已被 [showPermissionSetupDialog] 替代 */
    @Suppress("DEPRECATION")
    private fun checkAndRequestPermissions() {
        showPermissionSetupDialog = true
    }
    
    private fun checkShortcutIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(ShortcutTrampolineActivity.EXTRA_SHOW_WIDGET_GUIDE, false) == true) {
            val widgetSize = intent.getStringExtra(ShortcutTrampolineActivity.EXTRA_WIDGET_SIZE) ?: "3x3"
            addWidgetGuideSize = widgetSize
            showAddWidgetGuide = true
            intent.removeExtra(ShortcutTrampolineActivity.EXTRA_SHOW_WIDGET_GUIDE)
        }
    }
    
    private fun retryPermissionCheck() {
        PermissionCacheManager.resetDismissState(this)
        val newStatus = SmsPermissionChecker.checkAllPermissions(this)
        newPermissionResult = newStatus
        if (newStatus.isFullyGranted) {
            showPermissionGuideDialog = false
            newPermissionResult = null
            PermissionCacheManager.saveResult(this, true)
            Toast.makeText(this@ComposeMainActivity, "权限设置成功，正在导入短信...", Toast.LENGTH_SHORT).show()
            importSms()
        } else {
            Toast.makeText(this@ComposeMainActivity, "权限仍未生效，请检查设置", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 每次回到前台时检查权限状态
     * 
     * 如果缺少关键权限（短信、通知、电池优化、自启动等）且用户没有在最近7天内跳过，
     * 则显示统一权限引导弹窗（复用 PermissionSetupDialog）
     * 
     * 性能优化：30分钟内缓存有效则跳过检测
     */
    /**
     * 廉价的"短信基础权限是否还在"复核（只读 Android 权限位）。
     *
     * 刻意**不**调用 `SmsPermissionChecker.checkAllPermissions()` ——
     * 后者会做 ContentProvider 查询等昂贵操作，正是缓存想规避的东西。
     * 这里只用它来兜住"用户把权限收回去了"这种最常见的状态变化。
     */
    private fun hasBasicSmsPermission(): Boolean {
        val granted = { perm: String ->
            checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return granted(Manifest.permission.READ_SMS) && granted(Manifest.permission.RECEIVE_SMS)
    }

    private fun checkPermissionsOnStartup() {
        // 性能优化：如果30分钟内的缓存仍然有效，跳过昂贵的 ContentProvider 查询
        if (PermissionCacheManager.isCacheValid(this)) {
            val cachedResult = PermissionCacheManager.getCachedResult(this)
            if (cachedResult == true) {
                // R4 / H-10②：缓存说"已授权"不能无条件相信。
                // 用户完全可能在系统设置里把权限收回去，而本缓存 30 分钟内不会失效，
                // 于是 App 继续按"已授权"运行 —— 不弹引导，导入又静默失败。
                // 这里加一道**只查权限位**的廉价复核：一致就照旧跳过；
                // 不一致立刻作废缓存，往下走完整检测。
                if (hasBasicSmsPermission()) {
                    return
                }
                Log.d("PermSetup", "缓存声称已授权，但实时复核不通过 —— 缓存已作废，重新完整检测")
                PermissionCacheManager.clearCache(this)
            }
        }
        
        // 检查是否应该显示权限引导（7天冷却期）
        if (!PermissionCacheManager.shouldShowDialog(this)) {
            return
        }
        
        // 执行完整权限检测
        val result = SmsPermissionChecker.checkAllPermissions(this)
        
        // 缓存检测结果
        PermissionCacheManager.saveResult(this, result.isFullyGranted)
        
        // 如果所有权限都已授予，不显示弹窗
        if (result.isFullyGranted) {
            return
        }
        
        // 检查是否有任何关键权限缺失（不限制具体类型，覆盖短信、通知、电池优化、自启动等所有关键权限）
        val hasCriticalMissing = result.missingPermissions.any { it.isCritical }
        
        // 有缺失的关键权限 → 显示统一权限引导弹窗
        if (hasCriticalMissing) {
            showPermissionSetupDialog = true
        }
    }
    
    private fun openNotificationSettings() {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
    }
    
    /**
     * 跳转到小米「通知类短信」权限设置页
     * 路径：设置 → 权限管理 → 其他权限 → 通知类短信
     * 不可检测，只要用户离开界面就认为已处理
     */
    private fun openXiaomiSmsNotifySettings() {
        try {
            // 尝试直接跳转 MIUI 权限管理页
            val intent = Intent().apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
                )
                putExtra("extra_pkgname", packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (packageManager.resolveActivity(intent, 0) != null) {
                startActivity(intent)
                return
            }
        } catch (_: Exception) {}
        // 兜底：打开应用详情页
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }
    
    /**
     * 跳转到系统自启动设置页面
     */
    private fun openAutoStartSettings() {
        try {
            val intent = PermissionChecker.getAutoStartIntent(this)
            startActivity(intent)
            // 不再提前标记：由 onResume 重新检测实际权限状态
        } catch (e: Exception) {
            // 兜底：打开应用详情页
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }
    
    override fun onResume() {
        super.onResume()
        // 递增重组触发器，强制 PermissionSetupDialog 重新检测权限状态
        setupRecomposeKey++
        checkShortcutIntent(intent)
        
        // 权限引导弹窗检测（手动导入流程的旧逻辑）
        if (showPermissionGuideDialog) {
            val newResult = SmsPermissionChecker.checkAllPermissions(this)
            if (newResult.isFullyGranted) {
                showPermissionGuideDialog = false
                newPermissionResult = null
                Toast.makeText(this, "权限设置成功，正在导入短信...", Toast.LENGTH_SHORT).show()
                importSms()
            } else {
                newPermissionResult = newResult
            }
        }
        
        // 每次回到前台时检查权限状态（如果两个弹窗都没显示）
        if (!showPermissionGuideDialog && !showPermissionSetupDialog) {
            checkPermissionsOnStartup()
        }
    }
    
    private fun handleMenuItemClick(menuItem: MenuItemType) {
        when (menuItem) {
            MenuItemType.IMPORT_SMS -> checkSmsPermissionAndImport(isManualImport = true)
            MenuItemType.ABOUT -> showAboutDialog()
            MenuItemType.CHECK_UPDATE -> openReleasePage()
            MenuItemType.SETTINGS -> {}
            MenuItemType.ROUTE_SETTINGS -> {}
            else -> {}
        }
    }

    /**
     * 启动时后台检查更新：只拉一次 version.json，不上传任何数据。
     * 发现新版本且用户未选择"不再提示此版本"时才弹窗；失败一律静默。
     */
    private fun checkForUpdates() {
        lifecycleScope.launch {
            val (currentCode, _) = UpdateChecker.currentVersion(this@ComposeMainActivity)
            val info = UpdateChecker.check(currentCode) ?: return@launch
            if (info.versionCode == PreferenceManager.getSkippedVersionCode(this@ComposeMainActivity)) {
                return@launch
            }
            updateInfo = info
        }
    }

    /**
     * 检查更新：用系统浏览器打开 Gitee Release 页。
     * 本应用不声明 INTERNET 权限也不发任何网络请求 —— 链接交给浏览器，
     * 是否联网、走不走代理完全由用户和设备决定，"不联网"卖点不受影响。
     */
    private fun openReleasePage() {
        // 直接指向 APK 文件：点一下即下载，无需在 Release 页里再找附件。
        // 本应用不声明 INTERNET 权限，链接交给浏览器，是否联网由用户决定。
        val url = "https://gitee.com/884024720/pickup-manager/releases/download/latest/pickupmanager.apk"
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure {
            Toast.makeText(this, "无法打开浏览器，请手动访问 Gitee 下载 APK", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun checkSmsPermissionAndImport(isManualImport: Boolean = false) {
        val status = SmsPermissionManager.checkSmsPermissionStatus(this)
        
        when {
            !status.hasReadSmsPermission || !status.hasReceiveSmsPermission -> {
                requestSmsPermissionLauncher.launch(
                    SmsPermissionManager.getRequiredPermissions()
                )
            }
            PhoneBrandDetector.isXiaomi() -> {
                val miuiStatus = MiuiPermissionHelper.checkMiuiSmsPermissionStatus(this)
                when {
                    !miuiStatus.hasBasicSmsPermission -> {
                        requestSmsPermissionLauncher.launch(arrayOf(Manifest.permission.READ_SMS))
                    }
                    !miuiStatus.isFullyGranted -> {
                        showPermissionGuideDialog = true
                    }
                    else -> importSms()
                }
            }
            else -> {
                val newResult = SmsPermissionChecker.checkAllPermissions(this)
                if (newResult.isFullyGranted) {
                    importSms()
                } else {
                    newPermissionResult = newResult
                    showPermissionGuideDialog = true
                }
            }
        }
    }
    
    private fun importSms() {
        // R5 / M-04②：这里原先不看权限就直接往下走。缺 READ_SMS 时
        // contentResolver.query() 抛 SecurityException，被
        // PickupRepository.importSmsFromDevice 的兜底 catch 吞成返回值 0，
        // 于是弹出的是「扫描完成，未发现新的取件信息」——
        // 真相是「根本没有权限读短信库」。用户会反复点导入，永远修不好。
        // 修法：扫描之前只读权限位（廉价、无 ContentProvider 查询），
        // 缺权限就用与原因一致的提示，并且**不去查库**。
        if (!hasBasicSmsPermission()) {
            Toast.makeText(this, R.string.import_need_sms_permission, Toast.LENGTH_LONG).show()
            return
        }
        if (isImporting) {
            Toast.makeText(this, "正在导入短信，请稍候...", Toast.LENGTH_SHORT).show()
            return
        }
        isImporting = true
        lifecycleScope.launch {
            try {
                val repository = PickupRepository.getInstance(application)
                val count = repository.importSmsFromDevice()
                if (count > 0) {
                    Toast.makeText(
                        this@ComposeMainActivity,
                        getString(R.string.import_success, count),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@ComposeMainActivity,
                        R.string.import_no_new,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Throwable) {
                android.util.Log.e("ComposeMainActivity", "importSms 异常: ${e.message}", e)
                Toast.makeText(this@ComposeMainActivity, "导入失败：${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
            } finally {
                isImporting = false
            }
        }
    }
    
    private fun showAboutDialog() {
        showAboutDialogState = true
    }

    private fun handleWidgetAction(intent: Intent?) {
        if (intent == null) return
        val fromWidget = intent.getBooleanExtra("from_widget", false)
        if (!fromWidget) return

        val widgetAction = intent.getStringExtra("widget_action")
        Log.d("ComposeMainActivity", "小部件点击: action=$widgetAction")

        when (widgetAction) {
            "refresh" -> {
                val appWidgetId = intent.getIntExtra("widget_id", -1)
                Log.d("ComposeMainActivity", "触发小部件刷新: widgetId=$appWidgetId")
                WidgetBrandAdapter.triggerWidgetUpdate(this)
                Toast.makeText(this, "正在刷新...", Toast.LENGTH_SHORT).show()
            }
            "open" -> {
                Log.d("ComposeMainActivity", "从小部件打开应用")
            }
        }
    }
}
