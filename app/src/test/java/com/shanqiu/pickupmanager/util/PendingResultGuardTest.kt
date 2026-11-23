/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

package com.shanqiu.pickupmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `PendingResultGuard` 的回归测试（R4 / H-08）。
 *
 * ## 为什么这条测试值得存在
 *
 * 这层包装解决的是一个**只会在真实进程里、特定时序下**才现形的缺陷：
 * AOSP 的 `BroadcastReceiver.goAsync()` 取走 `mPendingResult` 后就把它置空，
 * 同一个广播分发里第二次调用返回 `null`。原代码把 `goAsync()` 放在被复用的
 * 中间函数里，于是第二次进到那里拿到的就是 `null`，而随后的
 * `finally { pendingResult.finish() }` 抛 NPE —— 该 NPE 抛在 `finally` 中，
 * **同一个 `try` 的 `catch` 抓不到**，直接成为主线程崩溃。
 *
 * 这类缺陷靠"肉眼看代码"极难发现（NDK/框架语义、隐式时序），但它的核心契约
 * 是**纯逻辑**的，所以可以在 JVM 单测里钉死：
 *
 * 1. `finish()` 最多真正执行一次（幂等）—— 这是"多调用点共用同一守卫"成立的前提；
 * 2. 没拿到 `PendingResult` 时 `finish()` 是空操作 —— 这是"不会 NPE"的保证；
 * 3. 底层 `finish()` 抛异常不外泄 —— 崩在收尾上没有任何收益；
 * 4. 并发下也只有一次 —— 守卫的持有者分布在多个线程（IO 协程 + 主线程 Handler）。
 */
class PendingResultGuardTest {

    @Test
    fun `finish 只真正执行一次_后续调用都是空操作`() {
        val calls = AtomicInteger(0)
        val guard = PendingResultGuard { calls.incrementAndGet() }

        guard.finish()
        guard.finish()
        guard.finish()

        assertEquals("底层 finish() 必须最多生效一次", 1, calls.get())
        assertTrue(guard.alreadyFinished)
        assertTrue(guard.hasPendingResult)
    }

    @Test
    fun `没拿到 PendingResult 时 finish 是空操作且不抛`() {
        // onFinish == null 对应"goAsync() 返回 null"（onUpdate 等非广播分发调用点）
        val guard = PendingResultGuard(null)

        assertFalse("hasPendingResult 必须如实报告没取到", guard.hasPendingResult)

        // 不抛即可 —— 这正是修掉 NPE 的那一点
        guard.finish()
        guard.finish()

        assertTrue("空守卫调用 finish() 之后同样应标记为已收尾", guard.alreadyFinished)
    }

    @Test
    fun `底层 finish 抛异常时不外泄`() {
        val guard = PendingResultGuard { throw IllegalStateException("框架已因超时自行结束") }

        // 不应把异常抛给调用方：崩在收尾上只会把"异步结果丢了"升级成"进程崩了"
        guard.finish()

        assertTrue(guard.alreadyFinished)
    }

    @Test
    fun `底层 finish 抛异常后仍然只尝试一次`() {
        val calls = AtomicInteger(0)
        val guard = PendingResultGuard {
            calls.incrementAndGet()
            throw RuntimeException("boom")
        }

        guard.finish()
        guard.finish()

        assertEquals("异常也不该让幂等性失效", 1, calls.get())
    }

    @Test
    fun `并发 finish 只生效一次`() {
        val calls = AtomicInteger(0)
        val guard = PendingResultGuard { calls.incrementAndGet() }
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) {
            pool.execute {
                start.await()
                try {
                    guard.finish()
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue("并发 finish 未在超时内完成", done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals("CAS 必须保证底层 finish() 全局只执行一次", 1, calls.get())
    }

    @Test
    fun `初始状态未被标记为已收尾`() {
        val guard = PendingResultGuard { }

        assertFalse("构造出来还没调用 finish()，不应报告已收尾", guard.alreadyFinished)

        guard.finish()

        assertTrue(guard.alreadyFinished)
    }
}
