/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.ui.screens

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.shanqiu.pickupmanager.PickupApplication
import com.shanqiu.pickupmanager.data.repository.PickupRepository
import com.shanqiu.pickupmanager.ui.viewmodel.ComposePickupViewModel

class ComposeViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ComposePickupViewModel::class.java)) {
            val repository = PickupRepository.getInstance(application)
            return ComposePickupViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
