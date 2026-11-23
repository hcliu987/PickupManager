/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.math.abs

object ScrollOptimizations {

    @Composable
    fun rememberSmoothFlingBehavior(): FlingBehavior {
        val decaySpec = rememberSplineBasedDecay<Float>()
        return remember(decaySpec) {
            PhysicsFlingBehavior(decaySpec)
        }
    }

    // R4 / L-13：此处原有 `rememberIsScrollingUp()` 与 `rememberIsScrollInProgress()`。
    // 两者全仓零调用，且前者**从定义上就不可能返回 true**：
    // 它拿 `listState.firstVisibleItemIndex / firstVisibleItemScrollOffset` 去与
    // `layoutInfo.visibleItemsInfo.first().index / .offset` 比较，而这两组值本来就
    // 是同一个东西（都是"第一个可见项"），于是两个分支恒为 `a < a` = false。
    // 一个恒返回值、又没有调用方的函数没有保留价值，已删除（连同 `derivedStateOf`
    // 与 `LazyListState` 两个因此变为未使用的 import）。
}

private class PhysicsFlingBehavior(
    private val decaySpec: DecayAnimationSpec<Float>
) : FlingBehavior {
    
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (abs(initialVelocity) < 1f) {
            return initialVelocity
        }
        
        var lastValue = 0f
        val animationState = AnimationState(
            initialValue = 0f,
            initialVelocity = initialVelocity
        )
        
        animationState.animateDecay(
            animationSpec = decaySpec,
            sequentialAnimation = false
        ) {
            val delta = value - lastValue
            lastValue = value
            
            val consumed = scrollBy(delta)
            
            if (abs(delta - consumed) > 0.5f) {
                cancelAnimation()
            }
        }
        
        return animationState.velocity
    }
}
