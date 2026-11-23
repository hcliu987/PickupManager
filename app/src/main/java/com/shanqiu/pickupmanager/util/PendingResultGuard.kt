/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import java.util.concurrent.atomic.AtomicBoolean

/**
 * [android.content.BroadcastReceiver.PendingResult] 的**幂等、可空安全**包装。
 *
 * ## 为什么需要它（H-08 的成因）
 *
 * AOSP 的 `BroadcastReceiver.goAsync()` 实现只有三行：
 *
 * ```java
 * public final PendingResult goAsync() {
 *     PendingResult res = mPendingResult;
 *     mPendingResult = null;     // ← 取走即清空
 *     return res;
 * }
 * ```
 *
 * 两个直接后果：
 *
 * 1. **第二次调用返回 `null`**。只要有一条路径在一次广播分发里走到两次
 *    `goAsync()`（例如 `onReceive` 先调 A、A 内部又被 B 复用），第二次拿到的
 *    就是 null，而后面 `finally { pendingResult.finish() }` 会抛 NPE。
 *    `WidgetProvider` 里那些 `try/catch` **抓不到它** —— NPE 抛在 `finally` 里，
 *    同一个 try 的 catch 已经执行完了。结果是**主线程崩溃**。
 * 2. **非 `onReceive` 调用点恒返回 `null`**。`AppWidgetProvider.onUpdate` 由
 *    AppWidgetService 回调，不在广播分发链路里，`mPendingResult` 本来就是 null。
 *    也就是说"用 goAsync() 防止 onUpdate 被回收"这套写法**从来没有生效过**，
 *    它只是安静地返回了 null。
 *
 * 本类把这两件事都变成无害的：
 * - `finish()` 幂等 —— 用 CAS 保证底层 `finish()` 最多执行一次；
 * - 持空也安全 —— 没有拿到 PendingResult 时 `finish()` 是空操作。
 *
 * **它不解决"该不该保活"**，只解决"会不会崩"。是否真的需要保活必须由调用点
 * 自己判断，见 `BasePickupWidgetProvider.goAsyncSafely()` 的日志。
 */
class PendingResultGuard(private val onFinish: (() -> Unit)?) {

    private val finished = AtomicBoolean(false)

    /** 仅用于日志与断言，不参与业务判断。 */
    val alreadyFinished: Boolean get() = finished.get()

    /** 底层 PendingResult 是否真的取到了。取不到说明调用点不在 onReceive 分发中。 */
    val hasPendingResult: Boolean = onFinish != null

    /**
     * 结束本次广播分发。可重复调用；没有 PendingResult 时静默跳过。
     *
     * 底层 `finish()` 本身也可能抛（例如框架已因超时自行结束），
     * 所以这里再包一层 —— 崩在 `finish()` 上没有任何收益。
     */
    fun finish() {
        if (!finished.compareAndSet(false, true)) return
        try {
            onFinish?.invoke()
        } catch (_: Throwable) {
            // 吞掉：finish() 失败不影响任何业务结果，只影响框架的调度时机
        }
    }
}
