/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.ui.viewmodel

import com.shanqiu.pickupmanager.util.privacyRef
import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shanqiu.pickupmanager.data.DataChangeContentProvider
import com.shanqiu.pickupmanager.data.model.PickupItem
import com.shanqiu.pickupmanager.data.model.RouteSettings
import com.shanqiu.pickupmanager.data.model.SavedAddress
import com.shanqiu.pickupmanager.data.repository.PickupRepository
import com.shanqiu.pickupmanager.util.PickupSummaryNotifier
import com.shanqiu.pickupmanager.util.PreferenceManager
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

import kotlinx.coroutines.ExperimentalCoroutinesApi

class ComposePickupViewModel(
    application: Application,
    private val repository: PickupRepository
) : AndroidViewModel(application) {

    /**
     * 全局协程异常处理器
     * 防止 unhandled exception 导致 viewModelScope 异常，进而引发进程崩溃
     * 关键：必须捕获 Throwable，不能只 catch Exception
     */
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "ViewModel 协程未捕获异常: ${throwable.message}", throwable)
        // 这里不重新抛出，避免进程崩溃
    }

    companion object {
        private const val TAG = "ComposePickupViewModel"
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    
    private val _filterType = MutableStateFlow(PickupItem.STATUS_PENDING)
    val filterType: StateFlow<Int> = _filterType.asStateFlow()
    
    private val _showDetailDialog = MutableStateFlow(false)
    val showDetailDialog: StateFlow<Boolean> = _showDetailDialog.asStateFlow()
    
    private val _selectedItem = MutableStateFlow<PickupItem?>(null)
    val selectedItem: StateFlow<PickupItem?> = _selectedItem.asStateFlow()
    
    private val _copySuccess = MutableStateFlow(false)
    val copySuccess: StateFlow<Boolean> = _copySuccess.asStateFlow()
    
    // 数据刷新触发器
    private val _refreshTrigger = MutableStateFlow(0)
    
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    
    private val _showFeatureIntro = MutableStateFlow(false)
    val showFeatureIntro: StateFlow<Boolean> = _showFeatureIntro.asStateFlow()
    
    @OptIn(ExperimentalCoroutinesApi::class)
    private val _defaultDisplayDays = MutableStateFlow(
        PreferenceManager.getDefaultDisplayDays(application)
    )
    val defaultDisplayDays: StateFlow<Int> = _defaultDisplayDays.asStateFlow()
    
    private val _routeSettings = MutableStateFlow(
        PreferenceManager.getRouteSettings(application)
    )
    val routeSettings: StateFlow<RouteSettings> = _routeSettings.asStateFlow()
    
    // 优化2: 双缓存策略 - 同时预加载待取件和已取件数据
    // 这样切换Tab时直接使用缓存，无需等待数据库查询
    // 公开缓存供UI层直接使用，实现Tab切换时内容与动画同步
    // 注意：待取件缓存需要根据显示天数过滤，已取件不受限制
    // 通过 _refreshTrigger.flatMapLatest 强制每次 _refreshTrigger 变化时重新订阅 Room Flow
    // 这解决了：跨进程写入数据库后主进程的 Room Flow 没有自动 invalidate 的问题
    @OptIn(ExperimentalCoroutinesApi::class)
    private val allPendingItemsCache: StateFlow<List<PickupItem>> = _refreshTrigger
        .flatMapLatest { repository.getItemsByStatusLite(PickupItem.STATUS_PENDING) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    
    @OptIn(ExperimentalCoroutinesApi::class)
    val pickedItemsCache: StateFlow<List<PickupItem>> = _refreshTrigger
        .flatMapLatest { repository.getItemsByStatusLite(PickupItem.STATUS_PICKED) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    
    // 待取件缓存：根据显示天数实时过滤
    // 当用户修改显示天数设置时，列表会自动更新
    val pendingItemsCache: StateFlow<List<PickupItem>> = allPendingItemsCache
        .combine(_defaultDisplayDays) { items, days ->
            val currentTime = System.currentTimeMillis()
            val thresholdTime = currentTime - (days.toLong() * 24 * 60 * 60 * 1000)
            items.filter { it.receiveTime >= thresholdTime }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    
    // 优化1: 使用Eagerly策略，在ViewModel创建时就开始预加载数据
    // 这样切换Tab时数据已经准备好，实现瞬时切换
    // 注意：待取件数量需要根据显示天数过滤
    val pendingCount: StateFlow<Int> = pendingItemsCache
        .map { it.size }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    
    init {
        // 注册跨进程数据变化观察者：
        // 小部件进程（:widgetProvider）写入数据库后，调用 contentResolver.notifyChange()
        // 系统会通过 DataChangeContentProvider 跨进程通知主进程，触发 onChange()
        registerCrossProcessDataObserver()
        
        checkFeatureIntro()
        autoImportSms()

        // 冷启动时主动刷一次汇总通知：用户可能划掉过它，或刚在系统设置里
        // 重新授予了通知权限，此时并没有任何「数据变化」事件会来触发刷新。
        PickupSummaryNotifier.refresh(getApplication<Application>())
    }
    
    /**
     * 注册跨进程数据变化观察者
     * 监听小部件进程（:widgetProvider）的数据变化，触发重新查询
     */
    private fun registerCrossProcessDataObserver() {
        try {
            val app = getApplication<Application>()
            val resolver = app.contentResolver
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    // 收到跨进程通知，触发 _refreshTrigger 重新查询数据库
                    refreshData()
                }
            }
            contentObserver = observer
            contentResolver = resolver
            resolver.registerContentObserver(
                DataChangeContentProvider.CONTENT_URI,
                true,  // notifyForDescendants
                observer
            )
        } catch (e: Exception) {
            android.util.Log.e("ComposePickupViewModel", "注册跨进程观察者失败: ${e.message}", e)
        }
    }
    
    private var contentObserver: android.database.ContentObserver? = null
    private var contentResolver: android.content.ContentResolver? = null
    
    override fun onCleared() {
        super.onCleared()
        // 不反注册会让观察者随 ViewModel 重建而累积
        contentObserver?.let { obs -> contentResolver?.unregisterContentObserver(obs) }
        contentObserver = null
        contentResolver = null
    }
    
    private fun autoImportSms() {
        // PreferenceManager.isSmsImported() 早已存在、setSmsImported() 也有两处调用，
        // 但读取它做闸门的这一步一直缺失 —— 结果每次 ViewModel 创建（≈每次进入主界面）
        // 都会全表扫描一遍收件箱，与"仅首次启动导入"的对外说明不符。
        // 新短信由 SmsReceiver 广播实时处理，存量可在菜单里手动再次导入，因此这里只做一次。
        if (PreferenceManager.isSmsImported(getApplication())) return
        viewModelScope.launch(coroutineExceptionHandler) {
            try {
                repository.importSmsFromDevice(_defaultDisplayDays.value)
                // 闸门必须自己落锁，否则上面那句 isSmsImported() 永远为 false
                PreferenceManager.setSmsImported(getApplication(), true)
            } catch (e: Throwable) {
                // 兜底：捕获所有 throwable，避免进程崩溃
                Log.e(TAG, "autoImportSms 异常: ${e.message}", e)
            }
        }
    }
    
    private fun checkFeatureIntro() {
        val context = getApplication<Application>()
        if (!PreferenceManager.isFeatureIntroShown(context)) {
            _showFeatureIntro.value = true
        }
    }
    
    /**
     * 说明：曾经这里有一个每 60 秒的"紧急度刷新"定时器，
     * 实现是给 `_filterType` 赋同名同值 —— MutableStateFlow 对相等值不发射，
     * 所以它从未产生任何效果，只是每分钟白唤醒一次协程。已删除。
     * 卡片的紧急度配色（PickupCard.getUrgencyLevel）现在随数据变化重算；
     * 若确实需要按墙钟自动变色，应引入被 UI 观察的 tick 流而不是这样空转。
     */
    
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }
    
    fun setFilterType(type: Int) {
        _filterType.value = type
    }
    
    fun showDetailDialog(item: PickupItem) {
        // 列表投影不含 rawSms：先用列表项立即显示弹窗（无延迟），再异步补全短信原文。
        _selectedItem.value = item
        _showDetailDialog.value = true
        viewModelScope.launch(coroutineExceptionHandler) {
            repository.getItemById(item.id)?.let { full ->
                // 仅当弹窗仍在显示同一条时才替换，避免快速切换卡片的竞态
                if (_selectedItem.value?.id == item.id) {
                    _selectedItem.value = full
                }
            }
        }
    }
    
    fun hideDetailDialog() {
        _showDetailDialog.value = false
        _selectedItem.value = null
    }
    
    fun copyPickupCode(item: PickupItem) {
        viewModelScope.launch {
            _copySuccess.value = true
            delay(1500)
            _copySuccess.value = false
        }
    }
    
    fun markAsPicked(item: PickupItem) {
        viewModelScope.launch(coroutineExceptionHandler) {
            try {
                repository.markAsPicked(item.id)
                // 通知桌面小部件自动刷新
                com.shanqiu.pickupmanager.widget.WidgetSyncHelper.notifyWidgetDataChanged(getApplication())
            } catch (e: Throwable) {
                Log.e(TAG, "markAsPicked 异常: ${e.message}", e)
            }
        }
    }

    fun markAsPending(item: PickupItem) {
        viewModelScope.launch(coroutineExceptionHandler) {
            try {
                repository.markAsPending(item.id)
                // 通知桌面小部件自动刷新
                com.shanqiu.pickupmanager.widget.WidgetSyncHelper.notifyWidgetDataChanged(getApplication())
            } catch (e: Throwable) {
                Log.e(TAG, "markAsPending 异常: ${e.message}", e)
            }
        }
    }

    fun deleteItem(item: PickupItem) {
        viewModelScope.launch(coroutineExceptionHandler) {
            try {
                repository.deleteItem(item)
                // 通知桌面小部件自动刷新
                com.shanqiu.pickupmanager.widget.WidgetSyncHelper.notifyWidgetDataChanged(getApplication())
            } catch (e: Throwable) {
                Log.e(TAG, "deleteItem 异常: ${e.message}", e)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch(coroutineExceptionHandler) {
            _isRefreshing.value = true
            try {
                // 问题 22：刷新是"重读本地库"，不是重新扫描系统短信收件箱。
                // 补导漏掉的短信请用菜单里的「导入短信」(importSms)。
                refreshData()
            } catch (e: Throwable) {
                Log.e(TAG, "refresh 异常: ${e.message}", e)
            } finally {
                delay(1000)
                _isRefreshing.value = false
            }
        }
    }

    fun importSms() {
        viewModelScope.launch(coroutineExceptionHandler) {
            _isRefreshing.value = true
            try {
                repository.importSmsFromDevice(_defaultDisplayDays.value)
                PreferenceManager.setSmsImported(getApplication(), true)
            } catch (e: Throwable) {
                Log.e(TAG, "importSms 异常: ${e.message}", e)
            } finally {
                delay(500)
                _isRefreshing.value = false
            }
        }
    }
    
    /**
     * 问题 21：设置页「清空全部数据」的调用点。
     *
     * 二次确认在 SettingsDialog 里完成，这里只负责执行与善后：
     * 偏好文件被清掉后，StateFlow 里仍存着旧值（显示天数、路线设置），
     * 不同步回默认值就会出现"设置页显示 7 天、实际按 20 天过滤"的错位。
     */
    fun clearAllData() {
        viewModelScope.launch(coroutineExceptionHandler) {
            try {
                repository.deleteAll()
                hideDetailDialog()
                _defaultDisplayDays.value = PreferenceManager.getDefaultDisplayDays(getApplication())
                _routeSettings.value = PreferenceManager.getRouteSettings(getApplication())
                refreshData()
            } catch (e: Throwable) {
                Log.e(TAG, "clearAllData 异常: ${e.message}", e)
            }
        }
    }

    fun resetCopySuccess() {
        _copySuccess.value = false
    }
    
    fun dismissFeatureIntro() {
        _showFeatureIntro.value = false
        PreferenceManager.setFeatureIntroShown(getApplication(), true)
    }
    
    fun setDefaultDisplayDays(days: Int) {
        _defaultDisplayDays.value = days
        PreferenceManager.setDefaultDisplayDays(getApplication(), days)
    }
    
    fun setRouteSettings(settings: RouteSettings) {
        _routeSettings.value = settings
        PreferenceManager.setRouteSettings(getApplication(), settings)
    }
    
    val savedAddresses: StateFlow<List<SavedAddress>> = repository.getSavedAddresses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    fun deleteSavedAddress(address: SavedAddress) {
        viewModelScope.launch {
            repository.deleteSavedAddress(address)
        }
    }
    
    fun updateAddressOrder(addresses: List<SavedAddress>) {
        viewModelScope.launch {
            repository.updateAddressOrder(addresses)
        }
    }
    
    fun updateAddressCustomName(addressId: Long, customName: String?) {
        viewModelScope.launch {
            repository.updateAddressCustomName(addressId, customName)
        }
    }
    
    fun addPickupItem(item: PickupItem) {
        viewModelScope.launch {
            repository.insertItem(item)
            // 通知桌面小部件自动刷新
            com.shanqiu.pickupmanager.widget.WidgetSyncHelper.notifyWidgetDataChanged(getApplication())
        }
    }

    /**
     * 关键修复：去重感知的添加
     *
     * 背景：RuleMatchingScreen 等手动添加路径使用 System.currentTimeMillis()
     * 作为 receiveTime，与 SmsReceiver 的真实 SMS 时间戳不一致，
     * 导致同一条短信入库后产生重复卡片。
     *
     * 行为：
     * - 若同 pickupCode 已存在：返回 AddResult.Duplicate(existing)，不入库
     * - 若不存在：插入新记录，返回 AddResult.Inserted
     *
     * 回调形式调用，UI 层（RuleMatchingScreen）可显示"取件码已存在"提示，
     * 避免直接关闭弹窗导致用户不知道发生了什么。
     */
    fun addPickupItemWithDedup(item: PickupItem, onResult: (AddPickupResult) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val result = repository.insertItemWithDedup(item)
                when (result) {
                    is PickupRepository.InsertResult.Inserted -> {
                        com.shanqiu.pickupmanager.widget.WidgetSyncHelper.notifyWidgetDataChanged(getApplication())
                        onResult(AddPickupResult.Inserted(result.newId))
                    }
                    is PickupRepository.InsertResult.Duplicate -> {
                        // 不刷新小部件，因为没新增数据
                        onResult(AddPickupResult.Duplicate(result.existing))
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("ComposePickupViewModel", "去重添加失败", e)
                onResult(AddPickupResult.Error(e.message ?: "未知错误"))
            }
        }
    }

    sealed class AddPickupResult {
        data class Inserted(val newId: Long) : AddPickupResult()
        data class Duplicate(val existing: PickupItem) : AddPickupResult()
        data class Error(val message: String) : AddPickupResult()
    }

    /**
     * 关键修复：编辑模式下覆盖原记录
     *
     * 背景：用户在 RuleMatchingScreen 编辑模式下手动确认修改结果后，
     * 系统应当用新数据覆盖原记录（保留 id、receiveTime、status），
     * 而不是插入新行（避免重复卡片）。
     *
     * 注意：只有当用户**明确在编辑模式下确认覆盖**时才调用此方法。
     * 一般路径仍应使用 addPickupItemWithDedup()。
     */
    fun overwritePickupItem(
        pickupCode: String,
        address: String,
        addressTag: String,
        courierCompany: String,
        trackingNumber: String,
        rawSms: String,
        notes: String,
        onResult: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                val success = repository.overwriteItemByPickupCode(
                    pickupCode = pickupCode,
                    address = address,
                    addressTag = addressTag,
                    courierCompany = courierCompany,
                    trackingNumber = trackingNumber,
                    rawSms = rawSms,
                    notes = notes
                )
                if (success) {
                    com.shanqiu.pickupmanager.widget.WidgetSyncHelper.notifyWidgetDataChanged(getApplication())
                }
                onResult(success)
            } catch (e: Throwable) {
                android.util.Log.e("ComposePickupViewModel", "覆盖更新失败", e)
                onResult(false)
            }
        }
    }
    
    fun updateItemAddress(itemId: Long, newAddress: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val item = _selectedItem.value
            if (item != null) {
                val success = repository.updateItemAddressAndSave(
                    itemId = itemId,
                    newAddress = newAddress,
                    senderPhone = item.senderPhone,
                    courierCompany = item.courierCompany
                )
                
                if (success) {
                    android.util.Log.d("ComposePickupViewModel", "地址更新成功: ${privacyRef(newAddress)}")
                    // 刷新数据以确保UI更新
                    refreshData()
                }
            }
        }
    }
    
    /**
     * 更新所有相同地址标签的物品地址
     * 用于路径页面统一更新地址
     */
    fun updateAllItemsAddress(addressTag: String, newAddress: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = repository.updateAllItemsAddressByTag(addressTag, newAddress)
            if (success) {
                android.util.Log.d("ComposePickupViewModel", "批量地址更新成功: tag=$addressTag, address=${privacyRef(newAddress)}")
                // 刷新数据以确保UI更新
                refreshData()
            }
        }
    }
    
    private fun refreshData() {
        // 触发数据刷新
        _refreshTrigger.value += 1
    }
}
